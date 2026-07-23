package com.henrylumis.mediaprayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

class MediaPrayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyStoredTheme()
        createNotificationChannel()
    }

    private fun applyStoredTheme() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Default to dark ("the void"), matching the original web app's default phase.
        val isLight = prefs.getBoolean(KEY_LIGHT_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isLight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val PREFS = "media_prayer_prefs"
        const val KEY_LIGHT_MODE = "light_mode"
        const val CHANNEL_ID = "playback_channel"
    }
}
