package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.henrylumis.mediaprayer.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Compact now-playing indicator for library rows.
 * Uses a small, smooth five-bar animation rather than audio capture so it is
 * cheap enough to use in a RecyclerView while still feeling alive.
 */
class MiniEqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var playing = false
    private var phase = 0f
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_cyan)
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_cyan)
        style = Paint.Style.FILL
        alpha = 42
    }
    private val rect = RectF()

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        if (playing) postInvalidateOnAnimation() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val count = 5
        val gap = max(1f, w * 0.075f)
        val barWidth = max(1.5f, (w - gap * (count - 1)) / count)
        val radius = barWidth * 0.42f
        val minHeight = h * 0.18f
        val maxHeight = h * 0.92f

        for (i in 0 until count) {
            val x = i * (barWidth + gap)
            val normalized = if (playing) {
                // Different frequencies/phases give the bars a more natural rhythm.
                val primary = (sin(phase * (1.05f + i * 0.045f) + i * 1.17f) + 1f) * 0.5f
                val secondary = (cos(phase * 0.63f - i * 0.72f) + 1f) * 0.5f
                (primary * 0.72f + secondary * 0.28f)
            } else {
                0.32f + i * 0.025f
            }

            val barH = minHeight + (maxHeight - minHeight) * normalized.coerceIn(0f, 1f)
            val top = h - barH
            rect.set(x, top, x + barWidth, h)

            if (playing) {
                // A very subtle glow makes the indicator read better against glass cards.
                val glow = RectF(rect.left - 0.7f, rect.top - 0.7f, rect.right + 0.7f, rect.bottom)
                canvas.drawRoundRect(glow, radius + 0.5f, radius + 0.5f, glowPaint)
            }
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }

        if (playing) {
            phase += 0.16f
            postInvalidateOnAnimation()
        }
    }
}
