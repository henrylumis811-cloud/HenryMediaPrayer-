package com.henrylumis.mediaprayer.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "media_prayer_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"

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
}
