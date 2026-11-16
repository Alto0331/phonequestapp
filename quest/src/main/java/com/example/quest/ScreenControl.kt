package com.example.quest

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Éteint la dalle du téléphone via le programme d'aide (port 27185).
 * La connexion reste ouverte avec un ping par seconde : si elle tombe (appli fermée,
 * Wi-Fi perdu), le programme d'aide rallume tout seul l'écran du téléphone.
 */
class ScreenControl(
    private val host: String,
    private val onState: (off: Boolean, message: String?) -> Unit
) {
    companion object {
        private const val TAG = "ScreenControl"
        private const val PORT = 27185
        private const val CMD_SCREEN_OFF = 0
        private const val CMD_PING = 2
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var thread: Thread? = null

    val isOff get() = socket != null

    fun turnOff() {
        if (thread != null) return
        thread = Thread({ run() }, "ScreenControl").apply { start() }
    }

    /** Fermer la connexion suffit : le programme d'aide rallume l'écran immédiatement. */
    fun turnOn() {
        val s = socket
        socket = null
        thread?.interrupt()
        thread = null
        try { s?.close() } catch (_: IOException) {}
        onState(false, null)
    }

    private fun run() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, PORT), 3000)
            s.soTimeout = 3000
            s.getOutputStream().apply { write(CMD_SCREEN_OFF); flush() }
            if (s.getInputStream().read() != 1) throw IOException("le téléphone a refusé")
            socket = s
            onState(true, null)
            while (socket === s) {
                Thread.sleep(1000)
                s.getOutputStream().apply { write(CMD_PING); flush() }
            }
        } catch (e: InterruptedException) {
            // turnOn()
        } catch (e: IOException) {
            Log.w(TAG, "Commande d'écran : ${e.message}")
            val wasOff = socket === s
            socket = null
            thread = null
            onState(false, if (wasOff) "écran du téléphone rallumé (connexion perdue)"
                else "programme d'aide non lancé → exécute start-dex.bat sur le PC")
        } finally {
            if (socket !== s) try { s.close() } catch (_: IOException) {}
        }
    }
}
