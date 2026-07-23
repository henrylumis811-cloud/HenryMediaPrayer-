package com.henrylumis.mediaprayer.util

import java.util.Locale
import kotlin.random.Random

object Format {
    fun time(seconds: Double): String {
        val s = if (seconds.isFinite() && seconds >= 0) seconds else 0.0
        val m = (s / 60).toInt()
        val sec = (s % 60).toInt()
        return String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun timeMs(ms: Long): String = time(ms / 1000.0)

    fun niceNameFromFile(fileName: String): String {
        val noExt = fileName.substringBeforeLast('.', fileName)
        val spaced = noExt.replace(Regex("[_\\-]+"), " ").trim()
        return spaced.ifEmpty { fileName }
    }

    fun newId(): String = "t_" + Random.nextInt(0, Int.MAX_VALUE).toString(36) + System.currentTimeMillis().toString(36)
}
