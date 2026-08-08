package com.henrylumis.mediaprayer.audio

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlin.math.pow

/**
 * Applies a per-track gain so quiet and loud tracks play back at a more
 * consistent perceived volume:
 *  - Tracks that need turning DOWN use plain player volume attenuation
 *    (ExoPlayer volume is 0.0-1.0, unity max, so it can only cut).
 *  - Tracks that need turning UP use LoudnessEnhancer, which can boost past
 *    unity gain -- clamped conservatively to avoid audible distortion.
 */
class LoudnessNormalizer(sessionId: Int) {

    private var enhancer: LoudnessEnhancer? = null
    val isAvailable: Boolean

    init {
        var e: LoudnessEnhancer? = null
        try {
            e = LoudnessEnhancer(sessionId)
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
     * Applies a gain expressed in dB. Positive (track was mastered quiet) is
     * boosted via LoudnessEnhancer; negative (track was mastered loud) is left
     * at 0 dB enhancer gain -- the caller should attenuate via player.volume instead.
     */
    fun applyBoostDb(db: Double) {
        try {
            val clamped = db.coerceIn(0.0, 9.0) // conservative ceiling against clipping/distortion
            enhancer?.setTargetGain((clamped * 100).toInt()) // API takes millibels
        } catch (_: Exception) {
        }
    }

    fun reset() {
        try { enhancer?.setTargetGain(0) } catch (_: Exception) {}
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
