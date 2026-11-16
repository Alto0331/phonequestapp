package com.example.quest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView

class XrActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "XrActivity"
    }

    private lateinit var videoRenderer: VideoRenderer
    private lateinit var surfaceView: SurfaceView
    private lateinit var textView: TextView
    private var ip: String = ""
    private var port: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xr)

        ip = intent.getStringExtra("IP") ?: "192.168.1.34"
        port = intent.getIntExtra("PORT", 27183)

        surfaceView = findViewById(R.id.surface_view)
        textView = findViewById(R.id.text_view)

        surfaceView.holder.addCallback(this)

        videoRenderer = VideoRenderer(this, surfaceView)
        videoRenderer.onError = { error ->
            runOnUiThread {
                textView.text = "ERREUR: $error"
            }
            Log.e(TAG, "VideoRenderer erreur: $error")
        }
        videoRenderer.onStatusUpdate = { status ->
            // Masquer le TextView quand on recoit du vrai flux
            if (status.contains("Connecte") || status.contains("HEVC")) {
                runOnUiThread { statusText.visibility = android.view.View.GONE }
            }
            runOnUiThread {
                statusText.text = status
            }
            Log.d(TAG, "Status: $status")
        }

        textView.text = "Démarrage..."

        connectToServer()
    }

    private fun connectToServer() {
        textView.text = "Connexion à $ip:$port..."
        videoRenderer.connect(ip, port)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        videoRenderer.notifySurfaceCreated(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: $width x $height")
        videoRenderer.notifySurfaceChanged(format, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        videoRenderer.notifySurfaceDestroyed()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoRenderer.disconnect()
    }
}
