package com.example.phone

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Service de premier plan qui détient la MediaProjection et fait tourner le serveur.
 * Android 14+ : startForeground(type MEDIA_PROJECTION) doit être appelé APRÈS le
 * consentement utilisateur et AVANT getMediaProjection() — d'où le passage du résultat ici.
 */
class MediaProjectionService : Service() {
    companion object {
        private const val TAG = "MediaProjectionService"
        const val CHANNEL_ID = "media_projection_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"

        @Volatile var isRunning = false
            private set
        @Volatile var lastStatus = "Arrêté"
            private set
        /** Écouteur d'état (même processus), appelé sur le thread principal. */
        var statusListener: ((String) -> Unit)? = null
    }

    private var server: ServerManager? = null

    /** Wi-Fi sans économie d'énergie pendant le miroir (les paquets ne sont plus retenus). */
    @Suppress("DEPRECATION")
    private val wifiLock by lazy {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm.createWifiLock(mode, "QuestMirror:stream").apply { setReferenceCounted(false) }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_NOT_STICKY

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Le type « micro » est requis pour capter le son en arrière-plan (Android 14+)
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (audioPermitted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            publishStatus("Autorisation de capture manquante")
            stopSelf()
            return START_NOT_STICKY
        }

        val width = intent!!.getIntExtra(EXTRA_WIDTH, 1080)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        val dpi = intent.getIntExtra(EXTRA_DPI, 420)

        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = pm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection a renvoyé null")
            server = ServerManager(
                projection, width, height, dpi,
                onStatus = ::publishStatus,
                onStopped = { mainHandler.post { stopSelf() } },
                onSessionActive = { active -> mainHandler.post { setHeartbeat(active) } },
                onTouch = { action, x, y ->
                    mainHandler.post { ControlService.instance?.injectTouch(action, x, y) }
                },
                onGlobalAction = { action ->
                    mainHandler.post { ControlService.instance?.globalAction(action) }
                },
                audioEnabled = audioPermitted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ).also { it.start() }
            if (!audioPermitted()) Log.w(TAG, "Son désactivé : autorisation micro refusée")
            isRunning = true
            wifiLock.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de démarrer la capture", e)
            publishStatus("Erreur capture : ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun audioPermitted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var fallbackHeartbeat: Heartbeat? = null

    private var sessionActive = false

    /** Vérifie toutes les 2 s, pendant une session, que le heartbeat tourne toujours. */
    private val heartbeatWatchdog = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            ensureHeartbeat()
            mainHandler.postDelayed(this, 2000)
        }
    }

    /** Heartbeat 60 fps pendant une session Quest (thread principal). */
    private fun setHeartbeat(enabled: Boolean) {
        sessionActive = enabled
        mainHandler.removeCallbacks(heartbeatWatchdog)
        if (enabled) {
            ensureHeartbeat()
            mainHandler.postDelayed(heartbeatWatchdog, 2000)
        } else {
            ControlService.instance?.setHeartbeat(false)
            fallbackHeartbeat?.stop()
        }
    }

    /**
     * Priorité à la superposition classique : les superpositions d'accessibilité sont exclues de
     * la capture d'écran, leurs changements ne produisent donc aucune image dans le miroir
     * (mesuré : 10 fps au lieu de 60). Le service d'accessibilité n'est qu'un dernier recours.
     * Relancé par le chien de garde si la fenêtre disparaît (reconnexion du service, etc.).
     */
    private fun ensureHeartbeat() {
        val canOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(this)
        if (canOverlay) {
            if (fallbackHeartbeat?.isRunning == true) return
            val hb = fallbackHeartbeat ?: Heartbeat(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                .also { fallbackHeartbeat = it }
            if (hb.start()) {
                ControlService.instance?.setHeartbeat(false)
                Log.i(TAG, "Heartbeat (re)lancé via la superposition")
                return
            }
        }
        val control = ControlService.instance
        if (control?.isHeartbeatRunning == true) return
        if (control != null && control.setHeartbeat(true)) {
            Log.w(TAG, "Heartbeat via l'accessibilité (moins efficace) : autoriser la superposition pour QuestMirror")
            return
        }
        Log.w(TAG, "Heartbeat inactif : autoriser la superposition pour QuestMirror")
    }

    private fun publishStatus(message: String) {
        Log.i(TAG, message)
        lastStatus = message
        mainHandler.post { statusListener?.invoke(message) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen Mirroring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quest Mirror")
            .setContentText("Partage de l'écran vers le Quest")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (wifiLock.isHeld) wifiLock.release()
        setHeartbeat(false)
        server?.stop()
        server = null
        isRunning = false
        publishStatus("Arrêté")
        super.onDestroy()
    }
}
