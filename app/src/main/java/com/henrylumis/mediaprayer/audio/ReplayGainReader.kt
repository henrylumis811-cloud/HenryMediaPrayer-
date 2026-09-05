package com.henrylumis.mediaprayer.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads embedded ReplayGain track gain from common local-audio metadata.
 * This is metadata-only: no full-track loudness analysis is performed.
 *
 * Supported sources:
 *  - MP3 ID3v2 TXXX / RVA2 replaygain tags
 *  - FLAC Vorbis comments
 *  - Ogg Vorbis / Opus comments
 *  - MP4/M4A freeform/standard metadata text
 *  - APEv2 key/value tags at the end of the file
 */
object ReplayGainReader {
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private val gainRegex = Regex("(-?\\d+(?:\\.\\d+)?)\\s*dB", RegexOption.IGNORE_CASE)
    private val gainKeyRegex = Regex("(?:^|\\b)(?:REPLAYGAIN_)?TRACK_GAIN(?:$|\\b)", RegexOption.IGNORE_CASE)

    fun readTrackGainDb(path: String?): Double? {
        if (path.isNullOrBlank()) return null
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val size = raf.length()
                val headSize = minOf(size, MAX_METADATA_BYTES.toLong()).toInt()
                val head = ByteArray(headSize)
                raf.seek(0)
                raf.readFully(head)
                readFromHead(head) ?: readFromTail(raf, size)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFromHead(data: ByteArray): Double? {
        if (data.size >= 3 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            readId3(data)?.let { return it }
        }
        if (data.size >= 4 && data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() && data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()) {
            readFlac(data)?.let { return it }
        }
        if (data.size >= 4 && String(data, 0, 4, Charsets.ISO_8859_1) == "OggS") {
            readOgg(data)?.let { return it }
        }
        if (data.size >= 8 && looksLikeMp4(data)) {
            readMp4(data)?.let { return it }
        }
        return null
    }

    private fun readId3(data: ByteArray): Double? {
        if (data.size < 10) return null
        val major = data[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val tagSize = synchsafe(data, 6)
        val end = minOf(data.size, 10 + tagSize)
        var pos = 10
        while (pos + (if (major == 2) 6 else 10) <= end) {
            val idLen = if (major == 2) 3 else 4
            val frameHeader = if (major == 2) 6 else 10
            val id = String(data, pos, idLen, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' || it == ' ' }) break
            val frameSize = if (major == 2) {
                ((data[pos + 3].toInt() and 0xFF) shl 16) or ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
            } else if (major == 4) {
                synchsafe(data, pos + 4)
            } else {
                ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            }
            if (frameSize <= 0) break
            val start = pos + frameHeader
            val finish = minOf(end, start + frameSize)
            if (start < finish) {
                if ((major == 2 && id == "TXX") || (major >= 3 && id == "TXXX")) {
                    val text = decodeId3Text(data, start, finish)
                    if (gainKeyRegex.containsMatchIn(text)) parseGain(text)?.let { return it }
                } else if ((major >= 3 && id == "RVA2") || (major == 2 && id == "RVA")) {
                    // RVA2 is binary and complex; many taggers still include a textual TXXX value.
                    // We intentionally leave binary RVA2 alone rather than guessing its channel math.
                }
            }
            pos = finish
        }
        return null
    }

    private fun decodeId3Text(data: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        val encoding = data[start].toInt() and 0xFF
        val bodyStart = start + 1
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_16
        }
        return String(data, bodyStart.coerceAtMost(end), end - bodyStart.coerceAtMost(end), charset)
    }

    private fun readFlac(data: ByteArray): Double? {
        var pos = 4
        while (pos + 4 <= data.size) {
            val last = (data[pos].toInt() and 0x80) != 0
            val type = data[pos].toInt() and 0x7F
            val len = ((data[pos + 1].toInt() and 0xFF) shl 16) or ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            val end = minOf(data.size, start + len)
            if (type == 4 && start + 4 <= end) {
                var p = start + 4 // vendor length
                if (p <= end) {
                    val vendorLen = readLeInt(data, start)
                    p = (start + 4 + vendorLen).coerceAtMost(end)
                    if (p + 4 <= end) {
                        val count = readLeInt(data, p); p += 4
                        repeat(count.coerceAtMost(10000)) {
                            if (p + 4 > end) return@repeat
                            val n = readLeInt(data, p); p += 4
                            if (n < 0 || p + n > end) return@repeat
                            val s = String(data, p, n, Charsets.UTF_8); p += n
                            if (gainKeyRegex.containsMatchIn(s.substringBefore('='))) parseGain(s)?.let { return it }
                        }
                    }
                }
            }
            pos = end
            if (last) break
        }
        return null
    }

    private fun readOgg(data: ByteArray): Double? {
        // Comment packets are enough for ReplayGain. We scan page payloads for
        // Vorbis/Opus comment signatures and then parse their little-endian KV list.
        var pos = 0
        val packet = ArrayList<Byte>()
        while (pos + 27 <= data.size) {
            if (String(data, pos, 4, Charsets.ISO_8859_1) != "OggS") break
            val segs = data[pos + 26].toInt() and 0xFF
            if (pos + 27 + segs > data.size) break
            val tableStart = pos + 27
            val payloadStart = tableStart + segs
            var payloadPos = payloadStart
            for (i in 0 until segs) {
                val n = data[tableStart + i].toInt() and 0xFF
                if (payloadPos + n > data.size) return null
                for (j in 0 until n) packet.add(data[payloadPos + j])
                payloadPos += n
                if (n < 255) {
                    val bytes = packet.toByteArray()
                    if (bytes.size >= 8 && (String(bytes, 0, 7, Charsets.ISO_8859_1) == "vorbis" || String(bytes, 0, minOf(8, bytes.size), Charsets.ISO_8859_1).startsWith("OpusTags"))) {
                        parseVorbisPacket(bytes)?.let { return it }
                    }
                    packet.clear()
                }
            }
            pos = payloadPos
        }
        return null
    }

    private fun parseVorbisPacket(bytes: ByteArray): Double? {
        val sig = when {
            String(bytes, 0, minOf(7, bytes.size), Charsets.ISO_8859_1) == "vorbis" -> 7
            String(bytes, 0, minOf(8, bytes.size), Charsets.ISO_8859_1) == "OpusTags" -> 8
            else -> return null
        }
        var p = sig
        if (p + 4 > bytes.size) return null
        val vendorLen = readLeInt(bytes, p); p += 4 + vendorLen
        if (p + 4 > bytes.size) return null
        val count = readLeInt(bytes, p); p += 4
        repeat(count.coerceAtMost(10000)) {
            if (p + 4 > bytes.size) return@repeat
            val n = readLeInt(bytes, p); p += 4
            if (n < 0 || p + n > bytes.size) return@repeat
            val s = String(bytes, p, n, Charsets.UTF_8); p += n
            if (gainKeyRegex.containsMatchIn(s.substringBefore('='))) parseGain(s)?.let { return it }
        }
        return null
    }

    private fun looksLikeMp4(data: ByteArray): Boolean =
        data.size >= 8 && String(data, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "free", "wide")

    private fun readMp4(data: ByteArray): Double? {
        fun walk(start: Int, end: Int): Double? {
            var p = start
            while (p + 8 <= end && p + 8 <= data.size) {
                val size = readBeInt(data, p)
                val type = String(data, p + 4, 4, Charsets.ISO_8859_1)
                val atomEnd = if (size == 1 && p + 16 <= end) minOf(end, p + readBeLong(data, p + 8).toInt()) else minOf(end, p + size)
                if (atomEnd <= p + 8) break
                val childStart = when (type) {
                    "meta" -> minOf(atomEnd, p + 12)
                    else -> p + 8
                }
                if (type == "----" || type == "©lyr" || type == "lyr ") {
                    val text = String(data, childStart, atomEnd - childStart, Charsets.UTF_8)
                    if (gainKeyRegex.containsMatchIn(text) || text.contains("REPLAYGAIN", true)) parseGain(text)?.let { return it }
                }
                if (type in setOf("moov", "udta", "meta", "ilst", "----", "free", "trak", "mdia", "minf", "stbl")) {
                    walk(childStart, atomEnd)?.let { return it }
                }
                p = atomEnd
            }
            return null
        }
        return walk(0, data.size)
    }

    private fun readFromTail(raf: RandomAccessFile, size: Long): Double? {
        val bytes = minOf(size, MAX_METADATA_BYTES.toLong()).toInt()
        if (bytes <= 0) return null
        raf.seek(size - bytes)
        val tail = ByteArray(bytes)
        raf.readFully(tail)
        val text = String(tail, Charsets.ISO_8859_1)
        // APEv2 key/value text is UTF-8/UTF-16 but the ReplayGain number is ASCII,
        // so scanning the bounded tail is safe and avoids false positives elsewhere.
        Regex("REPLAYGAIN_TRACK_GAIN\\s*[\\u0000=\\u0001\\s:]*(-?\\d+(?:\\.\\d+)?)\\s*dB", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
        return parseGain(text)
    }

    private fun parseGain(text: String): Double? = gainRegex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun synchsafe(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0x7F) shl 21) or ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or (data[offset + 3].toInt() and 0x7F)

    private fun readLeInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readBeInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

    private fun readBeLong(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }
}
