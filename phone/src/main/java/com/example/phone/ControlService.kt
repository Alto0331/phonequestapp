package com.example.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * Service d'accessibilité : seul moyen pour une appli normale d'injecter des touches
 * sans adb (dispatchGesture). Héberge aussi le heartbeat (overlay d'accessibilité,
 * aucune autorisation « superposition » nécessaire).
 *
 * Chaque geste est envoyé d'un seul bloc au relâchement : les traits « continués »
 * (glisser en direct) sont annulés aléatoirement par Android, ce qui laissait le doigt posé.
 */
class ControlService : AccessibilityService() {
    companion object {
        private const val TAG = "ControlService"
        /** Tolérance de mouvement pour qu'un pincement reste un tap (tremblement de la main). */
        private const val TOUCH_SLOP_DP = 12f
        private const val MAX_GESTURE_MS = 10_000L
        private const val LONG_PRESS_MS = 600L
        private const val TAP_MS = 50L

        @Volatile var instance: ControlService? = null
            private set
    }

    private data class TouchPoint(val action: Int, val x: Float, val y: Float, val time: Long)

    private val pending = ArrayDeque<TouchPoint>()
    /** Points du geste en cours (du DOWN jusqu'au UP). */
    private val gesture = ArrayList<TouchPoint>()
    private var heartbeat: Heartbeat? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service de contrôle connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        heartbeat?.stop()
        heartbeat = null
        if (instance === this) instance = null
    }

    val isHeartbeatRunning get() = heartbeat?.isRunning == true

    /** Heartbeat via overlay d'accessibilité. Thread principal. */
    fun setHeartbeat(enabled: Boolean): Boolean {
        if (enabled) {
            val hb = heartbeat ?: Heartbeat(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { heartbeat = it }
            return hb.start()
        }
        heartbeat?.stop()
        return false
    }

    /** Bouton système (retour / accueil / applis récentes). Thread principal. */
    fun globalAction(action: Int) {
        val global = when (action) {
            ServerManager.GLOBAL_BACK -> GLOBAL_ACTION_BACK
            ServerManager.GLOBAL_HOME -> GLOBAL_ACTION_HOME
            ServerManager.GLOBAL_RECENTS -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(global)
    }

    /** Événement tactile, coordonnées normalisées 0..1. Thread principal. */
    fun injectTouch(action: Int, nx: Float, ny: Float) {
        val (w, h) = screenSize()
        val x = (nx * w).coerceIn(0f, w - 1f)
        val y = (ny * h).coerceIn(0f, h - 1f)
        pending.addLast(TouchPoint(action, x, y, SystemClock.uptimeMillis()))
        flush()
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /**
     * Traite les événements reçus : le geste est accumulé puis envoyé d'un bloc au relâchement.
     * Un geste complet contient toujours son UP → impossible de rester « appuyé ».
     */
    private fun flush() {
        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            when (p.action) {
                ServerManager.TOUCH_DOWN -> {
                    gesture.clear()
                    gesture.add(p)
                }
                ServerManager.TOUCH_MOVE -> if (gesture.isNotEmpty()) gesture.add(p)
                ServerManager.TOUCH_UP -> if (gesture.isNotEmpty()) {
                    gesture.add(p)
                    dispatchCompleteGesture(gesture.toList())
                    gesture.clear()
                }
            }
        }
    }

    private fun dispatchCompleteGesture(points: List<TouchPoint>) {
        val down = points.first()
        val up = points.last()
        val realDuration = (up.time - down.time).coerceIn(1L, MAX_GESTURE_MS)
        // Tremblement de la main au pincement : tant qu'on reste dans la zone de tolérance,
        // c'est un tap (ou un appui long) au point de départ, pas un glissement.
        val slop = TOUCH_SLOP_DP * resources.displayMetrics.density
        val moved = points.any { hypot(it.x - down.x, it.y - down.y) > slop }
        // Pincement court sans mouvement = tap net ; au-delà de LONG_PRESS_MS = appui long réel
        val duration = if (!moved && realDuration < LONG_PRESS_MS) TAP_MS else realDuration
        val path = Path().apply {
            moveTo(down.x, down.y)
            if (moved) points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Geste annulé")
                }
            }, null)
            Log.d(TAG, "Geste ${if (moved) "glissement" else "tap"} ${duration} ms, ${points.size} pts, envoyé=$ok")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Geste invalide", e)
        }
    }
}
