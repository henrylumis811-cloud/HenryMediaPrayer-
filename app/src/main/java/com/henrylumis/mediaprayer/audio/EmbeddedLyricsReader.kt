package com.henrylumis.mediaprayer.audio

import android.content.Context
import android.net.Uri
import com.henrylumis.mediaprayer.data.LyricsLine
import com.henrylumis.mediaprayer.data.LyricsParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads lyrics embedded in common local audio containers.
 *
 * The old implementation only understood a narrow MP3 ID3 frame layout. That
 * missed a surprising number of real files, especially ID3v2.4 tags and files
 * whose MediaStore DATA path is unavailable. This reader accepts both a
 * content:// URI and a filesystem path and understands the most common lyric
 * metadata used by MP3, FLAC and M4A/MP4 files.
 */
object EmbeddedLyricsReader {

    data class Result(
        val syncedLines: List<LyricsLine>,
        val plainText: String?,
        val source: Source = Source.UNKNOWN,
        val diagnostic: String? = null
    )

    enum class Source {
        ID3, FLAC, MP4, OGG, LYRICS3, APEV2, UNKNOWN
    }

    fun read(context: Context, uriString: String?, path: String?): Result? {
        val bytes = readBytes(context, uriString, path)
        if (bytes == null || bytes.isEmpty()) return Result(
            emptyList(), null, Source.UNKNOWN,
            "The audio file could not be read for lyric metadata."
        )

        val result = when {
            startsWith(bytes, "ID3") -> readId3(bytes)
            startsWith(bytes, "fLaC") -> readFlac(bytes)
            looksLikeMp4(bytes) -> readMp4(bytes)
            looksLikeOgg(bytes) -> readOggVorbisComments(bytes)
            else -> null
        }
        if (result != null) return result

        val tail = readLyrics3OrApeTail(context, uriString, path)
        if (tail != null) return tail

        val format = when {
            startsWith(bytes, "ID3") -> "ID3/MP3"
            startsWith(bytes, "fLaC") -> "FLAC"
            looksLikeMp4(bytes) -> "MP4/M4A"
            looksLikeOgg(bytes) -> "Ogg/Opus"
            else -> "unknown audio format"
        }
        return Result(
            emptyList(), null, Source.UNKNOWN,
            "Checked $format metadata and the end of the file, but no readable embedded lyrics were found."
        )
    }

    /** Kept for existing callers/tests that already have a filesystem path. */
    fun read(path: String?): Result? {
        val bytes = readBytes(path)
        if (bytes == null || bytes.isEmpty()) return Result(
            emptyList(), null, Source.UNKNOWN,
            "The audio file could not be read for lyric metadata."
        )
        val result = when {
            startsWith(bytes, "ID3") -> readId3(bytes)
            startsWith(bytes, "fLaC") -> readFlac(bytes)
            looksLikeMp4(bytes) -> readMp4(bytes)
            looksLikeOgg(bytes) -> readOggVorbisComments(bytes)
            else -> null
        }
        if (result != null) return result
        return readLyrics3OrApeTail(null, null, path) ?: Result(
            emptyList(), null, Source.UNKNOWN,
            "Checked the audio metadata and the end of the file, but no readable embedded lyrics were found."
        )
    }

    private fun readBytes(context: Context, uriString: String?, path: String?): ByteArray? {
        try {
            if (!uriString.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { return readAll(it) }
            }
        } catch (_: Exception) { }
        return readBytes(path)
    }

    private fun readLyrics3OrApeTail(context: Context?, uriString: String?, path: String?): Result? {
        val tail = try {
            if (!path.isNullOrBlank()) readTail(File(path))
            else if (context != null && !uriString.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { readTail(it) }
            } else null
        } catch (_: Exception) { null } ?: return null

        return readLyrics3(tail) ?: readApeV2(tail)
    }

    private fun readTail(file: File): ByteArray {
        file.inputStream().use { return readTail(it, file.length()) }
    }

