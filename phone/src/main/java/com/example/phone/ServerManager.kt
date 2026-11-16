package com.example.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class ServerManager(private val context: Context) {
    companion object {
        private const val TAG = "ServerManager"
        private const val VIDEO_WIDTH = 1080
        private const val VIDEO_HEIGHT = 1920
        private const val BIT_RATE = 8000000
        private const val FRAME_RATE = 30
        private const val TIMEOUT_MS = 10000L
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var acceptThread: Thread? = null
    private var isRunning = false
    private var isAccepting = true
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var outputStream: OutputStream? = null
    private var dataOutputStream: DataOutputStream? = null
    private var encodingThread: Thread? = null
    private var isEncoding = false

    var onStatusUpdate: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun startServer(port: Int): Boolean {
        if (isRunning) return true
        try {
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
            isRunning = true
            isAccepting = true
            Log.i(TAG, "Serveur demarre sur port $port")
            onStatusUpdate?.invoke("Serveur demarre sur port $port")
            acceptThread = Thread {
                try {
                    while (isAccepting) {
                        try {
                            clientSocket = serverSocket?.accept()
                            clientSocket?.let { socket ->
                                Log.i(TAG, "Client connecte")
                                onStatusUpdate?.invoke("Client connecte")
                                handleClientConnection(socket)
                            }
                        } catch (e: Exception) { Log.e(TAG, "Erreur accept", e) }
                    }
                } catch (e: Exception) { Log.e(TAG, "Thread accept termine", e) }
            }
            acceptThread?.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur demarrage serveur", e)
            onError?.invoke("Erreur: ${e.message}")
            stopServer()
            return false
        }
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            outputStream = socket.getOutputStream()
            dataOutputStream = DataOutputStream(outputStream)
            if (!performHandshake()) {
                Log.e(TAG, "Handshake echoue")
                onError?.invoke("Handshake echoue")
                cleanupClient()
                return
            }
            startScreenCaptureAndEncoding()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur gestion client", e)
            onError?.invoke("Erreur: ${e.message}")
            cleanupClient()
        }
    }

    private fun performHandshake(): Boolean {
        try {
            // Envoyer la version serveur et les infos device
            val deviceName = "QuestMirrorDevice"
            val deviceNameBytes = deviceName.toByteArray(Charsets.UTF_8)
            val paddedDeviceName = deviceNameBytes.copyOf(32)
            
            dataOutputStream?.writeInt(2)  // Version serveur
            dataOutputStream?.write(paddedDeviceName, 0, 32)  // Nom device
            dataOutputStream?.writeInt(VIDEO_WIDTH)  // Largeur
            dataOutputStream?.writeInt(VIDEO_HEIGHT)  // Hauteur
            dataOutputStream?.writeInt(0)  // DPI
            dataOutputStream?.writeInt(0)  // Flags
            
            // Lire la réponse client (8 bytes: version + flags)
            val clientResponse = ByteArray(8)
            val inputStream = clientSocket?.getInputStream()
            if (inputStream?.read(clientResponse, 0, 8) != 8) {
                Log.e(TAG, "Handshake: réponse client non lue")
                return false
            }
            
            // Envoyer les paramètres vidéo
            dataOutputStream?.writeInt(0)  // maxSize
            dataOutputStream?.writeInt(BIT_RATE)  // bitrate
            dataOutputStream?.writeInt(FRAME_RATE)  // fps
            
            // Envoyer le format vidéo (H.265)
            dataOutputStream?.writeByte(0x01)  // HEVC
            dataOutputStream?.writeByte(0)  // latency
            dataOutputStream?.writeInt(0)  // flags
            
            // Lire les params vidéo du client (16 bytes)
            val videoParams = ByteArray(16)
            if (inputStream?.read(videoParams, 0, 16) != 16) {
                Log.e(TAG, "Handshake: params vidéo non lues")
                return false
            }
            
            // Envoyer la configuration codec (8 bytes)
            val codecConfig = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            dataOutputStream?.write(codecConfig, 0, 8)
            
            Log.i(TAG, "Handshake réussi: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}")
            onStatusUpdate?.invoke("Connecté: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur handshake", e)
            return false
        }
    }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur handshake", e)
            return false
        }
    }

    private fun startScreenCaptureAndEncoding() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            Log.i(TAG, "MediaCodec encodage demarre")
            onStatusUpdate?.invoke("Encodage H.265 demarre")
            createVirtualDisplay(inputSurface)
            startEncodingThread()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur setup encodage", e)
            onError?.invoke("Erreur encodage: ${e.message}")
            cleanupEncoding()
        }
    }

    private fun createVirtualDisplay(surface: Surface?) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val density = metrics.densityDpi
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            
            if (mediaProjection != null) {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "QuestMirror", VIDEO_WIDTH, VIDEO_HEIGHT, density, flags, surface, null, null
                )
            } else {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                virtualDisplay = displayManager.createVirtualDisplay(
                    "QuestMirror", VIDEO_WIDTH, VIDEO_HEIGHT, density, flags, surface, null, null
                )
            }
            Log.i(TAG, "VirtualDisplay cree")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur creation VirtualDisplay", e)
            onError?.invoke("Erreur VirtualDisplay: ${e.message}")
        }
    }

    private fun startEncodingThread() {
        if (isEncoding) return
        isEncoding = true
        encodingThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var frameCounter = 0
            try {
                Log.i(TAG, "Thread encodage demarre")
                while (isEncoding && clientSocket?.isConnected == true) {
                    val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, TIMEOUT_MS) ?: break
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                        outputBuffer?.let { buffer ->
                            val data = ByteArray(bufferInfo.size)
                            buffer.get(data)
                            sendFrame(data, bufferInfo)
                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                            frameCounter++
                            if (frameCounter % 30 == 0) {
                                Log.d(TAG, "Frame $frameCounter envoyee")
                            }
                        }
                    }
                }
                Log.i(TAG, "Thread encodage termine")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur thread encodage", e)
            } finally {
                isEncoding = false
                cleanupEncoding()
            }
        }
        encodingThread?.start()
    }


    private fun sendFrame(data: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        try {
            dataOutputStream?.writeInt(0)
            dataOutputStream?.writeInt(data.size)
            dataOutputStream?.write(data)
            dataOutputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur envoi frame", e)
            cleanupClient()
        }
    }


    }



    }

    }

    }

    }

    }

    }

    }

    }

    }

    }

    }
