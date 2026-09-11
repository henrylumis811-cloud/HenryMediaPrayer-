package com.henrylumis.mediaprayer.trim

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import java.io.File
import java.io.FileInputStream

/**
 * Real audio clipping via Media3's Transformer (decodes + re-encodes just the
 * selected range into a standalone file) -- not a fake/simulated trim. Output
 * is then inserted into the system's Ringtones media collection so it shows
 * up in Settings > Sound > Ringtone like any other ringtone.
 *
 * Note: this saves the clip into the Ringtones collection so you can pick it
 * from system settings; it does not attempt to silently set it as the active
 * default ringtone, since that requires a separate special permission
 * (WRITE_SETTINGS) with its own consent flow.
 */
@OptIn(UnstableApi::class)
object AudioTrimmer {

    sealed class Result {
        data class Success(val ringtoneUri: Uri, val displayName: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun trimAndSaveAsRingtone(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        outputName: String,
        onResult: (Result) -> Unit
    ) {
        val cacheFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.m4a")

        val clippedItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
        val editedItem = EditedMediaItem.Builder(clippedItem).build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    val saved = saveToRingtonesCollection(context, cacheFile, outputName)
                    cacheFile.delete()
                    if (saved != null) {
                        onResult(Result.Success(saved, outputName))
                    } else {
                        onResult(Result.Failure("Export finished but saving to the Ringtones folder failed."))
                    }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    cacheFile.delete()
                    onResult(Result.Failure(exception.message ?: "Trim failed for an unknown reason."))
                }
            })
            .build()

        try {
            transformer.start(editedItem, cacheFile.absolutePath)
        } catch (e: Exception) {
            cacheFile.delete()
            onResult(Result.Failure(e.message ?: "Couldn't start the trim."))
        }
    }

    private fun saveToRingtonesCollection(context: Context, sourceFile: File, displayName: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$displayName.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.Audio.Media.IS_ALARM, 0)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { it.copyTo(out) }
            } ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            itemUri
        } catch (e: Exception) {
            null
        }
    }
}
