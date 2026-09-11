package com.henrylumis.mediaprayer

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.henrylumis.mediaprayer.databinding.ActivitySplashBinding

/**
 * Short cinematic launcher sequence. The actual player remains the single
 * source of truth; this activity only introduces the app and then exits.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val openPlayer = Runnable {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = getColor(R.color.bg_base)
        window.navigationBarColor = getColor(R.color.bg_base)
        runCinematicIntro()
    }

    private fun runCinematicIntro() {
        val emblem = binding.splashEmblem
        val views = listOf(
            binding.splashRvH,
            binding.splashDedication,
            binding.splashAppName,
            binding.splashLoading
        )

        emblem.scaleX = 0.72f
        emblem.scaleY = 0.72f
        views.forEach { it.translationY = 18f }

        val emblemIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(emblem, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(emblem, View.SCALE_X, 0.72f, 1f),
                ObjectAnimator.ofFloat(emblem, View.SCALE_Y, 0.72f, 1f)
            )
            duration = 700L
            startDelay = 120L
        }

        val titleIn = fadeRise(binding.splashRvH, 520L, 520L)
        val dedicationIn = fadeRise(binding.splashDedication, 460L, 760L)
        val appNameIn = fadeRise(binding.splashAppName, 420L, 900L)
        val loadingIn = fadeRise(binding.splashLoading, 380L, 1120L)

        AnimatorSet().apply {
            playTogether(emblemIn, titleIn, dedicationIn, appNameIn, loadingIn)
            start()
        }

        // Keep the intro premium but brief: long splash screens get annoying.
        handler.postDelayed(openPlayer, 2500L)
    }

    private fun fadeRise(view: View, duration: Long, delay: Long): AnimatorSet {
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 18f, 0f)
            )
            this.duration = duration
            startDelay = delay
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(openPlayer)
        super.onDestroy()
    }
}
