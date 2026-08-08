package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small "now playing" indicator for list rows (Library) -- a lightweight
 * self-animated 3-bar equalizer icon, not tied to real audio capture.
 * Keeps things simple and avoids creating extra android.media.audiofx
 * effect instances per row.
 */
class MiniEqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var playing = false
    private var phase = 0f
    private val paint = Paint().apply { isAntiAlias = true; color = 0xFF00E5FF.toInt() }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        if (playing) postInvalidateOnAnimation()
        else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val barCount = 3
        val barWidth = w / (barCount * 2f)
        if (playing) phase += 0.35f

        for (i in 0 until barCount) {
            val amp = if (playing) 0.3f + 0.6f * abs(sin(phase + i * 1.1f)) else 0.15f
            val barH = h * amp
            val x = barWidth * (i * 2 + 0.5f)
            canvas.drawRoundRect(x, h - barH, x + barWidth, h, 2f, 2f, paint)
        }

        if (playing) postInvalidateOnAnimation()
    }
}
