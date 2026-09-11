package com.henrylumis.mediaprayer.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import com.henrylumis.mediaprayer.util.Prefs

/**
 * A single stable Player identity that MediaSession is bound to for the
 * app's entire lifetime -- but internally, it holds TWO real ExoPlayer
 * instances and silently swaps which one is "live" for a true ping-pong
 * crossfade. The idle player is prepared early; for a beat-synchronised
 * transition it is deliberately seeked while muted to the detected incoming
 * beat phase before it becomes audible.
 *
 * The old zero-seek approach was attractive for gapless playback, but it could
 * never provide true phase alignment because every incoming song always began
 * at time zero. This version accepts one silent pre-roll seek when the analyser
 * has enough confidence, because correct beat phase is the higher priority.
 *
 * Natural end-of-song advances use the automatic beat-synchronised crossfade.
 * Manual navigation (Next/Previous/library/queue selection) deliberately uses
 * a separate fade-out -> switch -> fade-in path, so a user action never causes
 * an overlapping DJ-style transition.
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
        private const val TECHNICAL_FADE_MS = 150L
        private const val MANUAL_FADE_MS = 500L
        private const val WATCHER_INTERVAL_MS = 40L
        private const val SMART_MIN_FADE_MS = 2000L
        private const val SMART_PREPARE_LEAD_MS = 10000L
        private const val BEAT_SYNC_MAX_SPEED_ADJUST = 1.18
        private const val BEAT_SYNC_MIN_SPEED_ADJUST = 0.85
        private const val BEAT_SYNC_MIN_CONFIDENCE = 0.28
        private const val BEAT_SYNC_MIN_TEMPO_STABILITY = 0.90
        private const val BEAT_SYNC_LOOKAHEAD_BEATS = 2
        private const val BEATS_PER_BAR = 4
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
            .setAudioAttributes(audioAttributes, false)
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

                    override fun onMetadata(metadata: Metadata) {
                        val mediaId = this@apply.currentMediaItem?.mediaId ?: return
                        val bpm = extractBpm(metadata) ?: return
                        bpmByMediaId[mediaId] = bpm
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // The watcher normally starts a natural crossfade before
                        // the active track reaches its exact end. STATE_ENDED is
                        // a safety net for files where that narrow timing window
                        // is missed, so playback never just stops at the end.
                        if (playbackState == Player.STATE_ENDED &&
                            this@apply === active() &&
                            !isCrossfadingNow &&
                            !isManualFadingNow
                        ) {
                            if (repeatModeValue == Player.REPEAT_MODE_ONE) {
                                replayCurrentTrack()
                            } else {
                                nextIndexForAdvance()?.let {
                                    beginAdvanceTo(it, pushHistory = true, natural = true)
                                }
                            }
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        // The bridge deliberately disables per-player audio-focus ownership because
                        // two physical players must overlap during automatic fades.
                        // If Media3/Android reports a focus or noisy-output pause,
                        // collapse both players into one coherent paused state. Otherwise the hidden incoming
                        // player could continue a crossfade after the active player
                        // has already been suppressed.
                        if (!playWhenReady &&
                            (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                             reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) &&
                            isCrossfadingNow
                        ) {
                            cancelTransition()
                        }
                    }

                    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                        // A transient focus loss may be represented as playback
                        // suppression instead of a direct playWhenReady change.
                        // Cancelling the transition here prevents two independently
                        // suppressed players from getting out of sync when focus returns.
                        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS &&
                            isCrossfadingNow
                        ) {
                            cancelTransition()
                        }
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
    private var isManualFadingNow = false
    private var manualFadePhase = 0 // 0 = fade out, 1 = fade in

    // --- Shuffle / repeat state ---
    // Deliberately NOT delegated to playerA/playerB's own shuffle/repeat
    // properties -- each of those only ever holds ONE item at a time (no
    // playlist of its own), so those properties are inert here. All actual
    // "what plays next" logic lives in this class instead.
    private var shuffleEnabled = false
    private var repeatModeValue = Player.REPEAT_MODE_OFF
    private val shuffleHistory = ArrayDeque<Int>() // for "previous" while shuffling
    private val recentlyShuffled = ArrayDeque<Int>() // avoids immediate repeats
    private val RECENT_AVOID_COUNT = 3

    // BPM values discovered from embedded audio metadata. Beat matching is the
    // primary transition strategy. Missing/uncertain metadata does NOT mean an
    // immediate fallback: the next track is prebuffered early so its metadata
    // has a chance to arrive before the transition decision is made.
    private val bpmByMediaId = mutableMapOf<String, Double>()
    private val beatGridByMediaId = mutableMapOf<String, BeatGrid>()
    private val beatAnalysisInFlight = mutableSetOf<String>()
    private val beatAnalysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HenryBeatAnalysis").apply { isDaemon = true }
    }

    var onQueueAdvanced: ((MediaItem?) -> Unit)? = null
    /** Fired when a real transition starts. natural=true means the outgoing track
     * reached its configured end window; false means user/system navigation. */
    var onTransitionStarted: ((MediaItem, MediaItem, Boolean) -> Unit)? = null
    /** Called as soon as a transition target is known, before the incoming player starts. */
    var onTransitionTarget: ((MediaItem) -> Unit)? = null

    // Per-track attenuation used by ReplayGain. Kept separate from fade volume so
    // crossfade math never destroys normalization. Positive ReplayGain boosts are
    // handled by LoudnessEnhancer; negative gains are safely applied here.
    private val trackAttenuation = mutableMapOf<String, Float>()
    private var userVolume = 1f
    private var playbackParameters = PlaybackParameters(Prefs.getPlaybackSpeed(context))
    private fun baseVolume(item: MediaItem?): Float = userVolume * attenuationFor(item)
    private fun attenuationFor(item: MediaItem?): Float =
        item?.mediaId?.let { trackAttenuation[it] } ?: 1f

    fun setTrackAttenuation(mediaId: String, multiplier: Float) {
        if (mediaId.isBlank()) return
        val value = multiplier.coerceIn(0.05f, 1f)
        trackAttenuation[mediaId] = value
        if (active().currentMediaItem?.mediaId == mediaId && !isCrossfadingNow) {
            try { active().volume = userVolume * value } catch (_: Exception) {}
        }
        if (isCrossfadingNow && idle().currentMediaItem?.mediaId == mediaId) {
            // If metadata arrived while the fade is already running, reduce the
            // incoming player immediately. The next fade tick will continue from
            // the correct normalized ceiling.
            try { idle().volume = minOf(idle().volume, userVolume * value) } catch (_: Exception) {}
        }
    }

    /** Called after user-visible playback state changes so the service can
     * persist the session immediately instead of waiting for its periodic
     * checkpoint. */
    var onPlaybackStateChanged: (() -> Unit)? = null

    /** The track a fade is currently blending INTO, before the real switch has
     *  landed -- lets the UI show the new track instantly on tap, while the
     *  audio itself is still fading in underneath. Null when nothing's pending. */
    var pendingTarget: MediaItem? = null
        private set

    private val handler = Handler(looper)
    private var fadeRunnable: Runnable? = null
    /** Monotonically changes whenever a fade is cancelled/replaced. Any old
     *  runnable that wakes up after cancellation becomes a no-op. */
    private var transitionGeneration = 0L
    private var watcherRunnable: Runnable? = null
    private var speedRampRunnable: Runnable? = null
    /** Current normalized fade position, retained so master-volume changes during
     * a transition can be applied immediately instead of waiting for the next tick. */
    private var fadeProgress = 0f
    /** Wall-clock time at which the current transition actually began. */
    private var transitionStartedAtMs = 0L

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

    /**
     * Returns the requested fade length, but never lets a crossfade consume
     * most/all of a very short track. Starting a 4s fade on a 3s song would
     * otherwise trigger almost immediately and make the track sound as if it
     * never really started. Capping at half the outgoing duration keeps the
     * transition musical while still honoring the user's setting for normal
     * tracks.
     */
    private fun extractBpm(metadata: Metadata): Double? {
        for (index in 0 until metadata.length()) {
            val entry = metadata[index]
            val raw = when (entry) {
                is TextInformationFrame -> if (entry.id.equals("TBPM", ignoreCase = true)) entry.values.firstOrNull() else null
                is VorbisComment -> if (entry.key.equals("BPM", ignoreCase = true) ||
                    entry.key.equals("TBPM", ignoreCase = true)) entry.value else null
                else -> null
            } ?: continue
            val bpm = raw.trim().replace(',', '.').toDoubleOrNull()
            if (bpm != null && bpm in 40.0..240.0) return bpm
        }
        return null
    }

    private fun bpmFor(item: MediaItem?): Double? =
        item?.mediaId?.let { beatGridByMediaId[it]?.bpm ?: bpmByMediaId[it] }

    private fun beatGridFor(item: MediaItem?): BeatGrid? =
        item?.mediaId?.let { beatGridByMediaId[it] }

    /** Starts real PCM beat analysis off the main thread. Metadata BPM is kept
     * only as a discovery hint; it is never treated as proof of beat phase. */
    private fun scheduleBeatAnalysis(item: MediaItem?) {
        val mediaId = item?.mediaId ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (beatGridByMediaId.containsKey(mediaId) || !beatAnalysisInFlight.add(mediaId)) return
        beatAnalysisExecutor.execute {
            val grid = BeatGridAnalyzer.analyze(context, uri)
            handler.post {
                beatAnalysisInFlight.remove(mediaId)
                if (grid != null) {
                    beatGridByMediaId[mediaId] = grid
                    bpmByMediaId[mediaId] = grid.bpm
                }
            }
        }
    }

    private data class BeatSyncPlan(
        /** When the incoming player must begin its muted pre-roll. */
        val outgoingStartMs: Long,
        /** The outgoing beat at which the audible crossfade itself begins. */
        val fadeStartMs: Long,
        /** Media position to seek the incoming player to before muted pre-roll. */
        val incomingStartMs: Long,
        val incomingSpeed: Float,
        /** Wall-clock pre-roll needed before the selected incoming beat lands. */
        val preRollMs: Long
    )

    /**
     * Builds a real beat/phase-locked transition plan from decoded audio.
     * The outgoing beat nearest the preferred fade point is selected, then the
     * incoming track is seeked to its detected first beat so both tracks start
     * the overlap on the same beat phase. Tempo is corrected only within a
     * conservative range.
     */
    private fun beatSyncPlan(
        outgoing: MediaItem?,
        incoming: MediaItem?,
        outgoingPositionMs: Long,
        durationMs: Long,
        fadeMs: Long
    ): BeatSyncPlan? {
        val out = beatGridFor(outgoing) ?: return null
        val inc = beatGridFor(incoming) ?: return null
        if (out.confidenceAtEnd < BEAT_SYNC_MIN_CONFIDENCE || inc.confidenceAtStart < BEAT_SYNC_MIN_CONFIDENCE) return null
        // Beat sync is only allowed when the analyzed portions are tempo-stable.
        // Otherwise a single BPM estimate can look compatible while the actual
        // beat spacing drifts during the overlap. In that case Smart Crossfade
        // is safer and more musical.
        if (out.tempoStability < BEAT_SYNC_MIN_TEMPO_STABILITY ||
            inc.tempoStability < BEAT_SYNC_MIN_TEMPO_STABILITY) return null
        val outgoingBpm = out.bpmAtEnd
        val incomingBpm = inc.bpmAtStart
        val speedAdjust = outgoingBpm / incomingBpm
        if (speedAdjust !in BEAT_SYNC_MIN_SPEED_ADJUST..BEAT_SYNC_MAX_SPEED_ADJUST) return null
        val preferred = (durationMs - fadeMs).coerceAtLeast(outgoingPositionMs).toDouble()
        // Find the nearest outgoing beat without scanning the complete grid on
        // every 40 ms watcher tick. A linear scan here becomes surprisingly
        // expensive on long tracks and can make the UI thread do unnecessary
        // work precisely when playback timing needs to stay smooth.
        val beats = out.beatsMs
        if (beats.isEmpty()) return null
        val nearestIndex = nearestIndexAtOrAfter(beats, preferred)
        val beatPeriod = 60_000.0 / outgoingBpm

        // Prefer a nearby four-beat phrase boundary. Because the analyzer may
        // provide a partial grid at the beginning/end of a track, the boundary
        // is chosen relative to the detected grid index rather than assuming
        // that the file's first audio sample is beat zero.
        val candidateIndices = listOf(
            nearestIndex - 4, nearestIndex, nearestIndex + 4,
            nearestIndex - 8, nearestIndex + 8
        ).filter { it in beats.indices && beats[it] in outgoingPositionMs..durationMs }
        val barIndex = candidateIndices.minByOrNull { abs(beats[it] - preferred) }
        val nearestBeatIndex = listOf(nearestIndex - 1, nearestIndex, nearestIndex + 1)
            .filter { it in beats.indices && beats[it] in outgoingPositionMs..durationMs }
            .minByOrNull { abs(beats[it] - preferred) }
            ?: return null

        val exitIndex = if (barIndex != null && abs(beats[barIndex] - preferred) <= 0.55 * beatPeriod) {
            barIndex
        } else {
            if (abs(beats[nearestBeatIndex] - preferred) > 0.70 * beatPeriod) return null
            nearestBeatIndex
        }
        val exit = beats[exitIndex]

        // Start on a stable incoming beat. Deliberately avoid the first few
        // hundred milliseconds, where codec priming/silence can produce a false
        // onset. Prefer a beat that is followed by at least two more detected
        // beats; this rejects isolated transient peaks masquerading as the first
        // beat and gives the overlap a stable rhythmic runway.
        val incomingCandidates = inc.beatsMs
            .withIndex()
            .filter { (index, beat) ->
                beat >= 250L && index + BEAT_SYNC_LOOKAHEAD_BEATS < inc.beatsMs.size
            }
            .map { it.index to it.value }
        // Prefer the start of a four-beat phrase rather than blindly entering
        // on the very first transient. This makes the incoming song feel like
        // it is being introduced musically instead of simply being layered on
        // top of the outgoing track. If no convincing phrase boundary exists,
        // fall back to the earliest stable beat.
        val incomingPhraseBeat = incomingCandidates
            .filter { (index, _) -> index % BEATS_PER_BAR == 0 }
            .map { it.second }
            .firstOrNull()
        val incomingBeat = incomingPhraseBeat
            ?: incomingCandidates.firstOrNull()?.second
            ?: inc.firstBeatMs
        val incomingSpeed = (playbackParameters.speed * speedAdjust.toFloat()).coerceIn(0.1f, 8f)

        // Do the important part the old implementation could not do: start the
        // incoming player EARLY and keep it muted until its selected beat lands
        // on the exact outgoing beat. Merely seeking to an incoming beat at the
        // moment the fade begins is not phase synchronization.
        val speed = incomingSpeed.coerceAtLeast(0.1f)
        val preRollMs = (incomingBeat.toDouble() / speed).toLong().coerceAtLeast(1L)
        val launch = (exit - preRollMs).coerceAtLeast(outgoingPositionMs)
        val actualPreRoll = (exit - launch).coerceAtLeast(0L)
        val incomingPosition = (incomingBeat - actualPreRoll * speed)
            .toLong()
            .coerceAtLeast(0L)

        // If the requested phase cannot be reached before the selected exit
        // beat because playback is already too close, this plan is unsafe.
        // Smart Crossfade gets the transition instead of producing a fake sync.
        if (incomingPosition > incomingBeat || actualPreRoll < 80L) return null

        return BeatSyncPlan(
            outgoingStartMs = launch,
            fadeStartMs = exit,
            incomingStartMs = incomingPosition,
            incomingSpeed = incomingSpeed,
            preRollMs = actualPreRoll
        )
    }

    private fun nearestIndexAtOrAfter(values: LongArray, target: Double): Int {
        var low = 0
        var high = values.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid].toDouble() < target) low = mid + 1 else high = mid
        }
        if (low == 0) return 0
        val before = low - 1
        return if (abs(values[low] - target) < abs(values[before] - target)) low else before
    }

    /**
     * Smart fallback. It deliberately reserves a healthy transition window
     * rather than waiting until the last moment. If beat matching cannot be
     * established, this path is already ready to take over with a normal
     * equal-power fade.
     */
    private fun smartTransitionStartMs(durationMs: Long, fadeMs: Long): Long {
        // Smart fallback needs lead time, but the audible fade itself remains the
        // user's configured length. Starting the fade too early would make the
        // track feel like a permanent blend; the lead window is only preparation
        // time for the already-buffered incoming player.
        val lead = maxOf(fadeMs, SMART_MIN_FADE_MS)
        return (durationMs - lead).coerceAtLeast(0L)
    }

    private fun effectiveFadeDuration(outgoingDurationMs: Long = active().duration): Long {
        val requested = if (Prefs.isCrossfadeEnabled(context)) {
            Prefs.getCrossfadeSeconds(context) * 1000L
        } else {
            TECHNICAL_FADE_MS
        }
        if (outgoingDurationMs <= 0L) return requested
        return requested.coerceAtMost((outgoingDurationMs / 2L).coerceAtLeast(1L))
    }

    // --- Watcher: catches the natural end of the active track ---
    // Polls at WATCHER_INTERVAL_MS (tight -- 50ms) rather than a lazier
    // interval: the default no-crossfade blend is only ~150ms long, so a
    // coarser poll could trigger the swap AFTER the outgoing track already
    // hit its natural end, causing a brief native stop mid-fade -- exactly
    // the kind of "glitchy" transition this needs to avoid.

    private fun startWatcher() {
        watcherRunnable = object : Runnable {
            override fun run() {
                if (!isCrossfadingNow) {
                    val a = active()
                    val fadeMs = effectiveFadeDuration(a.duration)
                    if (a.isPlaying && a.duration > 0 && a.currentPosition > 0) {
                        val nextIndex = if (repeatModeValue == Player.REPEAT_MODE_ONE) currentIndex else nextIndexForAdvance()
                        val nextItem = nextIndex?.let { queue.getOrNull(it) }
                        // True beat sync uses decoded PCM beat grids. If both
                        // tracks have a confident compatible grid, the watcher
                        // targets an actual outgoing beat. Otherwise Smart
                        // Crossfade owns the transition.
                        val beatPlan = beatSyncPlan(
                            a.currentMediaItem,
                            nextItem,
                            a.currentPosition,
                            a.duration,
                            fadeMs
                        )
                        val smartStart = smartTransitionStartMs(a.duration, fadeMs)
                        // Prepare the actual next item well before the decision point.
                        // This is deliberately earlier than the fade itself so decoder
                        // startup/metadata latency cannot force a last-second fallback.
                        if (a.duration - a.currentPosition <= SMART_PREPARE_LEAD_MS) {
                            prebufferSpecificIndex(nextIndex)
                        }
                        val shouldStart = if (beatPlan != null) {
                            a.currentPosition >= beatPlan.outgoingStartMs - 35L
                        } else {
                            // Do NOT wait until the last instant. Smart fallback
                            // gets a longer runway so analysis/decoding failure
                            // cannot turn into a late abrupt transition.
                            a.currentPosition >= smartStart
                        }
                        if (shouldStart) {
                            if (repeatModeValue == Player.REPEAT_MODE_ONE) {
                                replayCurrentTrack(beatPlan)
                            } else {
                                nextIndexForAdvance()?.let { beginAdvanceTo(it, pushHistory = true, natural = true, beatPlan = beatPlan) }
                            }
                        }
                    }
                }
                handler.postDelayed(this, WATCHER_INTERVAL_MS)
            }
        }
        handler.postDelayed(watcherRunnable!!, WATCHER_INTERVAL_MS)
    }

    // --- Shuffle / repeat aware navigation ---

    /** Picks the next queue index respecting shuffle + repeat-all, or null if
     *  playback should simply stop (end of queue, repeat off). */
    private fun nextIndexForAdvance(): Int? {
        if (queue.isEmpty()) return null
        return if (shuffleEnabled) {
            pickRandomNextIndex()
        } else if (currentIndex + 1 < queue.size) {
            currentIndex + 1
        } else if (repeatModeValue == Player.REPEAT_MODE_ALL) {
            0
        } else {
            null
        }
    }

    private fun previousIndexForNav(): Int? {
        if (queue.isEmpty()) return null
        return if (shuffleEnabled) {
            shuffleHistory.removeLastOrNull()
        } else if (currentIndex - 1 >= 0) {
            currentIndex - 1
        } else if (repeatModeValue == Player.REPEAT_MODE_ALL) {
            queue.size - 1
        } else {
            null
        }
    }

    private fun pickRandomNextIndex(): Int? {
        if (queue.isEmpty()) return null
        if (queue.size == 1) return if (repeatModeValue != Player.REPEAT_MODE_OFF) currentIndex else null
        val avoid = (recentlyShuffled.toList() + currentIndex).toSet()
        val candidates = queue.indices.filter { it !in avoid }
        val pool = candidates.ifEmpty { queue.indices.filter { it != currentIndex } }
        return pool.randomOrNull()
    }

    private fun replayCurrentTrack(beatPlan: BeatSyncPlan? = null) {
        if (currentIndex !in queue.indices) return
        val outgoing = queue[currentIndex]
        onTransitionStarted?.invoke(outgoing, outgoing, true)
        beginCrossfadeToItem(queue[currentIndex], beatPlan) {
            onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
        }
    }

    private fun beginAdvanceTo(targetIndex: Int, pushHistory: Boolean, natural: Boolean = false, beatPlan: BeatSyncPlan? = null) {
        if (targetIndex !in queue.indices) return
        if (pushHistory) {
            shuffleHistory.addLast(currentIndex)
            if (shuffleHistory.size > 50) shuffleHistory.removeFirst()
            recentlyShuffled.addLast(targetIndex)
            if (recentlyShuffled.size > RECENT_AVOID_COUNT) recentlyShuffled.removeFirst()
        }
        val outgoing = queue.getOrNull(currentIndex)
        val incoming = queue[targetIndex]
        if (outgoing != null && outgoing.mediaId != incoming.mediaId) {
            onTransitionStarted?.invoke(outgoing, incoming, natural)
        }

        // Crossfade OFF means a true immediate navigation. Do not route a
        // manual Next/Previous through even the tiny technical fade: the
        // MediaSession command should visibly and audibly land on the next
        // track from a single press.
        // Crossfade is strictly for normal end-of-song transitions.
        // Manual navigation (Next/Previous) must behave like Crossfade OFF,
        // even when the setting is enabled.
        if (!natural) {
            beginManualFadeTo(targetIndex)
            return
        }
        if (!Prefs.isCrossfadeEnabled(context)) {
            hardSwitchTo(targetIndex, playWhenReady = true)
            return
        }

        beginCrossfadeToItem(incoming, beatPlan) {
            val committedIndex = queue.indexOfFirst { it.mediaId == incoming.mediaId }
            if (committedIndex >= 0) {
                currentIndex = committedIndex
                onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
            } else {
                // The target disappeared while the transition was running.
                // Do not manufacture an invalid queue index. Recover on the
                // nearest valid current item instead.
                currentIndex = currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
            }
        }
    }

    // --- Core swap mechanism ---

    /** Manual navigation transition: no overlap. The current track fades out,
     * the selected track is then loaded at zero volume, and it fades in. This
     * is intentionally separate from the automatic beat-matched crossfade so
     * tapping Library/Queue/Next/Previous never creates an unintended overlap. */
    private fun beginManualFadeTo(targetIndex: Int) {
        if (targetIndex !in queue.indices) return
        val outgoing = active()
        val targetItem = queue[targetIndex]
        if (outgoing.currentMediaItem?.mediaId == targetItem.mediaId) return

        cancelTransition()
        transitionGeneration++
        val generation = transitionGeneration
        val duration = MANUAL_FADE_MS
        val startedAt = android.os.SystemClock.elapsedRealtime()
        manualFadePhase = 0
        isManualFadingNow = true
        fadeProgress = 0f
        pendingTarget = targetItem
        onTransitionStarted?.invoke(outgoing.currentMediaItem ?: targetItem, targetItem, false)
        onTransitionTarget?.invoke(targetItem)

        fadeRunnable = object : Runnable {
            override fun run() {
                if (generation != transitionGeneration || !isManualFadingNow) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                fadeProgress = t
                try {
                    if (manualFadePhase == 0) {
                        val out = kotlin.math.cos(t * (Math.PI / 2.0)).toFloat()
                        outgoing.volume = out * baseVolume(outgoing.currentMediaItem)

                        if (t >= 1f) {
                            // Switch only after the old track is silent. Keep the
                            // same physical player active and fade the new track in
                            // from zero, so there is never a second audible stream.
                            outgoing.setMediaItem(targetItem)
                            outgoing.prepare()
                            try { outgoing.playbackParameters = playbackParameters } catch (_: Exception) {}
                            outgoing.volume = 0f
                            outgoing.playWhenReady = true
                            currentIndex = targetIndex
                            manualFadePhase = 1
                            fadeProgress = 0f
                            val fadeInStartedAt = android.os.SystemClock.elapsedRealtime()
                            fadeRunnable = object : Runnable {
                                override fun run() {
                                    if (generation != transitionGeneration || !isManualFadingNow) return
                                    val inElapsed = android.os.SystemClock.elapsedRealtime() - fadeInStartedAt
                                    val inT = (inElapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                    fadeProgress = inT
                                    val fadeIn = kotlin.math.sin(inT * (Math.PI / 2.0)).toFloat()
                                    try { outgoing.volume = fadeIn * baseVolume(outgoing.currentMediaItem) } catch (_: Exception) {}
                                    if (inT < 1f) {
                                        handler.postDelayed(this, 16L)
                                    } else {
                                        try { outgoing.volume = baseVolume(outgoing.currentMediaItem) } catch (_: Exception) {}
                                        isManualFadingNow = false
                                        fadeProgress = 0f
                                        pendingTarget = null
                                        fadeRunnable = null
                                        onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
                                        invalidateState()
                                        prebufferNextIfPossible()
                                        onPlaybackStateChanged?.invoke()
                                    }
                                }
                            }
                            handler.post(fadeRunnable!!)
                            return
                        }
                    }
                } catch (_: Exception) {
                    // If loading the selected item fails, recover to a normal
                    // full-volume state rather than leaving playback silent.
                    isManualFadingNow = false
                    pendingTarget = null
                    fadeRunnable = null
                    try { outgoing.volume = baseVolume(outgoing.currentMediaItem) } catch (_: Exception) {}
                    return
                }
                handler.postDelayed(this, 16L)
            }
        }
        handler.post(fadeRunnable!!)
    }

    private fun rampActiveSpeedToUser(generation: Long) {
        speedRampRunnable?.let { handler.removeCallbacks(it) }
        val player = active()
        val from = player.playbackParameters.speed
        val to = playbackParameters.speed
        if (abs(from - to) < 0.001f) {
            try { player.playbackParameters = playbackParameters } catch (_: Exception) {}
            return
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val rampMs = 600L
        speedRampRunnable = object : Runnable {
            override fun run() {
                if (generation != transitionGeneration) return
                val t = ((android.os.SystemClock.elapsedRealtime() - startedAt).toFloat() / rampMs)
                    .coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                val speed = from + (to - from) * eased
                try { player.playbackParameters = PlaybackParameters(speed, 1f) } catch (_: Exception) {}
                if (t < 1f) {
                    handler.postDelayed(this, 16L)
                } else {
                    try { player.playbackParameters = playbackParameters } catch (_: Exception) {}
                    speedRampRunnable = null
                }
            }
        }
        handler.post(speedRampRunnable!!)
    }

    private fun beginCrossfadeToItem(targetItem: MediaItem, plannedBeatSync: BeatSyncPlan? = null, onSwapComplete: () -> Unit) {
        // A transition owns both players until it completes. If another
        // transition replaces it, restore the old active player to full
        // volume and completely silence/stop the incoming player first.
        // Without this reset, rapid Next/Previous taps can leave the old
        // fade's volume curve applied to the new outgoing player, causing
        // volume dips or two players to remain audible at once.
        transitionGeneration++
        val generation = transitionGeneration
        fadeRunnable?.let { handler.removeCallbacks(it) }
        speedRampRunnable?.let { handler.removeCallbacks(it) }
        speedRampRunnable = null
        if (isCrossfadingNow) {
            val oldOutgoing = active()
            val oldIncoming = idle()
            try { oldIncoming.stop(); oldIncoming.volume = 0f } catch (_: Exception) {}
            try { oldOutgoing.volume = baseVolume(oldOutgoing.currentMediaItem) } catch (_: Exception) {}
            isCrossfadingNow = false
        }
        isCrossfadingNow = true
        fadeProgress = 0f
        pendingTarget = targetItem
        val outgoing = active()
        val incoming = idle()
        // Resolve the plan once, before touching the incoming player. Keeping
        // this outside the setup try-block also makes the scheduler below use
        // exactly the same plan that was used to seek/start the incoming track.
        val plan = plannedBeatSync ?: beatSyncPlan(
            outgoing.currentMediaItem,
            targetItem,
            outgoing.currentPosition,
            outgoing.duration,
            effectiveFadeDuration(outgoing.duration)
        )
        try {
            // If this exact track was already pre-buffered on the idle player
            // (see prebufferNextIfPossible), skip re-loading it -- that cold
            // start (however brief) inside the fade window was the likely
            // source of the "glitchy" feeling on manual skips especially.
            val alreadyBuffered = incoming.currentMediaItem?.mediaId == targetItem.mediaId &&
                incoming.playbackState != Player.STATE_IDLE
            if (!alreadyBuffered) {
                incoming.setMediaItem(targetItem)
                incoming.prepare()
            }
            incoming.volume = 0f
            // Beat/tempo lock: during the overlap the incoming track is
            // temporarily played at the speed required to follow the outgoing
            // BPM. This is intentionally limited to a safe ratio; incompatible
            // material is routed through Smart Crossfade instead.
            if (plan != null) {
                // True phase lock: seek to the position that makes the selected
                // incoming beat arrive at the outgoing beat. The player runs
                // silently during this pre-roll; the audible fade does not begin
                // until both beat grids meet.
                try { incoming.playbackParameters = PlaybackParameters(plan.incomingSpeed, 1f) } catch (_: Exception) {}
                try { incoming.seekTo(plan.incomingStartMs) } catch (_: Exception) {}
            } else {
                try { incoming.playbackParameters = playbackParameters } catch (_: Exception) {}
            }
            onTransitionTarget?.invoke(targetItem)
            incoming.volume = 0f
            incoming.play()
        } catch (e: Exception) {
            isCrossfadingNow = false
            pendingTarget = null
            onSwapComplete()
            return
        }

        // The beat-synchronised path has a muted pre-roll. The actual fade
        // duration must be measured from the moment the fade opens, NOT from
        // the moment the muted incoming player was launched. Otherwise the
        // pre-roll would consume part of the fade and could make the audible
        // overlap collapse almost instantly.
        val steps = 24
        var fadeStartedAt = 0L
        var duration = effectiveFadeDuration(outgoing.duration)
        transitionStartedAtMs = 0L
        fadeRunnable = object : Runnable {
            override fun run() {
                // A cancelled transition may still be present in the Handler
                // queue on some Android timing paths. Never let an obsolete
                // runnable touch either player's volume or perform a swap.
                if (generation != transitionGeneration || !isCrossfadingNow) return

                if (fadeStartedAt == 0L) {
                    fadeStartedAt = android.os.SystemClock.elapsedRealtime()
                    transitionStartedAtMs = fadeStartedAt
                    val remainingMs = if (outgoing.duration > 0L) {
                        (outgoing.duration - outgoing.currentPosition).coerceAtLeast(1L)
                    } else {
                        effectiveFadeDuration(outgoing.duration)
                    }
                    duration = effectiveFadeDuration(outgoing.duration).coerceAtMost(remainingMs)
                }

                // Fade duration is expressed in track-time milliseconds. Convert
                // it to wall-clock time using the CURRENT playback speed. This is
                // important at 1.5x/2x: otherwise the outgoing song can finish
                // before the fade has completed. Re-reading speed on every tick
                // also keeps a mid-fade speed change coherent.
                if (outgoing.playbackState == Player.STATE_ENDED ||
                    (outgoing.duration > 0L && outgoing.currentPosition >= outgoing.duration)) {
                    try {
                        outgoing.volume = 0f
                        incoming.volume = baseVolume(incoming.currentMediaItem)
                        incoming.play()
                    } catch (_: Exception) {}
                    activeIsA = !activeIsA
                    rampActiveSpeedToUser(generation)
                    try { outgoing.stop(); outgoing.volume = baseVolume(outgoing.currentMediaItem) } catch (_: Exception) {}
                    isCrossfadingNow = false
                    fadeProgress = 0f
                    pendingTarget = null
                    onSwapComplete()
                    invalidateState()
                    prebufferNextIfPossible()
                    return
                }

                val speed = playbackParameters.speed.coerceIn(0.1f, 8f)
                val wallDuration = (duration.toDouble() / speed).toLong().coerceAtLeast(1L)
                val elapsed = android.os.SystemClock.elapsedRealtime() - fadeStartedAt
                val t = (elapsed.toDouble() / wallDuration.toDouble()).toFloat().coerceIn(0f, 1f)
                fadeProgress = t
                // Equal-power curves avoid the perceived volume hole that a
                // simple linear 1-t / t crossfade can create in the middle.
                val fadeOut = kotlin.math.cos(t * (Math.PI / 2.0)).toFloat() * baseVolume(outgoing.currentMediaItem)
                val fadeIn = kotlin.math.sin(t * (Math.PI / 2.0)).toFloat() * baseVolume(incoming.currentMediaItem)
                try {
                    outgoing.volume = fadeOut
                    incoming.volume = fadeIn
                } catch (_: Exception) {
                }
                if (t < 1f) {
                    handler.postDelayed(this, (wallDuration / steps).coerceAtLeast(10L))
                } else {
                    // The swap: incoming has been playing continuously and
                    // correctly this whole time, so becoming "active" is just
                    // a label change -- nothing to seek, nothing to catch up.
                    activeIsA = !activeIsA
                    rampActiveSpeedToUser(generation)
                    try {
                        outgoing.pause()
                        outgoing.volume = baseVolume(outgoing.currentMediaItem)
                    } catch (_: Exception) {
                    }
                    isCrossfadingNow = false
                    fadeProgress = 0f
                    pendingTarget = null
                    onSwapComplete()
                    invalidateState()
                    prebufferNextIfPossible()
                }
            }
        }
        // With a beat plan the incoming player is already running silently.
        // Do NOT use a fixed wall-clock delay here: the watcher can fire a few
        // tens of milliseconds late, and the user can also change playback
        // speed between planning and launch. Instead, keep the runnable muted
        // and schedule from the outgoing player's actual media position until
        // the selected beat is reached. This is the final phase-lock guard.
        if (plan != null) {
            val targetBeat = plan.fadeStartMs
            val waitForBeat = object : Runnable {
                override fun run() {
                    if (generation != transitionGeneration || !isCrossfadingNow) return
                    val current = outgoing.currentPosition
                    val remaining = targetBeat - current
                    if (remaining <= 0L) {
                        handler.post(fadeRunnable!!)
                        return
                    }
                    val speed = outgoing.playbackParameters.speed.coerceIn(0.1f, 8f)
                    // Poll more tightly in the final 100 ms so the audible fade
                    // opens close to the actual outgoing beat instead of the
                    // old coarse watcher cadence.
                    val delay = if (remaining <= 100L) 8L
                    else ((remaining.toDouble() / speed).toLong() / 2L).coerceIn(8L, 40L)
                    handler.postDelayed(this, delay)
                }
            }
            handler.post(waitForBeat)
        } else {
            handler.post(fadeRunnable!!)
        }
    }

    /** Silently loads (paused, volume 0) whatever's next in the queue onto the
     *  currently-idle player, so the NEXT skip/auto-advance can start playing
     *  it instantly instead of cold-starting the file load inside the fade
     *  window -- this is what makes back-to-back "Next" taps feel instant. */
    private fun prebufferSpecificIndex(nextIndex: Int?) {
        if (nextIndex == null || nextIndex !in queue.indices || isCrossfadingNow) return
        try {
            val incoming = idle()
            try { incoming.playbackParameters = playbackParameters } catch (_: Exception) {}
            val nextItem = queue[nextIndex]
            if (incoming.currentMediaItem?.mediaId != nextItem.mediaId ||
                incoming.playbackState == Player.STATE_IDLE) {
                incoming.playWhenReady = false
                incoming.setMediaItem(nextItem)
                incoming.prepare()
            }
            incoming.volume = 0f
            incoming.playWhenReady = false
            scheduleBeatAnalysis(nextItem)
        } catch (_: Exception) {
        }
    }

    private fun prebufferNextIfPossible() {
        // Prebuffer the ACTUAL next item, including shuffle/repeat logic. This
        // is important because beat matching depends on knowing the incoming
        // track's BPM before the fade decision is made.
        val nextIndex = if (repeatModeValue == Player.REPEAT_MODE_ONE) {
            currentIndex
        } else {
            nextIndexForAdvance()
        }
        prebufferSpecificIndex(nextIndex)
    }

    private fun beginCrossfadeTo(targetIndex: Int) {
        // Kept for compatibility with older callers, but an explicit navigation
        // request must never create an overlapping DJ transition. Manual fades
        // are the only path for user-directed changes.
        if (targetIndex !in queue.indices || targetIndex == currentIndex) return
        beginManualFadeTo(targetIndex)
    }

    /** Instant navigation switch used for initial loads, recovery, and all
     *  manual navigation. Natural end-of-song transitions use the crossfade
     *  path when the setting is enabled. */
    private fun hardSwitchTo(index: Int, positionMs: Long = 0L, playWhenReady: Boolean = true) {
        if (index !in queue.indices) return
        transitionGeneration++
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        isCrossfadingNow = false
        isManualFadingNow = false
        manualFadePhase = 0
        fadeProgress = 0f
        pendingTarget = null
        // Hard switches are recovery/initial-load operations. Ensure no
        // previous incoming player can keep producing audio after the switch.
        val a = active()
        val b = idle()
        try { b.stop(); b.volume = 0f } catch (_: Exception) {}
        try { a.volume = baseVolume(a.currentMediaItem) } catch (_: Exception) {}
        try {
            a.setMediaItem(queue[index], positionMs)
            a.prepare()
            a.playbackParameters = playbackParameters
            a.volume = baseVolume(queue[index])
            a.playWhenReady = playWhenReady
        } catch (_: Exception) {
        }
        currentIndex = index
        onQueueAdvanced?.invoke(queue.getOrNull(currentIndex))
        // Analyze both sides as early as possible. The current track's tail grid
        // and the next track's start grid must exist before the natural transition
        // point; waiting until the last seconds defeats beat matching.
        scheduleBeatAnalysis(queue.getOrNull(currentIndex))
        // Analyze the next track immediately as well. Beat analysis can be CPU
        // and decoder intensive, so starting it only 10 seconds before the end
        // is still too late on slower devices. This gives the analyzer the full
        // lifetime of the current song to finish before the transition point.
        val nextIndex = if (repeatModeValue == Player.REPEAT_MODE_ONE) currentIndex else nextIndexForAdvance()
        scheduleBeatAnalysis(nextIndex?.let { queue.getOrNull(it) })
        invalidateState()
        prebufferNextIfPossible()
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
        val oldItem = active().currentMediaItem
        val wasPlaying = active().isPlaying
        queue = items

        // Library selection replaces the queue with the displayed library list,
        // but the selected song is still a manual navigation action. If audio is
        // already playing, fade the current song out and the selected library
        // song in. Initial loads/restores still use the immediate path.
        if (oldItem != null && oldItem.mediaId != items[clampedStart].mediaId && wasPlaying) {
            beginManualFadeTo(clampedStart)
        } else {
            hardSwitchTo(clampedStart, playWhenReady = true)
        }
    }

    /** Restores user playback-mode preferences after the service is recreated.
     *  Kept separate from setQueue so restoring a session cannot accidentally
     *  reset shuffle/repeat to defaults. */
    fun restorePlaybackModes(shuffleEnabled: Boolean, repeatMode: Int) {
        this.shuffleEnabled = shuffleEnabled
        this.repeatModeValue = when (repeatMode) {
            Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> repeatMode
            else -> Player.REPEAT_MODE_OFF
        }
        shuffleHistory.clear()
        recentlyShuffled.clear()
        invalidateState()
    }

    fun getQueueItems(): List<MediaItem> = queue
    fun getCurrentIndexPublic(): Int = currentIndex

    fun moveItem(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        // Queue mutation during an overlap can invalidate the pending target
        // index. Finish the current audible state first, then mutate the queue.
        if (isCrossfadingNow || pendingTarget != null) cancelTransition()
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
        // Never let a transition complete against a queue whose indices changed
        // underneath it. Cancelling here is inaudible because the incoming side
        // is stopped at zero and the outgoing side is restored before mutation.
        if (isCrossfadingNow || pendingTarget != null) cancelTransition()
        val wasCurrent = index == currentIndex
        val wasPlaying = active().isPlaying
        val mutable = queue.toMutableList()
        mutable.removeAt(index)
        queue = mutable

        when {
            queue.isEmpty() -> {
                transitionGeneration++
                fadeRunnable?.let { handler.removeCallbacks(it) }
                fadeRunnable = null
                isCrossfadingNow = false
                fadeProgress = 0f
                pendingTarget = null
                try { active().stop(); active().volume = 0f } catch (_: Exception) {}
                try { idle().stop(); idle().volume = 0f } catch (_: Exception) {}
                currentIndex = 0
                invalidateState()
            }
            wasCurrent -> {
                // Removing the playing item must not unexpectedly start audio
                // when the queue had been paused. Preserve the user's playback
                // intent while replacing the current item.
                val replacement = index.coerceAtMost(queue.size - 1)
                hardSwitchTo(replacement, playWhenReady = wasPlaying)
            }
            index < currentIndex -> {
                currentIndex--
                invalidateState()
            }
            else -> invalidateState()
        }
        onPlaybackStateChanged?.invoke()
    }

    fun skipToNext() {
        // A manual Next is navigation, not a request to replay the current
        // item. Repeat-one only applies to a natural end-of-track advance.
        // Do not wrap from the end to index 0 unless Repeat-all is enabled;
        // silently wrapping here makes the notification/Bluetooth Next button
        // behave differently from the queue's actual end-of-list semantics.
        val next = nextIndexForAdvance()
        if (next != null && next != currentIndex) {
            beginAdvanceTo(next, pushHistory = true)
        }
    }

    fun skipToPrevious() {
        val prev = previousIndexForNav()
        if (prev != null) beginAdvanceTo(prev, pushHistory = false)
    }

    /** Explicit queue-row selection. Manual navigation uses a short
     * fade-out/fade-in rather than the automatic overlapping crossfade. */
    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        if (index == currentIndex) {
            try { active().seekTo(0L); active().playWhenReady = true } catch (_: Exception) {}
            return
        }
        if (active().currentMediaItem != null && active().isPlaying) {
            beginManualFadeTo(index)
        } else {
            hardSwitchTo(index, positionMs = 0L, playWhenReady = true)
        }
    }

    fun releaseAll() {
        watcherRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        speedRampRunnable?.let { handler.removeCallbacks(it) }
        speedRampRunnable = null
        try { beatAnalysisExecutor.shutdownNow() } catch (_: Exception) {}
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
            .setPlaybackParameters(playbackParameters)
            .setIsLoading(a.isLoading)
            .setRepeatMode(repeatModeValue)
            .setShuffleModeEnabled(shuffleEnabled)
            .setVolume(userVolume)
            .build()
    }

    /** Cancels an in-flight transition and leaves the current active track in
     * a coherent state. This is important for pause/stop: during a crossfade
     * both physical players are playing, so pausing only the active player
     * would leave the incoming player audible in the background. */
    private fun cancelTransition() {
        speedRampRunnable?.let { handler.removeCallbacks(it) }
        speedRampRunnable = null
        if (!isCrossfadingNow && pendingTarget == null) return
        transitionGeneration++
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        val outgoing = active()
        val incoming = idle()
        try { incoming.pause() } catch (_: Exception) {}
        try { incoming.stop(); incoming.volume = 0f } catch (_: Exception) {}
        try { outgoing.volume = baseVolume(outgoing.currentMediaItem) } catch (_: Exception) {}
        isCrossfadingNow = false
        isManualFadingNow = false
        manualFadePhase = 0
        fadeProgress = 0f
        pendingTarget = null
        transitionStartedAtMs = 0L
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        userVolume = volume.coerceIn(0f, 1f)
        // Never overwrite the normalization layer: the player's exposed
        // volume is the user's master volume, while physical output volume
        // is master * track attenuation * crossfade curve.
        try {
            if (!isCrossfadingNow && !isManualFadingNow) {
                active().volume = baseVolume(active().currentMediaItem)
            } else if (isManualFadingNow) {
                val t = fadeProgress.coerceIn(0f, 1f)
                val curve = if (manualFadePhase == 0) {
                    kotlin.math.cos(t * (Math.PI / 2.0)).toFloat()
                } else {
                    kotlin.math.sin(t * (Math.PI / 2.0)).toFloat()
                }
                active().volume = curve * baseVolume(active().currentMediaItem)
            } else {
                // Apply the new master level to BOTH sides immediately. Using
                // the stored fade position avoids the old minOf() approach,
                // which could lower volume correctly but could not restore it
                // when the user raised the master volume during a fade.
                val t = fadeProgress.coerceIn(0f, 1f)
                val out = kotlin.math.cos(t * (Math.PI / 2.0)).toFloat()
                val inn = kotlin.math.sin(t * (Math.PI / 2.0)).toFloat()
                active().volume = out * baseVolume(active().currentMediaItem)
                idle().volume = inn * baseVolume(idle().currentMediaItem)
            }
        } catch (_: Exception) {
        }
        invalidateState()
        onPlaybackStateChanged?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        try {
            if (playWhenReady) {
                active().play()
            } else {
                // Both internal players may be producing audio during a fade.
                // Collapse the transition before pausing so no hidden player
                // continues playing after the user presses Pause.
                cancelTransition()
                active().pause()
            }
        } catch (_: Exception) {
        }
        invalidateState()
        onPlaybackStateChanged?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        try { active().prepare() } catch (_: Exception) {}
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        try {
            cancelTransition()
            active().stop()
            active().volume = baseVolume(active().currentMediaItem)
        } catch (_: Exception) {}
        invalidateState()
        onPlaybackStateChanged?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // IMPORTANT: lock-screen, Bluetooth, and notification Next/Previous all
        // arrive here (not through skipToNext/skipToPrevious directly) --
        // routing them through our own shuffle/repeat-aware methods instead of
        // trusting the raw mediaItemIndex is what makes shuffle and repeat
        // actually work when controlling playback with the phone locked.
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT -> skipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS -> skipToPrevious()
            else -> {
                // A seek during a crossfade changes the outgoing track's timeline.
                // Continuing the old fade after that point would make the two
                // players disagree about where the transition belongs. Cancel
                // the overlap first, then perform the seek on one player only.
                if (isCrossfadingNow || pendingTarget != null) {
                    cancelTransition()
                }
                if (mediaItemIndex != currentIndex && mediaItemIndex in queue.indices) {
                    // Explicit item selection is user navigation, not a natural
                    // end-of-song transition. Use the same immediate behavior
                    // as Crossfade OFF.
                    hardSwitchTo(mediaItemIndex, positionMs, playWhenReady = true)
                } else {
                    try {
                        active().seekTo(positionMs)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        invalidateState()
        onPlaybackStateChanged?.invoke()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeatModeValue = when (repeatMode) {
            Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> repeatMode
            else -> Player.REPEAT_MODE_OFF
        }
        Prefs.setRepeatMode(context, repeatModeValue)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffleEnabled = shuffleModeEnabled
        if (shuffleEnabled) {
            recentlyShuffled.clear()
            shuffleHistory.clear()
        } else {
            // History belongs to a particular shuffle session. Keeping it
            // after shuffle is disabled can make a later re-enable jump to an
            // unrelated old track when Previous is pressed.
            shuffleHistory.clear()
            recentlyShuffled.clear()
        }
        Prefs.setShuffleEnabled(context, shuffleEnabled)
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

    override fun handleSetPlaybackParameters(newParameters: PlaybackParameters): ListenableFuture<*> {
        val speed = newParameters.speed.coerceIn(0.5f, 2f)
        // setPlaybackSpeed changes rate without changing pitch in Media3. We
        // intentionally preserve the caller's pitch value if supplied, while
        // keeping the user-facing speed in a safe, music-friendly range.
        playbackParameters = newParameters.withSpeed(speed)
        Prefs.setPlaybackSpeed(context, speed)
        try {
            playerA.playbackParameters = playbackParameters
            playerB.playbackParameters = playbackParameters
        } catch (_: Exception) {
        }
        invalidateState()
        onPlaybackStateChanged?.invoke()
        return Futures.immediateVoidFuture()
    }
}
