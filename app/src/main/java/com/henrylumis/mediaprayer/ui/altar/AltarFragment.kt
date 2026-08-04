package com.henrylumis.mediaprayer.ui.altar

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.common.Player
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentAltarBinding
import java.util.concurrent.TimeUnit

class AltarFragment : Fragment() {

    private var _binding: FragmentAltarBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAltarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = activity as? MainActivity ?: return

        binding.btnPlayPause.setOnClickListener { activity.togglePlayPause() }
        binding.btnNext.setOnClickListener { activity.skipNext() }
        binding.btnPrev.setOnClickListener { activity.skipPrevious() }

        // The service connects asynchronously, so the ExoPlayer instance may not
        // exist yet at this exact moment -- poll briefly instead of assuming it's ready.
        attachVisualizerWhenReady()

        activity.player?.let { player ->
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.btnPlayPause.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    updateNowPlayingText()
                }
            })
            updateNowPlayingText()
        }

        startProgressUpdates()
    }

    private var visualizerAttached = false
    private fun attachVisualizerWhenReady() {
        val activity = activity as? MainActivity ?: return
        val exo = activity.exoPlayerForVisualizer
        if (exo != null) {
            binding.visualizer.attachTo(exo)
            visualizerAttached = true
            return
        }
        if (_binding == null) return
        handler.postDelayed({ if (!visualizerAttached) attachVisualizerWhenReady() }, 300)
    }

    private fun updateNowPlayingText() {
        val activity = activity as? MainActivity ?: return
        val item = activity.player?.currentMediaItem
        binding.trackTitle.text = item?.mediaMetadata?.title ?: "Nothing playing"
        binding.trackArtist.text = item?.mediaMetadata?.artist ?: "Open Library to pick a song"
    }

    private fun startProgressUpdates() {
        progressRunnable = object : Runnable {
            override fun run() {
                val player = (activity as? MainActivity)?.player
                if (player != null && _binding != null && player.duration > 0) {
                    val pos = player.currentPosition.coerceAtLeast(0)
                    val dur = player.duration.coerceAtLeast(1)
                    binding.seekBar.max = dur.toInt()
                    binding.seekBar.progress = pos.toInt()
                    binding.timeElapsed.text = format(pos)
                    binding.timeTotal.text = format(dur)
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun format(ms: Long): String {
        val s = TimeUnit.MILLISECONDS.toSeconds(ms)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    override fun onDestroyView() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        binding.visualizer.release()
        super.onDestroyView()
        _binding = null
    }
}
