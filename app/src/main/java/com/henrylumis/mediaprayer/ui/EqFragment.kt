package com.henrylumis.mediaprayer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.databinding.FragmentEqBinding
import com.henrylumis.mediaprayer.player.PlayerManager

class EqFragment : Fragment() {

    private var _binding: FragmentEqBinding? = null
    private val binding get() = _binding!!

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindBand(binding.seek60, binding.val60, PlayerManager.BAND_FREQS[0])
        bindBand(binding.seek250, binding.val250, PlayerManager.BAND_FREQS[1])
        bindBand(binding.seek1000, binding.val1000, PlayerManager.BAND_FREQS[2])
        bindBand(binding.seek4000, binding.val4000, PlayerManager.BAND_FREQS[3])
        bindBand(binding.seek12000, binding.val12000, PlayerManager.BAND_FREQS[4])

        binding.boostSwitch.setOnCheckedChangeListener { _, checked ->
            PlayerManager.setBoost(checked)
        }
        binding.motionSwitch.setOnCheckedChangeListener { _, checked ->
            PlayerManager.reduceMotion = checked
        }

        updateStats()
    }

    /** SeekBar 0..24 maps to -12..+12 dB, matching each row's centre-detent look. */
    private fun bindBand(seekBar: SeekBar, valueLabel: android.widget.TextView, freqHz: Int) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress - 12
                valueLabel.text = if (db >= 0) "+${db}dB" else "${db}dB"
                if (fromUser) PlayerManager.setBandGainDb(freqHz, db.toFloat())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateStats() {
        val player = PlayerManager.player
        val format = player.audioFormat
        binding.statRate.text = if (format != null && format.sampleRate > 0) {
            "${format.sampleRate / 1000.0} kHz"
        } else "—"
        binding.statCh.text = if (format != null && format.channelCount > 0) {
            when (format.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                else -> "${format.channelCount}-channel"
            }
        } else "—"
        binding.statFmt.text = format?.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: "—"
        val total = PlayerManager.tracks.size
        val pos = if (total > 0) PlayerManager.currentIndex() + 1 else 0
        binding.statPos.text = "$pos / $total"
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(statsTick)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(statsTick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
