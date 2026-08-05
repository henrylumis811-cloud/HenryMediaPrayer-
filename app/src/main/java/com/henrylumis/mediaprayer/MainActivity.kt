package com.henrylumis.mediaprayer

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.tabs.TabLayoutMediator
import com.google.common.util.concurrent.MoreExecutors
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ActivityMainBinding
import com.henrylumis.mediaprayer.ui.PagerAdapter
import com.henrylumis.mediaprayer.util.Prefs

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaController: MediaController? = null

    /** General playback control / metadata / state -- safe to use from any fragment. */
    val player: Player? get() = mediaController

    /** Direct ExoPlayer reference, same process, used only where audioSessionId is needed (visualizer). */
    val exoPlayerForVisualizer get() = PlaybackService.instance?.exoPlayer

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
            binding.bgPhoto.setImageURI(uri)
            binding.bgPhoto.alpha = Prefs.getBackgroundOpacity(this) / 100f
            binding.bgPhoto.visibility = android.view.View.VISIBLE
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
        }, MoreExecutors.directExecutor())
    }

    fun playQueue(songs: List<Song>, startIndex: Int) {
        val controller = mediaController ?: return
        val items = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.uriString)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .build()
                )
                .build()
        }
        controller.setMediaItems(items, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun skipNext() { mediaController?.seekToNextMediaItem() }
    fun skipPrevious() { mediaController?.seekToPreviousMediaItem() }

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
        mediaController?.apply {
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
