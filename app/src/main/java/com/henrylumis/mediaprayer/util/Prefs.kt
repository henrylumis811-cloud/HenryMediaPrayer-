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
}
