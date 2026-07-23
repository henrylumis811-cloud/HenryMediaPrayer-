package com.henrylumis.mediaprayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrackRepository private constructor(context: Context) {

    private val dbHelper = TrackDbHelper(context)

    suspend fun getAll(): List<Track> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Track>()
        val db = dbHelper.readableDatabase
        db.query(
            TrackDbHelper.TABLE, null, null, null, null, null,
            "${TrackDbHelper.COL_ADDED_AT} ASC"
        ).use { c ->
            val idxId = c.getColumnIndexOrThrow(TrackDbHelper.COL_ID)
            val idxUri = c.getColumnIndexOrThrow(TrackDbHelper.COL_URI)
            val idxTitle = c.getColumnIndexOrThrow(TrackDbHelper.COL_TITLE)
            val idxArtist = c.getColumnIndexOrThrow(TrackDbHelper.COL_ARTIST)
            val idxDuration = c.getColumnIndexOrThrow(TrackDbHelper.COL_DURATION)
            val idxLyrics = c.getColumnIndexOrThrow(TrackDbHelper.COL_LYRICS)
            val idxFormat = c.getColumnIndexOrThrow(TrackDbHelper.COL_FORMAT)
            val idxAdded = c.getColumnIndexOrThrow(TrackDbHelper.COL_ADDED_AT)
            while (c.moveToNext()) {
                list.add(
                    Track(
                        id = c.getString(idxId),
                        uriString = c.getString(idxUri),
                        title = c.getString(idxTitle) ?: "",
                        artist = c.getString(idxArtist) ?: "Unknown",
                        durationMs = c.getLong(idxDuration),
                        lyrics = c.getString(idxLyrics) ?: "",
                        format = c.getString(idxFormat) ?: "",
                        addedAt = c.getLong(idxAdded)
                    )
                )
            }
        }
        list
    }

    suspend fun upsert(track: Track) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.insertWithOnConflict(
            TrackDbHelper.TABLE, null, dbHelper.toValues(track),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(TrackDbHelper.TABLE, "${TrackDbHelper.COL_ID} = ?", arrayOf(id))
        Unit
    }

    companion object {
        @Volatile private var instance: TrackRepository? = null
        fun getInstance(context: Context): TrackRepository =
            instance ?: synchronized(this) {
                instance ?: TrackRepository(context.applicationContext).also { instance = it }
            }
    }
}