    private fun readTail(input: java.io.InputStream): ByteArray {
        // For generic content URIs we cannot reliably seek, so retain only the
        // final window with a bounded rolling buffer.
        val buffer = ByteArray(TAIL_READ_BYTES)
        var filled = 0
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n <= 0) break
            if (n >= buffer.size) {
                System.arraycopy(chunk, n - buffer.size, buffer, 0, buffer.size)
                filled = buffer.size
            } else {
                val keep = minOf(filled, buffer.size - n)
                if (keep > 0) System.arraycopy(buffer, filled - keep, buffer, 0, keep)
                System.arraycopy(chunk, 0, buffer, keep, n)
                filled = keep + n
            }
        }
        return buffer.copyOf(filled)
    }

    private fun readTail(input: java.io.InputStream, length: Long): ByteArray {
        val skip = (length - TAIL_READ_BYTES).coerceAtLeast(0L)
        var remaining = skip
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) break
            remaining -= n
        }
        return readTail(input)
    }

    private fun readBytes(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return try {
            File(path).inputStream().use { readAll(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            // Metadata lives near the front of these formats. Keep a generous
            // cap so a pathological audio file cannot consume unbounded memory.
            if (total + n > MAX_READ_BYTES) {
                out.write(buffer, 0, MAX_READ_BYTES - total)
                break
            }
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------
    // MP3 / ID3v2
    // ---------------------------------------------------------------------

    private fun readId3(bytes: ByteArray): Result? {
        if (bytes.size < 10) return null
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return null

        var tagSize = synchsafe(bytes, 6)
        if (tagSize <= 0) return null
        tagSize = minOf(tagSize, bytes.size - 10)
        var data = bytes.copyOfRange(10, 10 + tagSize)

        // ID3 header flag bit 7 means the whole tag payload was unsynchronized.
        if ((bytes[5].toInt() and 0x80) != 0) data = removeUnsynchronization(data)

        val candidates = mutableListOf<String>()
        val syncedCandidates = mutableListOf<List<LyricsLine>>()
        var pos = 0

        // v2.3/v2.4 extended headers are inside the tag payload.
        if (major >= 3 && data.size >= 4 && (bytes[5].toInt() and 0x40) != 0) {
            val extSize = if (major == 4) synchsafe(data, 0) else int32(data, 0)
            if (extSize > 0 && extSize <= data.size) pos = extSize
        }

        while (major >= 3 && pos + 10 <= data.size) {
            val id = ascii(data, pos, 4)
            if (id.all { it == '\u0000' || it == ' ' }) break

            val frameSize = if (major == 4) synchsafe(data, pos + 4) else int32(data, pos + 4)
            if (frameSize <= 0 || frameSize > data.size - (pos + 10)) break

            val flags = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
            var start = pos + 10
            var size = frameSize

            // v2.4 data-length-indicator/compression/encryption flags add frame
            // details. Lyrics frames normally don't use them, but skipping the
            // extra 4-byte DLI makes us tolerant of tags produced by taggers.
            if (major == 4 && (flags and 0x0001) != 0 && size >= 4) {
                start += 4
                size -= 4
            }
            val end = (start + size).coerceAtMost(data.size)
            if (end > start) {
                val frameData = data.copyOfRange(start, end)
                // Some taggers apply ID3 unsynchronization to individual frames
                // instead of the whole tag. Decode that form too.
                val normalizedFrame = if (major == 4 && (flags and 0x0002) != 0) {
                    removeUnsynchronization(frameData)
                } else frameData
                when (id) {
                    "USLT" -> parseUslt(normalizedFrame, 0, normalizedFrame.size)?.let(candidates::add)
                    "SYLT" -> parseSylt(normalizedFrame, 0, normalizedFrame.size)?.let(syncedCandidates::add)
                    // A few encoders use the legacy v2.2 names even inside
                    // otherwise v2.3/v2.4-looking tags. Being tolerant here is
                    // cheap and prevents a valid lyric frame from being lost.
                    "ULT " -> parseUslt(normalizedFrame, 0, normalizedFrame.size)?.let(candidates::add)
                    "SLT " -> parseSylt(normalizedFrame, 0, normalizedFrame.size)?.let(syncedCandidates::add)
                }
            }
            pos += 10 + frameSize
        }

        // ID3v2.2 uses 3-byte frame IDs and 3-byte sizes (not synchsafe).
        if (major == 2) {
            pos = 0
            while (pos + 6 <= data.size) {
                val id = ascii(data, pos, 3)
                if (id.all { it == '\u0000' || it == ' ' }) break
                val frameSize = uint24(data, pos + 3)
                if (frameSize <= 0 || frameSize > data.size - (pos + 6)) break
                val start = pos + 6
                val end = start + frameSize
                when (id) {
                    "ULT" -> parseUsltV22(data, start, end)?.let(candidates::add)
                    "SLT" -> parseSylt(data, start, end)?.let(syncedCandidates::add)
                }
                pos = end
            }
        }

        val bestSynced = syncedCandidates.maxByOrNull { it.size }
        if (!bestSynced.isNullOrEmpty()) return Result(bestSynced, null, Source.ID3, "Synced lyrics found in ID3 metadata.")

        val plain = candidates.maxByOrNull { it.length }?.trim()
        if (!plain.isNullOrBlank()) return lyricsResult(plain, Source.ID3)
        return null
    }

    private fun parseUslt(data: ByteArray, start: Int, end: Int): String? {
        if (end - start < 5) return null
        val encoding = data[start].toInt() and 0xFF
        var pos = start + 4 // encoding + ISO-8859-1 language
        val descEnd = findTerminator(data, pos, end, encoding)
        pos = (descEnd + terminatorLength(encoding)).coerceAtMost(end)
        return if (pos < end) decodeString(data, pos, end, encoding) else null
    }

    private fun parseUsltV22(data: ByteArray, start: Int, end: Int): String? {
        // v2.2 ULT layout is encoding + 3-byte language + description + text.
        return parseUslt(data, start, end)
    }

    private fun parseSylt(data: ByteArray, start: Int, end: Int): List<LyricsLine>? {
        if (end - start < 7) return null
        val encoding = data[start].toInt() and 0xFF
        val timestampFormat = data[start + 4].toInt() and 0xFF
        if (timestampFormat != 2) return null // milliseconds

        var pos = start + 6
        val descEnd = findTerminator(data, pos, end, encoding)
        pos = (descEnd + terminatorLength(encoding)).coerceAtMost(end)
        val term = terminatorLength(encoding)
        val lines = mutableListOf<LyricsLine>()

        while (pos < end) {
            val textEnd = findTerminator(data, pos, end, encoding)
            if (textEnd > end) break
            val text = decodeString(data, pos, textEnd, encoding).trim()
            pos = (textEnd + term).coerceAtMost(end)
            if (pos + 4 > end) break
            val ts = int32(data, pos).toLong() and 0xFFFFFFFFL
            pos += 4
            if (text.isNotEmpty()) lines.add(LyricsLine(ts, text))
        }
        return lines.sortedBy { it.timeMs }.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------
    // FLAC Vorbis comments
    // ---------------------------------------------------------------------

    private fun readFlac(bytes: ByteArray): Result? {
        var pos = 4
        val plain = mutableListOf<String>()
        val synced = mutableListOf<List<LyricsLine>>()

        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val last = (header and 0x80) != 0
            val type = header and 0x7F
            val size = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (size < 0 || size > bytes.size - pos) break

            if (type == 4) parseVorbisComments(bytes, pos, pos + size, plain, synced)
            pos += size
            if (last) break
        }

        val best = synced.maxByOrNull { it.size }
        if (!best.isNullOrEmpty()) return Result(best, null, Source.FLAC, "Synced lyrics found in FLAC Vorbis comments.")
        val text = plain.maxByOrNull { it.length }?.trim()
        return if (!text.isNullOrBlank()) lyricsResult(text, Source.FLAC) else null
    }

    private fun parseVorbisComments(
        data: ByteArray,
        start: Int,
        end: Int,
        plain: MutableList<String>,
        synced: MutableList<List<LyricsLine>>
    ) {
        if (end - start < 8) return
        var pos = start
        val vendorLength = littleInt(data, pos)
        pos += 4
        if (vendorLength < 0 || vendorLength > end - pos) return
        pos += vendorLength
        if (pos + 4 > end) return
        val count = littleInt(data, pos)
        pos += 4
        repeat(count.coerceAtMost(10000)) {
            if (pos + 4 > end) return@repeat
            val length = littleInt(data, pos)
            pos += 4
            if (length < 0 || length > end - pos) return@repeat
            val entry = String(data, pos, length, Charsets.UTF_8)
            pos += length
            val eq = entry.indexOf('=')
            if (eq <= 0) return@repeat
            val key = entry.substring(0, eq).trim().lowercase()
            val value = entry.substring(eq + 1).trim()
            if (key in LYRIC_KEYS && value.isNotBlank()) {
                val parsed = LyricsParser.parse(value)
                if (parsed.isNotEmpty()) synced.add(parsed) else plain.add(value)
            }
        }
    }

    // ---------------------------------------------------------------------
    // M4A / MP4 metadata. Common lyric tag is ©lyr; freeform atoms are also
    // checked because tagging applications use both styles.
    // ---------------------------------------------------------------------

    private fun readMp4(bytes: ByteArray): Result? {
        val texts = mutableListOf<String>()
        scanMp4Boxes(bytes, 0, bytes.size, texts)
        val parsed = texts.mapNotNull { LyricsParser.parse(it).takeIf { lines -> lines.isNotEmpty() } }
        val synced = parsed.maxByOrNull { it.size }
        if (!synced.isNullOrEmpty()) return Result(synced, null, Source.MP4, "Synced lyrics found in MP4/M4A metadata.")
        val text = texts
            .filter { looksLikeLyrics(it) }
            .maxByOrNull { it.length }?.trim()
        return if (!text.isNullOrBlank()) lyricsResult(text, Source.MP4) else null
    }

    private fun scanMp4Boxes(data: ByteArray, start: Int, end: Int, texts: MutableList<String>) {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = uint32(data, pos)
            val type = ascii(data, pos + 4, 4)
            var header = 8
            var size = size32
            if (size32 == 1L && pos + 16 <= end) {
                size = long64(data, pos + 8)
                header = 16
            } else if (size32 == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || size > end - pos) break
            val boxEnd = pos + size.toInt()
            val payloadStart = pos + header

            if (type == "©lyr" || type == "lyr ") {
                // The lyric atom normally contains a nested `data` atom.
                scanMp4Boxes(data, payloadStart, boxEnd, texts)
            } else if (type == "data" && boxEnd > payloadStart + 8) {
                extractMp4DataText(data, payloadStart, boxEnd, texts)
            } else if (type == "----") {
                // Freeform atoms contain mean/name/data children; the nested
                // scan will find their data text. We later filter to lyric-like
                // values by content/metadata keys where possible.
                scanMp4Boxes(data, payloadStart, boxEnd, texts)
            }

            if (type in MP4_CONTAINER_TYPES) {
                val childStart = if (type == "meta" && boxEnd - payloadStart >= 4) payloadStart + 4 else payloadStart
                scanMp4Boxes(data, childStart, boxEnd, texts)
            }
            pos = boxEnd
        }
    }

    private fun extractMp4DataText(data: ByteArray, start: Int, end: Int, texts: MutableList<String>) {
        if (end <= start) return
        var p = start
        // data atom: version/flags (4), data type + locale (4), payload.
        if (end - p >= 8) {
            val dataType = uint32(data, p + 4).toInt()
            p += 8
            if (p < end) {
                val payload = data.copyOfRange(p, end)
                val utf8 = String(payload, Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')
                val utf16 = try { String(payload, Charsets.UTF_16).trim('\u0000', ' ', '\r', '\n') } catch (_: Exception) { "" }
                val raw = when (dataType) {
                    2 -> utf8 // UTF-8
                    1, 6 -> utf16 // UTF-16 / UTF-16BE variants used by taggers
                    else -> if (looksLikeLyrics(utf8)) utf8 else utf16
                }
                if (looksLikeLyrics(raw)) texts.add(raw)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Ogg/Vorbis: collect Vorbis comment packets from the first pages.
    // ---------------------------------------------------------------------

    private fun readOggVorbisComments(bytes: ByteArray): Result? {
        var pos = 0
        val packet = ByteArrayOutputStream()
        val plain = mutableListOf<String>()
        val synced = mutableListOf<List<LyricsLine>>()
        var pages = 0
        while (pos + 27 <= bytes.size && pages++ < 30) {
            if (ascii(bytes, pos, 4) != "OggS") break
            val segCount = bytes[pos + 26].toInt() and 0xFF
            val tableEnd = pos + 27 + segCount
            if (tableEnd > bytes.size) break
            var payload = tableEnd
            for (i in 0 until segCount) {
                val len = bytes[pos + 27 + i].toInt() and 0xFF
                if (payload + len > bytes.size) return null
                packet.write(bytes, payload, len)
                payload += len
                if (len < 255) {
                    val packetBytes = packet.toByteArray()
                    parseOggPacket(packetBytes, plain, synced)
                    packet.reset()
                }
            }
            pos = payload
        }
        val best = synced.maxByOrNull { it.size }
        if (!best.isNullOrEmpty()) return Result(best, null, Source.OGG, "Synced lyrics found in Ogg/Vorbis/Opus comments.")
        val text = plain.maxByOrNull { it.length }?.trim()
        return if (!text.isNullOrBlank()) lyricsResult(text, Source.OGG) else null
    }

    private fun parseOggPacket(packet: ByteArray, plain: MutableList<String>, synced: MutableList<List<LyricsLine>>) {
        // Vorbis comment header: packet type 3 + "vorbis", then vendor length.
        if (packet.size >= 16 && packet[0].toInt() == 3 && ascii(packet, 1, 6) == "vorbis") {
            parseVorbisComments(packet, 7, packet.size, plain, synced)
            return
        }

        // Opus uses an "OpusTags" packet without the Vorbis packet-type byte.
        if (packet.size >= 16 && ascii(packet, 0, 8) == "OpusTags") {
            parseVorbisComments(packet, 8, packet.size, plain, synced)
        }
    }

    // ---------------------------------------------------------------------
    // End-of-file lyric containers. Lyrics3v2 and APEv2 are used by a number
    // of older/tagging tools and are easy to miss because their metadata is
    // stored after the audio rather than at the beginning.
    // ---------------------------------------------------------------------

    private fun readLyrics3(data: ByteArray): Result? {
        val marker = "LYRICS200".toByteArray(Charsets.ISO_8859_1)
        val end = lastIndexOf(data, marker)
        if (end < 0 || end < 10) return null
        val sizeStart = end - 10
        val sizeText = String(data, sizeStart, 6, Charsets.ISO_8859_1)
        val size = sizeText.toIntOrNull() ?: return null
        val start = end - 10 - size
        if (start < 0 || start >= end) return null
        val body = String(data, start, size, Charsets.ISO_8859_1)
        val text = Regex("""(?is)(?:LYRICS|IND|UNSYNCED|SYNCED|USER)\s*:\s*(.*?)(?=\b(?:LYRICS|IND|UNSYNCED|SYNCED|USER)\s*:|$)""")
            .findAll(body).map { it.groupValues[1].trim() }.maxByOrNull { it.length }
        return text?.takeIf { it.isNotBlank() }?.let { lyricsResult(it, Source.LYRICS3) }
    }

    private fun readApeV2(data: ByteArray): Result? {
        val marker = "APETAGEX".toByteArray(Charsets.ISO_8859_1)
        val markerPos = lastIndexOf(data, marker)
        if (markerPos < 0 || markerPos + 32 > data.size) return null
        val size = littleInt(data, markerPos + 12)
        val count = littleInt(data, markerPos + 16)
        if (size <= 32 || count !in 1..10000) return null

        // Prefer the footer marker, which is normally the last APETAGEX. The
        // tag size includes its 32-byte header/footer, so reconstruct the tag
        // start from the footer position.
        val tagStart = (markerPos + 32 - size).coerceAtLeast(0)
        var pos = tagStart + 32
        val tagEnd = (tagStart + size).coerceAtMost(data.size)
        if (pos >= tagEnd) return null

        var best: String? = null
        repeat(count) {
            if (pos + 8 > tagEnd) return@repeat
            val valueSize = littleInt(data, pos)
            pos += 8 // value size + flags
            val keyEnd = data.indexOfZero(pos, tagEnd)
            if (keyEnd < 0 || valueSize < 0 || keyEnd + 1 + valueSize > tagEnd) return@repeat
            val key = String(data, pos, keyEnd - pos, Charsets.ISO_8859_1).trim().lowercase()
            pos = keyEnd + 1
            val value = String(data, pos, valueSize, Charsets.UTF_8).trim()
            pos += valueSize
            if (key in LYRIC_KEYS && value.isNotBlank() && (best == null || value.length > best!!.length)) {
                best = value
            }
        }
        return best?.let { lyricsResult(it, Source.APEV2) }
    }

    private fun ByteArray.indexOfZero(from: Int, end: Int): Int {
        for (i in from until end) if (this[i].toInt() == 0) return i
        return -1
    }

    private fun lastIndexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > data.size) return -1
        outer@ for (i in data.size - needle.size downTo 0) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun lyricsResult(text: String, source: Source): Result {
        val parsed = LyricsParser.parse(text)
        return if (parsed.isNotEmpty()) {
            Result(parsed, null, source, "Synced lyrics found in ${source.name} metadata.")
        } else {
            Result(emptyList(), text, source, "Plain lyrics found in ${source.name} metadata.")
        }
    }

    private fun looksLikeLyrics(text: String): Boolean {
        val lines = text.lines().count { it.isNotBlank() }
        return lines >= 2 || text.contains("[00:") || text.contains("[01:") || text.contains("\n")
    }

    private fun terminatorLength(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun findTerminator(data: ByteArray, from: Int, end: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var p = from
            while (p + 1 < end) {
                if (data[p] == 0.toByte() && data[p + 1] == 0.toByte()) return p
                p += 2
            }
        } else {
            for (p in from until end) if (data[p] == 0.toByte()) return p
        }
        return end
    }

    private fun decodeString(data: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (end <= start) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return try { String(data, start, end - start, charset) } catch (_: Exception) { "" }
    }

    private fun removeUnsynchronization(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0x00.toByte()) i += 2
            else i++
        }
        return out.toByteArray()
    }

    private fun startsWith(data: ByteArray, text: String): Boolean {
        if (data.size < text.length) return false
        return ascii(data, 0, text.length) == text
    }

    private fun looksLikeMp4(data: ByteArray): Boolean = data.size >= 12 && ascii(data, 4, 4) == "ftyp"

    private fun looksLikeOgg(data: ByteArray): Boolean = data.size >= 4 && ascii(data, 0, 4) == "OggS"

    private fun ascii(data: ByteArray, start: Int, length: Int): String {
        if (start < 0 || length <= 0 || start + length > data.size) return ""
        return String(data, start, length, Charsets.ISO_8859_1)
    }

    private fun synchsafe(data: ByteArray, start: Int): Int =
        if (start + 4 <= data.size) ((data[start].toInt() and 0x7F) shl 21) or
            ((data[start + 1].toInt() and 0x7F) shl 14) or
            ((data[start + 2].toInt() and 0x7F) shl 7) or
            (data[start + 3].toInt() and 0x7F) else 0

    private fun int32(data: ByteArray, start: Int): Int =
        if (start + 4 <= data.size) ((data[start].toInt() and 0xFF) shl 24) or
            ((data[start + 1].toInt() and 0xFF) shl 16) or
            ((data[start + 2].toInt() and 0xFF) shl 8) or
            (data[start + 3].toInt() and 0xFF) else 0

    private fun uint24(data: ByteArray, start: Int): Int =
        ((data[start].toInt() and 0xFF) shl 16) or
            ((data[start + 1].toInt() and 0xFF) shl 8) or
            (data[start + 2].toInt() and 0xFF)

    private fun uint32(data: ByteArray, start: Int): Long = int32(data, start).toLong() and 0xFFFFFFFFL

    private fun long64(data: ByteArray, start: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (data[start + i].toLong() and 0xFF)
        return value
    }

    private fun littleInt(data: ByteArray, start: Int): Int =
        (data[start].toInt() and 0xFF) or
            ((data[start + 1].toInt() and 0xFF) shl 8) or
            ((data[start + 2].toInt() and 0xFF) shl 16) or
            ((data[start + 3].toInt() and 0xFF) shl 24)

    private val LYRIC_KEYS = setOf(
        "lyrics", "lyric", "unsyncedlyrics", "unsynced lyrics", "syncedlyrics",
        "synced lyrics", "lyrics3", "lrc"
    )

    private val MP4_CONTAINER_TYPES = setOf(
        "moov", "udta", "meta", "ilst", "trak", "mdia", "minf", "stbl", "edts", "dinf"
    )

    private const val MAX_READ_BYTES = 8 * 1024 * 1024
    private const val TAIL_READ_BYTES = 2 * 1024 * 1024
}
