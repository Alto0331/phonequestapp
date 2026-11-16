package com.example.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_NOTIFICATION_PERMISSION = 2
        private const val MAX_SIZE = 1920
    }

    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var controlButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startButton = findViewById(R.id.start_button)
        statusText = findViewById(R.id.text_view)
        controlButton = findViewById(R.id.control_button)
        controlButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startButton.setOnClickListener {
            if (MediaProjectionService.isRunning) {
                stopService(Intent(this, MediaProjectionService::class.java))
            } else {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            }
        }

        // Notifications (service) + son (capture de la lecture des applis)
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_NOTIFICATION_PERMISSION)
        }
    }

    override fun onStart() {
        super.onStart()
        MediaProjectionService.statusListener = { refreshUi(it) }
        refreshUi(MediaProjectionService.lastStatus)
    }

    override fun onStop() {
        MediaProjectionService.statusListener = null
        super.onStop()
    }

    @Deprecated("API startActivityForResult conservée pour rester sans dépendance")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            refreshUi("Capture refusée")
            return
        }
        val (width, height, dpi) = captureSize()
        val intent = Intent(this, MediaProjectionService::class.java).apply {
            putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
            putExtra(MediaProjectionService.EXTRA_WIDTH, width)
            putExtra(MediaProjectionService.EXTRA_HEIGHT, height)
            putExtra(MediaProjectionService.EXTRA_DPI, dpi)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /** Taille de capture : écran réel réduit à MAX_SIZE sur le grand côté, multiples de 16 (encodeur). */
    private fun captureSize(): Triple<Int, Int, Int> {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            w = bounds.width(); h = bounds.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        val scale = minOf(1.0, MAX_SIZE.toDouble() / max(w, h))
        fun align16(v: Double) = max(16, (v / 16).roundToInt() * 16)
        return Triple(align16(w * scale), align16(h * scale), resources.displayMetrics.densityDpi)
    }

    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    private fun refreshUi(status: String) {
        val ips = localIps().joinToString("\n") { "$it:${ServerManager.PORT}" }.ifEmpty { "(pas de réseau)" }
        val controlActive = ControlService.instance != null
        val control = if (controlActive) "actif ✓" else "inactif — activer « QuestMirror » dans Accessibilité"
        statusText.text = "$status\n\nAdresse(s) :\n$ips\n\nContrôle depuis le casque + 60 fps : $control"
        controlButton.visibility = if (controlActive) View.GONE else View.VISIBLE
        startButton.text = if (MediaProjectionService.isRunning) "Arrêter" else "Démarrer"
    }
}
