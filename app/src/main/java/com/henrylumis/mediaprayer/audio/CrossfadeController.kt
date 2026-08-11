package com.henrylumis.mediaprayer.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Real dual-player volume crossfade between tracks.
 *
 * The main player (owned by PlaybackService, wired to the MediaSession) is
 * NEVER swapped out -- that would risk destabilizing the notification/queue/
 * session state. Instead, a short-lived secondary ExoPlayer plays the
 * upcoming track quietly underneath the tail of the current one, their
 * volumes cross-fade.
 *
 * Position continuity: whichever player the listener was actually hearing
 * (the secondary, mid-fade) must hand off to the main player at the SAME
 * position, or the track audibly "restarts" from 0 the instant main takes
 * over. But a raw seekTo() on main isn't enough by itself -- even for local
 * files, ExoPlayer briefly flushes/re-syncs its decoder on a seek, which is
 * audible as a short silence/glitch if main is already the audible player at
 * that moment. So every handoff below forces main silent *before* seeking it,
 * and keeps the secondary player (untouched, still playing normally) as the
 * only audible source until main reports STATE_READY at the new position --
 * only then do we swap audibility and release the secondary. The gap in
 * main's playback happens entirely while main is inaudible.
 *
 * Two trigger paths:
 *  - Auto-advance: the natural end-of-track approaches: we don't control
 *    exactly when main itself transitions (ExoPlayer's own gapless engine
 *    decides that), so we just align main's position the instant it happens.
 *  - Manual (skip / tap a song): WE control the timing -- the ramp completes,
 *    THEN we perform the actual seek/queue change, THEN align position.
 *
 * Known limitation: with shuffle mode on, the "next" track for auto-advance
 * crossfade is guessed via queue order rather than the actual shuffled
 * order, so that specific preview can occasionally pick the wrong track.
 * Manual crossfade (skip/tap) is unaffected since the target is explicit.
 */
