package com.henrylumis.mediaprayer.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.provider.MediaStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Writes common music tags using jaudiotagger, while keeping Android's MediaStore
 * index in sync. The caller is responsible for obtaining write access to the
 * MediaStore Uri before calling commit().
 */
object TagEditor {
    data class Values(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val genre: String,
        val year: String,
        val track: String,
        val disc: String,
        val comment: String
    )

    data class Result(val tempFile: File, val mediaStoreValues: ContentValues)

    fun prepare(context: Context, sourceUri: Uri, values: Values): Result {
        val resolver = context.contentResolver
        val extension = sourceExtension(resolver, sourceUri)
        val temp = File.createTempFile("tag-edit-", ".${extension}", context.cacheDir)
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw IllegalStateException("Unable to read the selected audio file")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }

        val audioFile = try {
            AudioFileIO.read(temp)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        val tag = audioFile.tagOrCreateAndSetDefault
        set(tag, FieldKey.TITLE, values.title)
        set(tag, FieldKey.ARTIST, values.artist)
        set(tag, FieldKey.ALBUM, values.album)
        set(tag, FieldKey.ALBUM_ARTIST, values.albumArtist)
        set(tag, FieldKey.GENRE, values.genre)
        set(tag, FieldKey.YEAR, values.year)
        set(tag, FieldKey.TRACK, values.track)
        set(tag, FieldKey.DISC_NO, values.disc)
        set(tag, FieldKey.COMMENT, values.comment)
        AudioFileIO.write(audioFile)

        val mediaValues = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, values.title)
            put(MediaStore.Audio.Media.ARTIST, values.artist)
            put(MediaStore.Audio.Media.ALBUM, values.album)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.ALBUM_ARTIST, values.albumArtist)
            }
            if (values.year.isNotBlank()) put(MediaStore.Audio.Media.YEAR, values.year.toIntOrNull() ?: 0)
            if (values.track.isNotBlank()) put(MediaStore.Audio.Media.TRACK, values.track.toIntOrNull() ?: 0)
        }
        return Result(temp, mediaValues)
    }

    fun writePrepared(context: Context, sourceUri: Uri, result: Result) {
        val resolver = context.contentResolver
        resolver.openOutputStream(sourceUri, "wt")?.use { output ->
            result.tempFile.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("Unable to write the audio file")
        resolver.update(sourceUri, result.mediaStoreValues, null, null)
        result.tempFile.delete()
    }


    /** Jaudiotagger selects its reader/writer from the file extension. Keep the
     * original audio extension on the cache copy instead of using a generic
     * .audio extension, otherwise it reports "No Reader associated". */
    private fun sourceExtension(resolver: android.content.ContentResolver, uri: Uri): String {
        var name: String? = null
        var mime: String? = null
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (mimeIndex >= 0) mime = cursor.getString(mimeIndex)
                }
            }
        } catch (_: Throwable) {
            // Fall through to URI/MIME inference.
        }

        val fromName = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
        if (fromName != null) return fromName

        val fromUri = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 8 }
        if (fromUri != null) return fromUri

        return when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/aac" -> "aac"
            else -> throw IllegalArgumentException("Unsupported audio type: ${mime ?: "unknown"}")
        }
    }

    private fun set(tag: org.jaudiotagger.tag.Tag, key: FieldKey, value: String) {
        if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim())
    }
}
