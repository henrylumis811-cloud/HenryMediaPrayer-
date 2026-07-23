package com.henrylumis.mediaprayer.util

/** A single lyric line; [timeSec] is null for plain, untimed lyrics. */
data class LyricLine(val timeSec: Double?, val text: String)

object LyricsParser {

    private val TS_REGEX = Regex("""^\[(\d+):(\d{2}(?:\.\d+)?)]\s*(.*)$""")

    /**
     * Accepts either plain lyrics (one line per row) or lines prefixed with a
     * `[mm:ss.xx]` timestamp. If at least one timed line is found, only timed
     * lines are kept (sorted by time), matching the original web app's rule.
     */
    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrEmpty()) return emptyList()
        val lines = raw.split(Regex("\r\n|\n|\r"))
        val out = mutableListOf<LyricLine>()
        var anyTimed = false
        for (line in lines) {
            val m = TS_REGEX.find(line)
            if (m != null) {
                anyTimed = true
                val minutes = m.groupValues[1].toDouble()
                val seconds = m.groupValues[2].toDouble()
                out.add(LyricLine(minutes * 60 + seconds, m.groupValues[3]))
            } else if (line.trim().isNotEmpty()) {
                out.add(LyricLine(null, line.trim()))
            }
        }
        return if (!anyTimed) {
            out.map { LyricLine(null, it.text) }
        } else {
            out.filter { it.timeSec != null }.sortedBy { it.timeSec }
        }
    }

    /** Index of the last line whose timestamp has passed, or -1. Requires timed lines. */
    fun activeIndex(lines: List<LyricLine>, positionSec: Double): Int {
        if (lines.isEmpty() || lines[0].timeSec == null) return -1
        var idx = -1
        for (i in lines.indices) {
            val t = lines[i].timeSec ?: break
            if (t <= positionSec) idx = i else break
        }
        return idx
    }
}
