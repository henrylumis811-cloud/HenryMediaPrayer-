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
        equalizer = EqualizerController(bridge.audioSessionId)
        loudnessNormalizer = LoudnessNormalizer(bridge.audioSessionId)

        bridge.onQueueAdvanced = { mediaItem ->
            mediaItem?.mediaId?.let { RecentlyPlayedStore.addPlayed(applicationContext, it) }
            applyNormalizationForCurrentTrack(mediaItem)
            resetStatsTrackingForNewTrack(mediaItem)
            savePlaybackState()
        }

        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = sessionActivityIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

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

    private fun applyNormalizationForCurrentTrack(mediaItem: MediaItem?) {
        loudnessNormalizer?.reset()
        if (!Prefs.isNormalizationEnabled(applicationContext)) return
        val dataPath = mediaItem?.mediaMetadata?.extras?.getString("data_path") ?: return

        serviceScope.launch {
            val gainDb = withContext(Dispatchers.IO) { ReplayGainReader.readTrackGainDb(dataPath) }
            withContext(Dispatchers.Main) {
                if (gainDb == null) return@withContext
                val normalizer = loudnessNormalizer ?: return@withContext
                if (gainDb >= 0) {
                    normalizer.applyBoostDb(gainDb)
                } else {
                    normalizer.reset()
                    // Note: with the dual-player bridge, per-track volume trims
                    // are applied via the bridge's own active-player volume in
                    // a future pass if needed -- normalization boost (the more
                    // common case, quiet tracks) already works via the shared
                    // session's LoudnessEnhancer above regardless of attenuation.
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

    /** Rebuilds the last-played track (paused, at its saved position) so the
     *  Altar screen and playback are ready to continue the instant the app
     *  reopens, even after a full process restart. */
    private fun restoreLastSessionIfFresh() {
        val saved = PlaybackStateStore.load(applicationContext) ?: return
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
