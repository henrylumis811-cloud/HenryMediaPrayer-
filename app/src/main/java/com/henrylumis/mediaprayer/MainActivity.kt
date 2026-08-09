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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.tabs.TabLayoutMediator
import com.google.common.util.concurrent.MoreExecutors
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ActivityMainBinding
import com.henrylumis.mediaprayer.ui.PagerAdapter
import com.henrylumis.mediaprayer.util.Prefs
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaController: MediaController? = null

    /** General playback control / metadata / state -- safe to use from any fragment. */
    val player: Player? get() = mediaController

    /** Direct ExoPlayer reference, same process, used only where audioSessionId is needed (visualizer). */
    val exoPlayerForVisualizer get() = PlaybackService.instance?.exoPlayer

    val equalizer get() = PlaybackService.instance?.equalizer

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* LibraryFragment re-scans itself when it becomes visible / on refresh */ }

    private val pickBackgroundPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            Prefs.setBackgroundUri(this, uri)
            applyBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = PagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 3
        val tabTitles = listOf("Altar", "Library", "Queue", "Verses", "Signal")
        TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

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
        if (uri != null) {
            try {
                binding.bgPhoto.setImageURI(uri)
                binding.bgPhoto.alpha = Prefs.getBackgroundOpacity(this) / 100f
                binding.bgPhoto.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                // Access to a previously-picked photo can be revoked by the
                // system after the app process is fully killed (varies by
                // OEM/Android version). Rather than crash every launch after
                // that happens, just fall back to the default HUD background.
                Prefs.setBackgroundUri(this, null)
                binding.bgPhoto.setImageDrawable(null)
                binding.bgPhoto.visibility = android.view.View.GONE
            }
        } else {
            binding.bgPhoto.visibility = android.view.View.GONE
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
            mediaController = controllerFuture.get()
            mediaController?.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.mediaId?.let {
                        com.henrylumis.mediaprayer.util.RecentlyPlayedStore.addPlayed(this@MainActivity, it)
                    }
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (mediaController == null) return
        val items = songs.map { song ->
            val extras = android.os.Bundle().apply {
                song.dataPath?.let { putString("data_path", it) }
            }
            MediaItem.Builder()
                .setUri(song.uriString)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }
        // Routed through the service directly (not the remote controller) so
        // manual song selection can crossfade too, same as auto-advance and skip.
        val service = PlaybackService.instance
        if (service != null) {
            service.crossfadePlayQueue(items, startIndex)
        } else {
            mediaController?.apply {
                setMediaItems(items, startIndex, 0L)
                prepare()
                play()
            }
        }
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
        PlaybackService.instance?.crossfadeSkipNext() ?: mediaController?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        PlaybackService.instance?.crossfadeSkipPrevious() ?: mediaController?.seekToPreviousMediaItem()
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
        val service = PlaybackService.instance
        if (service != null) {
            service.crossfadePlayQueueIndex(index)
        } else {
            mediaController?.apply {
                seekTo(index, 0L)
                play()
            }
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
