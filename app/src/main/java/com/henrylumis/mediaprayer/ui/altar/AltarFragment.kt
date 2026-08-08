package com.henrylumis.mediaprayer.ui.altar

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.data.LyricsLine
import com.henrylumis.mediaprayer.data.LyricsParser
import com.henrylumis.mediaprayer.databinding.FragmentAltarBinding
import com.henrylumis.mediaprayer.ui.VisualizerStyle
import com.henrylumis.mediaprayer.util.LyricsStore
import com.henrylumis.mediaprayer.util.PlaylistStore
import com.henrylumis.mediaprayer.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    // Lyrics ticker bar state (separate from the full Verses screen; reuses
    // the same LyricsParser/LyricsStore data so both stay consistent).
    private var lyricsLines: List<LyricsLine> = emptyList()
    private var lyricsSynced = false
    private var lastLyricsMediaId: String? = null
    private var lastActiveLyricIndex = -1
    private var lyricsCrawlAnimator: ValueAnimator? = null

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

        binding.lyricsTickerBar.setOnClickListener {
            (activity as? MainActivity)?.goToVerses()
        }

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
        val item = activity.player?.currentMediaItem
        binding.trackTitle.text = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
        binding.trackArtist.text = item?.mediaMetadata?.artist?.toString() ?: "Open Library to pick a song"
        updateFavoriteButton(item?.mediaId?.let { PlaylistStore.isFavorite(requireContext(), it) } ?: false)
        loadAlbumArt(item)
        loadLyricsForCurrentSongIfNeeded(item)
    }

    /** Loads lyrics for the ticker bar using the exact same sources the Verses
     *  screen uses (a synced .lrc file beside the audio, else in-app pasted
     *  lyrics), so both screens always agree on what's synced vs plain. */
    private fun loadLyricsForCurrentSongIfNeeded(item: MediaItem?) {
        val mediaId = item?.mediaId
        if (mediaId == lastLyricsMediaId) return
        lastLyricsMediaId = mediaId

        stopLyricsCrawl()
        lastActiveLyricIndex = -1

        if (mediaId == null) {
            lyricsLines = emptyList()
            lyricsSynced = false
            binding.lyricsTickerText.text = "LYRICS"
            return
        }

        val dataPath = item.mediaMetadata.extras?.getString("data_path")
        val lrcPath = LyricsParser.findLrcPath(dataPath)

        viewLifecycleOwner.lifecycleScope.launch {
            val fileSynced = withContext(Dispatchers.IO) {
                try {
                    if (lrcPath != null) {
                        val file = File(lrcPath)
                        if (file.exists()) LyricsParser.parse(file.readText()) else emptyList()
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (_binding == null || mediaId != lastLyricsMediaId) return@launch

            if (fileSynced.isNotEmpty()) {
                applyLyrics(fileSynced, isSynced = true)
                return@launch
            }

            val stored = LyricsStore.get(requireContext(), mediaId)
            if (!stored.isNullOrBlank()) {
                val storedSynced = LyricsParser.parse(stored)
                if (storedSynced.isNotEmpty()) {
                    applyLyrics(storedSynced, isSynced = true)
                } else {
                    val plainLines = stored.lines().filter { it.isNotBlank() }.map { LyricsLine(0L, it) }
                    applyLyrics(plainLines, isSynced = false)
                }
            } else {
                lyricsLines = emptyList()
                lyricsSynced = false
                binding.lyricsTickerText.text = "No lyrics yet -- tap to add"
            }
        }
    }

    private fun applyLyrics(lines: List<LyricsLine>, isSynced: Boolean) {
        lyricsLines = lines
        lyricsSynced = isSynced
        if (isSynced) {
            binding.lyricsTickerText.translationY = 0f
            updateLyricsTicker((activity as? MainActivity)?.player?.currentPosition ?: 0L)
        } else {
            startLyricsCrawl(lines)
        }
    }

    /** Synced lyrics: swap to whichever line is current for the playback
     *  position, same "last line at/ before position" rule the Verses list uses. */
    private fun updateLyricsTicker(positionMs: Long) {
        if (_binding == null || !lyricsSynced || lyricsLines.isEmpty()) return
        var idx = -1
        for (i in lyricsLines.indices) {
            if (lyricsLines[i].timeMs <= positionMs) idx = i else break
        }
        if (idx == lastActiveLyricIndex) return
        lastActiveLyricIndex = idx
        binding.lyricsTickerText.text = if (idx >= 0) lyricsLines[idx].text else "\u266A"
    }

    /** Untimed lyrics: a slow, continuous upward crawl through all the lines,
     *  credits-style, clipped inside the fixed-height bar. */
    private fun startLyricsCrawl(lines: List<LyricsLine>) {
        stopLyricsCrawl()
        val text = lines.joinToString("\n\n") { it.text }
        binding.lyricsTickerText.text = text.ifBlank { "No lyrics yet -- tap to add" }

        binding.lyricsTickerText.post {
            if (_binding == null) return@post
            val barHeight = binding.lyricsTickerBar.height.toFloat()
            val textHeight = binding.lyricsTickerText.height.toFloat()
            if (barHeight <= 0f) return@post

            val startY = barHeight
            val endY = -textHeight
            val distancePx = startY - endY
            val pxPerSecond = 18f * resources.displayMetrics.density
            val durationMs = ((distancePx / pxPerSecond) * 1000).toLong().coerceAtLeast(4000L)

            lyricsCrawlAnimator = ValueAnimator.ofFloat(startY, endY).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener {
                    if (_binding != null) binding.lyricsTickerText.translationY = it.animatedValue as Float
                }
                start()
            }
        }
    }

    private fun stopLyricsCrawl() {
        lyricsCrawlAnimator?.cancel()
        lyricsCrawlAnimator = null
        if (_binding != null) binding.lyricsTickerText.translationY = 0f
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
                        updateLyricsTicker(pos)
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
        stopLyricsCrawl()
        binding.visualizer.release()
        super.onDestroyView()
        _binding = null
    }
}
