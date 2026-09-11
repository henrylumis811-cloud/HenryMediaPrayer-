package com.henrylumis.mediaprayer.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uriString: String,
    val albumId: Long,
    val dateAdded: Long = 0L,
    val dataPath: String? = null
)

/** Single source of truth for building a MediaItem from a Song, used by both
 *  Library/Queue playback and the restore-on-launch path, so both carry the
 *  same extras (data_path, duration_ms) that lyrics/normalization/analytics rely on. */
fun Song.toMediaItem(): MediaItem {
    val extras = android.os.Bundle().apply {
        dataPath?.let { putString("data_path", it) }
        putLong("duration_ms", durationMs)
    }
    return MediaItem.Builder()
        .setUri(uriString)
        .setMediaId(id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                // Media3/System UI can read this public MediaProvider URI directly.
                // Supplying artworkUri is what makes the embedded album art available
                // to the lock-screen and music notification instead of only the app UI.
                .setArtworkUri(
                    Uri.parse("content://media/external/audio/media/$id/albumart")
                )
                .setExtras(extras)
                .build()
        )
        .build()
}
