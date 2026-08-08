package com.henrylumis.mediaprayer.util

import android.content.Context

data class SongStats(val id: String, val title: String, val artist: String, val genre: String?, val playCount: Int, val listenedMs: Long)

/**
 * Tracks real listening habits, entirely on-device:
 *  - Per-song play count (counted once a track has actually been listened to
 *    for 30+ seconds, not just tapped and skipped)
 *  - Per-song accumulated listened time
 *  - Per-song genre (read lazily from the file the first time it's played,
 *    and cached -- so genre-based insights improve the more you use the app)
 *  - Library-wide total listening time and total counted plays
 */
object ListeningStatsStore {
    private const val FILE = "media_prayer_listening_stats"
    private const val KEY_TRACKED_IDS = "tracked_ids"
    private const val KEY_TOTAL_MS = "total_listened_ms"
    private const val KEY_TOTAL_PLAYS = "total_plays"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun trackedIds(context: Context): MutableSet<String> =
        prefs(context).getStringSet(KEY_TRACKED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun ensureTracked(context: Context, id: String) {
        val ids = trackedIds(context)
        if (ids.add(id)) {
            prefs(context).edit().putStringSet(KEY_TRACKED_IDS, ids).apply()
        }
    }

    fun setTrackMeta(context: Context, id: String, title: String, artist: String) {
        ensureTracked(context, id)
        prefs(context).edit()
            .putString("title_$id", title)
            .putString("artist_$id", artist)
            .apply()
    }

    fun setGenreIfAbsent(context: Context, id: String, genre: String?) {
        if (genre.isNullOrBlank()) return
        val p = prefs(context)
        if (p.getString("genre_$id", null) == null) {
            p.edit().putString("genre_$id", genre).apply()
        }
    }

    fun addListenedMs(context: Context, id: String, deltaMs: Long) {
        if (deltaMs <= 0) return
        val p = prefs(context)
        val current = p.getLong("ms_$id", 0L)
        p.edit()
            .putLong("ms_$id", current + deltaMs)
            .putLong(KEY_TOTAL_MS, p.getLong(KEY_TOTAL_MS, 0L) + deltaMs)
            .apply()
    }

    fun incrementPlayCount(context: Context, id: String) {
        val p = prefs(context)
        val current = p.getInt("count_$id", 0)
        p.edit()
            .putInt("count_$id", current + 1)
            .putInt(KEY_TOTAL_PLAYS, p.getInt(KEY_TOTAL_PLAYS, 0) + 1)
            .apply()
    }

    fun getPlayCount(context: Context, id: String): Int = prefs(context).getInt("count_$id", 0)

    fun getGenre(context: Context, id: String): String? = prefs(context).getString("genre_$id", null)

    fun getTotalListenedMs(context: Context): Long = prefs(context).getLong(KEY_TOTAL_MS, 0L)

    fun getTotalPlays(context: Context): Int = prefs(context).getInt(KEY_TOTAL_PLAYS, 0)

    fun getAllStats(context: Context): List<SongStats> {
        val p = prefs(context)
        return trackedIds(context).map { id ->
            SongStats(
                id = id,
                title = p.getString("title_$id", "Unknown") ?: "Unknown",
                artist = p.getString("artist_$id", "") ?: "",
                genre = p.getString("genre_$id", null),
                playCount = p.getInt("count_$id", 0),
                listenedMs = p.getLong("ms_$id", 0L)
            )
        }
    }

    fun topPlayed(context: Context, limit: Int): List<SongStats> =
        getAllStats(context).sortedByDescending { it.playCount }.take(limit)

    fun topGenres(context: Context, limit: Int): List<Pair<String, Int>> =
        getAllStats(context)
            .mapNotNull { it.genre }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(limit)
}
