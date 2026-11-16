package com.example.phone

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Serveur de streaming : capture l'écran via MediaProjection, encode en H.265 (repli H.264)
 * et envoie le flux brut au Quest via TCP.
 *
 * Protocole (big-endian) :
 *   En-tête session : magic "QMIR" (4) | codec "h265"/"h264" (4) | largeur (4) | hauteur (4)
 *   Puis paquets   : pts+flags (8) | taille (4) | données (taille)
 *     bit 63 du pts = paquet de config (VPS/SPS/PPS), bit 62 = image clé
 *     bit 61 = paquet audio (PCM 16 bits 48 kHz stéréo entrelacé)
 *     taille 0 = keepalive (envoyé après 1 s sans image)
 *
 * Sens Quest -> téléphone (même socket) : messages de contrôle MSG_TOUCH / MSG_GLOBAL.
 */
class ServerManager(
    private val projection: MediaProjection,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val densityDpi: Int,
    private val port: Int = PORT,
    private val onStatus: (String) -> Unit,
    private val onStopped: () -> Unit,
    /** Session Quest active ou non (pour démarrer/arrêter le heartbeat). */
    private val onSessionActive: (Boolean) -> Unit = {},
    /** Toucher reçu du Quest : action (TOUCH_*), x/y normalisés 0..1. Appelé hors thread principal. */
    private val onTouch: (Int, Float, Float) -> Unit = { _, _, _ -> },
    /** Bouton système reçu du Quest (GLOBAL_*). Appelé hors thread principal. */
    private val onGlobalAction: (Int) -> Unit = {},
    /** Capture du son (nécessite RECORD_AUDIO et Android 10+). */
    private val audioEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "ServerManager"
        const val PORT = 27183
        private const val BIT_RATE = 10_000_000
        private const val FRAME_RATE = 60
        private const val I_FRAME_INTERVAL_S = 10
        // Ré-encode l'image précédente si l'écran est statique (évite 0 fps / timeout côté Quest)
        private const val REPEAT_FRAME_DELAY_US = 100_000L
        private const val KEEPALIVE_MS = 1000L

        const val MAGIC = 0x514D4952      // "QMIR"
        const val CODEC_H265 = 0x68323635 // "h265"
        const val CODEC_H264 = 0x68323634 // "h264"
        const val FLAG_CONFIG = 1L shl 63
        const val FLAG_KEY_FRAME = 1L shl 62
        const val FLAG_AUDIO = 1L shl 61 // paquet PCM 16 bits 48 kHz stéréo

        // Messages Quest -> téléphone
        const val MSG_TOUCH = 1   // [1][action u8][x float][y float]
        const val MSG_GLOBAL = 2  // [2][action u8]
        const val TOUCH_DOWN = 0
        const val TOUCH_MOVE = 1
        const val TOUCH_UP = 2
        const val GLOBAL_BACK = 1
        const val GLOBAL_HOME = 2
        const val GLOBAL_RECENTS = 3
    }

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        running = true
        // Android 14+ : un callback doit être enregistré AVANT createVirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection arrêtée par le système")
                stop()
            }
        }, mainHandler)

        // Un seul VirtualDisplay par jeton MediaProjection (Android 14+) :
        // on le garde et on change juste sa surface à chaque connexion du Quest.
        // AUTO_MIRROR = miroir de l'écran réel (pas un écran secondaire, donc pas de DeX).
        virtualDisplay = projection.createVirtualDisplay(
            "QuestMirror", captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, mainHandler
        )

        thread = Thread(::serveLoop, "QuestMirrorServer").apply { start() }
    }

    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        thread?.join(1500)
        thread = null
        virtualDisplay?.release()
        virtualDisplay = null
        try { projection.stop() } catch (_: Exception) {}
        onStatus("Serveur arrêté")
        onStopped()
    }

    private fun serveLoop() {
        var session: Thread? = null
        try {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                onStatus("En attente du Quest sur le port $port (${captureWidth}x$captureHeight)")
                while (running) {
                    val client = try { ss.accept() } catch (e: IOException) { break }
                    // Une nouvelle connexion remplace toujours l'ancienne (reconnexion du Quest)
                    try { clientSocket?.close() } catch (_: IOException) {}
                    session?.join(2000)
                    clientSocket = client
                    onStatus("Quest connecté : ${client.inetAddress.hostAddress}")
                    session = Thread({ runSession(client) }, "QuestMirrorSession").apply { start() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur serveur", e)
            if (running) onStatus("Erreur serveur : ${e.message}")
        } finally {
            try { clientSocket?.close() } catch (_: IOException) {}
            session?.join(2000)
        }
    }

    private fun runSession(client: Socket) {
        try {
            streamTo(client)
        } catch (e: Exception) {
            Log.w(TAG, "Session terminée : ${e.message}")
        } finally {
            try { client.close() } catch (_: IOException) {}
            if (running && clientSocket === client) onStatus("Quest déconnecté — en attente sur le port $port")
        }
    }

    private fun streamTo(client: Socket) {
        client.tcpNoDelay = true
        val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 1 shl 16))
        val (codec, codecId) = createEncoder()
        var inputSurface: Surface? = null
        var audio: AudioCapture? = null
        try {
            inputSurface = codec.createInputSurface()
            codec.start()
            virtualDisplay?.surface = inputSurface

            out.writeInt(MAGIC)
            out.writeInt(codecId)
            out.writeInt(captureWidth)
            out.writeInt(captureHeight)
            out.flush()

            Thread({ readControl(client) }, "QuestMirrorControl").apply { isDaemon = true; start() }
            onSessionActive(true)

            // Son : un thread séparé écrit dans le même flux (synchronisé sur `out`)
            if (audioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audio = AudioCapture(projection) { pcm, n ->
                    synchronized(out) {
                        out.writeLong(FLAG_AUDIO)
                        out.writeInt(n)
                        out.write(pcm, 0, n)
                        out.flush()
                    }
                }.also { it.start() }
            }

            val info = MediaCodec.BufferInfo()
            var chunk = ByteArray(1 shl 18)
            var frames = 0
            var lastReport = System.currentTimeMillis()
            var lastWrite = lastReport

            while (running && !client.isClosed) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) { // TRY_AGAIN_LATER / OUTPUT_FORMAT_CHANGED
                    // Écran statique : keepalive (paquet vide) pour détecter une déconnexion du Quest
                    if (System.currentTimeMillis() - lastWrite >= KEEPALIVE_MS) {
                        synchronized(out) {
                            out.writeLong(0L)
                            out.writeInt(0)
                            out.flush()
                        }
                        lastWrite = System.currentTimeMillis()
                    }
                    continue
                }
                try {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        var header = if (isConfig) FLAG_CONFIG else info.presentationTimeUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) header = header or FLAG_KEY_FRAME

                        if (info.size > chunk.size) chunk = ByteArray(info.size)
                        buffer.get(chunk, 0, info.size)
                        synchronized(out) {
                            out.writeLong(header)
                            out.writeInt(info.size)
                            out.write(chunk, 0, info.size)
                            out.flush() // IOException ici si le Quest s'est déconnecté
                        }
                        lastWrite = System.currentTimeMillis()
                        if (!isConfig) frames++
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break

                val now = System.currentTimeMillis()
                if (now - lastReport >= 2000) {
                    onStatus("Streaming vers ${client.inetAddress.hostAddress} — ${frames * 1000 / (now - lastReport)} fps")
                    frames = 0
                    lastReport = now
                }
            }
        } finally {
            audio?.stop()
            onSessionActive(false)
            virtualDisplay?.surface = null
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            inputSurface?.release()
        }
    }

    /** Lit les événements de contrôle envoyés par le Quest jusqu'à la fin de la session. */
    private fun readControl(client: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            while (running) {
                when (val type = input.readUnsignedByte()) {
                    MSG_TOUCH -> {
                        val action = input.readUnsignedByte()
                        val x = input.readFloat()
                        val y = input.readFloat()
                        onTouch(action, x, y)
                    }
                    MSG_GLOBAL -> onGlobalAction(input.readUnsignedByte())
                    else -> throw IOException("Message de contrôle inconnu : $type")
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Fin du canal de contrôle : ${e.message}")
        }
    }

    /** Encodeur matériel H.265, repli H.264 si indisponible. */
    private fun createEncoder(): Pair<MediaCodec, Int> {
        val candidates = listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC to CODEC_H265,
            MediaFormat.MIMETYPE_VIDEO_AVC to CODEC_H264
        )
        for ((mime, id) in candidates) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(mime, captureWidth, captureHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US)
                    // L'écran peut tourner à 120 Hz (et le heartbeat le force) : on plafonne à 60 fps
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FRAME_RATE.toFloat())
                    }
                }
                codec = MediaCodec.createEncoderByType(mime)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "Encodeur ${codec.name} ($mime) ${captureWidth}x$captureHeight")
                return codec to id
            } catch (e: Exception) {
                Log.w(TAG, "Encodeur $mime indisponible", e)
                codec?.release()
            }
        }
        throw IOException("Aucun encodeur vidéo disponible")
    }
}
