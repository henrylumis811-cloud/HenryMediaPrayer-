package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.henrylumis.mediaprayer.player.PlayerManager
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Kotlin/Canvas re-implementation of the web app's rotating mandala / arc
 * reactor visualizer. Three modes cycle the same way the original did:
 * MANDALA (rings + ticks + core), WAVEFORM (circular oscilloscope trace),
 * and SPECTRUM (radial frequency bars).
 */
class AltarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MODE_MANDALA = 0
        const val MODE_WAVEFORM = 1
        const val MODE_SPECTRUM = 2
    }

    var visMode: Int = MODE_MANDALA

    var cyan = Color.parseColor("#4FD8FF")
    var gold = Color.parseColor("#FFB454")
    var ink = Color.parseColor("#E8EEF2")

    private var rot = 0f
    private var lastFrameNanos = 0L

    private var waveform: ByteArray = ByteArray(0)
    private var fft: ByteArray = ByteArray(0)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(64, 79, 216, 255)
    }

    private val running = object : Runnable {
        override fun run() {
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PlayerManager.visualizerListener = { wf, ft -> waveform = wf; fft = ft }
        postOnAnimation(running)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        PlayerManager.visualizerListener = null
        removeCallbacks(running)
    }

    private fun bandAvg(from: Int, to: Int): Float {
        if (fft.isEmpty()) return 0f
        var sum = 0f
        var n = 0
        var i = from
        while (i < to && i < fft.size) {
            // fft bytes from Visualizer are signed magnitudes; normalize to 0..1
            sum += (fft[i].toInt() and 0xFF) / 255f
            n++
            i++
        }
        return if (n > 0) sum / n else 0f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) * 0.42f

        val now = System.nanoTime()
        val dtSec = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        val playing = PlayerManager.player.isPlaying
        val bass = bandAvg(1, 10)
        val mid = bandAvg(10, 60)
        val treble = bandAvg(60, 160)
        val level = (bass + mid + treble) / 3f

        if (!PlayerManager.reduceMotion) rot += (0.9f + (if (playing) bass * 4f else 0f)) * dtSec

        // soft glow backdrop
        fillPaint.shader = RadialGradient(
            cx, cy, radius * 1.4f,
            Color.argb((25 + level * 40).toInt().coerceIn(0, 255), 79, 216, 255),
            Color.argb(0, 79, 216, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null

        when (visMode) {
            MODE_MANDALA -> drawMandala(canvas, cx, cy, radius, level, playing)
            MODE_WAVEFORM -> drawWaveform(canvas, cx, cy, radius)
            else -> drawSpectrum(canvas, cx, cy, radius, playing)
        }

        outlinePaint.color = Color.argb(64, 79, 216, 255)
        canvas.drawCircle(cx, cy, radius * 1.02f, outlinePaint)
    }

    private fun drawMandala(canvas: Canvas, cx: Float, cy: Float, radius: Float, level: Float, playing: Boolean) {
        val rings = 3
        for (ring in 0 until rings) {
            val rr = radius * (0.55f + ring * 0.22f)
            val ticks = 48
            canvas.save()
            canvas.translate(cx, cy)
            val dir = if (ring % 2 == 0) 1f else -1f
            canvas.rotate(Math.toDegrees((rot * dir * (1 + ring * 0.3f)).toDouble()).toFloat())
            for (i in 0 until ticks) {
                val a = (i.toFloat() / ticks) * (2 * Math.PI)
                val bin = ((i.toFloat() / ticks) * fft.size * 0.5f).toInt()
                val amp = if (playing) bandAvg(bin, bin + 1) else 0.08f
                val len = 6 + amp * 22 * (1 - ring * 0.15f)
                val hue = if (ring == 0) 190f else if (ring == 1) 200f else 38f
                ringPaint.color = Color.HSVToColor(
                    (90 + amp * 140).toInt().coerceIn(0, 255),
                    floatArrayOf(hue, 0.9f, (0.6f + amp * 0.2f).coerceIn(0f, 1f))
                )
                ringPaint.strokeWidth = 3f
                val x1 = (cos(a) * rr).toFloat()
                val y1 = (sin(a) * rr).toFloat()
                val x2 = (cos(a) * (rr + len)).toFloat()
                val y2 = (sin(a) * (rr + len)).toFloat()
                canvas.drawLine(x1, y1, x2, y2, ringPaint)
            }
            canvas.restore()
        }

        val coreR = radius * 0.34f * (1 + level * 0.12f)
        fillPaint.shader = RadialGradient(
            cx, cy, coreR,
            Color.argb(230, 79, 216, 255),
            Color.argb(0, 79, 216, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, fillPaint)
        fillPaint.shader = null

        outlinePaint.color = Color.argb(230, 232, 238, 242)
        outlinePaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, radius * 0.34f, outlinePaint)
        outlinePaint.color = Color.argb(140, 255, 180, 84)
        canvas.drawCircle(cx, cy, radius * 0.15f, outlinePaint)

        val motes = 6
        fillPaint.color = Color.argb(200, 255, 180, 84)
        for (i in 0 until motes) {
            val a = rot * 2 + (i.toFloat() / motes) * (2 * Math.PI).toFloat()
            val rr = radius * 0.9f
            val x = cx + cos(a.toDouble()).toFloat() * rr
            val y = cy + sin(a.toDouble()).toFloat() * rr
            canvas.drawCircle(x, y, 2 + level * 2, fillPaint)
        }
    }

    private fun drawWaveform(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (waveform.isEmpty()) return
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees((rot * 0.3f).toDouble()).toFloat())
        val path = android.graphics.Path()
        val n = waveform.size
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * (2 * Math.PI)
            val v = ((waveform[i].toInt() and 0xFF) - 128) / 128f
            val rr = radius * 0.6f + v * radius * 0.35f
            val x = (cos(a) * rr).toFloat()
            val y = (sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        ringPaint.color = Color.argb(220, 79, 216, 255)
        ringPaint.strokeWidth = 4f
        canvas.drawPath(path, ringPaint)
        canvas.restore()
        fillPaint.color = Color.argb(180, 255, 180, 84)
        canvas.drawCircle(cx, cy, radius * 0.12f, fillPaint)
    }

    private fun drawSpectrum(canvas: Canvas, cx: Float, cy: Float, radius: Float, playing: Boolean) {
        val bars = 64
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees((rot * 0.5f).toDouble()).toFloat())
        for (i in 0 until bars) {
            val a = (i.toFloat() / bars) * (2 * Math.PI)
            val bin = ((i.toFloat() / bars) * fft.size * 0.6f).toInt()
            val amp = if (playing) bandAvg(bin, bin + 1) else 0.05f
            val len = radius * 0.15f + amp * radius * 0.5f
            val hue = 190 + amp * 80
            ringPaint.color = Color.HSVToColor(
                (128 + amp * 127).toInt().coerceIn(0, 255),
                floatArrayOf(hue, 0.85f, 0.62f)
            )
            ringPaint.strokeWidth = 5f
            val x1 = (cos(a) * radius * 0.32f).toFloat()
            val y1 = (sin(a) * radius * 0.32f).toFloat()
            val x2 = (cos(a) * (radius * 0.32f + len)).toFloat()
            val y2 = (sin(a) * (radius * 0.32f + len)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, ringPaint)
        }
        canvas.restore()
    }
}
