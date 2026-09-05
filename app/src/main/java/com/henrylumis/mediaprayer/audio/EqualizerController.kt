package com.henrylumis.mediaprayer.audio

import android.media.audiofx.Equalizer
import android.util.Log
import com.henrylumis.mediaprayer.util.Prefs

data class EqBand(val index: Short, val centerFreqHz: Int, val minLevel: Short, val maxLevel: Short, var level: Short)

/**
 * Thin, crash-proof wrapper around android.media.audiofx.Equalizer.
 * Same lesson as VisualizerView: audio effect APIs can throw on some OEM
 * audio stacks or if the session id isn't valid yet, so every entry point
 * is guarded and failures degrade to "no equalizer" instead of crashing.
 */
class EqualizerController(private val contextForPrefs: android.content.Context, sessionId: Int) {

    private var equalizer: Equalizer? = null
    val bands: List<EqBand>
    val presetNames: List<String>
    val isAvailable: Boolean
    val isEnabled: Boolean get() = try { equalizer?.enabled == true } catch (_: Exception) { false }

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
                    level = Prefs.getEqBandLevel(contextForPrefs, idx, newEq.getBandLevel(idx))
                )
            }
            builtPresets = (0 until newEq.numberOfPresets).map { newEq.getPresetName(it.toShort()) }

            // Android's Equalizer keeps its own runtime band state. Restoring the
            // values only into our EqBand models is not enough: after a service
            // recreation the effect would silently return to the device default.
            // Apply persisted levels to the real effect before enabling it.
            builtBands.forEach { band ->
                try { newEq.setBandLevel(band.index, band.level) } catch (_: Exception) { }
            }
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
                it.level = try { equalizer?.getBandLevel(it.index) ?: it.level } catch (e: Exception) { it.level }
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
