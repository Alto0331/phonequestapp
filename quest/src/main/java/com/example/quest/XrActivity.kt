package com.example.quest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Fenêtre 2D qui affiche un flux du téléphone et renvoie les touchers.
 * Mode normal : miroir de l'écran (port 27183). Sous-classe DexActivity : bureau DeX (port 27184).
 */
open class XrActivity : Activity() {
    companion object {
        private const val TAG = "XrActivity"
        const val EXTRA_IP = "IP"
        const val EXTRA_PORT = "PORT"
        const val DEFAULT_IP = "192.168.1.156"
        const val DEFAULT_PORT = 27183
        const val DEX_PORT = 27184
    }

    /** true pour le panneau DeX (voir DexActivity). */
    protected open val dexMode = false

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var renderer: VideoRenderer
    private var screenControl: ScreenControl? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var loggedInputSource = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xr)
        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.text_view)

        val ip = intent.getStringExtra(EXTRA_IP) ?: DEFAULT_IP
        val port = if (dexMode) DEX_PORT else intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)

        renderer = VideoRenderer(
            onStatus = { msg ->
                val text = if (dexMode && msg.contains("refused", ignoreCase = true))
                    "DeX : programme d'aide non lancé → exécute start-dex.bat sur le PC (nouvelle tentative…)"
                else msg
                runOnUiThread {
                    // Flux en cours (message « NN fps ») : image seule, sans texte par-dessus
                    val streaming = text.endsWith(" fps")
                    statusText.visibility = if (streaming) android.view.View.GONE else android.view.View.VISIBLE
                    statusText.text = "$ip:$port — $text"
                }
            },
            onVideoSize = { w, h -> runOnUiThread { videoWidth = w; videoHeight = h; fitSurface() } }
        )

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = renderer.setSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.setSurface(null)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }

        // Pincement / gâchette sur l'image → toucher sur le téléphone (coordonnées normalisées)
        surfaceView.setOnTouchListener { v, event ->
            if (!loggedInputSource) {
                Log.i(TAG, "Source d'entrée : 0x%x".format(event.source))
                loggedInputSource = true
            }
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> VideoRenderer.TOUCH_DOWN
                MotionEvent.ACTION_MOVE -> VideoRenderer.TOUCH_MOVE
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> VideoRenderer.TOUCH_UP
                else -> return@setOnTouchListener true
            }
            val x = (event.x / v.width).coerceIn(0f, 1f)
            val y = (event.y / v.height).coerceIn(0f, 1f)
            renderer.sendTouch(action, x, y)
            true
        }

        findViewById<android.view.View>(R.id.button_back).setOnClickListener {
            renderer.sendGlobalAction(VideoRenderer.GLOBAL_BACK)
        }
        findViewById<android.view.View>(R.id.button_home).setOnClickListener {
            renderer.sendGlobalAction(VideoRenderer.GLOBAL_HOME)
        }
        findViewById<android.view.View>(R.id.button_recents).setOnClickListener {
            renderer.sendGlobalAction(VideoRenderer.GLOBAL_RECENTS)
        }

        val volumeButton = findViewById<android.view.View>(R.id.button_volume)
        val dexButton = findViewById<android.view.View>(R.id.button_dex)
        val screenButton = findViewById<android.widget.Button>(R.id.button_screen)
        if (!dexMode) {
            // Fenêtre du téléphone sans boutons : tout est dans le panneau de commandes
            findViewById<android.view.View>(R.id.nav_bar).visibility = android.view.View.GONE
        }
        if (dexMode) {
            screenButton.visibility = android.view.View.GONE
            // Le panneau DeX garde Retour / Accueil / Récents, le reste est sur la fenêtre principale
            volumeButton.visibility = android.view.View.GONE
            dexButton.visibility = android.view.View.GONE
        } else {
            volumeButton.setOnClickListener { openVolumePanel() }
            dexButton.setOnClickListener { openDexPanel(ip) }
            // ☀ = écran du téléphone allumé ; ● = dalle éteinte, le miroir continue
            val control = ScreenControl(ip) { off, message ->
                runOnUiThread {
                    MirrorControls.setScreenOff(off)
                    if (message != null) statusText.text = "Écran : $message"
                }
            }
            screenControl = control
            MirrorControls.sendGlobal = { renderer.sendGlobalAction(it) }
            MirrorControls.toggleScreen = { if (control.isOff) control.turnOn() else control.turnOff() }
            MirrorControls.openDex = { openDexPanel(ip) }
        }

        VolumeSettings.load(this)
        statusText.text = "Connexion à $ip:$port…"
        renderer.start(ip, port)
        // (le panneau de commandes est ouvert par onResume, collé à la fenêtre du téléphone)
    }

    @Suppress("DEPRECATION")
    private val wifiLock by lazy {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm.createWifiLock(mode, "QuestMirror:$localClassName").apply { setReferenceCounted(false) }
    }

    override fun onPause() {
        wifiLock.release()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Wi-Fi sans mise en veille pendant que le flux est affiché → moins de saccades
        wifiLock.acquire()
        // Le panneau de commandes a été fermé : on le rouvre (c'est là que sont les boutons)
        if (!dexMode && VolumeActivity.instance == null) root.post { openVolumePanel() }
    }

    /** Ouvre (ou ramène) le panneau volume dans sa propre tâche = panneau séparé. */
    private fun openVolumePanel() {
        startActivity(Intent(this, VolumeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ouvre (ou ramène) le grand panneau paysage du bureau DeX. */
    private fun openDexPanel(ip: String) {
        startActivity(Intent(this, DexActivity::class.java)
            .putExtra(EXTRA_IP, ip)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Garde le ratio de l'écran du téléphone (pas d'étirement). */
    private fun fitSurface() {
        if (videoWidth <= 0 || videoHeight <= 0 || root.width == 0 || root.height == 0) return
        val scale = minOf(root.width.toFloat() / videoWidth, root.height.toFloat() / videoHeight)
        val w = (videoWidth * scale).toInt()
        val h = (videoHeight * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    override fun onDestroy() {
        // Fermer la fenêtre principale ferme aussi le panneau volume
        if (isFinishing && !dexMode) {
            VolumeActivity.instance?.finishAndRemoveTask()
            DexActivity.instance?.finishAndRemoveTask()
        }
        screenControl?.turnOn()
        if (!dexMode) MirrorControls.clear()
        renderer.stop()
        super.onDestroy()
    }
}
