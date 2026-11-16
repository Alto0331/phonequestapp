package com.example.quest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

class XrActivity : Activity() {
    companion object {
        private const val TAG = "XrActivity"
    }

    private lateinit var videoRenderer: VideoRenderer
    private lateinit var statusText: TextView
    private var ip: String = ""
    private var port: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xr)

        ip = intent.getStringExtra("IP") ?: "192.168.1.34"
        port = intent.getIntExtra("PORT", 27183)

        statusText = findViewById(R.id.text_view)
        statusText.text = "Connexion a $ip:$port..."

        videoRenderer = VideoRenderer(this, null)
        videoRenderer.onError = { error ->
            Log.e(TAG, "Error: $error")
            runOnUiThread {
                statusText.text = "ERREUR: $error"
            }
        }
        videoRenderer.onStatusUpdate = { status ->
            Log.d(TAG, "Status: $status")
            runOnUiThread {
                statusText.text = status
            }
        }

        connectToServer()
    }

    private fun connectToServer() {
        Thread {
            try {
                videoRenderer.connect(ip, port)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                runOnUiThread {
                    statusText.text = "Erreur: ${e.message}"
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoRenderer.disconnect()
    }
}