class CrossfadeController(
    private val context: Context,
    private val audioAttributes: AudioAttributes
) {
    private var secondaryPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null
    private var readyTimeoutRunnable: Runnable? = null

    // --- Auto-advance path ---

    fun maybeStartCrossfade(mainPlayer: ExoPlayer, durationMs: Long) {
        if (isCrossfading || durationMs <= 0) return
        val currentIndex = mainPlayer.currentMediaItemIndex
        val count = mainPlayer.mediaItemCount
        val nextIndex = when {
            currentIndex + 1 < count -> currentIndex + 1
            mainPlayer.repeatMode == Player.REPEAT_MODE_ALL && count > 0 -> 0
            else -> -1
        }
        if (nextIndex == -1) return
        try {
            startFade(mainPlayer, mainPlayer.getMediaItemAt(nextIndex), durationMs, onRampComplete = null)
        } catch (_: Exception) {
            isCrossfading = false
            cleanupSecondary()
        }
    }

    /** Call this from the main player's onMediaItemTransition -- crossfade is done either way,
     *  since main has now (on its own) moved to the next track. */
    fun onTrackTransitioned(mainPlayer: ExoPlayer) {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        val secondaryPosition = secondaryPlayer?.currentPosition ?: 0L

        if (secondaryPosition <= 0) {
            try { mainPlayer.volume = 1f } catch (_: Exception) {}
            cleanupSecondary()
            isCrossfading = false
            return
        }

        try {
            // Force silent immediately (regardless of exactly where the ramp
            // had gotten to) so the upcoming seek's brief internal re-buffer
            // is never audible from main's side -- the secondary player (still
            // playing normally, untouched) is what carries the sound through it.
            mainPlayer.volume = 0f
            mainPlayer.seekTo(secondaryPosition)
            waitForMainReadyThenFinalize(mainPlayer)
        } catch (_: Exception) {
            try { mainPlayer.volume = 1f } catch (_: Exception) {}
            cleanupSecondary()
            isCrossfading = false
        }
    }

    // --- Manual path (skip next/previous, or tapping a specific song) ---

    /**
     * Crossfades into [targetItem], then calls [onReadyToSwitch] to actually
     * perform the real seek/queue change on the main player once the fade
     * completes, then aligns main's position to match what was just heard.
     */
    fun crossfadeToTarget(
        mainPlayer: ExoPlayer,
        targetItem: MediaItem,
        durationMs: Long,
        onReadyToSwitch: () -> Unit
    ) {
        if (isCrossfading || durationMs <= 0) {
            onReadyToSwitch()
            return
        }
        try {
            startFade(mainPlayer, targetItem, durationMs, onRampComplete = { secondaryPosition ->
                onReadyToSwitch()
                try {
                    if (secondaryPosition > 0) {
                        mainPlayer.volume = 0f
                        mainPlayer.seekTo(secondaryPosition)
                        waitForMainReadyThenFinalize(mainPlayer)
                    } else {
                        mainPlayer.volume = 1f
                        cleanupSecondary()
                        isCrossfading = false
                    }
                } catch (_: Exception) {
                    try { mainPlayer.volume = 1f } catch (_: Exception) {}
                    cleanupSecondary()
                    isCrossfading = false
                }
            })
        } catch (_: Exception) {
            isCrossfading = false
            cleanupSecondary()
            onReadyToSwitch()
        }
    }

    /** Keeps the secondary player as the audible source until main confirms
     *  it has actually finished re-syncing to the target position (STATE_READY),
     *  then does a silent-to-silent handoff -- main was inaudible (volume 0)
     *  the whole time it was buffering, so nothing is ever heard glitching. */
    private fun waitForMainReadyThenFinalize(mainPlayer: ExoPlayer) {
        if (mainPlayer.playbackState == Player.STATE_READY) {
            finalizeHandoff(mainPlayer)
            return
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    mainPlayer.removeListener(this)
                    readyTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    finalizeHandoff(mainPlayer)
                }
            }
        }
        mainPlayer.addListener(listener)
        // Safety net: if READY somehow never fires promptly, finalize anyway
        // after a short timeout rather than leaving main silent indefinitely.
        val timeout = Runnable {
            mainPlayer.removeListener(listener)
            finalizeHandoff(mainPlayer)
        }
        readyTimeoutRunnable = timeout
        handler.postDelayed(timeout, 4000)
    }

    private fun finalizeHandoff(mainPlayer: ExoPlayer) {
        try { mainPlayer.volume = 1f } catch (_: Exception) {}
        cleanupSecondary()
        isCrossfading = false
    }

    private fun startFade(
        mainPlayer: ExoPlayer,
        targetItem: MediaItem,
        durationMs: Long,
        onRampComplete: ((Long) -> Unit)?
    ) {
        isCrossfading = true
        val secondary = ExoPlayer.Builder(context).build()
        secondary.setAudioAttributes(audioAttributes, false)
        secondary.setMediaItem(targetItem)
        secondary.prepare()
        secondary.volume = 0f
        secondary.play()
        secondaryPlayer = secondary

        val steps = 24
        val stepDelay = (durationMs / steps).coerceAtLeast(20)
        var stepCount = 0
        fadeRunnable = object : Runnable {
            override fun run() {
                stepCount++
                val t = (stepCount.toFloat() / steps).coerceIn(0f, 1f)
                try {
                    mainPlayer.volume = 1f - t
                    secondary.volume = t
                } catch (_: Exception) {
                }
                if (stepCount < steps && isCrossfading) {
                    handler.postDelayed(this, stepDelay)
                } else if (onRampComplete != null && isCrossfading) {
                    val pos = secondary.currentPosition
                    // Cleanup/isCrossfading reset is now owned by whatever
                    // onRampComplete does (e.g. waitForMainReadyThenFinalize) --
                    // releasing secondary here too would kill the very player
                    // that's supposed to bridge the gap while main re-syncs.
                    onRampComplete(pos)
                }
            }
        }
        handler.post(fadeRunnable!!)
    }

    private fun cleanupSecondary() {
        secondaryPlayer?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        secondaryPlayer = null
    }

    fun release() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        cleanupSecondary()
        isCrossfading = false
    }
}
