package com.henrylumis.mediaprayer.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Wraps the single shared [PlayerManager.player] in a [MediaSession] so the
 * system gives us: a lock-screen / notification transport, Bluetooth and
 * headset button handling, and a proper foreground-service lifecycle while
 * music is playing in the background. The UI (fragments) still talk to
 * [PlayerManager] directly since they run in the same process.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        PlayerManager.init(this)
        mediaSession = MediaSession.Builder(this, PlayerManager.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        PlayerManager.release()
        super.onDestroy()
    }
}
