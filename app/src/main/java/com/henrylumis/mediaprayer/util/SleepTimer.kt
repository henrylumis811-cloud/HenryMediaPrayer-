package com.henrylumis.mediaprayer.util

import android.os.CountDownTimer

class SleepTimer {
    var onFire: (() -> Unit)? = null
    private var timer: CountDownTimer? = null
    var remainingMs: Long = 0L
        private set

    fun start(minutes: Int) {
        cancel()
        val totalMs = minutes * 60_000L
        timer = object : CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMs = millisUntilFinished
            }
            override fun onFinish() {
                remainingMs = 0
                onFire?.invoke()
            }
        }.start()
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        remainingMs = 0
    }

    fun isRunning(): Boolean = timer != null
}
