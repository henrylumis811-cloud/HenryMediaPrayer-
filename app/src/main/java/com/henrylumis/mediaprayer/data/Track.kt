package com.henrylumis.mediaprayer.data

/**
 * A single "offering" in the reliquary (library). [uriString] points at a file
 * the user picked through the system file/document chooser; we never copy the
 * audio itself, we just keep a persistable content:// permission for it, which
 * mirrors the original web app storing the picked File/Blob handle only.
 */
data class Track(
    val id: String,
    val uriString: String,
    var title: String,
    var artist: String = "Unknown",
    var durationMs: Long = 0L,
    var lyrics: String = "",
    var format: String = "",
    val addedAt: Long = System.currentTimeMillis()
)
