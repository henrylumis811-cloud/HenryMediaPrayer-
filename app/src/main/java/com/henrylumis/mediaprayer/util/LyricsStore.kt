package com.henrylumis.mediaprayer.util

import android.content.Context

/**
 * Stores manually pasted-in lyrics per song (keyed by MediaItem.mediaId,
 * which is the song's MediaStore id -- stable across app restarts).
 * Separate from synced .lrc file lyrics, which are read straight from disk.
 */
object LyricsStore {
    private const val FILE = "media_prayer_pasted_lyrics"

    fun get(context: Context, mediaId: String): String? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getString(mediaId, null)
    }

    fun set(context: Context, mediaId: String, lyrics: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(mediaId, lyrics).apply()
    }

    fun clear(context: Context, mediaId: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().remove(mediaId).apply()
    }
}
