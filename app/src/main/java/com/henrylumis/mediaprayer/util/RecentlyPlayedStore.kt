package com.henrylumis.mediaprayer.util

import android.content.Context

/** Tracks the last-played song ids, most recent first, capped at 50. */
object RecentlyPlayedStore {
    private const val FILE = "media_prayer_recent"
    private const val KEY = "recent_ids"
    private const val MAX = 50

    fun addPlayed(context: Context, songId: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = getRecentIds(context).toMutableList()
        current.remove(songId)
        current.add(0, songId)
        val trimmed = if (current.size > MAX) current.take(MAX) else current
        prefs.edit().putString(KEY, trimmed.joinToString(",")).apply()
    }

    fun getRecentIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",")
    }
}
