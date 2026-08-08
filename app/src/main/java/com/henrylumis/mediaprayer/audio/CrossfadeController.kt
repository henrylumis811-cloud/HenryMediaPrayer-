package com.henrylumis.mediaprayer.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Real dual-player volume crossfade between consecutive queue tracks.
 *
 * The main player (owned by PlaybackService, wired to the MediaSession) is
 * NEVER swapped out -- that would risk destabilizing the notification/queue/
 * session state. Instead, a short-lived secondary ExoPlayer plays the
 * upcoming track quietly underneath the tail of the current one, their
 * volumes cross-fade, and the secondary player is torn down the instant the
 * main player naturally advances to that same track (which ExoPlayer does on
 * its own via gapless playback).
 *
 * Known limitation: with shuffle mode on, the "next" track is guessed via
 * queue order rather than the actual shuffled order, so the preview lookup
 * can occasionally pick the wrong track. Playback itself is unaffected --
 * only the crossfade preview could rarely mismatch in that mode.
 */
class CrossfadeController(
    private val context: Context,
    private val audioAttributes: AudioAttributes
) {
    private var secondaryPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

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
            val nextItem = mainPlayer.getMediaItemAt(nextIndex)
            isCrossfading = true

            val secondary = ExoPlayer.Builder(context).build()
            secondary.setAudioAttributes(audioAttributes, false)
            secondary.setMediaItem(nextItem)
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
                    if (stepCount < steps && isCrossfading) handler.postDelayed(this, stepDelay)
                }
            }
            handler.post(fadeRunnable!!)
        } catch (_: Exception) {
            isCrossfading = false
            cleanupSecondary()
        }
    }

    /** Call this from the main player's onMediaItemTransition -- crossfade is done either way. */
    fun onTrackTransitioned(mainPlayer: ExoPlayer) {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        try { mainPlayer.volume = 1f } catch (_: Exception) {}
        cleanupSecondary()
        isCrossfading = false
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
