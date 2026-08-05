package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

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
    private var fallbackMode = false
    private var fallbackPhase = 0f

    // Playback-linked animation state: when paused, bars decay smoothly to
    // rest instead of freezing mid-frame or continuing to bounce forever.
    private var isPlaying = true
    private var levelScale = 1f

    private val barPaint = Paint().apply { isAntiAlias = true }
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

    /** Call this whenever playback play/pause state changes so the bars follow it. */
    fun setPlaying(playing: Boolean) {
        val wasPlaying = isPlaying
        isPlaying = playing
        if (playing && !wasPlaying) {
            levelScale = 1f
            postInvalidateOnAnimation()
        } else if (!playing && wasPlaying) {
            // Kick off the decay animation toward silence.
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
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    fftBytes = fft
                    if (isPlaying) postInvalidateOnAnimation()
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        barPaint.shader = LinearGradient(
            0f, h, 0f, 0f,
            intArrayOf(0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt()),
            null, Shader.TileMode.CLAMP
        )

        // While paused, ease the visible amplitude down to ~0 instead of
        // freezing instantly -- reads as "powering down", not glitching.
        if (!isPlaying) {
            levelScale *= 0.88f
            if (levelScale < 0.01f) levelScale = 0f
        }

        val barWidth = w / barCount
        val bytes = fftBytes

        if (fallbackMode || bytes == null) {
            if (isPlaying) fallbackPhase += 0.12f
            for (i in 0 until barCount) {
                val amp = (0.15f + 0.35f * kotlin.math.abs(kotlin.math.sin(fallbackPhase + i * 0.35f))) * levelScale
                val barH = h * amp
                canvas.drawRect(i * barWidth + 2, h - barH, (i + 1) * barWidth - 2, h, barPaint)
            }
        } else {
            try {
                val n = (bytes.size / 2).coerceAtMost(barCount)
                for (i in 0 until n) {
                    val idx = (i * 2).coerceIn(0, bytes.size - 2)
                    val magnitude = kotlin.math.hypot(bytes[idx].toDouble(), bytes[idx + 1].toDouble())
                    val amp = (magnitude / 128.0).coerceIn(0.02, 1.0).toFloat() * levelScale
                    val barH = h * amp
                    canvas.drawRect(i * barWidth + 2, h - barH, (i + 1) * barWidth - 2, h, barPaint)
                }
            } catch (_: Exception) {
                // Never let a drawing glitch crash the UI thread.
            }
        }

        // Keep animating while actually playing, or while still decaying down to rest.
        if (isPlaying || levelScale > 0f) {
            postInvalidateOnAnimation()
        }
    }
}
