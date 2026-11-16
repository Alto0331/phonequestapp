package com.example.phone

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Capture le son joué par les applis (Android 10+, via la MediaProjection) en PCM
 * 16 bits 48 kHz stéréo, par blocs de 10 ms. Pas d'encodage : latence minimale,
 * ~1,5 Mbps, négligeable à côté de la vidéo.
 * Limite Android : les applis qui interdisent la capture (certaines applis de streaming
 * protégé, les appels) ne sont pas captées.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCapture(
    private val projection: MediaProjection,
    /** Reçoit chaque bloc PCM ; lève IOException si le Quest est déconnecté. */
    private val sink: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val CHUNK_BYTES = SAMPLE_RATE / 100 * CHANNELS * 2 // 10 ms
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::captureLoop, "QuestMirrorAudio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO vérifiée avant création
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                // 500 ms : si l'envoi est bloqué par une grosse image vidéo, rien n'est perdu
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 50))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de créer la capture audio", e)
            return
        }
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            record.startRecording()
            Log.i(TAG, "Capture audio démarrée (${SAMPLE_RATE} Hz stéréo)")
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    Log.w(TAG, "Erreur de lecture audio : $n")
                    break
                }
                if (n > 0) sink(buffer, n)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Fin de la capture audio : ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }
}
