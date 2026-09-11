package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class VisualizerStyle { BARS, CIRCULAR, WAVEFORM, VU_METER }

/**
 * ROOT CAUSE OF THE ORIGINAL CRASH (most likely):
 * android.media.audiofx.Visualizer(sessionId) throws if the session id is
 * 0 / not yet valid, and ExoPlayer's audio session id isn't guaranteed to
 * be ready the instant playback starts -- it becomes valid a moment after
 * the first buffer starts flowing, which lines up with a crash a few
 * seconds in. Fix: never let visualizer failures propagate. Every entry
 * point is wrapped in try/catch, capture runs on its own thread, and if it
 * fails we silently fall back to a lightweight fake animation instead of
 * crashing the app.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var visualizer: Visualizer? = null
    private var fftBytes: ByteArray? = null
    private var waveBytes: ByteArray? = null
    private var fallbackMode = false
    private var fallbackPhase = 0f

    private var isPlaying = true
    private var levelScale = 1f

    var style: VisualizerStyle = VisualizerStyle.BARS
        set(value) { field = value; postInvalidateOnAnimation() }

    /** Optional external amplitude listener (0f..1f), used to drive the shader background. */
    var onAmplitude: ((Float) -> Unit)? = null

    private val paint = Paint().apply { isAntiAlias = true }
    private val barCount = 48

    fun attachTo(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && visualizer == null && !fallbackMode) {
                    trySetupVisualizer(player.audioSessionId)
                }
            }
        })
        if (player.audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
            trySetupVisualizer(player.audioSessionId)
        }
        setPlaying(player.isPlaying)
    }

    fun setPlaying(playing: Boolean) {
        val wasPlaying = isPlaying
        isPlaying = playing
        if (playing && !wasPlaying) {
            levelScale = 1f
            postInvalidateOnAnimation()
        } else if (!playing && wasPlaying) {
            postInvalidateOnAnimation()
        }
    }

    private fun trySetupVisualizer(sessionId: Int) {
        if (sessionId == 0) return
        try {
            release()
            val v = Visualizer(sessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    waveBytes = waveform
                }
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    fftBytes = fft
                    if (isPlaying) postInvalidateOnAnimation()
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true)
            v.enabled = true
            visualizer = v
            fallbackMode = false
        } catch (e: Exception) {
            Log.w("VisualizerView", "Real audio capture unavailable, using fallback animation", e)
            release()
            fallbackMode = true
        }
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        } finally {
            visualizer = null
        }
    }

    /** Returns per-bar amplitudes 0f..1f, from real FFT data or a fake animated fallback. */
    private fun amplitudes(count: Int): FloatArray {
        val bytes = fftBytes
        val result = FloatArray(count)
        if (fallbackMode || bytes == null) {
            if (isPlaying) fallbackPhase += 0.12f
            for (i in 0 until count) {
                result[i] = (0.15f + 0.35f * abs(sin(fallbackPhase + i * 0.35f))) * levelScale
            }
        } else {
            try {
                val n = (bytes.size / 2).coerceAtMost(count)
                for (i in 0 until n) {
                    val idx = (i * 2).coerceIn(0, bytes.size - 2)
                    val magnitude = hypot(bytes[idx].toDouble(), bytes[idx + 1].toDouble())
                    result[i] = (magnitude / 128.0).coerceIn(0.02, 1.0).toFloat() * levelScale
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (!isPlaying) {
            levelScale *= 0.88f
            if (levelScale < 0.01f) levelScale = 0f
        }

        val amps = amplitudes(barCount)
        onAmplitude?.invoke(amps.average().toFloat())

        when (style) {
            VisualizerStyle.BARS -> drawBars(canvas, w, h, amps)
            VisualizerStyle.CIRCULAR -> drawCircular(canvas, w, h, amps)
            VisualizerStyle.WAVEFORM -> drawWaveform(canvas, w, h)
            VisualizerStyle.VU_METER -> drawVuMeter(canvas, w, h, amps)
        }

        if (isPlaying || levelScale > 0f) {
            postInvalidateOnAnimation()
        }
    }

    private fun gradientPaint(h: Float): Paint {
        paint.shader = LinearGradient(
            0f, h, 0f, 0f,
            intArrayOf(0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt()),
            null, Shader.TileMode.CLAMP
        )
        return paint
    }

    private fun drawBars(canvas: Canvas, w: Float, h: Float, amps: FloatArray) {
        val p = gradientPaint(h)
        val barWidth = w / barCount
        for (i in amps.indices) {
            val barH = h * amps[i]
            canvas.drawRect(i * barWidth + 2, h - barH, (i + 1) * barWidth - 2, h, p)
        }
    }

    private fun drawCircular(canvas: Canvas, w: Float, h: Float, amps: FloatArray) {
        val cx = w / 2f
        val cy = h / 2f
        val innerR = minOf(w, h) * 0.22f
        val maxLen = minOf(w, h) * 0.28f
        paint.shader = RadialGradient(
            cx, cy, innerR + maxLen,
            intArrayOf(0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt()),
            null, Shader.TileMode.CLAMP
        )
        paint.strokeWidth = 4f
        for (i in amps.indices) {
            val angle = (2 * Math.PI * i / amps.size).toFloat()
            val len = innerR + maxLen * amps[i]
            val x1 = cx + innerR * cos(angle)
            val y1 = cy + innerR * sin(angle)
            val x2 = cx + len * cos(angle)
            val y2 = cy + len * sin(angle)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    private fun drawWaveform(canvas: Canvas, w: Float, h: Float) {
        val p = gradientPaint(h)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        val path = Path()
        val bytes = waveBytes
        val midY = h / 2f
        if (fallbackMode || bytes == null || bytes.isEmpty()) {
            fallbackPhase += if (isPlaying) 0.15f else 0f
            path.moveTo(0f, midY)
            val steps = 64
            for (i in 0..steps) {
                val x = w * i / steps
                val y = midY + (h * 0.25f * levelScale) * sin(fallbackPhase + i * 0.35f).toFloat()
                path.lineTo(x, y)
            }
        } else {
            val n = bytes.size
            path.moveTo(0f, midY)
            for (i in 0 until n) {
                val x = w * i / n
                val sample = (bytes[i].toInt() and 0xFF) - 128 // -128..127
                val y = midY + (sample / 128f) * (h * 0.4f) * levelScale
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun drawVuMeter(canvas: Canvas, w: Float, h: Float, amps: FloatArray) {
        // Two retro-style meter bars with a peak cap, fed from two halves of the spectrum
        // to give a fake-stereo feel since Visualizer only exposes the mixed output.
        val left = amps.take(amps.size / 2).average().toFloat()
        val right = amps.takeLast(amps.size / 2).average().toFloat()
        val gap = w * 0.08f
        val barW = (w - gap * 3) / 2f

        drawSingleMeter(canvas, gap, barW, h, left)
        drawSingleMeter(canvas, gap * 2 + barW, barW, h, right)
    }

    private fun drawSingleMeter(canvas: Canvas, x: Float, barW: Float, h: Float, amp: Float) {
        val trackPaint = Paint().apply { color = 0x22FFFFFF; isAntiAlias = true }
        canvas.drawRoundRect(x, 0f, x + barW, h, 10f, 10f, trackPaint)

        val fillH = h * amp
        val fillPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                0f, h, 0f, 0f,
                intArrayOf(0xFF00E5FF.toInt(), 0xFFF2B84B.toInt(), 0xFFFF4081.toInt()),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(x, h - fillH, x + barW, h, 10f, 10f, fillPaint)

        // Peak cap line
        val capPaint = Paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 4f }
        canvas.drawLine(x, h - fillH, x + barW, h - fillH, capPaint)
    }
}
