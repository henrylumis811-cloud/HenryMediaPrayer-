package com.henrylumis.mediaprayer.audio

import android.media.audiofx.Equalizer
import android.util.Log
import com.henrylumis.mediaprayer.util.Prefs

 data class EqBand(val index: Short, val centerFreqHz: Int, val minLevel: Short, val maxLevel: Short, var level: Short)

/**
 * User-friendly wrapper around Android's Equalizer effect.
 *
 * The UI no longer exposes raw frequency bands. Instead it exposes a few
 * musical controls (Bass Boost, Vocal Clarity and Treble Boost) and presets.
 * The actual Android Equalizer is still used underneath.
 */
class EqualizerController(private val contextForPrefs: android.content.Context, sessionId: Int) {

    private var equalizer: Equalizer? = null
    val bands: List<EqBand>
    val presetNames: List<String>
    val isAvailable: Boolean
    val isEnabled: Boolean get() = try { equalizer?.enabled == true } catch (_: Exception) { false }

    var bassBoost: Int = 0
        private set
    var vocalClarity: Int = 0
        private set
    var trebleBoost: Int = 0
        private set

    companion object {
        private const val KEY_BASS = "simple_eq_bass"
        private const val KEY_VOCAL = "simple_eq_vocal"
        private const val KEY_TREBLE = "simple_eq_treble"
    }

    init {
        var eq: Equalizer? = null
        var builtBands = emptyList<EqBand>()
        var builtPresets = emptyList<String>()
        try {
            if (sessionId <= 0) throw IllegalArgumentException("Invalid audio session: $sessionId")
            val newEq = Equalizer(0, sessionId)
            val range = newEq.bandLevelRange
            builtBands = (0 until newEq.numberOfBands).map { i ->
                val idx = i.toShort()
                EqBand(
                    index = idx,
                    centerFreqHz = newEq.getCenterFreq(idx) / 1000,
                    minLevel = range[0],
                    maxLevel = range[1],
                    level = newEq.getBandLevel(idx)
                )
            }
            builtPresets = (0 until newEq.numberOfPresets).map { newEq.getPresetName(it.toShort()) }

            // New friendly controls start neutral unless the user has already
            // used them. This intentionally replaces the old raw-band UI with
            // a predictable musical starting point.
            val prefs = contextForPrefs.getSharedPreferences("simple_eq", android.content.Context.MODE_PRIVATE)
            bassBoost = prefs.getInt(KEY_BASS, 0).coerceIn(0, 100)
            vocalClarity = prefs.getInt(KEY_VOCAL, 0).coerceIn(0, 100)
            trebleBoost = prefs.getInt(KEY_TREBLE, 0).coerceIn(0, 100)
            applySimpleControls(newEq, builtBands)
            newEq.enabled = Prefs.isEqEnabled(contextForPrefs)
            eq = newEq
        } catch (e: Exception) {
            Log.w("EqualizerController", "Equalizer unavailable on this device", e)
            try { eq?.release() } catch (_: Exception) {}
            eq = null
        }
        equalizer = eq
        bands = builtBands
        presetNames = builtPresets
        isAvailable = eq != null
    }

    fun setEnabled(enabled: Boolean) {
        try {
            equalizer?.enabled = enabled
            Prefs.setEqEnabled(contextForPrefs, enabled)
        } catch (_: Exception) {
        }
    }

    fun setSimpleControl(kind: SimpleControl, value: Int) {
        val v = value.coerceIn(0, 100)
        when (kind) {
            SimpleControl.BASS -> bassBoost = v
            SimpleControl.VOCAL -> vocalClarity = v
            SimpleControl.TREBLE -> trebleBoost = v
        }
        contextForPrefs.getSharedPreferences("simple_eq", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BASS, bassBoost)
            .putInt(KEY_VOCAL, vocalClarity)
            .putInt(KEY_TREBLE, trebleBoost)
            .apply()
        applySimpleControls(equalizer, bands)
    }

    fun applyPreset(name: String) {
        when (name.lowercase()) {
            "balanced" -> setSimpleValues(0, 0, 0)
            "bass boost" -> setSimpleValues(75, 0, 10)
            "pop" -> setSimpleValues(45, 25, 45)
            "rock" -> setSimpleValues(60, 10, 55)
            "vocal" -> setSimpleValues(10, 75, 25)
            "electronic" -> setSimpleValues(65, 10, 70)
            else -> setSimpleValues(0, 0, 0)
        }
    }

    private fun setSimpleValues(bass: Int, vocal: Int, treble: Int) {
        bassBoost = bass
        vocalClarity = vocal
        trebleBoost = treble
        contextForPrefs.getSharedPreferences("simple_eq", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BASS, bass)
            .putInt(KEY_VOCAL, vocal)
            .putInt(KEY_TREBLE, treble)
            .apply()
        applySimpleControls(equalizer, bands)
    }

    /** Maps the three musical controls onto whatever bands this device exposes. */
    private fun applySimpleControls(target: Equalizer?, targetBands: List<EqBand>) {
        if (target == null) return
        val range = try { target.bandLevelRange } catch (_: Exception) { return }
        val min = range[0].toInt()
        val max = range[1].toInt()
        val headroom = max.coerceAtMost(600)
        try {
            targetBands.forEach { band ->
                val hz = band.centerFreqHz.toFloat()
                val bassWeight = when {
                    hz <= 120f -> 1f
                    hz <= 250f -> (250f - hz) / 130f
                    else -> 0f
                }
                val vocalWeight = when {
                    hz in 700f..1800f -> 1f
                    hz > 1800f && hz <= 4000f -> (4000f - hz) / 2200f
                    else -> 0f
                }
                val trebleWeight = when {
                    hz >= 8000f -> 1f
                    hz >= 4000f -> (hz - 4000f) / 4000f
                    else -> 0f
                }
                // Maximum musical boost is about +6 dB. We keep it below the
                // raw device range so the friendly controls cannot become extreme.
                val gain = (6f * (bassBoost / 100f) * bassWeight) +
                    (4f * (vocalClarity / 100f) * vocalWeight) +
                    (5f * (trebleBoost / 100f) * trebleWeight)
                val millibels = gain * 100f
                val level = millibels.toInt().coerceIn(min, headroom)
                target.setBandLevel(band.index, level.toShort())
                band.level = level.toShort()
            }
        } catch (_: Exception) {
        }
    }

    // Kept for compatibility with any existing callers.
    fun setBandLevel(bandIndex: Short, level: Short) {
        try {
            equalizer?.setBandLevel(bandIndex, level)
            bands.find { it.index == bandIndex }?.level = level
            Prefs.setEqBandLevel(contextForPrefs, bandIndex, level)
        } catch (_: Exception) {
        }
    }

    fun usePreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
            bands.forEach {
                it.level = try { equalizer?.getBandLevel(it.index) ?: it.level } catch (_: Exception) { it.level }
                Prefs.setEqBandLevel(contextForPrefs, it.index, it.level)
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        try { equalizer?.enabled = false; equalizer?.release() } catch (_: Exception) {}
        equalizer = null
    }
}

enum class SimpleControl { BASS, VOCAL, TREBLE }
