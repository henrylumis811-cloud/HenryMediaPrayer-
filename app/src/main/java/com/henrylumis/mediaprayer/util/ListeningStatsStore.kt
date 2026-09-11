package com.henrylumis.mediaprayer.util

import android.content.Context
import java.util.Calendar

data class SongStats(val id: String, val title: String, val artist: String, val genre: String?, val playCount: Int, val listenedMs: Long, val durationMs: Long = 0L, val album: String = "", val skipCount: Int = 0, val completionCount: Int = 0)
data class ArtistStats(val artist: String, val listenedMs: Long, val playCount: Int)
data class AlbumStats(val album: String, val artist: String, val listenedMs: Long, val playCount: Int)

/**
 * Tracks real listening habits, entirely on-device:
 *  - Per-song play count (counted once a track has actually been listened to
 *    for 30+ seconds, not just tapped and skipped)
 *  - Per-song accumulated listened time, both all-time AND per calendar
 *    month/year, so monthly/yearly recaps are real historical data rather
 *    than a snapshot of current totals
 *  - Per-song genre (read lazily, cached)
 *  - Library-wide total listening time and total counted plays
 */
object ListeningStatsStore {
    private const val FILE = "media_prayer_listening_stats"
    private const val KEY_TRACKED_IDS = "tracked_ids"
    private const val KEY_TOTAL_MS = "total_listened_ms"
    private const val KEY_TOTAL_PLAYS = "total_plays"
    private const val KEY_LAST_SEEN_MONTH = "last_seen_month"
    private const val KEY_LAST_SEEN_YEAR = "last_seen_year"
    private const val KEY_ACTIVE_DAYS = "active_days"
    private const val KEY_TOTAL_SKIPS = "total_skips"
    private const val KEY_TOTAL_COMPLETIONS = "total_completions"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun currentMonthKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${(cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}"
    }

    fun currentYearKey(): String = Calendar.getInstance().get(Calendar.YEAR).toString()

    /** Returns the just-completed month key if the calendar month has rolled
     *  over since the app last checked, else null. Updates the stored marker. */
    fun checkAndConsumeMonthRollover(context: Context): String? {
        val p = prefs(context)
        val current = currentMonthKey()
        val lastSeen = p.getString(KEY_LAST_SEEN_MONTH, null)
        p.edit().putString(KEY_LAST_SEEN_MONTH, current).apply()
        return if (lastSeen != null && lastSeen != current) lastSeen else null
    }

    /** Returns the just-completed year key if the calendar year has rolled
     *  over since the app last checked, else null. Updates the stored marker. */
    fun checkAndConsumeYearRollover(context: Context): String? {
        val p = prefs(context)
        val current = currentYearKey()
        val lastSeen = p.getString(KEY_LAST_SEEN_YEAR, null)
        p.edit().putString(KEY_LAST_SEEN_YEAR, current).apply()
        return if (lastSeen != null && lastSeen != current) lastSeen else null
    }

