package com.example.quest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Joue le PCM 16 bits 48 kHz stéréo reçu du téléphone.
 *
 * Tampon de gigue maison : le thread réseau dépose les blocs de 10 ms dans une file,
 * un thread audio dédié les écrit en mode BLOQUANT dans l'AudioTrack. C'est donc la
 * vitesse réelle de lecture du Quest qui cadence la consommation (sur Quest, le son
 * passe par le service audio Horizon et la position de lecture n'est pas fiable).
 *
 * - Démarrage / après une coupure : on attend PREFILL_MS de son avant de jouer (pas de hachures).
 * - Si la file dépasse MAX_QUEUE_MS (rafale réseau), on la ramène à TARGET_QUEUE_MS en jetant
 *   d'abord les blocs silencieux, sinon les plus anciens → le son ne dérive pas par rapport à l'image.
 */
class AudioPlayer {
    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_FRAME = 4 // stéréo 16 bits
        private const val PREFILL_MS = 100
        private const val TARGET_QUEUE_MS = 120
        private const val MAX_QUEUE_MS = 250
        private const val SILENCE_THRESHOLD = 64
    }

    private class Chunk(val data: ByteArray, val ms: Int, val silent: Boolean)

    private val queue = ArrayDeque<Chunk>()
    private val lock = Object()
    private var queuedMs = 0
    @Volatile private var running = true
    private val track: AudioTrack
    private val thread: Thread

    // Statistiques (journal toutes les 5 s)
    private var underruns = 0
    private var emptyQueue = 0
    private var droppedSilentMs = 0
    private var droppedAudibleMs = 0
    private var playedMs = 0L
    private var audibleMs = 0L
    private var lastReport = SystemClock.elapsedRealtime()

    init {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            // Tampon système modeste : la gigue est absorbée par notre file, pas ici
            .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE * BYTES_PER_FRAME * 40 / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        Log.i(TAG, "AudioTrack prêt : minBuf ${minBuf * 1000 / (SAMPLE_RATE * BYTES_PER_FRAME)} ms, " +
            "tampon ${track.bufferSizeInFrames * 1000 / SAMPLE_RATE} ms")
        thread = Thread(::playLoop, "QuestMirrorAudioOut").apply { start() }
    }

    /** Appelé par le thread réseau pour chaque bloc PCM reçu. */
    fun write(data: ByteArray, size: Int) {
        val chunk = Chunk(data.copyOf(size), size * 1000 / (SAMPLE_RATE * BYTES_PER_FRAME), isSilent(data, size))
        synchronized(lock) {
            // Pendant les silences, on résorbe le retard sans rien couper d'audible
            if (chunk.silent && queuedMs > TARGET_QUEUE_MS) {
                droppedSilentMs += chunk.ms
                return
            }
            queue.addLast(chunk)
            queuedMs += chunk.ms
            if (queuedMs > MAX_QUEUE_MS) trimQueue()
            lock.notifyAll()
        }
    }

    /** Ramène la file à TARGET_QUEUE_MS : blocs silencieux d'abord, puis les plus anciens. */
    private fun trimQueue() {
        val it = queue.iterator()
        while (queuedMs > TARGET_QUEUE_MS && it.hasNext()) {
            val c = it.next()
            if (c.silent) {
                it.remove(); queuedMs -= c.ms; droppedSilentMs += c.ms
            }
        }
        while (queuedMs > TARGET_QUEUE_MS && queue.isNotEmpty()) {
            val c = queue.removeFirst(); queuedMs -= c.ms; droppedAudibleMs += c.ms
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var buffering = true
        var lastTrackUnderruns = 0
        var appliedVolume = -1f
        track.play()
        try {
            while (running) {
                val chunk: Chunk = synchronized(lock) {
                    if (buffering) {
                        // Démarrage, ou après une vraie coupure : reconstituer un peu d'avance
                        while (running && queuedMs < PREFILL_MS) lock.wait(50)
                        buffering = false
                    }
                    if (queue.isEmpty()) {
                        // File vide : l'AudioTrack a encore ~80 ms en réserve, on joue
                        // le prochain bloc dès qu'il arrive (pas de re-remplissage → pas de trou)
                        emptyQueue++
                        while (running && queue.isEmpty()) lock.wait(20)
                    }
                    val c = queue.removeFirstOrNull() ?: return@synchronized null
                    queuedMs -= c.ms
                    c
                } ?: continue
                val volume = VolumeSettings.effectiveVolume
                if (volume != appliedVolume) {
                    track.setVolume(volume)
                    appliedVolume = volume
                }
                track.write(chunk.data, 0, chunk.data.size) // bloquant : cadencé par la lecture réelle
                playedMs += chunk.ms
                if (!chunk.silent) audibleMs += chunk.ms

                // Vraie coupure audible (l'AudioTrack s'est vidé) → on reprend de l'avance
                val trackUnderruns = track.underrunCount
                if (trackUnderruns > lastTrackUnderruns) {
                    underruns += trackUnderruns - lastTrackUnderruns
                    lastTrackUnderruns = trackUnderruns
                    buffering = true
                }
                report()
            }
        } catch (e: InterruptedException) {
            // arrêt
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack arrêté", e)
        }
    }

    private fun report() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < 5000) return
        val q = synchronized(lock) { queuedMs }
        Log.d(TAG, "5 s : joué ${playedMs} ms (dont son audible ${audibleMs} ms), file ${q} ms, file vide ${emptyQueue}x, coupures réelles $underruns, " +
            "jeté silence ${droppedSilentMs} ms / audible ${droppedAudibleMs} ms")
        playedMs = 0; audibleMs = 0; underruns = 0; emptyQueue = 0; droppedSilentMs = 0; droppedAudibleMs = 0
        lastReport = now
    }

    private fun isSilent(data: ByteArray, size: Int): Boolean {
        var i = 0
        while (i + 1 < size) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            if (abs(sample.toShort().toInt()) > SILENCE_THRESHOLD) return false
            i += 2
        }
        return true
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        try { track.pause(); track.flush() } catch (_: Exception) {}
        thread.interrupt()
        thread.join(500)
        track.release()
    }
}
