package com.henrylumis.mediaprayer.data

import android.content.Context
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import com.henrylumis.mediaprayer.util.RecentlyPlayedStore

/**
 * Single source of truth for library sort order, shared between the Library
 * tab's UI and the playback-restore path (so "resume where I left off"
 * rebuilds the queue in the SAME order you actually see in Library, instead
 * of a separately-maintained copy that could silently drift out of sync).
 */
object SongSorter {

    enum class SortMode(val label: String) {
        TITLE_ASC("Title (A-Z)"),
        TITLE_DESC("Title (Z-A)"),
        ARTIST("Artist"),
        DURATION("Duration"),
        DATE_ADDED("Date Added (Newest)"),
        RECENTLY_PLAYED("Recently Played"),
        TOP_PLAYED("Top Played"),
        GENRE("Genre")
    }

    fun modeFromSavedName(name: String?): SortMode =
        name?.let { saved -> SortMode.values().find { it.name == saved } } ?: SortMode.TITLE_ASC

    fun sort(context: Context, songs: List<Song>, mode: SortMode): List<Song> = when (mode) {
        SortMode.TITLE_ASC -> songs.sortedBy { it.title.lowercase() }
        SortMode.TITLE_DESC -> songs.sortedByDescending { it.title.lowercase() }
        SortMode.ARTIST -> songs.sortedBy { it.artist.lowercase() }
        SortMode.DURATION -> songs.sortedBy { it.durationMs }
        SortMode.DATE_ADDED -> songs.sortedByDescending { it.dateAdded }
        SortMode.RECENTLY_PLAYED -> {
            val order = RecentlyPlayedStore.getRecentIds(context)
            songs.sortedBy { song ->
                val pos = order.indexOf(song.id.toString())
                if (pos == -1) Int.MAX_VALUE else pos
            }
        }
        SortMode.TOP_PLAYED -> songs.sortedByDescending {
            ListeningStatsStore.getPlayCount(context, it.id.toString())
        }
        SortMode.GENRE -> songs.sortedWith(
            compareBy(
                { ListeningStatsStore.getGenre(context, it.id.toString()) ?: "\uFFFF" },
                { it.title.lowercase() }
            )
        )
    }
}
