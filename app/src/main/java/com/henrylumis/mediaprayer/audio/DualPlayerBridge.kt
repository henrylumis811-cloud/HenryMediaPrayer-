package com.henrylumis.mediaprayer.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.henrylumis.mediaprayer.util.Prefs

/**
 * A single stable Player identity that MediaSession is bound to for the
 * app's entire lifetime -- but internally, it holds TWO real ExoPlayer
 * instances and silently swaps which one is "live" for a true ping-pong
 * crossfade with ZERO seeks, ever.
 *
 * Why this exists: every earlier crossfade attempt eventually needed one
 * player to "catch up" to where the other had already gotten to, and any
 * seek -- even on local files -- costs a brief decoder re-sync that can be
 * heard as a micro gap or drift. The only way to truly avoid that is to
 * never seek at all: start the *next* track fresh on the idle player the
 * moment a transition begins, let it play continuously and undisturbed the
 * whole time, and when the transition finishes, just relabel it "the active
 * player" -- it was already exactly where it needed to be the entire time.
 *
 * Every transition (auto-advance, manual skip, tapping a song, even a
 * lock-screen/Bluetooth skip button) goes through this SAME mechanism, just
 * with a different fade length: your configured crossfade duration when
 * it's turned on, or a technical ~80ms fade when it's off -- long enough to
 * avoid an audible digital click, short enough to feel like an instant cut.
 * This also means gapless playback no longer depends on ExoPlayer's own
 * internal queue engine at all; it's built into this swap directly.
 *
 * MediaSession, the notification, and the Queue/Altar UI only ever see this
 * wrapper, never the two real ExoPlayers -- they don't need to know a swap
 * ever happened.
 */
