package com.henrylumis.mediaprayer.audio

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlin.math.pow

/**
 * Utility for ReplayGain calculations and optional device-side enhancement.
 *
 * Playback currently uses attenuation-only normalization because the two
 * crossfade players share one audio session. A session-wide LoudnessEnhancer
 * boost would affect both sides of a crossfade at once and could create a
 * level jump on the outgoing track. The enhancer remains available as a
 * device capability, but the bridge deliberately does not use it for the
 * per-track normalization path.
 */
class LoudnessNormalizer(sessionId: Int) {

    companion object {
        // Small constant headroom-free output lift. ExoPlayer itself tops out at
        // 1.0, while the system music stream volume is controlled separately.
        // A modest 1.5 dB session gain makes the same Android volume percentage
        // feel closer to players that use a little output gain, without turning
        // the master volume into an aggressive boost or changing crossfade math.
        private const val BASE_OUTPUT_GAIN_DB = 1.5
    }

    private var enhancer: LoudnessEnhancer? = null
    val isAvailable: Boolean

    init {
        var e: LoudnessEnhancer? = null
        try {
            e = LoudnessEnhancer(sessionId)
            e.setTargetGain((BASE_OUTPUT_GAIN_DB * 100).toInt())
            e.enabled = true
        } catch (ex: Exception) {
            Log.w("LoudnessNormalizer", "LoudnessEnhancer unavailable on this device", ex)
            try { e?.release() } catch (_: Exception) {}
            e = null
        }
        enhancer = e
        isAvailable = e != null
    }

    /**
     * Applies additional ReplayGain boost on top of the small constant output
     * lift. Negative ReplayGain is still handled by player-volume attenuation.
     */
    fun applyBoostDb(db: Double) {
        try {
            val clamped = db.coerceIn(0.0, 9.0) // conservative ceiling against clipping/distortion
            enhancer?.setTargetGain((clamped * 100).toInt()) // API takes millibels
        } catch (_: Exception) {
        }
    }

    fun reset() {
        try { enhancer?.setTargetGain((BASE_OUTPUT_GAIN_DB * 100).toInt()) } catch (_: Exception) {}
    }

    /** Converts a dB value to a linear multiplier suitable for ExoPlayer's volume (0f..1f). */
    fun dbToLinearAttenuation(db: Double): Float {
        val linear = 10.0.pow(db / 20.0)
        return linear.coerceIn(0.15, 1.0).toFloat()
    }

    fun release() {
        try { enhancer?.enabled = false; enhancer?.release() } catch (_: Exception) {}
        enhancer = null
    }
}
