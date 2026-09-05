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
import android.widget.EditText
import android.content.DialogInterface
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

import com.henrylumis.mediaprayer.ui.common.DialogStyler
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

        binding.btnPlayPause.contentDescription = "Play or pause"
        binding.btnAddPlaylist.contentDescription = "Add current song to playlist or favorites"
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
                activity.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })

        setupVolumeSlider()
        setupShuffleRepeat()
        setupVisualizerStyle()

        binding.btnAddPlaylist.setOnClickListener {
            showPlaylistChooser()
        }

        attachVisualizerWhenReady()
        attachPlayerListenerWhenReady()
        startProgressUpdates()

        binding.visualizer.onAmplitude = { amp -> binding.shaderBackground.setAmplitude(amp) }
    }


    private fun showPlaylistChooser() {
        val activity2 = activity as? MainActivity
        val mediaId = activity2?.player?.currentMediaItem?.mediaId
        if (mediaId == null) {
            Toast.makeText(requireContext(), "Play a song first", Toast.LENGTH_SHORT).show()
            return
        }

        val names = PlaylistStore.getPlaylistNames(requireContext()).toMutableList()
        val labels = ArrayList<String>()
        labels.add(if (PlaylistStore.isFavorite(requireContext(), mediaId)) "★ Favorites (remove)" else "☆ Favorites (add)")
        labels.addAll(names.map { name ->
            if (PlaylistStore.contains(requireContext(), name, mediaId)) "✓ $name (remove)" else name
        })
        labels.add("＋ Create new playlist")

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Add to playlist")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        val nowFavorite = PlaylistStore.toggleFavorite(requireContext(), mediaId)
                        updateFavoriteButton(nowFavorite)
                        Toast.makeText(requireContext(), if (nowFavorite) "Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
                    }
                    which <= names.size -> {
                        val name = names[which - 1]
                        val added = PlaylistStore.toggleSong(requireContext(), name, mediaId)
                        Toast.makeText(requireContext(), if (added) "Added to $name" else "Removed from $name", Toast.LENGTH_SHORT).show()
                    }
                    else -> showCreatePlaylistDialog(mediaId)
                }
            }
            .let { DialogStyler.show(it) }
    }

    private fun showCreatePlaylistDialog(mediaId: String) {
        val input = EditText(requireContext()).apply {
            hint = "Playlist name"
            setSingleLine(true)
            setPadding(48, 0, 48, 0)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Create playlist")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!PlaylistStore.createPlaylist(requireContext(), name)) {
                    input.error = "Enter a unique name (1–60 characters)"
                    return@setOnClickListener
                }
                PlaylistStore.addSong(requireContext(), name, mediaId)
                Toast.makeText(requireContext(), "Added to $name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        DialogStyler.show(dialog)
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
        val enabled = player.shuffleModeEnabled
        binding.btnShuffle.alpha = if (enabled) 1f else 0.5f
        binding.btnShuffle.contentDescription = if (enabled) "Shuffle on" else "Shuffle off"
        binding.btnShuffle.imageTintList = android.content.res.ColorStateList.valueOf(
            resources.getColor(if (enabled) R.color.accent_cyan else R.color.text_secondary, null)
        )
    }

    private fun updateRepeatIcon() {
        val player = (activity as? MainActivity)?.player ?: return
        val mode = player.repeatMode
        binding.btnRepeat.alpha = if (mode == Player.REPEAT_MODE_OFF) 0.5f else 1f
        binding.btnRepeat.contentDescription = when (mode) {
            Player.REPEAT_MODE_ALL -> "Repeat all"
            Player.REPEAT_MODE_ONE -> "Repeat one"
            else -> "Repeat off"
        }
        // Distinct colors so "repeat all" vs "repeat one" are tellable at a
        // glance, not just on/off -- previously both looked identical.
        val color = when (mode) {
            Player.REPEAT_MODE_ALL -> R.color.accent_cyan
            Player.REPEAT_MODE_ONE -> R.color.accent_purple
            else -> R.color.text_secondary
        }
        binding.btnRepeat.imageTintList = android.content.res.ColorStateList.valueOf(resources.getColor(color, null))
        // Keep the same unmistakable repeat-arrows icon in every mode; a small
        // "1" badge makes Repeat One immediately distinguishable from Repeat All.
        binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
        binding.repeatOneBadge.visibility = if (mode == Player.REPEAT_MODE_ONE) View.VISIBLE else View.GONE
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
                    updatePlaybackStatus()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNowPlaying()
                    updatePlaybackStatus()
                    updateShuffleIcon()
                    updateRepeatIcon()
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    updateShuffleIcon()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    updateRepeatIcon()
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
        binding.trackAlbum.text = item?.mediaMetadata?.albumTitle?.toString().orEmpty()
        binding.trackAlbum.visibility = if (item?.mediaMetadata?.albumTitle != null) View.VISIBLE else View.GONE
        updatePlaybackStatus()
        updateFavoriteButton(item?.mediaId?.let { PlaylistStore.isFavorite(requireContext(), it) } ?: false)
        loadAlbumArt(item)
    }

    private fun updatePlaybackStatus() {
        if (_binding == null) return
        val activity = activity as? MainActivity ?: return
        val item = activity.pendingTrack ?: activity.player?.currentMediaItem
        val player = activity.player
        if (item == null) {
            binding.playbackStatus.text = "READY"
            binding.playbackStatus.setTextColor(resources.getColor(R.color.text_secondary, null))
            binding.queuePosition.text = ""
            return
        }
        if (activity.pendingTrack != null) {
            binding.playbackStatus.text = "CROSSFADE • NEXT"
            binding.playbackStatus.setTextColor(resources.getColor(R.color.accent_purple, null))
        } else {
            binding.playbackStatus.text = if (player?.isPlaying == true) "PLAYING" else "PAUSED"
            binding.playbackStatus.setTextColor(resources.getColor(R.color.accent_cyan, null))
        }
        val count = player?.mediaItemCount ?: 0
        val index = player?.currentMediaItemIndex ?: -1
        binding.queuePosition.text = if (count > 0 && index >= 0) "TRACK ${index + 1} / $count" else ""
    }

    private fun loadAlbumArt(item: MediaItem?) {
        val mediaId = item?.mediaId
        if (mediaId == lastArtMediaId) return
        lastArtMediaId = mediaId

        if (item == null || item.localConfiguration?.uri == null) {
            binding.albumArt.setImageResource(R.drawable.ic_album_placeholder)
            binding.backdrop.setImageResource(R.drawable.bg_supercar_default)
            return
        }
        val uri: Uri = item.localConfiguration!!.uri
        val requestedMediaId = mediaId
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(requireContext(), uri)
                        retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } finally {
                        retriever.release()
                    }
                } catch (_: Exception) {
                    null
                }
            }
            // Track changes can happen faster than MediaMetadataRetriever. Never
            // let an old artwork job overwrite the artwork of the newly selected
            // song (especially noticeable during rapid Next presses/crossfade).
            if (_binding == null || lastArtMediaId != requestedMediaId) return@launch
            if (bitmap != null) {
                binding.albumArt.setImageBitmap(bitmap)
                binding.backdrop.setImageBitmap(bitmap)
                extractPaletteAsync(bitmap, requestedMediaId)
            } else {
                binding.albumArt.setImageResource(R.drawable.ic_album_placeholder)
                binding.backdrop.setImageResource(R.drawable.bg_supercar_default)
                binding.shaderBackground.setPaletteColors(
                    listOf(0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt())
                )
            }
        }
    }

    private fun extractPaletteAsync(bitmap: android.graphics.Bitmap, requestedMediaId: String?) {
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
            if (_binding != null && lastArtMediaId == requestedMediaId && colors.isNotEmpty()) {
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
                    // A pending crossfade track is not the active player yet.
                    // Seeking during this tiny handoff window would otherwise
                    // seek the outgoing song and make the new track appear to
                    // ignore the user's gesture. Keep the timeline visibly calm
                    // until the handoff completes.
                    binding.seekBar.isEnabled = !isPending
                    binding.seekBar.alpha = if (isPending) 0.55f else 1f

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
                    updatePlaybackStatus()
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
