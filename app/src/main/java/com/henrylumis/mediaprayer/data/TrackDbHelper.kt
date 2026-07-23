package com.henrylumis.mediaprayer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain framework SQLite (no Room) on purpose: it keeps the dependency graph
 * small, which matters when building with a constrained on-device/mobile IDE
 * that may struggle with annotation-processor-heavy setups.
 */
class TrackDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "henrys_media_prayer.db"
        private const val DB_VERSION = 1
        const val TABLE = "tracks"

        const val COL_ID = "id"
        const val COL_URI = "uri"
        const val COL_TITLE = "title"
        const val COL_ARTIST = "artist"
        const val COL_DURATION = "duration_ms"
        const val COL_LYRICS = "lyrics"
        const val COL_FORMAT = "format"
        const val COL_ADDED_AT = "added_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_URI TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_ARTIST TEXT,
                $COL_DURATION INTEGER,
                $COL_LYRICS TEXT,
                $COL_FORMAT TEXT,
                $COL_ADDED_AT INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun toValues(track: Track): ContentValues = ContentValues().apply {
        put(COL_ID, track.id)
        put(COL_URI, track.uriString)
        put(COL_TITLE, track.title)
        put(COL_ARTIST, track.artist)
        put(COL_DURATION, track.durationMs)
        put(COL_LYRICS, track.lyrics)
        put(COL_FORMAT, track.format)
        put(COL_ADDED_AT, track.addedAt)
    }
}
