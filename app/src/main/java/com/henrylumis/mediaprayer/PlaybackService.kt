package com.henrylumis.mediaprayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.henrylumis.mediaprayer.audio.DualPlayerBridge
import com.henrylumis.mediaprayer.audio.EqualizerController
import com.henrylumis.mediaprayer.audio.LoudnessNormalizer
import com.henrylumis.mediaprayer.audio.ReplayGainReader
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.SongSorter
import com.henrylumis.mediaprayer.data.toMediaItem
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import com.henrylumis.mediaprayer.util.PlaybackStateStore
import com.henrylumis.mediaprayer.util.Prefs
import com.henrylumis.mediaprayer.util.RecentlyPlayedStore
import com.henrylumis.mediaprayer.util.SavedPlayback
import com.henrylumis.mediaprayer.util.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Media3's MediaSessionService owns the ExoPlayer instance, the system
 * notification, lock-screen controls, and the foreground-service lifecycle
 * all in one place.
 *
 * Playback itself is delegated to DualPlayerBridge, which wraps two real
 * ExoPlayer instances and ping-pongs between them for genuinely gapless
 * (and, when enabled, crossfaded) transitions -- see that class for why.
 * MediaSession is bound to the bridge, not to a raw ExoPlayer, so every
 * standard playback command (from this app OR from lock-screen/Bluetooth/
 * Android Auto controls) automatically gets the same smooth handling.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var bridge: DualPlayerBridge
    private var mediaSession: MediaSession? = null
    val sleepTimer = SleepTimer()
    private val stateSaveHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    companion object {
        var instance: PlaybackService? = null
        private const val PLAY_COUNT_THRESHOLD_MS = 30_000L
    }

    /** Same-process accessor for the visualizer -- reflects whichever internal
     *  player is currently active, but the audio session id it reports is
     *  fixed for the bridge's lifetime, so callers don't need to re-attach. */
    val exoPlayer get() = bridge.activeExoPlayer

    var equalizer: EqualizerController? = null
        private set

    /** The track a crossfade is currently fading INTO, before the switch has
     *  actually landed -- lets the UI show the new track instantly on tap. */
    val pendingCrossfadeTarget: MediaItem? get() = bridge.pendingTarget

    /** Direct, same-process seek -- bypasses the MediaController/Session IPC
     *  layer entirely for reliability (dragging the Altar seek bar was landing
     *  on the session but not visibly taking effect, most likely a command-
     *  availability propagation timing issue over that layer). */
    fun seekToPosition(positionMs: Long) {
        try { bridge.seekTo(positionMs) } catch (_: Exception) {}
    }

    /** Same-process navigation used by the visible player controls. This
     * avoids waiting for a MediaController command round-trip, so Next is a
     * single-tap action even when crossfade is disabled. */
    fun skipNextDirect() {
        try { bridge.skipToNext() } catch (_: Exception) {}
    }

    /** Direct queue selection used by the Queue screen. */
    fun playQueueIndexDirect(index: Int) {
        try { bridge.playIndex(index) } catch (_: Exception) {}
    }

    private var loudnessNormalizer: LoudnessNormalizer? = null

    // --- Listening stats tracking state ---
    private var statsLastPositionMs = 0L
    private var statsAccumulatedMs = 0L
    private var statsCountedThisTrack = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        bridge = DualPlayerBridge(applicationContext)

        // The shared session id is fixed at construction, so effects can be
        // set up immediately -- no need to wait for playback to start.
        equalizer = EqualizerController(applicationContext, bridge.audioSessionId)
        loudnessNormalizer = LoudnessNormalizer(bridge.audioSessionId)

        // Restore navigation modes before rebuilding the saved queue so the
        // first Next/Previous after process death follows the same behavior
        // the user had chosen previously.
        bridge.restorePlaybackModes(
            Prefs.isShuffleEnabled(applicationContext),
            Prefs.getRepeatMode(applicationContext)
        )

        bridge.onTransitionTarget = { mediaItem ->
            // Read ReplayGain as soon as the target is known, not after the fade
            // has already completed. This prevents a loud incoming track from
            // briefly arriving at full level. The bridge also guards the volume
            // layer separately from its crossfade curve.
            applyNormalizationForCurrentTrack(mediaItem)
        }

        bridge.onTransitionStarted = { outgoing, _, natural ->
            val id = outgoing.mediaId
            if (natural) {
                ListeningStatsStore.incrementCompletion(applicationContext, id)
            } else if (statsAccumulatedMs >= 3_000L) {
                // Very short taps are not treated as meaningful skips. Once a
                // listener has actually spent a few seconds on the track, a
                // navigation away is recorded as a skip.
                ListeningStatsStore.incrementSkip(applicationContext, id)
            }
        }

        bridge.onQueueAdvanced = { mediaItem ->
            mediaItem?.mediaId?.let { RecentlyPlayedStore.addPlayed(applicationContext, it) }
            applyNormalizationForCurrentTrack(mediaItem)
            resetStatsTrackingForNewTrack(mediaItem)
            savePlaybackState()
        }

        bridge.onPlaybackStateChanged = {
            // Save immediately for explicit Play/Pause/Seek/Stop commands.
            // The periodic checkpoint remains as a crash-safety net.
            savePlaybackState()
        }

        // Notification/lock-screen taps should open the player directly, not
        // replay the cinematic launcher every time a user taps a media control.
        // SplashActivity is only the true app-launch entry point.
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1001, sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionBuilder = MediaSession.Builder(this, bridge)
        pendingIntent?.let { sessionBuilder.setSessionActivity(it) }
        mediaSession = sessionBuilder.build()

        sleepTimer.onFire = {
            bridge.pause()
        }

        restoreLastSessionIfFresh()
        startPeriodicStateSaving()
    }

    // --- Volume normalization (ReplayGain) ---

    private var normalizationGeneration = 0L

    private fun applyNormalizationForCurrentTrack(mediaItem: MediaItem?) {
        normalizationGeneration++
        val generation = normalizationGeneration
        val itemId = mediaItem?.mediaId ?: return
        bridge.setTrackAttenuation(itemId, 1f)
        loudnessNormalizer?.reset()
        if (!Prefs.isNormalizationEnabled(applicationContext)) return
        val dataPath = mediaItem.mediaMetadata.extras?.getString("data_path") ?: return

        serviceScope.launch {
            val gainDb = withContext(Dispatchers.IO) { ReplayGainReader.readTrackGainDb(dataPath) }
            withContext(Dispatchers.Main) {
                if (generation != normalizationGeneration) return@withContext
                if (bridge.currentMediaItem?.mediaId != itemId && bridge.pendingTarget?.mediaId != itemId) return@withContext
                if (gainDb == null) return@withContext
                // The two ExoPlayers intentionally share one audio session so the
                // EQ/visualizer session stays stable across crossfades. A LoudnessEnhancer
                // attached to that shared session would therefore boost BOTH sides of a
                // crossfade when the incoming track has positive gain. That makes the
                // outgoing track jump in level and defeats the carefully controlled fade.
                // Normalize safely by attenuation only: loud masters are turned down,
                // while quiet masters are never digitally boosted.
                loudnessNormalizer?.reset()
                val attenuation = if (gainDb < 0.0) {
                    10.0.pow(gainDb / 20.0).coerceIn(0.15, 1.0).toFloat()
                } else {
                    1f
                }
                bridge.setTrackAttenuation(itemId, attenuation)
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
            mediaItem.mediaMetadata.artist?.toString() ?: "",
            mediaItem.mediaMetadata.extras?.getLong("duration_ms") ?: 0L,
            mediaItem.mediaMetadata.albumTitle?.toString() ?: ""
        )

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
        val item = bridge.currentMediaItem ?: return
        if (!bridge.isPlaying) return
        val position = bridge.currentPosition
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

    /** Rebuilds the FULL library (in the same sort order shown in the Library
     *  tab), positioned at the last-played track and its saved position, so
     *  pressing Play resumes not just that one song but continues naturally
     *  through the rest of your library from there -- exactly like the
     *  session never ended. Falls back to just the single saved track if the
     *  library scan fails for any reason, rather than leaving nothing loaded. */
    private fun restoreLastSessionIfFresh() {
        val saved = PlaybackStateStore.load(applicationContext) ?: return

        serviceScope.launch {
            try {
                val allSongs = MusicScanner.scan(applicationContext)
                val sortMode = SongSorter.modeFromSavedName(Prefs.getSortMode(applicationContext))
                val sorted = SongSorter.sort(applicationContext, allSongs, sortMode)
                if (sorted.isEmpty()) {
                    restoreSingleTrackFallback(saved)
                    return@launch
                }
                val items = sorted.map { it.toMediaItem() }
                val targetIndex = sorted.indexOfFirst { it.id.toString() == saved.mediaId }
                    .let { if (it == -1) 0 else it }
                withContext(Dispatchers.Main) {
                    bridge.restoreQueueAtPosition(items, targetIndex, saved.positionMs)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { restoreSingleTrackFallback(saved) }
            }
        }
    }

    private fun restoreSingleTrackFallback(saved: com.henrylumis.mediaprayer.util.SavedPlayback) {
        try {
            val extras = android.os.Bundle().apply {
                saved.dataPath?.let { putString("data_path", it) }
                if (saved.durationMs > 0) putLong("duration_ms", saved.durationMs)
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
            bridge.restoreQueueAtPosition(listOf(item), 0, saved.positionMs)
        } catch (_: Exception) {
            // Corrupt/removed file -- just start with an empty queue instead of crashing.
        }
    }

    private fun savePlaybackState() {
        val item = bridge.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        PlaybackStateStore.save(
            applicationContext,
            SavedPlayback(
                mediaId = item.mediaId,
                uri = uri,
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                album = item.mediaMetadata.albumTitle?.toString() ?: "",
                positionMs = bridge.currentPosition,
                dataPath = item.mediaMetadata.extras?.getString("data_path"),
                durationMs = item.mediaMetadata.extras?.getLong("duration_ms") ?: 0L
            )
        )
    }

    private fun startPeriodicStateSaving() {
        val runnable = object : Runnable {
            override fun run() {
                if (bridge.mediaItemCount > 0) savePlaybackState()
                stateSaveHandler.postDelayed(this, 5000)
            }
        }
        stateSaveHandler.postDelayed(runnable, 5000)

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
        serviceScope.cancel()
        sleepTimer.cancel()
        equalizer?.release()
        loudnessNormalizer?.release()
        mediaSession?.release()
        mediaSession = null
        bridge.releaseAll()
        instance = null
        super.onDestroy()
    }
}
