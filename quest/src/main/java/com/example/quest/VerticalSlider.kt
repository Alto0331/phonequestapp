package com.example.quest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Curseur vertical simple (0 en bas, 1 en haut), pensé pour le pointeur/pincement du Quest. */
class VerticalSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var value = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** Appelé pendant le glissement (fromUser = true). */
    var onValueChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C8DFF.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()
    private val trackWidth = 10 * density
    private val thumbRadius = 14 * density

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val top = thumbRadius
        val bottom = height - thumbRadius
        val y = bottom - (bottom - top) * value
        rect.set(cx - trackWidth / 2, top, cx + trackWidth / 2, bottom)
        canvas.drawRoundRect(rect, trackWidth / 2, trackWidth / 2, trackPaint)
        rect.set(cx - trackWidth / 2, y, cx + trackWidth / 2, bottom)
        canvas.drawRoundRect(rect, trackWidth / 2, trackWidth / 2, fillPaint)
        canvas.drawCircle(cx, y, thumbRadius, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = thumbRadius
                val bottom = height - thumbRadius
                value = (bottom - event.y) / (bottom - top)
                onValueChanged?.invoke(value)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
