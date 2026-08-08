package com.henrylumis.mediaprayer.audio

import java.io.RandomAccessFile

/**
 * Reads an embedded ReplayGain value (in dB) from an MP3's ID3v2 tag, if
 * present -- specifically a TXXX frame named REPLAYGAIN_TRACK_GAIN, which is
 * what taggers like Mp3Gain / foobar2000 / most rippers write.
 *
 * This intentionally does NOT do its own loudness analysis (that would mean
 * fully decoding every track, which is too heavy to do live on a phone).
 * Tracks without an embedded tag simply aren't gain-adjusted from this path --
 * see LoudnessNormalizer for the live fallback used in that case.
 */
object ReplayGainReader {

    /** Returns the track gain in dB, or null if no usable tag was found. */
    fun readTrackGainDb(path: String?): Double? {
        if (path.isNullOrBlank()) return null
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val header = ByteArray(10)
                if (raf.read(header) != 10) return null
                if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                    return null
                }
                val tagSize = synchsafeToInt(header[6], header[7], header[8], header[9])
                val tagBytes = ByteArray(tagSize.coerceAtMost(2_000_000))
                raf.read(tagBytes)
                findReplayGainFrame(tagBytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun synchsafeToInt(b1: Byte, b2: Byte, b3: Byte, b4: Byte): Int =
        ((b1.toInt() and 0x7F) shl 21) or
            ((b2.toInt() and 0x7F) shl 14) or
            ((b3.toInt() and 0x7F) shl 7) or
            (b4.toInt() and 0x7F)

    private fun findReplayGainFrame(data: ByteArray): Double? {
        var i = 0
        while (i + 10 < data.size) {
            val frameId = String(data, i, 4, Charsets.ISO_8859_1)
            if (frameId == "\u0000\u0000\u0000\u0000") break // padding reached
            val frameSize = ((data[i + 4].toInt() and 0xFF) shl 24) or
                ((data[i + 5].toInt() and 0xFF) shl 16) or
                ((data[i + 6].toInt() and 0xFF) shl 8) or
                (data[i + 7].toInt() and 0xFF)
            val frameStart = i + 10
            val frameEnd = (frameStart + frameSize).coerceAtMost(data.size)
            if (frameId == "TXXX" && frameEnd > frameStart) {
                val content = String(data, frameStart, frameEnd - frameStart, Charsets.ISO_8859_1)
                if (content.contains("REPLAYGAIN_TRACK_GAIN", ignoreCase = true)) {
                    val match = Regex("(-?\\d+\\.?\\d*)\\s*dB", RegexOption.IGNORE_CASE).find(content)
                    match?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
                }
            }
            if (frameSize <= 0) break
            i = frameEnd
        }
        return null
    }
}
