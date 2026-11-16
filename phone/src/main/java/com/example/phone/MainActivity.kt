package com.example.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PORT = 27183
        private const val REQUEST_CODE_PERMISSIONS = 100
        private const val REQUEST_CODE_MEDIA_PROJECTION = 101
    }

    private lateinit var textView: TextView
    private lateinit var serverManager: ServerManager
    private lateinit var handler: Handler
    private var isRunning = false
    private var mediaProjectionManager: MediaProjectionManager? = null
    private val requiredPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.SYSTEM_ALERT_WINDOW
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.text_view)
        val button = findViewById<Button>(R.id.start_button)

        serverManager = ServerManager(this)
        handler = Handler(Looper.getMainLooper())
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Demander les permissions
        requestPermissions()

        // Démarrer automatiquement le serveur
        button.setOnClickListener {
            if (!isRunning) {
                startServer()
            } else {
                stopServer()
            }
        }

        // Démarrer automatiquement au lancement
        startServer()
    }

    private fun requestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest,
                REQUEST_CODE_PERMISSIONS
            )
        } else {
            // Toutes les permissions sont accordées, demander MediaProjection
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        try {
            val projectionIntent = mediaProjectionManager?.createScreenCaptureIntent()
            if (projectionIntent != null) {
                startActivityForResult(projectionIntent, REQUEST_CODE_MEDIA_PROJECTION)
            } else {
                Log.e(TAG, "Impossible de créer l'intent MediaProjection")
                textView.text = "Erreur: Impossible de demander MediaProjection"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur demande MediaProjection", e)
            textView.text = "Erreur MediaProjection: ${e.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // MediaProjection accordée, démarrer le serveur avec la projection
                val projection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                serverManager.setMediaProjection(projection)
                
                if (!isRunning) {
                    startServer()
                }
            } else {
                Log.e(TAG, "MediaProjection refusée")
                textView.text = "MediaProjection refusée. Impossible de capturer l'écran."
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                requestMediaProjection()
            } else {
                Log.e(TAG, "Permissions refusées")
                textView.text = "Permissions refusées"
            }
        }
    }

    private fun startServer() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipAddress = Formatter.formatIpAddress(wifiInfo.ipAddress)

        textView.text = "Démarrage du serveur sur $ipAddress:$PORT..."

        Thread {
            try {
                if (serverManager.startServer(PORT)) {
                    isRunning = true
                    handler.post {
                        updateUI()
                        textView.text = "Serveur démarré sur $ipAddress:$PORT"
                    }
                } else {
                    handler.post {
                        textView.text = "Échec du démarrage du serveur"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    textView.text = "Erreur: ${e.message}"
                }
                Log.e(TAG, "Erreur", e)
            }
        }.start()
    }

    private fun stopServer() {
        serverManager.stopServer()
        isRunning = false
        updateUI()
        textView.text = "Serveur arrêté"
    }

    private fun updateUI() {
        findViewById<Button>(R.id.start_button).text = if (isRunning) "Arrêter" else "Démarrer"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            serverManager.stopServer()
            isRunning = false
        }
    }
}
