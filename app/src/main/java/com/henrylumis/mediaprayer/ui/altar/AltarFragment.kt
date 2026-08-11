package com.henrylumis.mediaprayer.ui.altar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
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
import com.henrylumis.mediaprayer.ui.VisualizerStyle
import com.henrylumis.mediaprayer.util.PlaylistStore
import com.henrylumis.mediaprayer.util.Prefs
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
    private lateinit var audioManager: AudioManager
    private var volumeReceiver: BroadcastReceiver? = null

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
        binding.btnNext.setOnClickListener {
            activity.skipNext()
            updateNowPlaying() // instant visual change; audio itself fades in underneath
        }
        binding.btnPrev.setOnClickListener {
            activity.skipPrevious()
            updateNowPlaying()
        }

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
        setupVisualizerStyle()

        binding.btnAddPlaylist.setOnClickListener {
            val activity2 = activity as? MainActivity
            val mediaId = activity2?.player?.currentMediaItem?.mediaId
            if (mediaId == null) {
                Toast.makeText(requireContext(), "Play a song first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nowFavorite = PlaylistStore.toggleFavorite(requireContext(), mediaId)
            updateFavoriteButton(nowFavorite)
            Toast.makeText(
                requireContext(),
                if (nowFavorite) "Added to Favorites" else "Removed from Favorites",
                Toast.LENGTH_SHORT
            ).show()
        }

        attachVisualizerWhenReady()
        attachPlayerListenerWhenReady()
        startProgressUpdates()

        binding.visualizer.onAmplitude = { amp -> binding.shaderBackground.setAmplitude(amp) }
    }

    private fun setupVisualizerStyle() {
        val ctx = requireContext()
        val saved = Prefs.getVisualizerStyle(ctx)?.let {
            try { VisualizerStyle.valueOf(it) } catch (e: Exception) { null }
        } ?: VisualizerStyle.BARS
        binding.visualizer.style = saved

        binding.btnVisualizerStyle.setOnClickListener {
            val styles = VisualizerStyle.values()
            val next = styles[(styles.indexOf(binding.visualizer.style) + 1) % styles.size]
            binding.visualizer.style = next
            Prefs.setVisualizerStyle(ctx, next.name)
            Toast.makeText(ctx, "Visualizer: ${next.name.replace('_', ' ')}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupVolumeSlider() {
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        refreshVolumeDisplay()
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    updateVolumePercentText(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun refreshVolumeDisplay() {
        if (_binding == null || !::audioManager.isInitialized) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.max = max
        binding.volumeSlider.progress = current
        updateVolumePercentText(current, max)
    }

    private fun updateVolumePercentText(current: Int, max: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) {
        if (_binding == null) return
        val percent = if (max > 0) (current * 100) / max else 0
        binding.volumePercent.text = "$percent%"
    }

    /** Picks up volume changes made via the phone's hardware volume buttons
     *  (or any other app) so the slider and percentage stay accurate live. */
    private fun registerVolumeReceiver() {
        if (volumeReceiver != null) return
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshVolumeDisplay()
            }
        }
        try {
            requireContext().registerReceiver(
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            )
        } catch (_: Exception) {
        }
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let {
            try { requireContext().unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
    }

    override fun onResume() {
        super.onResume()
        registerVolumeReceiver()
        refreshVolumeDisplay()
    }

    override fun onPause() {
        unregisterVolumeReceiver()
        super.onPause()
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

    private fun updateFavoriteButton(isFavorite: Boolean) {
        if (_binding == null) return
        binding.btnAddPlaylist.text = if (isFavorite) "★ IN FAVORITES" else "ADD TO PLAYLIST"
    }

    private fun updateNowPlaying() {
        val activity = activity as? MainActivity ?: return
        // Prefer the crossfade-pending track (if a manual skip/select is currently
        // fading in) so the screen updates the instant you tap, not several
        // seconds later when the audio handoff actually completes.
        val item = activity.pendingTrack ?: activity.player?.currentMediaItem
        binding.trackTitle.text = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        binding.trackArtist.text = item?.mediaMetadata?.artist?.toString() ?: "Open Library to pick a song"
        updateFavoriteButton(item?.mediaId?.let { PlaylistStore.isFavorite(requireContext(), it) } ?: false)
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
                extractPaletteAsync(bitmap)
            } else {
                binding.albumArt.setImageResource(R.drawable.ic_album_placeholder)
                binding.backdrop.setImageResource(R.drawable.ic_album_placeholder)
                binding.shaderBackground.setPaletteColors(
                    listOf(0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt())
                )
            }
        }
    }

    private fun extractPaletteAsync(bitmap: android.graphics.Bitmap) {
        viewLifecycleOwner.lifecycleScope.launch {
            val colors = withContext(Dispatchers.IO) {
                try {
                    val palette = Palette.from(bitmap).generate()
                    listOfNotNull(
                        palette.vibrantSwatch?.rgb,
                        palette.dominantSwatch?.rgb,
                        palette.mutedSwatch?.rgb
                    )
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (_binding != null && colors.isNotEmpty()) {
                binding.shaderBackground.setPaletteColors(colors)
            }
        }
    }

    private fun startProgressUpdates() {
        var lastPendingState = false
        progressRunnable = object : Runnable {
            override fun run() {
                val activity = activity as? MainActivity
                val player = activity?.player
                val isPending = activity?.pendingTrack != null

                // Catch pending-track changes triggered from Library/Queue (not just
                // the buttons on this screen) within one tick.
                if (isPending != lastPendingState) {
                    lastPendingState = isPending
                    updateNowPlaying()
                }

                if (player != null && _binding != null) {
                    if (isPending) {
                        // Audio is still fading in underneath -- show the new
                        // track "at the start" rather than the outgoing track's
                        // real (soon-to-be-irrelevant) position.
                        binding.seekBar.progress = 0
                        binding.timeReadout.text = "0:00 / --:--"
                    } else if (player.duration > 0) {
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
