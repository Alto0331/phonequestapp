package com.example.quest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodec.BufferInfo
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File

class VideoRenderer(private val activity: XrActivity, private val surfaceView: SurfaceView?) {

    companion object {
        private const val TAG = "VideoRenderer"
    }

    private val core = Core()
    private var mediaCodec: MediaCodec? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var readingThread: Thread? = null
    private var isReading = false
    private var videoWidth = 1080
    private var videoHeight = 1920
    private var isConnected = false
    private var frameCounter = 0
    private val logFile get() = File(activity.filesDir, "videorenderer.log")

    var onError: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    private fun logToFile(message: String) {
        try {
            logFile.appendText("${System.currentTimeMillis()} $message\n")
        } catch (e: Exception) {}
    }

    init {
        Log.d(TAG, "VideoRenderer initialisé")
        logToFile("VideoRenderer initialisé")
    }

    fun connect(ip: String, port: Int) {
        logToFile("Connexion à $ip:$port")
        Log.d(TAG, "Connexion à $ip:$port")
        Thread {
            try {
                isConnected = false
                if (!core.connect(ip, port)) {
                    logToFile("Échec connexion")
                    onError?.invoke("Échec de la connexion")
                    return@Thread
                }
                logToFile("Connecté")
                Log.e(TAG, "=== CONNECTE ===")
                if (!core.handshake()) {
                    logToFile("Échec handshake")
                    onError?.invoke("Échec handshake")
                    return@Thread
                }
                videoWidth = core.getWidth()
                videoHeight = core.getHeight()
                logToFile("Handshake OK")
                activity.runOnUiThread {
                    onStatusUpdate?.invoke("Connecté: ${videoWidth}x${videoHeight}")
                }
                isConnected = true
                if (surfaceHolder != null) {
                    setupMediaCodec(videoWidth, videoHeight)
                }
                startReadingFrames()
            } catch (e: Exception) {
                logToFile("Exception: ${e.message}")
                onError?.invoke("Erreur")
                Log.e(TAG, "Erreur", e)
            }
        }.start()
    }

    private fun setupMediaCodec(width: Int, height: Int) {
        Log.e(TAG, "=== SETUP MEDIACODEC ===")
        logToFile("Setup MediaCodec")
        activity.runOnUiThread {
            try {
                val format = MediaFormat.createVideoFormat("video/hevc", width, height)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                mediaCodec = MediaCodec.createDecoderByType("video/hevc")
                mediaCodec?.configure(format, surfaceHolder?.surface, null, 0)
                mediaCodec?.start()
                Log.e(TAG, "MediaCodec démarré")
                onStatusUpdate?.invoke("Décodeur démarré")
            } catch (e: Exception) {
                logToFile("Erreur setup")
                onError?.invoke("Erreur setup")
                Log.e(TAG, "Erreur setup", e)
            }
        }
    }

    private fun startReadingFrames() {
        if (isReading) return
        isReading = true
        readingThread = Thread {
            try {
                logToFile("Start reading frames")
                Log.e(TAG, "=== DEBUT LECTURE ===")
                val buffer = ByteArray(65536)
                while (isReading && isConnected) {
                    val headerBytes = core.readFrame(buffer, 0, 8)
                    if (headerBytes <= 0) {
                        if (headerBytes == 0) {
                            logToFile("Connexion fermée")
                        } else {
                            logToFile("Erreur lecture header")
                        }
                        break
                    }
                    if (headerBytes != 8) {
                        logToFile("Header incomplet")
                        continue
                    }
                    val frameSize = (buffer[4].toInt() and 0xFF) or
                                   ((buffer[5].toInt() and 0xFF) shl 8) or
                                   ((buffer[6].toInt() and 0xFF) shl 16) or
                                   ((buffer[7].toInt() and 0xFF) shl 24)
                    logToFile("Frame size=$frameSize")
                    if (frameSize <= 0 || frameSize > 10 * 1024 * 1024) {
                        logToFile("Taille invalide")
                        continue
                    }
                    var bytesRead: Int
                    var totalRead = 0
                    while (totalRead < frameSize && isReading && isConnected) {
                        bytesRead = core.readFrame(buffer, totalRead, frameSize - totalRead)
                        if (bytesRead <= 0) {
                            logToFile("Erreur lecture data")
                            break
                        }
                        totalRead += bytesRead
                    }
                    if (totalRead != frameSize) {
                        logToFile("Frame incomplète")
                        continue
                    }
                    injectFrameToDecoder(buffer, 0, frameSize)
                    frameCounter++
                    if (frameCounter % 30 == 0) {
                        logToFile("Frame #$frameCounter")
                    }
                }
                logToFile("Lecture terminée")
                Log.e(TAG, "=== FIN LECTURE ===")
            } catch (e: Exception) {
                logToFile("Erreur lecture")
                Log.e(TAG, "Erreur lecture", e)
            } finally {
                isReading = false
            }
        }
        readingThread?.start()
    }

    private fun injectFrameToDecoder(data: ByteArray, offset: Int, length: Int) {
        try {
            val codec = mediaCodec ?: run {
                logToFile("MediaCodec non initialisé")
                return
            }
            val inputBufferIndex = codec.dequeueInputBuffer(10000L)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data, offset, length)
                val pts = System.nanoTime() / 1000
                codec.queueInputBuffer(inputBufferIndex, 0, length, pts, 0)
                val bufferInfo = BufferInfo()
                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                while (outputBufferIndex >= 0) {
                    codec.releaseOutputBuffer(outputBufferIndex, true)
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                }
            }
        } catch (e: Exception) {
            logToFile("Erreur injection")
            Log.e(TAG, "Erreur injection", e)
        }
    }

    fun notifySurfaceCreated(holder: SurfaceHolder) {
        Log.e(TAG, "=== SURFACE CREEE ===")
        logToFile("Surface créée")
        surfaceHolder = holder
        if (isConnected) {
            setupMediaCodec(videoWidth, videoHeight)
        }
    }

    fun notifySurfaceChanged(format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changée")
        logToFile("Surface changée")
    }

    fun notifySurfaceDestroyed() {
        Log.d(TAG, "Surface détruite")
        logToFile("Surface détruite")
        surfaceHolder = null
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
    }

    fun disconnect() {
        isReading = false
        isConnected = false
        readingThread?.interrupt()
        readingThread?.join(1000)
        readingThread = null
        core.disconnect()
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
        Log.d(TAG, "Déconnecté")
    }
}
