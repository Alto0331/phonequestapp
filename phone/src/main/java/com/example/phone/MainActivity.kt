package com.example.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
        private const val REQUEST_CODE = 100
    }

    private lateinit var textView: TextView
    private lateinit var serverManager: ServerManager
    private lateinit var handler: Handler
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.text_view)
        val button = findViewById<Button>(R.id.start_button)

        serverManager = ServerManager(this)
        handler = Handler(Looper.getMainLooper())

        // Demander les permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.ACCESS_WIFI_STATE
                ),
                REQUEST_CODE
            )
        }

        // Démarrer automatiquement le serveur
        button.setOnClickListener {
            if (!isRunning) {
                startServer()
            } else {
                stopServer()
            }
        }

        // Démarrer automatiquement au lanceement
        startServer()
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
