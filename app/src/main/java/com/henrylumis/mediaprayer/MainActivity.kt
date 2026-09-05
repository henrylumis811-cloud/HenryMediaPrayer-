package com.henrylumis.mediaprayer

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.tabs.TabLayoutMediator
import com.google.common.util.concurrent.MoreExecutors
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.data.toMediaItem
import com.henrylumis.mediaprayer.databinding.ActivityMainBinding
import com.henrylumis.mediaprayer.ui.PagerAdapter
import com.henrylumis.mediaprayer.util.Prefs
import kotlinx.coroutines.launch

/**
 * Playback here is bound to PlaybackService's DualPlayerBridge (not a raw
 * ExoPlayer directly), so every standard MediaController command below --
 * play/pause, seekToNextMediaItem, setMediaItems, moveMediaItem, etc. --
 * automatically gets gapless/crossfaded handling for free. No special
 * per-action routing is needed here anymore.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaController: MediaController? = null
    private var lastBackPressAt = 0L

    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Navigation-first behavior: Back from a secondary section returns
            // to Now Playing instead of immediately closing the player. A
            // second Back on the home section exits normally.
            if (binding.viewPager.currentItem != 0) {
                binding.viewPager.currentItem = 0
                return
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBackPressAt < 1800L) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            } else {
                lastBackPressAt = now
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Press Back again to exit",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** General playback control / metadata / state -- safe to use from any fragment. */
    val player: Player? get() = mediaController

    /** Direct ExoPlayer reference, same process, used only where audioSessionId is needed (visualizer). */
    val exoPlayerForVisualizer get() = PlaybackService.instance?.exoPlayer

    val equalizer get() = PlaybackService.instance?.equalizer

    /** The track a crossfade is currently fading toward but hasn't actually
     *  switched to yet -- UI screens prefer this over player.currentMediaItem
     *  when non-null, so tapping Next/a song feels instant even though the
     *  audio itself is still fading in underneath. */
    val pendingTrack: MediaItem? get() = PlaybackService.instance?.pendingCrossfadeTarget

    /** Direct seek -- see PlaybackService.seekToPosition for why this bypasses
     *  the normal MediaController path. */
    fun seekTo(positionMs: Long) {
        val service = PlaybackService.instance
        if (service != null) service.seekToPosition(positionMs)
        else mediaController?.seekTo(positionMs)
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* LibraryFragment re-scans itself when it becomes visible / on refresh */ }

    private val pickBackgroundPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers return a non-persistable URI; keep using it
                // for this session and fall back safely after access is lost.
            }
            Prefs.setBackgroundUri(this, uri)
            applyBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.viewPager.adapter = PagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 3
        // Premium page motion: neighboring screens stay subtly visible while
        // the active screen rises into focus instead of bluntly replacing it.
        binding.viewPager.setPageTransformer { page, position ->
            val absPosition = kotlin.math.abs(position)
            page.alpha = 0.58f + (1f - absPosition).coerceIn(0f, 1f) * 0.42f
            val scale = 0.965f + (1f - absPosition).coerceIn(0f, 1f) * 0.035f
            page.scaleX = scale
            page.scaleY = scale
            page.translationX = -position * page.width * 0.055f
            page.translationY = absPosition * 8f
        }
        val tabTitles = listOf("Altar", "Library", "Queue", "Verses", "Signal", "Playlists")
        val tabIcons = listOf(
            com.henrylumis.mediaprayer.R.drawable.ic_nav_home,
            com.henrylumis.mediaprayer.R.drawable.ic_nav_library,
            com.henrylumis.mediaprayer.R.drawable.ic_nav_queue,
            com.henrylumis.mediaprayer.R.drawable.ic_nav_lyrics,
            com.henrylumis.mediaprayer.R.drawable.ic_nav_signal,
            com.henrylumis.mediaprayer.R.drawable.ic_nav_playlist
        )
        val headerLabels = listOf("NOW PLAYING", "LIBRARY", "QUEUE", "VERSES", "SIGNAL", "PLAYLISTS")
        TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
            tab.setIcon(tabIcons[position])
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val nextTitle = headerLabels.getOrElse(position) { "NOW PLAYING" }
                binding.headerTitle.animate()
                    .alpha(0f)
                    .translationY(-5f)
                    .setDuration(90L)
                    .withEndAction {
                        binding.headerTitle.text = nextTitle
                        binding.headerTitle.translationY = 5f
                        binding.headerTitle.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(180L)
                            .start()
                    }
                    .start()
            }
        })

        binding.btnHeaderSearch.setOnClickListener {
            binding.viewPager.currentItem = 1 // Library tab
        }

        applyBackground()
        requestNeededPermissions()
        connectToPlaybackService()
    }

    fun pickBackground() {
        pickBackgroundPhoto.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun clearBackground() {
        Prefs.setBackgroundUri(this, null)
        applyBackground()
    }

    fun setBackgroundOpacity(percent: Int) {
        Prefs.setBackgroundOpacity(this, percent)
        binding.bgPhoto.alpha = percent / 100f
    }

    fun applyBackground() {
        val uri = Prefs.getBackgroundUri(this)
        val opacity = Prefs.getBackgroundOpacity(this) / 100f

        if (uri != null) {
            try {
                binding.bgPhoto.setImageURI(uri)
                binding.bgPhoto.alpha = opacity
                binding.bgPhoto.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                // Access to a previously-picked photo can be revoked by the
                // system after the app process is fully killed (varies by
                // OEM/Android version). Fall back to the built-in cinematic
                // supercar background instead of leaving a blank backdrop.
                Prefs.setBackgroundUri(this, null)
                binding.bgPhoto.setImageResource(R.drawable.bg_supercar_default)
                binding.bgPhoto.alpha = opacity
                binding.bgPhoto.visibility = android.view.View.VISIBLE
            }
        } else {
            // Henry Media Player ships with a premium supercar backdrop by
            // default. It is still controlled by the same Signal opacity
            // slider, so the user can make it subtle or prominent.
            binding.bgPhoto.setImageResource(R.drawable.bg_supercar_default)
            binding.bgPhoto.alpha = opacity
            binding.bgPhoto.visibility = android.view.View.VISIBLE
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    private fun connectToPlaybackService() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
            } catch (_: Exception) {
                mediaController = null
            }
        }, MoreExecutors.directExecutor())
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        val controller = mediaController ?: return
        val items = songs.map { it.toMediaItem() }
        controller.setMediaItems(items, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            // Nothing loaded yet (e.g. fresh app launch) -- pressing Play should
            // just start the library playing rather than silently doing nothing.
            lifecycleScope.launch {
                val songs = MusicScanner.scan(this@MainActivity)
                if (songs.isNotEmpty()) playQueue(songs, 0)
            }
            return
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun skipNext() {
        // The on-screen Next button stays in-process with PlaybackService.
        // This removes a MediaController round-trip from the critical path and
        // guarantees a single-tap immediate switch when crossfade is off.
        PlaybackService.instance?.skipNextDirect()
            ?: mediaController?.seekToNextMediaItem()
    }
    fun skipPrevious() {
        val controller = mediaController ?: return
        // Familiar music-player behavior: if the current track is already
        // meaningfully underway, Previous first returns to its beginning.
        // Only a second press (or a press near the start) navigates backward.
        // During a crossfade, let the bridge handle Previous so its transition
        // state and shuffle history stay authoritative.
        if (pendingTrack != null) {
            controller.seekToPreviousMediaItem()
            return
        }
        if (controller.currentPosition > 4_000L) {
            controller.seekTo(0L)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    // --- Queue management ---

    fun getQueue(): List<MediaItem> {
        val controller = mediaController ?: return emptyList()
        return (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it) }
    }

    fun currentQueueIndex(): Int = mediaController?.currentMediaItemIndex ?: -1

    fun moveQueueItem(from: Int, to: Int) {
        mediaController?.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) {
        mediaController?.removeMediaItem(index)
    }

    fun playQueueIndex(index: Int) {
        // Queue-row taps are explicit user navigation. Go directly to the
        // playback bridge so the selected song starts immediately and never
        // uses the configured crossfade.
        PlaybackService.instance?.playQueueIndexDirect(index)
            ?: mediaController?.apply {
                seekTo(index, 0L)
                play()
            }
    }

    fun startSleepTimer(minutes: Int) {
        PlaybackService.instance?.sleepTimer?.start(minutes)
    }

    fun cancelSleepTimer() {
        PlaybackService.instance?.sleepTimer?.cancel()
    }

    override fun onDestroy() {
        mediaController?.release()
        mediaController = null
        super.onDestroy()
    }

}
