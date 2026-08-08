package com.henrylumis.mediaprayer.util

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "media_prayer_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"
    private const val KEY_BG_URI = "bg_photo_uri"
    private const val KEY_BG_OPACITY = "bg_photo_opacity" // 0-100
    private const val KEY_SORT_MODE = "library_sort_mode"
    private const val KEY_VISUALIZER_STYLE = "visualizer_style"
    private const val KEY_SAVED_QUEUE_IDS = "saved_queue_ids"
    private const val KEY_SAVED_QUEUE_INDEX = "saved_queue_index"
    private const val KEY_SAVED_QUEUE_POSITION = "saved_queue_position_ms"

    fun getNightMode(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_YES)
    }

    fun setNightMode(context: Context, mode: Int) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun isDark(context: Context): Boolean =
        getNightMode(context) != AppCompatDelegate.MODE_NIGHT_NO

    fun getBackgroundUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BG_URI, null) ?: return null
        return Uri.parse(raw)
    }

    fun setBackgroundUri(context: Context, uri: Uri?) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BG_URI, uri?.toString()).apply()
    }

    fun getBackgroundOpacity(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BG_OPACITY, 100)
    }

    fun setBackgroundOpacity(context: Context, opacity: Int) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BG_OPACITY, opacity.coerceIn(0, 100)).apply()
    }

    fun getSortMode(context: Context): String? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SORT_MODE, null)
    }

    fun setSortMode(context: Context, modeName: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SORT_MODE, modeName).apply()
    }

    fun getVisualizerStyle(context: Context): String? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VISUALIZER_STYLE, null)
    }

    fun setVisualizerStyle(context: Context, styleName: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_VISUALIZER_STYLE, styleName).apply()
    }

    data class SavedQueueState(val songIds: List<Long>, val index: Int, val positionMs: Long)

    /** Persists the playback queue (by song id), current index, and exact
     *  position so playback can resume exactly where it left off after the
     *  app/process is closed and reopened. */
    fun setSavedQueue(context: Context, songIds: List<Long>, index: Int, positionMs: Long) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SAVED_QUEUE_IDS, songIds.joinToString(","))
            .putInt(KEY_SAVED_QUEUE_INDEX, index)
            .putLong(KEY_SAVED_QUEUE_POSITION, positionMs)
            .apply()
    }

    fun getSavedQueue(context: Context): SavedQueueState? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SAVED_QUEUE_IDS, null) ?: return null
        val ids = raw.split(",").mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        val index = prefs.getInt(KEY_SAVED_QUEUE_INDEX, 0)
        val position = prefs.getLong(KEY_SAVED_QUEUE_POSITION, 0L)
        return SavedQueueState(ids, index, position)
    }
}
