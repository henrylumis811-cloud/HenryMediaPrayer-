package com.henrylumis.mediaprayer.util

import android.content.Context

/**
 * A simple single "Favorites" playlist, keyed by song id (matches
 * MediaItem.mediaId, which is set to the song's MediaStore id as a string).
 */
object PlaylistStore {
    private const val FILE = "media_prayer_favorites"
    private const val KEY_IDS = "favorite_ids"

    fun getFavoriteIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
    }

    fun isFavorite(context: Context, songId: String): Boolean =
        getFavoriteIds(context).contains(songId)

    /** Returns the new favorite state after toggling. */
    fun toggleFavorite(context: Context, songId: String): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = getFavoriteIds(context).toMutableSet()
        val nowFavorite = if (current.contains(songId)) {
            current.remove(songId)
            false
        } else {
            current.add(songId)
            true
        }
        prefs.edit().putStringSet(KEY_IDS, current).apply()
        return nowFavorite
    }
}
