package com.henrylumis.mediaprayer.audio

import android.media.audiofx.Equalizer
import android.util.Log

data class EqBand(val index: Short, val centerFreqHz: Int, val minLevel: Short, val maxLevel: Short, var level: Short)

/**
 * Thin, crash-proof wrapper around android.media.audiofx.Equalizer.
 * Same lesson as VisualizerView: audio effect APIs can throw on some OEM
 * audio stacks or if the session id isn't valid yet, so every entry point
 * is guarded and failures degrade to "no equalizer" instead of crashing.
 */
class EqualizerController(sessionId: Int) {

    private var equalizer: Equalizer? = null
    val bands: List<EqBand>
    val presetNames: List<String>
    val isAvailable: Boolean

    init {
        var eq: Equalizer? = null
        var builtBands = emptyList<EqBand>()
        var builtPresets = emptyList<String>()
        try {
            val newEq = Equalizer(0, sessionId)
            newEq.enabled = true
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

    fun setBandLevel(bandIndex: Short, level: Short) {
        try {
            equalizer?.setBandLevel(bandIndex, level)
            bands.find { it.index == bandIndex }?.level = level
        } catch (_: Exception) {
        }
    }

    fun usePreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
            bands.forEach { it.level = try { equalizer?.getBandLevel(it.index) ?: it.level } catch (e: Exception) { it.level } }
        } catch (_: Exception) {
        }
    }

    fun release() {
        try { equalizer?.enabled = false; equalizer?.release() } catch (_: Exception) {}
        equalizer = null
    }
}