    private fun trackedIds(context: Context): MutableSet<String> =
        prefs(context).getStringSet(KEY_TRACKED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun ensureTracked(context: Context, id: String) {
        val ids = trackedIds(context)
        if (ids.add(id)) {
            prefs(context).edit().putStringSet(KEY_TRACKED_IDS, ids).apply()
        }
    }

    fun setTrackMeta(context: Context, id: String, title: String, artist: String, durationMs: Long = 0L, album: String = "") {
        ensureTracked(context, id)
        prefs(context).edit()
            .putString("title_$id", title)
            .putString("artist_$id", artist)
            .putString("album_$id", album)
            .putLong("duration_$id", durationMs.coerceAtLeast(0L))
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
        val month = currentMonthKey()
        val year = currentYearKey()
        p.edit()
            .putLong("ms_$id", p.getLong("ms_$id", 0L) + deltaMs)
            .putLong(KEY_TOTAL_MS, p.getLong(KEY_TOTAL_MS, 0L) + deltaMs)
            .putLong("ms_${id}_m$month", p.getLong("ms_${id}_m$month", 0L) + deltaMs)
            .putLong("ms_${id}_y$year", p.getLong("ms_${id}_y$year", 0L) + deltaMs)
            .putLong("ms_${id}_d${currentDayKey()}", p.getLong("ms_${id}_d${currentDayKey()}", 0L) + deltaMs)
            .putLong("day_${currentDayKey()}", p.getLong("day_${currentDayKey()}", 0L) + deltaMs)
            .putStringSet(KEY_ACTIVE_DAYS, p.getStringSet(KEY_ACTIVE_DAYS, emptySet())?.plus(currentDayKey()) ?: setOf(currentDayKey()))
            .apply()
    }

    fun incrementSkip(context: Context, id: String) {
        val p = prefs(context)
        val month = currentMonthKey()
        val year = currentYearKey()
        p.edit()
            .putInt("skip_$id", p.getInt("skip_$id", 0) + 1)
            .putInt("skip_${id}_m$month", p.getInt("skip_${id}_m$month", 0) + 1)
            .putInt("skip_${id}_y$year", p.getInt("skip_${id}_y$year", 0) + 1)
            .putInt("skip_${id}_d${currentDayKey()}", p.getInt("skip_${id}_d${currentDayKey()}", 0) + 1)
            .putInt(KEY_TOTAL_SKIPS, p.getInt(KEY_TOTAL_SKIPS, 0) + 1)
            .apply()
    }

    fun incrementCompletion(context: Context, id: String) {
        val p = prefs(context)
        val month = currentMonthKey()
        val year = currentYearKey()
        p.edit()
            .putInt("complete_$id", p.getInt("complete_$id", 0) + 1)
            .putInt("complete_${id}_m$month", p.getInt("complete_${id}_m$month", 0) + 1)
            .putInt("complete_${id}_y$year", p.getInt("complete_${id}_y$year", 0) + 1)
            .putInt("complete_${id}_d${currentDayKey()}", p.getInt("complete_${id}_d${currentDayKey()}", 0) + 1)
            .putInt(KEY_TOTAL_COMPLETIONS, p.getInt(KEY_TOTAL_COMPLETIONS, 0) + 1)
            .apply()
    }

    fun getSkipCount(context: Context, id: String): Int = prefs(context).getInt("skip_$id", 0)
    fun getCompletionCount(context: Context, id: String): Int = prefs(context).getInt("complete_$id", 0)
    fun getTotalSkips(context: Context): Int = prefs(context).getInt(KEY_TOTAL_SKIPS, 0)
    fun getTotalCompletions(context: Context): Int = prefs(context).getInt(KEY_TOTAL_COMPLETIONS, 0)

    fun incrementPlayCount(context: Context, id: String) {
        val p = prefs(context)
        val month = currentMonthKey()
        val year = currentYearKey()
        p.edit()
            .putInt("count_$id", p.getInt("count_$id", 0) + 1)
            .putInt(KEY_TOTAL_PLAYS, p.getInt(KEY_TOTAL_PLAYS, 0) + 1)
            .putInt("count_${id}_m$month", p.getInt("count_${id}_m$month", 0) + 1)
            .putInt("count_${id}_y$year", p.getInt("count_${id}_y$year", 0) + 1)
            .putInt("count_${id}_d${currentDayKey()}", p.getInt("count_${id}_d${currentDayKey()}", 0) + 1)
            .apply()
    }

    fun currentDayKey(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    fun getDayListenedMs(context: Context, dayKey: String): Long =
        prefs(context).getLong("day_$dayKey", 0L)

    fun getActiveDayCount(context: Context): Int = prefs(context).getStringSet(KEY_ACTIVE_DAYS, emptySet())?.size ?: 0

    fun getCurrentStreakDays(context: Context): Int {
        val p = prefs(context)
        val cal = Calendar.getInstance()
        var streak = 0
        while (streak < 3650) {
            val key = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            if (p.getLong("day_$key", 0L) <= 0L) break
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    fun getPlayCount(context: Context, id: String): Int = prefs(context).getInt("count_$id", 0)

    fun getGenre(context: Context, id: String): String? = prefs(context).getString("genre_$id", null)
    fun getAlbum(context: Context, id: String): String = prefs(context).getString("album_$id", "") ?: ""

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
                listenedMs = p.getLong("ms_$id", 0L),
                durationMs = p.getLong("duration_$id", 0L),
                album = p.getString("album_$id", "") ?: "",
                skipCount = p.getInt("skip_$id", 0),
                completionCount = p.getInt("complete_$id", 0)
            )
        }
    }

    /** Per-song stats scoped to a single calendar period (month key like
     *  "2026-08", or year key like "2026") -- used for recaps. Songs with no
     *  activity that period are omitted. */
    fun getPeriodStats(context: Context, periodKey: String, monthly: Boolean): List<SongStats> {
        val p = prefs(context)
        val suffix = if (monthly) "_m$periodKey" else "_y$periodKey"
        return trackedIds(context).mapNotNull { id ->
            val ms = p.getLong("ms_$id$suffix", 0L)
            val count = p.getInt("count_$id$suffix", 0)
            if (ms <= 0 && count <= 0) return@mapNotNull null
            SongStats(
                id = id,
                title = p.getString("title_$id", "Unknown") ?: "Unknown",
                artist = p.getString("artist_$id", "") ?: "",
                genre = p.getString("genre_$id", null),
                playCount = count,
                listenedMs = ms,
                durationMs = p.getLong("duration_$id", 0L),
                album = p.getString("album_$id", "") ?: "",
                skipCount = p.getInt("skip_$id$suffix", 0),
                completionCount = p.getInt("complete_$id$suffix", 0)
            )
        }
    }

    fun topPlayed(context: Context, limit: Int): List<SongStats> =
        getAllStats(context).sortedByDescending { it.playCount }.take(limit)

    /** Top genres by actual listening time, not by the number of tagged tracks. */
    fun topGenresByListeningMs(context: Context, limit: Int, stats: List<SongStats>? = null): List<Pair<String, Long>> =
        (stats ?: getAllStats(context))
            .filter { it.listenedMs > 0 && !it.genre.isNullOrBlank() }
            .groupBy { it.genre!!.trim() }
            .map { (genre, songs) -> genre to songs.sumOf { it.listenedMs } }
            .sortedByDescending { it.second }
            .take(limit)

    fun topGenres(context: Context, limit: Int): List<Pair<String, Int>> =
        getAllStats(context)
            .mapNotNull { it.genre }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(limit)

    /** Aggregates the per-song daily buckets for the requested recent window. */
    fun getRecentStats(context: Context, days: Int): List<SongStats> {
        val safeDays = days.coerceIn(1, 365)
        val p = prefs(context)
        val cal = Calendar.getInstance()
        val totals = mutableMapOf<String, Long>()
        val plays = mutableMapOf<String, Int>()
        val skips = mutableMapOf<String, Int>()
        val completions = mutableMapOf<String, Int>()
        repeat(safeDays) {
            val day = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            trackedIds(context).forEach { id ->
                val ms = p.getLong("ms_${id}_d$day", 0L)
                if (ms > 0) totals[id] = (totals[id] ?: 0L) + ms
                val count = p.getInt("count_${id}_d$day", 0)
                if (count > 0) plays[id] = (plays[id] ?: 0) + count
                val skip = p.getInt("skip_${id}_d$day", 0)
                if (skip > 0) skips[id] = (skips[id] ?: 0) + skip
                val complete = p.getInt("complete_${id}_d$day", 0)
                if (complete > 0) completions[id] = (completions[id] ?: 0) + complete
            }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return trackedIds(context).mapNotNull { id ->
            val ms = totals[id] ?: 0L
            val count = plays[id] ?: 0
            val skip = skips[id] ?: 0
            val complete = completions[id] ?: 0
            if (ms <= 0L && count <= 0 && skip <= 0 && complete <= 0) return@mapNotNull null
            SongStats(
                id = id,
                title = p.getString("title_$id", "Unknown") ?: "Unknown",
                artist = p.getString("artist_$id", "") ?: "",
                genre = p.getString("genre_$id", null),
                playCount = count,
                listenedMs = ms,
                durationMs = p.getLong("duration_$id", 0L),
                album = p.getString("album_$id", "") ?: "",
                skipCount = skip,
                completionCount = complete
            )
        }
    }

    fun topAlbums(context: Context, limit: Int, stats: List<SongStats>? = null): List<AlbumStats> {
        val source = stats ?: getAllStats(context)
        return source
            .filter { it.album.isNotBlank() && it.listenedMs > 0 }
            .groupBy { it.album to it.artist }
            .map { (key, songs) ->
                AlbumStats(key.first, key.second, songs.sumOf { it.listenedMs }, songs.sumOf { it.playCount })
            }
            .sortedByDescending { it.listenedMs }
            .take(limit)
    }

    fun topArtists(context: Context, limit: Int, stats: List<SongStats>? = null): List<ArtistStats> {
        val source = stats ?: getAllStats(context)
        return source
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (artist, songs) ->
                ArtistStats(
                    artist = artist,
                    listenedMs = songs.sumOf { it.listenedMs },
                    playCount = songs.sumOf { it.playCount }
                )
            }
            .sortedByDescending { it.listenedMs }
            .take(limit)
    }
}
