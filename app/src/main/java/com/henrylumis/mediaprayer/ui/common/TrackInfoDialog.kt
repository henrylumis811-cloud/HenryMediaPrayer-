package com.henrylumis.mediaprayer.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import com.henrylumis.mediaprayer.util.PlaylistStore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Premium, read-only track/file inspector. Editing is deliberately separate so
 * this feature cannot accidentally mutate a user's library. */
object TrackInfoDialog {
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    fun show(context: Context, song: Song) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 12)
        }

        val art = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(112, 112).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 12
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_album_placeholder)
            contentDescription = null
        }
        content.addView(art)

        val title = TextView(context).apply {
            text = song.title
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 20f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(title)

        val subtitle = TextView(context).apply {
            text = "${song.artist} • ${song.album}"
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 4, 0, 16)
        }
        content.addView(subtitle)

        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(details)

        val scroll = ScrollView(context).apply {
            addView(content)
        }

        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("TRACK INFO")
            .setView(scroll)
            .setNegativeButton("Edit tags") { _, _ -> TagEditorActivity.launch(context, song) }
            .setPositiveButton("Done", null)
            .create()
        DialogStyler.show(dialog)

        val favorite = PlaylistStore.isFavorite(context, song.id.toString())
        addSection(details, context, "LIBRARY")
        addRow(details, context, "Title", song.title)
        addRow(details, context, "Artist", song.artist)
        addRow(details, context, "Album", song.album)
        addRow(details, context, "Duration", formatDuration(song.durationMs))
        addRow(details, context, "Favorite", if (favorite) "Yes" else "No")
        addRow(details, context, "Play count", ListeningStatsStore.getPlayCount(context, song.id.toString()).toString())

        addSection(details, context, "AUDIO / FILE")
        addRow(details, context, "Reading file…", "")

        executor.execute {
            val info = inspect(context, song)
            main.post {
                if (!dialog.isShowing) return@post
                // Remove the temporary row and replace it with the complete set.
                if (details.childCount > 0) details.removeViewAt(details.childCount - 1)
                info.rows.forEach { addRow(details, context, it.first, it.second) }
                info.artwork?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { art.setImageBitmap(it) }
                }
            }
        }
    }

    private data class Inspection(val rows: List<Pair<String, String>>, val artwork: ByteArray?)

    private fun inspect(context: Context, song: Song): Inspection {
        val rows = mutableListOf<Pair<String, String>>()
        var artwork: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(song.uriString))
            artwork = retriever.embeddedPicture
            val duration = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))?.toLongOrNull()
            val mime = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            val bitrate = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE))?.toLongOrNull()
            val sampleRate = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE))?.toLongOrNull()
            val genre = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
            val date = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            val track = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            val disc = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            val albumArtist = value(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))

            addIfPresent(rows, "Format", mime?.removePrefix("audio/"))
            addIfPresent(rows, "Bitrate", bitrate?.let { formatBitrate(it) })
            addIfPresent(rows, "Sample rate", sampleRate?.let { "${it} Hz" })
            addIfPresent(rows, "Track", track)
            addIfPresent(rows, "Disc", disc)
            addIfPresent(rows, "Genre", genre)
            addIfPresent(rows, "Album artist", albumArtist)
            addIfPresent(rows, "Date / year", date)
            addIfPresent(rows, "Duration", duration?.let { formatDuration(it) })
        } catch (_: Exception) {
            rows.add("Audio metadata" to "Unavailable")
        } finally {
            retriever.release()
        }

        try {
            context.contentResolver.query(
                Uri.parse(song.uriString),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    addIfPresent(rows, "File name", c.getString(0))
                    val size = if (!c.isNull(1)) c.getLong(1) else 0L
                    if (size > 0) addIfPresent(rows, "File size", formatBytes(size))
                    addIfPresent(rows, "MIME type", c.getString(2))
                    val modified = if (!c.isNull(3)) c.getLong(3) else 0L
                    if (modified > 0) addIfPresent(rows, "Modified", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(modified * 1000L)))
                }
            }
        } catch (_: Exception) { }

        val path = song.dataPath
        if (!path.isNullOrBlank()) {
            addIfPresent(rows, "Location", path)
            try {
                val file = File(path)
                if (file.exists()) {
                    if (!rows.any { it.first == "File size" } && file.length() > 0) addIfPresent(rows, "File size", formatBytes(file.length()))
                }
            } catch (_: Exception) { }
        }
        return Inspection(rows, artwork)
    }

    private fun addSection(parent: LinearLayout, context: Context, text: String) {
        TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.accent_cyan))
            textSize = 12f
            setPadding(0, 12, 0, 6)
        }.also(parent::addView)
    }

    private fun addRow(parent: LinearLayout, context: Context, label: String, value: String) {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 5, 0, 5)
            val left = TextView(context).apply {
                text = label
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.40f)
            }
            val right = TextView(context).apply {
                text = value.ifBlank { "—" }
                setTextColor(context.getColor(R.color.text_primary))
                textSize = 13f
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.60f)
            }
            addView(left); addView(right)
        }.also(parent::addView)
    }

    private fun addIfPresent(rows: MutableList<Pair<String, String>>, label: String, value: String?) {
        if (!value.isNullOrBlank() && value != "unknown") rows.add(label to value.trim())
    }

    private fun value(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val total = TimeUnit.MILLISECONDS.toSeconds(ms)
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    }

    private fun formatBitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1000 -> "${bitsPerSecond / 1000} kbps"
        else -> "$bitsPerSecond bps"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
