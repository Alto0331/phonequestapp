package com.example.phone.dex

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.example.phone.ServerManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Programme d'aide DeX, exécuté avec les droits du shell adb (pas comme une appli) :
 *   CLASSPATH=<apk> app_process / com.example.phone.dex.DexHelper
 *
 * Seul le shell peut créer un écran virtuel « public » : Samsung y lance alors le bureau DeX.
 * Le helper encode cet écran en H.265 et le sert au Quest sur le port 27184 avec le même
 * protocole QMIR que le miroir, et injecte directement les touches sur l'écran DeX.
 * L'écran DeX est conservé entre deux connexions du Quest (les fenêtres ouvertes restent).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "WrongConstant")
object DexHelper {
    private const val TAG = "QuestMirrorDex"
    const val PORT = 27184
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    private const val DPI = 160
    private const val BIT_RATE = 12_000_000
    private const val FRAME_RATE = 60
    private const val KEEPALIVE_MS = 1000L
    private const val SHELL_UID = 2000

    /** Port de commande « écran du téléphone éteint ». */
    const val SCREEN_PORT = 27185
    private const val CMD_SCREEN_OFF = 0
    private const val CMD_SCREEN_ON = 1
    private const val CMD_PING = 2
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    // Drapeaux d'écran virtuel (cachés dans le SDK), comme scrcpy --new-display
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    private lateinit var context: Context
    private lateinit var inputManager: InputManager
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId = -1
    @Volatile private var clientSocket: Socket? = null
    private var touchDownTime = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        log("Démarrage du programme d'aide DeX (uid ${android.os.Process.myUid()})")
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        restoreScreenShareProtection() // au cas où une exécution précédente se serait arrêtée brutalement
        context = createShellContext()
        inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        Thread(::serveLoop, "DexServer").start()
        Thread(::screenControlLoop, "ScreenControl").start()
        Looper.loop()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        println(msg)
    }

    // ---------------------------------------------------------------- Contexte shell

    /** Contexte système « déguisé » en com.android.shell (même principe que scrcpy). */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName("com.android.shell").build()
    }

    private fun createShellContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        val ctor = atClass.getDeclaredConstructor().apply { isAccessible = true }
        val activityThread = ctor.newInstance()
        atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, activityThread)
        atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(activityThread, true)

        // Workarounds facultatifs (MediaCodec et certains services en ont besoin selon les ROM)
        try {
            val ccClass = Class.forName("android.app.ConfigurationController")
            val atiClass = Class.forName("android.app.ActivityThreadInternal")
            val cc = ccClass.getDeclaredConstructor(atiClass).apply { isAccessible = true }.newInstance(activityThread)
            atClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(activityThread, cc)
        } catch (t: Throwable) { log("ConfigurationController : ${t.message}") }
        try {
            val abdClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val abd = abdClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            abdClass.getDeclaredField("appInfo").apply { isAccessible = true }
                .set(abd, ApplicationInfo().apply { packageName = "com.android.shell" })
            atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThread, abd)
        } catch (t: Throwable) { log("AppBindData : ${t.message}") }

        val systemContext = atClass.getDeclaredMethod("getSystemContext").invoke(activityThread) as Context
        val shell = ShellContext(systemContext)
        try {
            val app = Instrumentation.newApplication(Application::class.java, shell)
            atClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThread, app)
        } catch (t: Throwable) { log("Application : ${t.message}") }
        return shell
    }

    // ---------------------------------------------------------------- Écran DeX

    private fun ensureDisplay(surface: Surface) {
        val vd = virtualDisplay
        if (vd != null) {
            vd.surface = surface
            return
        }
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP

        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val dm = ctor.newInstance(context)
        val created = dm.createVirtualDisplay("QuestMirror DeX", WIDTH, HEIGHT, DPI, surface, flags)
        virtualDisplay = created
        displayId = created.display.displayId
        log("Écran DeX créé : ${WIDTH}x$HEIGHT/$DPI (id=$displayId)")
    }

    // ---------------------------------------------------------------- Réseau

    private fun serveLoop() {
        try {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                log("En attente du Quest sur le port $PORT")
                var session: Thread? = null
                while (true) {
                    val client = ss.accept()
                    // Une nouvelle connexion remplace l'ancienne
                    try { clientSocket?.close() } catch (_: IOException) {}
                    session?.join(2000)
                    clientSocket = client
                    log("Quest connecté : ${client.inetAddress.hostAddress}")
                    session = Thread({
                        try {
                            streamTo(client)
                        } catch (e: Exception) {
                            log("Session terminée : ${e.message}")
                        } finally {
                            try { client.close() } catch (_: IOException) {}
                        }
                    }, "DexSession").apply { start() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur serveur", e)
            println("Erreur serveur : ${e.message}")
            System.exit(1)
        }
    }

    private fun streamTo(client: Socket) {
        client.tcpNoDelay = true
        val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 1 shl 16))
        val (codec, codecId) = createEncoder()
        val inputSurface = codec.createInputSurface()
        try {
            codec.start()
            ensureDisplay(inputSurface)
            disableScreenShareProtection()

            out.writeInt(ServerManager.MAGIC)
            out.writeInt(codecId)
            out.writeInt(WIDTH)
            out.writeInt(HEIGHT)
            out.flush()
            Thread({ readControl(client) }, "DexControl").apply { isDaemon = true; start() }

            val info = MediaCodec.BufferInfo()
            var chunk = ByteArray(1 shl 18)
            var lastWrite = SystemClock.uptimeMillis()
            while (!client.isClosed) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) {
                    if (SystemClock.uptimeMillis() - lastWrite >= KEEPALIVE_MS) {
                        out.writeLong(0L); out.writeInt(0); out.flush()
                        lastWrite = SystemClock.uptimeMillis()
                    }
                    continue
                }
                try {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        var header = if (isConfig) ServerManager.FLAG_CONFIG else info.presentationTimeUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) header = header or ServerManager.FLAG_KEY_FRAME
                        if (info.size > chunk.size) chunk = ByteArray(info.size)
                        buffer.get(chunk, 0, info.size)
                        out.writeLong(header)
                        out.writeInt(info.size)
                        out.write(chunk, 0, info.size)
                        out.flush()
                        lastWrite = SystemClock.uptimeMillis()
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            // L'écran DeX reste en vie (sans surface) pour garder les fenêtres ouvertes
            virtualDisplay?.surface = null
            if (clientSocket === client || clientSocket?.isClosed != false) restoreScreenShareProtection()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            inputSurface.release()
            log("Quest déconnecté")
        }
    }

    private fun createEncoder(): Pair<MediaCodec, Int> {
        for ((mime, id) in listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC to ServerManager.CODEC_H265,
            MediaFormat.MIMETYPE_VIDEO_AVC to ServerManager.CODEC_H264
        )) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FRAME_RATE.toFloat())
                }
                codec = MediaCodec.createEncoderByType(mime)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                log("Encodeur ${codec.name} ($mime)")
                return codec to id
            } catch (e: Exception) {
                log("Encodeur $mime indisponible : ${e.message}")
                codec?.release()
            }
        }
        throw IOException("Aucun encodeur vidéo")
    }

    // ---------------------------------------------------------------- Protection du partage d'écran

    /*
     * Android 15+ marque les applis sensibles (Google Messages, codes de connexion…) comme
     * « sécurisées » pendant un partage d'écran (notre miroir MediaProjection). Un calque sécurisé
     * est noir sur l'écran DeX (écran virtuel non sécurisé). Choix de l'utilisateur : couper cette
     * protection uniquement pendant une session DeX, puis rétablir la valeur d'origine.
     * La valeur d'origine est notée dans un fichier pour être restaurée même après un arrêt brutal.
     */
    private const val SSP_KEY = "disable_screen_share_protections_for_apps_and_notifications"
    private val sspBackup = java.io.File("/data/local/tmp/questmirror-ssp-backup")

    @Synchronized
    private fun disableScreenShareProtection() {
        if (sspBackup.exists()) return // déjà coupée par nous
        val original = runSettings("get", "global", SSP_KEY)?.trim().takeUnless { it.isNullOrEmpty() || it == "null" } ?: "0"
        if (original == "1") return // déjà désactivée par l'utilisateur : rien à rétablir ensuite
        sspBackup.writeText(original)
        runSettings("put", "global", SSP_KEY, "1")
        log("Protection du partage d'écran coupée pendant DeX (valeur d'origine : $original)")
    }

    @Synchronized
    private fun restoreScreenShareProtection() {
        if (!sspBackup.exists()) return
        val original = sspBackup.readText().trim().ifEmpty { "0" }
        runSettings("put", "global", SSP_KEY, original)
        sspBackup.delete()
        log("Protection du partage d'écran rétablie ($original)")
    }

    private fun runSettings(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("settings") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    } catch (e: Exception) {
        log("settings ${args.joinToString(" ")} : ${e.message}")
        null
    }

    // ---------------------------------------------------------------- Écran physique éteint

    @Volatile private var screenClient: Socket? = null
    @Volatile private var panelOff = false

    /**
     * Tant qu'un Quest garde cette connexion ouverte (avec un ping par seconde) et a demandé
     * l'extinction, la dalle physique reste éteinte. À la moindre coupure → dalle rallumée.
     * Android considère toujours l'écran allumé : applis, capture et miroir continuent.
     */
    private fun screenControlLoop() {
        try {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(SCREEN_PORT))
                log("Commande d'écran sur le port $SCREEN_PORT")
                while (true) {
                    val client = ss.accept()
                    try { screenClient?.close() } catch (_: IOException) {}
                    screenClient = client
                    Thread({ handleScreenClient(client) }, "ScreenClient").start()
                }
            }
        } catch (e: Exception) {
            log("Commande d'écran indisponible : ${e.message}")
        }
    }

    private fun handleScreenClient(client: Socket) {
        try {
            client.soTimeout = 5000 // plus de ping depuis 5 s → on considère le Quest parti
            val input = client.getInputStream()
            val output = client.getOutputStream()
            while (true) {
                when (input.read()) {
                    -1 -> break
                    CMD_SCREEN_OFF -> output.write(if (setPanelPower(false)) 1 else 0)
                    CMD_SCREEN_ON -> output.write(if (setPanelPower(true)) 1 else 0)
                    CMD_PING -> {}
                }
                output.flush()
            }
        } catch (e: IOException) {
            // coupure ou délai dépassé
        } finally {
            try { client.close() } catch (_: IOException) {}
            // Sécurité : seul le client actif peut laisser l'écran éteint
            if (screenClient === client && panelOff) {
                log("Quest parti : rallumage de l'écran du téléphone")
                setPanelPower(true)
            }
        }
    }

    /** Allume/éteint la dalle physique sans changer l'état « écran allumé » d'Android. */
    @Synchronized
    private fun setPanelPower(on: Boolean): Boolean {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        return try {
            val sc = Class.forName("android.view.SurfaceControl")
            val setMode = sc.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
            // Android 14+ : getPhysicalDisplayIds/Token ont migré dans DisplayControl (system_server)
            val source: Class<*> = try {
                sc.getMethod("getPhysicalDisplayIds"); sc
            } catch (e: NoSuchMethodException) {
                displayControlClass()
            }
            val ids = source.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val getToken = source.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            for (id in ids) {
                val token = getToken.invoke(null, id) as android.os.IBinder? ?: continue
                setMode.invoke(null, token, mode)
            }
            panelOff = !on
            log(if (on) "Écran du téléphone rallumé" else "Écran du téléphone éteint (${ids.size} dalle(s))")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Impossible de changer l'alimentation de l'écran", t)
            println("Impossible de changer l'alimentation de l'écran : $t")
            false
        }
    }

    private var displayControl: Class<*>? = null

    private fun displayControlClass(): Class<*> {
        displayControl?.let { return it }
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod("createClassLoader", String::class.java, String::class.java,
            String::class.java, ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
        val loader = create.invoke(null, android.system.Os.getenv("SYSTEMSERVERCLASSPATH"), null, null,
            ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val cls = loader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary0 = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary0.isAccessible = true
        loadLibrary0.invoke(Runtime.getRuntime(), cls, "android_servers")
        displayControl = cls
        return cls
    }

    // ---------------------------------------------------------------- Contrôle

    private fun readControl(client: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            while (true) {
                when (val type = input.readUnsignedByte()) {
                    ServerManager.MSG_TOUCH -> {
                        val action = input.readUnsignedByte()
                        val x = input.readFloat()
                        val y = input.readFloat()
                        injectTouch(action, x * WIDTH, y * HEIGHT)
                    }
                    ServerManager.MSG_GLOBAL -> injectGlobal(input.readUnsignedByte())
                    else -> throw IOException("Message de contrôle inconnu : $type")
                }
            }
        } catch (e: IOException) {
            // fin de session
        }
    }

    private fun injectTouch(action: Int, x: Float, y: Float) {
        if (displayId < 0) return
        val now = SystemClock.uptimeMillis()
        val motionAction = when (action) {
            ServerManager.TOUCH_DOWN -> { touchDownTime = now; MotionEvent.ACTION_DOWN }
            ServerManager.TOUCH_MOVE -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_UP
        }
        val event = MotionEvent.obtain(touchDownTime, now, motionAction, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        inject(event)
        event.recycle()
    }

    private fun injectGlobal(action: Int) {
        val keyCode = when (action) {
            ServerManager.GLOBAL_BACK -> KeyEvent.KEYCODE_BACK
            ServerManager.GLOBAL_HOME -> KeyEvent.KEYCODE_HOME
            ServerManager.GLOBAL_RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        for (keyAction in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, keyAction, keyCode, 0, 0,
                KeyEvent.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(event)
        }
    }

    /** InputManager.injectInputEvent (caché) sur l'écran DeX. */
    private fun inject(event: InputEvent) {
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .invoke(inputManager, event, 0)
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Injection impossible", e)
        }
    }
}
