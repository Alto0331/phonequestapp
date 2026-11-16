package com.example.phone

import android.content.Context
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Micro-overlay 1×1 px qui change (imperceptiblement) de couleur à chaque vsync.
 * Force SurfaceFlinger à recomposer en continu → l'encodeur reçoit ~60 images/s
 * même sur un écran statique (sinon 0-10 fps et reprise saccadée).
 */
class Heartbeat(private val context: Context, private val windowType: Int) : Choreographer.FrameCallback {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var toggle = false

    /** À appeler sur le thread principal. */
    fun start(): Boolean {
        if (view != null) return true
        val v = View(context)
        val lp = WindowManager.LayoutParams(
            1, 1, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Pendant une session Quest, le téléphone ne se met pas en veille
                // (indispensable quand la dalle est éteinte depuis le casque)
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "QuestMirrorHeartbeat"
        }
        return try {
            windowManager.addView(v, lp)
            view = v
            Choreographer.getInstance().postFrameCallback(this)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val v = view ?: return
        toggle = !toggle
        // Noir quasi transparent (alpha 1/255 ou 2/255) : invisible à l'œil
        v.setBackgroundColor(if (toggle) 0x01000000 else 0x02000000)
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** À appeler sur le thread principal. */
    fun stop() {
        Choreographer.getInstance().removeFrameCallback(this)
        view?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    val isRunning get() = view != null
}
