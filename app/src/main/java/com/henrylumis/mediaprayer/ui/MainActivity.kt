package com.henrylumis.mediaprayer.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.MediaPrayerApp
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.databinding.ActivityMainBinding
import com.henrylumis.mediaprayer.player.PlaybackService
import com.henrylumis.mediaprayer.player.PlayerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var isFullscreen = false

    private val clockTick = object : Runnable {
        override fun run() {
            binding.clockText.text = clockFormat.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        PlayerManager.init(this)
        ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))

        if (savedInstanceState == null) {
            showFragment(PlayerFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_altar -> PlayerFragment()
                R.id.nav_library -> LibraryFragment()
                R.id.nav_verses -> LyricsFragment()
                R.id.nav_signal -> EqFragment()
                else -> PlayerFragment()
            }
            showFragment(fragment)
            true
        }

        binding.themeBtn.setOnClickListener { toggleTheme() }
        binding.fullscreenBtn.setOnClickListener { toggleFullscreen() }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun toggleTheme() {
        val prefs = getSharedPreferences(MediaPrayerApp.PREFS, Context.MODE_PRIVATE)
        val currentlyLight = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_NO
        val nowLight = !currentlyLight
        prefs.edit().putBoolean(MediaPrayerApp.KEY_LIGHT_MODE, nowLight).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (nowLight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isFullscreen) {
                window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                window.insetsController?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isFullscreen) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        clockHandler.post(clockTick)
    }

    override fun onStop() {
        super.onStop()
        clockHandler.removeCallbacks(clockTick)
    }
}
