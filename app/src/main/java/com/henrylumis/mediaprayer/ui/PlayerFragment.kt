package com.henrylumis.mediaprayer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.media3.common.Player
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.databinding.FragmentPlayerBinding
import com.henrylumis.mediaprayer.player.PlayerManager
import com.henrylumis.mediaprayer.util.Format

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val visLabels = listOf(R.string.mandala, R.string.waveform, R.string.spectrum)

    private var shuffleOn = false
    private var repeatMode = 0 // 0 off, 1 all, 2 one
    private var isSeeking = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val positionTick = object : Runnable {
        override fun run() {
            updatePosition()
            uiHandler.postDelayed(this, 400)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateNowPlaying()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.playBtn.setOnClickListener { PlayerManager.togglePlay() }
        binding.nextBtn.setOnClickListener { PlayerManager.next() }
        binding.prevBtn.setOnClickListener { PlayerManager.previous() }

        binding.shuffleBtn.setOnClickListener {
            shuffleOn = !shuffleOn
            PlayerManager.setShuffle(shuffleOn)
            binding.shuffleBtn.isActivated = shuffleOn
            binding.shuffleBtn.alpha = if (shuffleOn) 1f else 0.6f
        }

        binding.repeatBtn.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            PlayerManager.setRepeatMode(repeatMode)
            binding.repeatBtn.alpha = if (repeatMode != 0) 1f else 0.6f
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = PlayerManager.player.duration
                    if (dur > 0) {
                        binding.curTime.text = Format.timeMs((progress / 1000.0 * dur).toLong())
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                PlayerManager.seekToFraction((seekBar?.progress ?: 0) / 1000f)
            }
        })

        binding.volSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlayerManager.setVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val speedAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            speeds.map { "${it}x" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.speedSpinner.adapter = speedAdapter
        binding.speedSpinner.setSelection(1) // 1.00x
        binding.speedSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                PlayerManager.setSpeed(speeds[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        var visMode = 0
        binding.visModeLabel.text = getString(visLabels[visMode])
        binding.altarView.visMode = visMode
        binding.visModeLabel.setOnClickListener {
            visMode = (visMode + 1) % 3
            binding.altarView.visMode = visMode
            binding.visModeLabel.text = getString(visLabels[visMode])
        }

        updateNowPlaying()
        updatePlayIcon(PlayerManager.player.isPlaying)
    }

    private fun updateNowPlaying() {
        val idx = PlayerManager.currentIndex()
        val track = PlayerManager.tracks.getOrNull(idx)
        if (track == null) {
            binding.nowPlayingTitle.text = getString(R.string.awaiting_offering)
            binding.nowPlayingArtist.text = ""
        } else {
            binding.nowPlayingTitle.text = track.title
            binding.nowPlayingArtist.text = track.artist
        }
    }

    private fun updatePlayIcon(isPlaying: Boolean) {
        binding.playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updatePosition() {
        val player = PlayerManager.player
        val dur = player.duration
        if (!isSeeking && dur > 0) {
            binding.seekBar.progress = ((player.currentPosition.toFloat() / dur) * 1000).toInt()
            binding.curTime.text = Format.timeMs(player.currentPosition)
            binding.durTime.text = Format.timeMs(dur)
        }
    }

    override fun onStart() {
        super.onStart()
        PlayerManager.player.addListener(playerListener)
        uiHandler.post(positionTick)
    }

    override fun onStop() {
        super.onStop()
        PlayerManager.player.removeListener(playerListener)
        uiHandler.removeCallbacks(positionTick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
