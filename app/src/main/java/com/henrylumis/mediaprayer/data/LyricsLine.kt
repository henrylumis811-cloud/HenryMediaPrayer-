package com.henrylumis.mediaprayer.data

data class LyricsLine(val timeMs: Long, val text: String)

/**
 * Minimal LRC (synced lyrics) file parser. Looks for lines like:
 *   [00:12.34]Some lyric text
 * Multiple timestamps on one line (e.g. [00:12.34][00:45.10]text) are
 * supported since some LRC files repeat a chorus line that way.
 */
object LyricsParser {

    private val LINE_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricsLine> {
        var offsetMs = 0L
        val lines = mutableListOf<LyricsLine>()
        raw.lineSequence().forEach { originalLine ->
            val line = originalLine.replace("\uFEFF", "").trimStart()
            val offsetMatch = Regex("""\[offset:([+-]?\d+)\]""", RegexOption.IGNORE_CASE).find(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }
            val matches = LINE_REGEX.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            // Ignore standard LRC metadata tags such as [ar:], [ti:], [al:]
            // and [by:] when they appear before lyric text.
            val metadataOnly = Regex("\\[(?:ar|ti|al|by|re|ve|length):.*?\\]", RegexOption.IGNORE_CASE)
            if (matches.size == 1 && metadataOnly.matches(line)) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty() || text.startsWith("//")) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                if (sec !in 0..59) return@forEach
                val fracStr = m.groupValues[3]
                val fracMs = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                val timeMs = ((min * 60 + sec) * 1000 + fracMs + offsetMs).coerceAtLeast(0L)
                lines.add(LyricsLine(timeMs, text))
            }
        }
        return lines.distinctBy { it.timeMs to it.text }.sortedBy { it.timeMs }
    }

    /** Given a song's audio file path, look for a same-named .lrc file beside it. */
    fun findLrcPath(audioDataPath: String?): String? {
        if (audioDataPath.isNullOrBlank()) return null
        val dot = audioDataPath.lastIndexOf('.')
        val base = if (dot > 0) audioDataPath.substring(0, dot) else audioDataPath
        return "$base.lrc"
    }
}
