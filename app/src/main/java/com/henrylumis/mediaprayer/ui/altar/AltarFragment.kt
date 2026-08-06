package com.henrylumis.mediaprayer.ui.altar

import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.databinding.FragmentAltarBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AltarFragment : Fragment() {

    private var _binding: FragmentAltarBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var listenerAttached = false
    private var visualizerAttached = false
    private var lastArtMediaId: String? = null
    private var userIsDraggingSeek = false

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

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userIsDraggingSeek = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsDraggingSeek = false
                activity.player?.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })

        setupVolumeSlider()
        setupShuffleRepeat()

        binding.btnAddPlaylist.setOnClickListener {
            // Custom named playlists aren't built yet -- flag it honestly rather
            // than silently doing nothing.
            Toast.makeText(requireContext(), "Playlists are coming soon", Toast.LENGTH_SHORT).show()
        }

        attachVisualizerWhenReady()
        attachPlayerListenerWhenReady()
        startProgressUpdates()
    }

    private fun setupVolumeSlider() {
        val audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.max = max
        binding.volumeSlider.progress = current
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupShuffleRepeat() {
        val activity = activity as? MainActivity ?: return
        updateShuffleIcon()
        updateRepeatIcon()

        binding.btnShuffle.setOnClickListener {
            val player = activity.player ?: return@setOnClickListener
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            updateShuffleIcon()
        }
        binding.btnRepeat.setOnClickListener {
            val player = activity.player ?: return@setOnClickListener
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            updateRepeatIcon()
        }
    }

    private fun updateShuffleIcon() {
        val player = (activity as? MainActivity)?.player ?: return
        binding.btnShuffle.alpha = if (player.shuffleModeEnabled) 1f else 0.5f
    }

    private fun updateRepeatIcon() {
        val player = (activity as? MainActivity)?.player ?: return
        binding.btnRepeat.alpha = if (player.repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f
    }

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

    private fun attachPlayerListenerWhenReady() {
        val activity = activity as? MainActivity ?: return
        val player = activity.player
        if (player != null) {
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButton(isPlaying)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNowPlaying()
                }
            })
            listenerAttached = true
            updatePlayPauseButton(player.isPlaying)
            updateNowPlaying()
            return
        }
        if (_binding == null) return
        handler.postDelayed({ if (!listenerAttached) attachPlayerListenerWhenReady() }, 300)
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (_binding == null) return
        binding.btnPlayPause.text = if (isPlaying) "PAUSE" else "PLAY"
        binding.btnPlayPause.setIconResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        binding.visualizer.setPlaying(isPlaying)
    }

    private fun updateNowPlaying() {
        val activity = activity as? MainActivity ?: return
        val item = activity.player?.currentMediaItem
        binding.trackTitle.text = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        binding.trackArtist.text = item?.mediaMetadata?.artist?.toString() ?: "Open Library to pick a song"
        loadAlbumArt(item)
    }

    private fun loadAlbumArt(item: MediaItem?) {
        val mediaId = item?.mediaId
        if (mediaId == lastArtMediaId) return
        lastArtMediaId = mediaId

        if (item == null || item.localConfiguration?.uri == null) {
            binding.albumArt.setImageResource(R.drawable.ic_album_placeholder)
            binding.backdrop.setImageResource(R.drawable.ic_album_placeholder)
            return
        }
        val uri: Uri = item.localConfiguration!!.uri
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(requireContext(), uri)
                    val art = retriever.embeddedPicture
                    retriever.release()
                    art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } catch (e: Exception) {
                    null
                }
            }
            if (_binding == null) return@launch
            if (bitmap != null) {
                binding.albumArt.setImageBitmap(bitmap)
                binding.backdrop.setImageBitmap(bitmap)
            } else {
                binding.albumArt.setImageResource(R.drawable.ic_album_placeholder)
                binding.backdrop.setImageResource(R.drawable.ic_album_placeholder)
            }
        }
    }

    private fun startProgressUpdates() {
        progressRunnable = object : Runnable {
            override fun run() {
                val player = (activity as? MainActivity)?.player
                if (player != null && _binding != null) {
                    if (player.duration > 0) {
                        val pos = player.currentPosition.coerceAtLeast(0)
                        val dur = player.duration.coerceAtLeast(1)
                        binding.seekBar.max = dur.toInt()
                        if (!userIsDraggingSeek) binding.seekBar.progress = pos.toInt()
                        binding.timeReadout.text = "${format(pos)} / ${format(dur)}"
                    }
                    updatePlayPauseButton(player.isPlaying)
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
