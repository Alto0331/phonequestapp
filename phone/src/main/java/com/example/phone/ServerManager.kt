package com.example.phone
import android.content.Context
import android.media.projection.MediaProjection
import java.net.ServerSocket
class ServerManager(private val context: Context) {
    companion object { private const val TAG = "ServerManager" }
    private var serverSocket: ServerSocket? = null
    private var mediaProjection: MediaProjection? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMediaProjectionRequest: (() -> Unit)? = null
    fun startServer(port: Int): Boolean {
        try { serverSocket = ServerSocket(port); return true } catch (e: Exception) { return false }
    }
    fun setMediaProjection(projection: MediaProjection?) { mediaProjection = projection }
    fun stopServer() { serverSocket?.close(); serverSocket = null }
}
