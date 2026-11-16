package com.example.quest

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client du flux : se connecte au téléphone, lit le protocole QMIR (voir phone/ServerManager.kt)
 * et décode le H.265/H.264 brut avec MediaCodec directement sur la Surface d'affichage.
 * Reconnexion automatique tant que le renderer tourne.
 */
class VideoRenderer(
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "VideoRenderer"
        private const val MAGIC = 0x514D4952      // "QMIR"
        private const val CODEC_H265 = 0x68323635 // "h265"
        private const val CODEC_H264 = 0x68323634 // "h264"
        private const val FLAG_CONFIG = 1L shl 63
        private const val FLAG_KEY_FRAME = 1L shl 62
        private const val FLAG_AUDIO = 1L shl 61
        private const val PTS_MASK = FLAG_AUDIO - 1
        private const val MAX_PACKET = 32 * 1024 * 1024

        // Tampon de lecture vidéo : latence ajoutée en échange d'un rythme régulier
        private const val PLAYOUT_DELAY_US = 60_000L
        /** On confie l'image au compositeur un peu avant son heure (il l'affiche au bon vsync). */
        private const val RELEASE_AHEAD_NS = 12_000_000L
        /** Au-delà de ce retard sur son heure prévue, l'image est sautée. */
        private const val LATE_DROP_NS = 40_000_000L
        /** Le décalage d'horloge remonte de 2 µs par image (~0,12 ms/s) pour suivre la dérive. */
        private const val OFFSET_DRIFT_US = 2L
        private const val MAX_PENDING_FRAMES = 6

        // Messages Quest -> téléphone (voir phone/ServerManager.kt)
        private const val MSG_TOUCH = 1
        private const val MSG_GLOBAL = 2
        const val TOUCH_DOWN = 0
        const val TOUCH_MOVE = 1
        const val TOUCH_UP = 2
        const val GLOBAL_BACK = 1
        const val GLOBAL_HOME = 2
        const val GLOBAL_RECENTS = 3
    }

    /** Flux d'écriture de la session en cours (null si déconnecté). */
    @Volatile private var output: DataOutputStream? = null
    /** Les écritures réseau sont interdites sur le thread UI : un thread dédié les sérialise. */
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "QuestMirrorSender") }

    /** Toucher sur l'image, coordonnées normalisées 0..1. Appelable depuis le thread UI. */
    fun sendTouch(action: Int, x: Float, y: Float) = send { out ->
        out.writeByte(MSG_TOUCH)
        out.writeByte(action)
        out.writeFloat(x)
        out.writeFloat(y)
    }

    /** Bouton système du téléphone (retour / accueil / récents). */
    fun sendGlobalAction(action: Int) = send { out ->
        out.writeByte(MSG_GLOBAL)
        out.writeByte(action)
    }

    private fun send(write: (DataOutputStream) -> Unit) {
        val out = output ?: return
        sender.execute {
            try {
                write(out)
                out.flush()
            } catch (e: IOException) {
                Log.d(TAG, "Envoi du contrôle impossible : ${e.message}")
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null
    private val surfaceLock = Object()
    private var surface: Surface? = null

    /** Appelé par l'activité quand la Surface est créée (non null) ou détruite (null). */
    fun setSurface(newSurface: Surface?) {
        synchronized(surfaceLock) {
            surface = newSurface
            surfaceLock.notifyAll()
        }
        // Surface détruite : on coupe la session, elle reprendra quand la surface reviendra
        if (newSurface == null) closeSocket()
    }

    fun start(host: String, port: Int) {
        if (running) return
        running = true
        thread = Thread({ connectLoop(host, port) }, "QuestMirrorClient").apply { start() }
    }

    fun stop() {
        running = false
        closeSocket()
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
        thread?.interrupt()
        thread?.join(1500)
        thread = null
        sender.shutdown()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun connectLoop(host: String, port: Int) {
        while (running) {
            try {
                runSession(host, port)
            } catch (e: Exception) {
                Log.w(TAG, "Session terminée", e)
                if (running) onStatus("Déconnecté (${e.message}) — nouvelle tentative…")
            }
            if (!running) break
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun waitForSurface(): Surface? {
        synchronized(surfaceLock) {
            while (running && surface == null) surfaceLock.wait(500)
            return surface
        }
    }

    private fun runSession(host: String, port: Int) {
        if (waitForSurface() == null) return
        onStatus("Connexion à $host:$port…")

        val s = Socket()
        socket = s
        s.use {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            // Le téléphone ré-émet une image toutes les 100 ms même sur écran statique
            s.soTimeout = 5000
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64))

            val magic = input.readInt()
            if (magic != MAGIC) throw IOException("Protocole inattendu (0x%08x)".format(magic))
            val codecId = input.readInt()
            val width = input.readInt()
            val height = input.readInt()
            val mime = when (codecId) {
                CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
                else -> throw IOException("Codec inconnu 0x%08x".format(codecId))
            }
            Log.i(TAG, "Flux $mime ${width}x$height")
            onVideoSize(width, height)
            onStatus("Flux ${width}x$height reçu, démarrage du décodeur…")

            val outSurface = waitForSurface() ?: return
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            val active = AtomicBoolean(true)
            val frameCount = AtomicInteger(0)
            var drainThread: Thread? = null
            var audio: AudioPlayer? = null
            try {
                codec.configure(format, outSurface, null, 0)
                codec.start()
                drainThread = Thread({ drainLoop(codec, active, frameCount) }, "QuestMirrorDrain").apply { start() }

                var buffer = ByteArray(1 shl 20)
                while (running) {
                    val header = input.readLong()
                    val size = input.readInt()
                    if (size == 0) continue // keepalive du téléphone (écran statique)
                    if (size < 0 || size > MAX_PACKET) throw IOException("Taille de paquet invalide : $size")
                    if (size > buffer.size) buffer = ByteArray(size)
                    input.readFully(buffer, 0, size)

                    if (header and FLAG_AUDIO != 0L) {
                        val player = audio ?: AudioPlayer().also { audio = it }
                        player.write(buffer, size)
                        continue
                    }

                    val isConfig = header and FLAG_CONFIG != 0L
                    val flags = when {
                        isConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        header and FLAG_KEY_FRAME != 0L -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else -> 0
                    }
                    val ptsUs = if (isConfig) 0L else header and PTS_MASK

                    var index: Int
                    do {
                        index = codec.dequeueInputBuffer(10_000)
                    } while (index < 0 && running)
                    if (index < 0) break
                    val inBuf = codec.getInputBuffer(index) ?: throw IOException("Buffer d'entrée null")
                    inBuf.clear()
                    if (size > inBuf.capacity()) throw IOException("Paquet trop gros pour le décodeur : $size")
                    inBuf.put(buffer, 0, size)
                    codec.queueInputBuffer(index, 0, size, ptsUs, flags)
                }
            } finally {
                output = null
                audio?.release()
                active.set(false)
                drainThread?.join(1000)
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
        }
    }

    private class PendingFrame(val index: Int, val targetNs: Long)

    /**
     * Sortie du décodeur avec tampon de lecture (anti-saccades).
     *
     * Chaque image porte l'heure de capture du téléphone (pts, horloge du téléphone).
     * On estime le décalage entre les deux horloges par le plus petit (arrivée - pts) observé :
     * c'est le trajet le plus rapide. Chaque image est ensuite affichée à pts + décalage +
     * PLAYOUT_DELAY : le rythme de capture est restitué même si le réseau livre par à-coups.
     * Le décalage remonte doucement (dérive d'horloge, réseau durablement plus lent).
     */
    private fun drainLoop(codec: MediaCodec, active: AtomicBoolean, frameCount: AtomicInteger) {
        val info = MediaCodec.BufferInfo()
        val pending = ArrayDeque<PendingFrame>()
        var offsetUs = Long.MAX_VALUE // horloge Quest - horloge téléphone (+ trajet minimal)
        var lastReport = System.nanoTime()
        var rendered = 0
        var late = 0
        var maxJitterUs = 0L
        var sumJitterUs = 0L
        var jitterSamples = 0

        fun releaseDue(): Boolean {
            val now = System.nanoTime()
            while (pending.isNotEmpty() && pending.first().targetNs - now <= RELEASE_AHEAD_NS) {
                val f = pending.removeFirst()
                try {
                    if (f.targetNs < now - LATE_DROP_NS) {
                        codec.releaseOutputBuffer(f.index, false) // trop tard : on saute l'image
                        late++
                    } else {
                        // Présentée par le compositeur au vsync le plus proche de targetNs
                        codec.releaseOutputBuffer(f.index, f.targetNs)
                        rendered++
                        frameCount.incrementAndGet()
                    }
                } catch (e: IllegalStateException) {
                    return false // surface détruite : fin de session
                }
            }
            return true
        }

        while (active.get()) {
            if (!releaseDue()) break
            // Attendre une nouvelle image, mais pas au-delà de la prochaine échéance d'affichage
            val waitUs = if (pending.isEmpty()) 10_000L
                else ((pending.first().targetNs - RELEASE_AHEAD_NS - System.nanoTime()) / 1000).coerceIn(0L, 10_000L)
            val index = try {
                codec.dequeueOutputBuffer(info, waitUs)
            } catch (e: IllegalStateException) {
                break
            }
            if (index >= 0) {
                val nowUs = System.nanoTime() / 1000
                val ptsUs = info.presentationTimeUs
                val sample = nowUs - ptsUs
                offsetUs = if (offsetUs == Long.MAX_VALUE || sample < offsetUs) sample else offsetUs + OFFSET_DRIFT_US
                // Gigue = retard de cette image par rapport au trajet le plus rapide
                val jitter = sample - offsetUs
                if (jitter > maxJitterUs) maxJitterUs = jitter
                sumJitterUs += jitter; jitterSamples++

                val targetNs = (ptsUs + offsetUs + PLAYOUT_DELAY_US) * 1000
                pending.addLast(PendingFrame(index, targetNs))
                // Sécurité : ne pas bloquer le décodeur en gardant trop de tampons
                while (pending.size > MAX_PENDING_FRAMES) {
                    val f = pending.removeFirst()
                    try { codec.releaseOutputBuffer(f.index, true) } catch (e: IllegalStateException) { break }
                    rendered++
                }
            }

            val now = System.nanoTime()
            if (now - lastReport >= 1_000_000_000L) {
                onStatus("$rendered fps")
                if (now / 5_000_000_000L != lastReport / 5_000_000_000L) {
                    Log.d(TAG, "vidéo : ${rendered} img/s, en retard (sautées) $late, gigue réseau moy " +
                        "${if (jitterSamples > 0) sumJitterUs / jitterSamples / 1000 else 0} ms / max ${maxJitterUs / 1000} ms, " +
                        "tampon ${PLAYOUT_DELAY_US / 1000} ms")
                    maxJitterUs = 0; sumJitterUs = 0; jitterSamples = 0; late = 0
                }
                rendered = 0
                lastReport = now
            }
        }
        // Rendre les tampons encore détenus
        for (f in pending) try { codec.releaseOutputBuffer(f.index, false) } catch (_: IllegalStateException) {}
    }
}
