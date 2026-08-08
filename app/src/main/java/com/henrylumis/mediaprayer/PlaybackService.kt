package com.henrylumis.mediaprayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.henrylumis.mediaprayer.audio.CrossfadeController
import com.henrylumis.mediaprayer.audio.EqualizerController
import com.henrylumis.mediaprayer.audio.LoudnessNormalizer
import com.henrylumis.mediaprayer.audio.ReplayGainReader
import com.henrylumis.mediaprayer.util.PlaybackStateStore
import com.henrylumis.mediaprayer.util.Prefs
import com.henrylumis.mediaprayer.util.RecentlyPlayedStore
import com.henrylumis.mediaprayer.util.SavedPlayback
import com.henrylumis.mediaprayer.util.SleepTimer
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media3's MediaSessionService owns the ExoPlayer instance, the system
 * notification, lock-screen controls, and the foreground-service lifecycle
 * all in one place. Rolling these by hand separately (old app did) is
 * exactly where the previous crash likely came from -- a stale/duplicate
 * player + notification + session getting out of sync a few seconds in.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var audioAttributes: AudioAttributes
    private var mediaSession: MediaSession? = null
    val sleepTimer = SleepTimer()
    private val stateSaveHandler = Handler(Looper.getMainLooper())
    private val crossfadeWatcherHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    companion object {
        var instance: PlaybackService? = null
    }

    val exoPlayer: ExoPlayer get() = player

    var equalizer: EqualizerController? = null
        private set

    private var loudnessNormalizer: LoudnessNormalizer? = null
    private var crossfade: CrossfadeController? = null

    // --- Listening stats tracking state ---
    private var statsLastPositionMs = 0L
    private var statsAccumulatedMs = 0L
    private var statsCountedThisTrack = false
    private val PLAY_COUNT_THRESHOLD_MS = 30_000L

    private fun setupAudioEffectsWhenReady() {
        val sessionId = player.audioSessionId
        if (sessionId == 0) return
        if (equalizer == null) equalizer = EqualizerController(sessionId)
        if (loudnessNormalizer == null) loudnessNormalizer = LoudnessNormalizer(sessionId)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        crossfade = CrossfadeController(applicationContext, audioAttributes)

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
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
                    setupAudioEffectsWhenReady()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                crossfade?.onTrackTransitioned(player)
                mediaItem?.mediaId?.let { RecentlyPlayedStore.addPlayed(applicationContext, it) }
                applyNormalizationForCurrentTrack(mediaItem)
                resetStatsTrackingForNewTrack(mediaItem)
                savePlaybackState()
            }
        })

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

        restoreLastSessionIfFresh()
        startPeriodicStateSaving()
        startCrossfadeWatcher()
    }

    // --- Gapless playback ---
    // No special code is required for basic gapless transitions between
    // tracks -- ExoPlayer's extractors read embedded gapless metadata (e.g.
    // MP3 LAME/Xing headers) automatically and preload the next queue item
    // ahead of time, so back-to-back tracks already play with no gap by
    // default. Crossfade (below) is an optional, separate, opt-in effect on
    // top of that for a more deliberate DJ-style blend.

    // --- Crossfade ---

    private fun startCrossfadeWatcher() {
        val runnable = object : Runnable {
            override fun run() {
                if (Prefs.isCrossfadeEnabled(applicationContext) && player.isPlaying) {
                    val duration = player.duration
                    val position = player.currentPosition
                    val crossfadeMs = Prefs.getCrossfadeSeconds(applicationContext) * 1000L
                    if (duration > 0 && duration - position <= crossfadeMs) {
                        crossfade?.maybeStartCrossfade(player, crossfadeMs)
                    }
                }
                crossfadeWatcherHandler.postDelayed(this, 500)
            }
        }
        crossfadeWatcherHandler.postDelayed(runnable, 500)
    }

    // --- Volume normalization (ReplayGain) ---

    private fun applyNormalizationForCurrentTrack(mediaItem: MediaItem?) {
        player.volume = 1f
        loudnessNormalizer?.reset()
        if (!Prefs.isNormalizationEnabled(applicationContext)) return
        val dataPath = mediaItem?.mediaMetadata?.extras?.getString("data_path") ?: return

        serviceScope.launch {
            val gainDb = withContext(Dispatchers.IO) { ReplayGainReader.readTrackGainDb(dataPath) }
            withContext(Dispatchers.Main) {
                if (gainDb == null) return@withContext // no embedded tag -- leave at normal volume
                val normalizer = loudnessNormalizer ?: return@withContext
                if (gainDb >= 0) {
                    normalizer.applyBoostDb(gainDb)
                } else {
                    normalizer.reset()
                    player.volume = normalizer.dbToLinearAttenuation(gainDb)
                }
            }
        }
    }

    // --- Listening analytics ---

    private fun resetStatsTrackingForNewTrack(mediaItem: MediaItem?) {
        statsAccumulatedMs = 0L
        statsCountedThisTrack = false
        statsLastPositionMs = 0L
        if (mediaItem == null) return

        ListeningStatsStore.setTrackMeta(
            applicationContext,
            mediaItem.mediaId,
            mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
            mediaItem.mediaMetadata.artist?.toString() ?: ""
        )

        // Genre isn't in MediaStore's basic projection, so read it lazily from
        // the file itself the first time each song plays, then cache it --
        // avoids re-reading it (and the I/O cost) on every single play.
        if (ListeningStatsStore.getGenre(applicationContext, mediaItem.mediaId) == null) {
            val dataPath = mediaItem.mediaMetadata.extras?.getString("data_path")
            if (dataPath != null) {
                serviceScope.launch {
                    val genre = withContext(Dispatchers.IO) {
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(dataPath)
                            val g = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                            retriever.release()
                            g
                        } catch (e: Exception) {
                            null
                        }
                    }
                    ListeningStatsStore.setGenreIfAbsent(applicationContext, mediaItem.mediaId, genre)
                }
            }
        }
    }

    /** Called from the periodic tick -- accumulates real listened time (seek-aware:
     *  a jump in position isn't counted as "listened", only steady playback is). */
    private fun trackListeningProgress() {
        val item = player.currentMediaItem ?: return
        if (!player.isPlaying) return
        val position = player.currentPosition
        val delta = position - statsLastPositionMs
        statsLastPositionMs = position

        if (delta in 1..10_000) {
            ListeningStatsStore.addListenedMs(applicationContext, item.mediaId, delta)
            statsAccumulatedMs += delta
            if (!statsCountedThisTrack && statsAccumulatedMs >= PLAY_COUNT_THRESHOLD_MS) {
                statsCountedThisTrack = true
                ListeningStatsStore.incrementPlayCount(applicationContext, item.mediaId)
            }
        }
    }

    /** Rebuilds the last-played track (paused, at its saved position) so the
     *  Altar screen and playback are ready to continue the instant the app
     *  reopens, even after a full process restart. */
    private fun restoreLastSessionIfFresh() {
        if (player.mediaItemCount > 0) return
        val saved = PlaybackStateStore.load(applicationContext) ?: return
        try {
            val extras = android.os.Bundle().apply {
                saved.dataPath?.let { putString("data_path", it) }
            }
            val item = MediaItem.Builder()
                .setUri(saved.uri)
                .setMediaId(saved.mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(saved.title)
                        .setArtist(saved.artist)
                        .setAlbumTitle(saved.album)
                        .setExtras(extras)
                        .build()
                )
                .build()
            player.setMediaItem(item, saved.positionMs)
            player.prepare()
            player.playWhenReady = false
        } catch (_: Exception) {
            // Corrupt/removed file -- just start with an empty queue instead of crashing.
        }
    }

    private fun savePlaybackState() {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        PlaybackStateStore.save(
            applicationContext,
            SavedPlayback(
                mediaId = item.mediaId,
                uri = uri,
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                album = item.mediaMetadata.albumTitle?.toString() ?: "",
                positionMs = player.currentPosition,
                dataPath = item.mediaMetadata.extras?.getString("data_path")
            )
        )
    }

    private fun startPeriodicStateSaving() {
        val runnable = object : Runnable {
            override fun run() {
                if (player.mediaItemCount > 0) savePlaybackState()
                stateSaveHandler.postDelayed(this, 5000)
            }
        }
        stateSaveHandler.postDelayed(runnable, 5000)

        // Separate, tighter-interval tick for listening-time accuracy.
        val statsRunnable = object : Runnable {
            override fun run() {
                trackListeningProgress()
                stateSaveHandler.postDelayed(this, 2000)
            }
        }
        stateSaveHandler.postDelayed(statsRunnable, 2000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackState()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePlaybackState()
        stateSaveHandler.removeCallbacksAndMessages(null)
        crossfadeWatcherHandler.removeCallbacksAndMessages(null)
        crossfade?.release()
        serviceScope.cancel()
        sleepTimer.cancel()
        equalizer?.release()
        loudnessNormalizer?.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }
}
