package com.example.phone
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
class MainActivity : AppCompatActivity() {
    companion object { private const val TAG = "PHONE_MAIN" }
    private lateinit var statusText: TextView
    private lateinit var serverManager: ServerManager
    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjectionManager: MediaProjectionManager? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        statusText.text = "Starting..."
        serverManager = ServerManager(this)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        serverManager.onStatusUpdate = { status -> updateStatus(status) }
        serverManager.onError = { error -> updateStatus("ERROR: $error") }
        serverManager.onMediaProjectionRequest = { requestMediaProjection() }
        startServer()
    }
    private fun startServer() {
        Thread {
            try {
                val ipAddress = getLocalIpAddress()
                val success = serverManager.startServer(27183)
                handler.post {
                    if (success) updateStatus("Server started on port 27183\nIP: $ipAddress\nWaiting for connection...")
                    else updateStatus("Failed to start server")
                }
            } catch (e: Exception) { handler.post { updateStatus("Error: ${e.message}") } }
        }.start()
    }
    private fun requestMediaProjection() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) startActivityForResult(intent, 101)
        else updateStatus("Cannot request MediaProjection")
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val projection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                serverManager.setMediaProjection(projection)
                updateStatus("MediaProjection granted - video stream active")
            } else { updateStatus("MediaProjection denied") }
        }
    }
    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text; Log.e(TAG, "STATUS: $text") }
    }
    private fun getLocalIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        } catch (e: Exception) { "Unknown IP" }
    }
    override fun onDestroy() {
        super.onDestroy()
        serverManager.stopServer()
    }
}
