package com.example.quest

import android.util.Log

class Core {
    companion object {
        private const val TAG = "Core"
        
        init {
            System.loadLibrary("core")
            Log.d(TAG, "Librairie core chargée")
        }
    }

    // Fonctions natives declarées
    private external fun nativeConnect(ip: String, port: Int): Boolean
    private external fun nativeHandshake(): Boolean
    private external fun nativeGetWidth(): Int
    private external fun nativeGetHeight(): Int
    private external fun nativeDisconnect()
    private external fun nativeReadFrame(buffer: ByteArray, offset: Int, length: Int): Int

    // Wrapper Kotlin
    fun connect(ip: String, port: Int): Boolean {
        Log.d(TAG, "connect: $ip:$port")
        return nativeConnect(ip, port)
    }

    fun handshake(): Boolean {
        Log.d(TAG, "handshake")
        return nativeHandshake()
    }

    fun getWidth(): Int {
        return nativeGetWidth()
    }

    fun getHeight(): Int {
        return nativeGetHeight()
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        nativeDisconnect()
    }
    
    /**
     * Lit des données depuis le socket
     * @return nombre de bytes lus, -1 en cas d'erreur, 0 si connexion fermée
     */
    fun readFrame(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        return nativeReadFrame(buffer, offset, length)
    }
}
