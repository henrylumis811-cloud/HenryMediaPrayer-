package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * "Shader-style" audio-reactive backdrop, built with plain Canvas drawing
 * (no GL context needed, so it can't crash on GPU-constrained devices).
 * A handful of soft blurred color blobs drift and pulse continuously; their
 * pulse size responds to live playback amplitude, and their colors follow
 * the current track's dominant palette (see AltarFragment / Palette usage).
 */
class ShaderBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var amplitude = 0.2f
    private var phase = 0f
    private var colors = intArrayOf(0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt())

    private val blobPaint = Paint().apply {
        isAntiAlias = true
        maskFilter = BlurMaskFilter(80f, BlurMaskFilter.Blur.NORMAL)
    }

    fun setAmplitude(value: Float) {
        amplitude = value.coerceIn(0f, 1f)
    }

    fun setPaletteColors(newColors: List<Int>) {
        if (newColors.isNotEmpty()) {
            colors = newColors.take(3).toIntArray()
            if (colors.size < 3) colors = IntArray(3) { colors[it % colors.size] }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        phase += 0.015f
        val pulse = 0.6f + amplitude * 0.8f

        drawBlob(canvas, w * 0.3f + w * 0.08f * sin(phase), h * 0.25f + h * 0.05f * sin(phase * 1.3f), w * 0.45f * pulse, colors[0], 70)
        drawBlob(canvas, w * 0.7f + w * 0.06f * sin(phase * 0.8f + 1f), h * 0.35f + h * 0.06f * sin(phase * 0.9f), w * 0.4f * pulse, colors[1], 60)
        drawBlob(canvas, w * 0.5f + w * 0.1f * sin(phase * 0.6f + 2f), h * 0.75f + h * 0.05f * sin(phase * 1.1f), w * 0.5f * pulse, colors[2], 55)

        postInvalidateOnAnimation()
    }

    private fun drawBlob(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        blobPaint.color = color
        blobPaint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, blobPaint)
    }
}
