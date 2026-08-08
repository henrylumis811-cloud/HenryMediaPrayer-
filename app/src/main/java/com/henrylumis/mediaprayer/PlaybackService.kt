package com.henrylumis.mediaprayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.henrylumis.mediaprayer.audio.EqualizerController
import com.henrylumis.mediaprayer.util.Prefs
import com.henrylumis.mediaprayer.util.RecentlyPlayedStore
import com.henrylumis.mediaprayer.util.SleepTimer

/**
 * Media3's MediaSessionService owns the ExoPlayer instance, the system
 * notification, lock-screen controls, and the foreground-service lifecycle
 * all in one place. Rolling these by hand separately (old app did) is
 * exactly where the previous crash likely came from -- a stale/duplicate
 * player + notification + session getting out of sync a few seconds in.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    val sleepTimer = SleepTimer()

    // Same-process accessor so the UI (visualizer) can read audioSessionId,
    // which isn't exposed on the generic Player/MediaController interface.
    companion object {
        var instance: PlaybackService? = null
    }

    val exoPlayer: ExoPlayer get() = player

    var equalizer: EqualizerController? = null
        private set

    // Periodically persists queue + exact position while playing, so an
    // abrupt process kill (swipe-away, low-memory) doesn't lose the spot --
    // onPause/onDestroy/onTaskRemoved cover the clean-shutdown paths below.
    private val saveStateHandler = Handler(Looper.getMainLooper())
    private var saveStateRunnable: Runnable? = null

    private fun saveState() {
        if (!::player.isInitialized) return
        try {
            val ids = (0 until player.mediaItemCount).mapNotNull { i ->
                player.getMediaItemAt(i).mediaId.toLongOrNull()
            }
            if (ids.isEmpty()) return
            val index = player.currentMediaItemIndex.coerceIn(0, ids.size - 1)
            val position = player.currentPosition.coerceAtLeast(0)
            Prefs.setSavedQueue(this, ids, index, position)
        } catch (_: Exception) {
        }
    }

    private fun setupEqualizerWhenReady() {
        val sessionId = player.audioSessionId
        if (sessionId == 0 || equalizer != null) return
        equalizer = EqualizerController(sessionId)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Belt-and-braces: never let an unexpected player error kill the process.
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Skip the broken track instead of crashing the whole app.
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    player.stop()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    setupEqualizerWhenReady()
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Records plays regardless of how playback started (Library tap,
                // Queue tap, or auto-advance to the next track).
                mediaItem?.mediaId?.let { RecentlyPlayedStore.addPlayed(applicationContext, it) }
                saveState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveState()
            }
        })

        saveStateRunnable = object : Runnable {
            override fun run() {
                if (::player.isInitialized && player.isPlaying) saveState()
                saveStateHandler.postDelayed(this, 5000)
            }
        }
        saveStateHandler.post(saveStateRunnable!!)

        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = sessionActivityIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val sessionBuilder = MediaSession.Builder(this, player)
        pendingIntent?.let { sessionBuilder.setSessionActivity(it) }
        mediaSession = sessionBuilder.build()

        sleepTimer.onFire = {
            player.pause()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState()
        // If nothing is playing, let the service die with the task (normal Android behavior).
        // If something IS playing, Media3 keeps the foreground service alive automatically.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveState()
        saveStateRunnable?.let { saveStateHandler.removeCallbacks(it) }
        sleepTimer.cancel()
        equalizer?.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }
}
