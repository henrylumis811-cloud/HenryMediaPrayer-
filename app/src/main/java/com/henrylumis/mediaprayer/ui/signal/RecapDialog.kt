package com.henrylumis.mediaprayer.ui.signal

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.databinding.DialogRecapBinding
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import com.henrylumis.mediaprayer.util.SongStats

import com.henrylumis.mediaprayer.ui.common.DialogStyler
/** Builds and shows a "recap" summary (top songs/artists, total time) for a
 *  given month or year -- shown automatically once when a period rolls over,
 *  and also available on demand from the Signal tab. */
object RecapDialog {

    fun show(context: Context, periodKey: String, monthly: Boolean, label: String) {
        val binding = DialogRecapBinding.inflate(LayoutInflater.from(context))
        val stats = ListeningStatsStore.getPeriodStats(context, periodKey, monthly)

        binding.recapTitle.text = if (monthly) "Your Month in Music" else "Your Year in Music"

        if (stats.isEmpty()) {
            binding.recapSummary.text = "No tracked listening for $label yet."
        } else {
            val totalMs = stats.sumOf { it.listenedMs }
            val totalPlays = stats.sumOf { it.playCount }
            binding.recapSummary.text = "$label \u2022 ${formatDuration(totalMs)} listened \u2022 $totalPlays tracked plays"
        }

        binding.recapTopSongs.removeAllViews()
        stats.sortedByDescending { it.listenedMs }.take(5).forEach { stat ->
            binding.recapTopSongs.addView(row(context, "${stat.title} \u2014 ${stat.artist}", formatDuration(stat.listenedMs)))
        }
        if (stats.isEmpty()) binding.recapTopSongs.addView(row(context, "Nothing tracked yet", ""))

        binding.recapTopArtists.removeAllViews()
        ListeningStatsStore.topArtists(context, 5, stats).forEach { artistStat ->
            binding.recapTopArtists.addView(row(context, artistStat.artist, formatDuration(artistStat.listenedMs)))
        }
        if (binding.recapTopArtists.childCount == 0) {
            binding.recapTopArtists.addView(row(context, "Nothing tracked yet", ""))
        }

        binding.recapTopAlbums.removeAllViews()
        ListeningStatsStore.topAlbums(context, 3, stats).forEachIndexed { index, album ->
            val artist = album.artist.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
            binding.recapTopAlbums.addView(row(context, "${index + 1}. ${album.album}$artist", formatDuration(album.listenedMs)))
        }
        if (binding.recapTopAlbums.childCount == 0) {
            binding.recapTopAlbums.addView(row(context, "Nothing tracked yet", ""))
        }

        DialogStyler.show(
            AlertDialog.Builder(context)
                .setView(binding.root)
                .setPositiveButton("Close", null)
        )
    }

    private fun row(context: Context, left: String, right: String): TextView {
        return TextView(context).apply {
            text = if (right.isNotBlank()) "$left  \u2014  $right" else left
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 13f
            setPadding(0, 4, 0, 4)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