class DualPlayerBridge(
    private val context: Context,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {

    companion object {
        private const val TECHNICAL_FADE_MS = 80L
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Both players share ONE fixed audio session id, since which physical
    // player is "active" swaps over time -- without this, the Equalizer/
    // LoudnessEnhancer (attached to a session id once) would silently stop
    // affecting the audio the first time a swap happened.
    private val sharedAudioSessionId: Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()

    private fun newExoPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                audioSessionId = sharedAudioSessionId
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Don't let a single bad file take down playback -- move on.
                        if (this@apply === active()) skipToNext()
                    }
                })
            }

    /** The single audio session id shared by both internal players -- stable
     *  for the lifetime of the service regardless of which player is "active",
     *  so the visualizer/equalizer/normalizer never need to re-attach. */
    val audioSessionId: Int get() = sharedAudioSessionId

    private var playerA: ExoPlayer = newExoPlayer()
    private var playerB: ExoPlayer = newExoPlayer()
    private var activeIsA = true
    private fun active(): ExoPlayer = if (activeIsA) playerA else playerB
    private fun idle(): ExoPlayer = if (activeIsA) playerB else playerA

    /** The real ExoPlayer currently producing audio -- used by the visualizer,
     *  equalizer, and normalizer, which need a genuine audio session id. */
    val activeExoPlayer: ExoPlayer get() = active()

    private var queue: List<MediaItem> = emptyList()
    private var currentIndex = 0
    private var isCrossfadingNow = false

    var onQueueAdvanced: ((MediaItem?) -> Unit)? = null

    /** The track a fade is currently blending INTO, before the real switch has
     *  landed -- lets the UI show the new track instantly on tap, while the
     *  audio itself is still fading in underneath. Null when nothing's pending. */
    var pendingTarget: MediaItem? = null
        private set

    private val handler = Handler(looper)
    private var fadeRunnable: Runnable? = null
    private var watcherRunnable: Runnable? = null

    init {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                invalidateState()
            }
        }
        playerA.addListener(listener)
        playerB.addListener(listener)
        startWatcher()
    }

    private fun effectiveFadeDuration(): Long =
        if (Prefs.isCrossfadeEnabled(context)) Prefs.getCrossfadeSeconds(context) * 1000L else TECHNICAL_FADE_MS

    // --- Watcher: catches the natural end of the active track ---

    private fun startWatcher() {
        watcherRunnable = object : Runnable {
            override fun run() {
                if (!isCrossfadingNow) {
                    val a = active()
                    val fadeMs = effectiveFadeDuration()
                    if (a.isPlaying && a.duration > 0 &&
                        a.duration - a.currentPosition <= fadeMs &&
                        currentIndex + 1 < queue.size
                    ) {
                        beginCrossfadeTo(currentIndex + 1)
                    }
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(watcherRunnable!!, 200)
    }

    // --- Core swap mechanism ---

    private fun beginCrossfadeToItem(targetItem: MediaItem, onSwapComplete: () -> Unit) {
        if (isCrossfadingNow) {
            // Rapid re-trigger (e.g. user tapping Next repeatedly): cancel the
            // in-flight fade and jump straight there instead of queuing up
            // multiple overlapping fades.
            fadeRunnable?.let { handler.removeCallbacks(it) }
            try { idle().stop() } catch (_: Exception) {}
            isCrossfadingNow = false
        }
        isCrossfadingNow = true
        pendingTarget = targetItem
        val outgoing = active()
        val incoming = idle()
        try {
            incoming.setMediaItem(targetItem)
            incoming.prepare()
            incoming.volume = 0f
            incoming.play()
        } catch (e: Exception) {
            isCrossfadingNow = false
            pendingTarget = null
            onSwapComplete()
            return
        }

        val duration = effectiveFadeDuration()
        val steps = 20
        val stepDelay = (duration / steps).coerceAtLeast(10)
        var step = 0
        fadeRunnable = object : Runnable {
            override fun run() {
                step++
                val t = (step.toFloat() / steps).coerceIn(0f, 1f)
                try {
                    outgoing.volume = 1f - t
                    incoming.volume = t
                } catch (_: Exception) {
                }
                if (step < steps) {
                    handler.postDelayed(this, stepDelay)
                } else {
                    // The swap: incoming has been playing continuously and
                    // correctly this whole time, so becoming "active" is just
                    // a label change -- nothing to seek, nothing to catch up.
                    activeIsA = !activeIsA
                    try {
                        outgoing.pause()
                        outgoing.volume = 1f
                    } catch (_: Exception) {
                    }
                    isCrossfadingNow = false
                    pendingTarget = null
                    onSwapComplete()
                    invalidateState()
                }
            }
        }
        handler.post(fadeRunnable!!)
    }

    private fun beginCrossfadeTo(targetIndex: Int) {
        if (targetIndex !in queue.indices) return
        beginCrossfadeToItem(queue[targetIndex]) {
            currentIndex = targetIndex
            onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
        }
    }

    /** Instant, no-fade switch -- only used for first-ever load and recovery
     *  fallbacks, never for a normal in-session transition. */
    private fun hardSwitchTo(index: Int, positionMs: Long = 0L, playWhenReady: Boolean = true) {
        if (index !in queue.indices) return
        fadeRunnable?.let { handler.removeCallbacks(it) }
        isCrossfadingNow = false
        val a = active()
        try {
            a.setMediaItem(queue[index], positionMs)
            a.prepare()
            a.volume = 1f
            a.playWhenReady = playWhenReady
        } catch (_: Exception) {
        }
        currentIndex = index
        onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
        invalidateState()
    }

    /** Restores a saved session (e.g. after a full app/process restart) at an
     *  exact position, paused -- ready to continue the instant Play is tapped,
     *  without an audible play-then-immediately-pause blip. */
    fun restoreQueueAtPosition(items: List<MediaItem>, index: Int, positionMs: Long) {
        if (items.isEmpty()) return
        queue = items
        hardSwitchTo(index.coerceIn(0, items.size - 1), positionMs, playWhenReady = false)
    }

    // --- Public queue API (used by PlaybackService) ---

    fun crossfadeToIndex(targetIndex: Int) {
        if (targetIndex !in queue.indices || targetIndex == currentIndex) return
        beginCrossfadeTo(targetIndex)
    }

    fun setQueue(items: List<MediaItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val clampedStart = startIndex.coerceIn(0, items.size - 1)
        val target = items[clampedStart]
        if (queue.isEmpty()) {
            // Nothing playing yet -- nothing to fade from.
            queue = items
            hardSwitchTo(clampedStart)
            return
        }
        beginCrossfadeToItem(target) {
            queue = items
            currentIndex = clampedStart
            onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
        }
    }

    fun getQueueItems(): List<MediaItem> = queue
    fun getCurrentIndexPublic(): Int = currentIndex

    fun moveItem(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        val newCurrent = when {
            currentIndex == from -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        queue = mutable
        currentIndex = newCurrent
        invalidateState()
    }

    fun removeItem(index: Int) {
        if (index !in queue.indices) return
        val mutable = queue.toMutableList()
        mutable.removeAt(index)
        val wasCurrent = index == currentIndex
        queue = mutable
        when {
            wasCurrent && queue.isNotEmpty() -> hardSwitchTo(index.coerceAtMost(queue.size - 1))
            index < currentIndex -> { currentIndex--; invalidateState() }
            else -> invalidateState()
        }
    }

    fun skipToNext() {
        if (currentIndex + 1 < queue.size) crossfadeToIndex(currentIndex + 1)
    }

    fun skipToPrevious() {
        if (currentIndex - 1 >= 0) crossfadeToIndex(currentIndex - 1)
    }

    fun releaseAll() {
        watcherRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        try { playerA.release() } catch (_: Exception) {}
        try { playerB.release() } catch (_: Exception) {}
    }

    // --- SimpleBasePlayer contract: reports the ACTIVE player's real state ---

    override fun getState(): State {
        val a = active()
        val itemDatas = queue.mapIndexed { index, item ->
            val durationUs = if (index == currentIndex && a.duration > 0) {
                a.duration * 1000
            } else {
                item.mediaMetadata.extras?.getLong("duration_ms")?.takeIf { it > 0 }?.let { it * 1000 } ?: C.TIME_UNSET
            }
            MediaItemData.Builder(index.toString())
                .setMediaItem(item)
                .setDurationUs(durationUs)
                .build()
        }
        val playlist = ImmutableList.copyOf(itemDatas)

        val playbackState = if (queue.isEmpty()) Player.STATE_IDLE else a.playbackState

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_PREPARE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_SET_SPEED_AND_PITCH)
                    .add(Player.COMMAND_SET_SHUFFLE_MODE)
                    .add(Player.COMMAND_SET_REPEAT_MODE)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_SET_MEDIA_ITEM)
                    .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .add(Player.COMMAND_GET_VOLUME)
                    .add(Player.COMMAND_SET_VOLUME)
                    .build()
            )
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)))
            .setContentPositionMs(if (a.duration > 0) a.currentPosition else 0L)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(a.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(a.isLoading)
            .setRepeatMode(a.repeatMode)
            .setShuffleModeEnabled(a.shuffleModeEnabled)
            .setVolume(1f)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        try {
            if (playWhenReady) active().play() else active().pause()
        } catch (_: Exception) {
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        try { active().prepare() } catch (_: Exception) {}
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        try { active().stop() } catch (_: Exception) {}
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != currentIndex && mediaItemIndex in queue.indices) {
            crossfadeToIndex(mediaItemIndex)
        } else {
            try {
                active().seekTo(positionMs)
            } catch (_: Exception) {
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        try { playerA.repeatMode = repeatMode; playerB.repeatMode = repeatMode } catch (_: Exception) {}
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        try {
            playerA.shuffleModeEnabled = shuffleModeEnabled
            playerB.shuffleModeEnabled = shuffleModeEnabled
        } catch (_: Exception) {
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        setQueue(mediaItems, if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        // Our UI only ever drag-reorders one row at a time, which Player's
        // default moveMediaItem(from, to) expresses as a range of exactly 1.
        if (toIndex - fromIndex == 1) {
            moveItem(fromIndex, newIndex)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        // Remove highest-index-first so earlier indices in the range stay valid mid-loop.
        for (i in (toIndex - 1) downTo fromIndex) {
            removeItem(i)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        try {
            playerA.playbackParameters = playbackParameters
            playerB.playbackParameters = playbackParameters
        } catch (_: Exception) {
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
