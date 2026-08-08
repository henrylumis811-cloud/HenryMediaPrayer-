package com.henrylumis.mediaprayer.util

import android.content.Context

data class SavedPlayback(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val dataPath: String? = null
)

/**
 * Persists just enough about the current track to resume exactly where
 * playback left off, even after the app (and its background service) has
 * been fully closed and the process restarted from scratch.
 */
object PlaybackStateStore {
    private const val FILE = "media_prayer_playback_state"

    fun save(context: Context, state: SavedPlayback) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("media_id", state.mediaId)
            .putString("uri", state.uri)
            .putString("title", state.title)
            .putString("artist", state.artist)
            .putString("album", state.album)
            .putLong("position_ms", state.positionMs)
            .putString("data_path", state.dataPath)
            .apply()
    }

    fun load(context: Context): SavedPlayback? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mediaId = prefs.getString("media_id", null) ?: return null
        val uri = prefs.getString("uri", null) ?: return null
        return SavedPlayback(
            mediaId = mediaId,
            uri = uri,
            title = prefs.getString("title", "") ?: "",
            artist = prefs.getString("artist", "") ?: "",
            album = prefs.getString("album", "") ?: "",
            positionMs = prefs.getLong("position_ms", 0L),
            dataPath = prefs.getString("data_path", null)
        )
    }
}
