package com.henrylumis.mediaprayer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * On-device beat/onset analyser for local music.
 *
 * The analyser deliberately works from decoded PCM, not BPM tags.  It analyses
 * both the beginning and the ending of a track because a transition almost
 * always enters near the beginning of the incoming song and exits near the end
 * of the outgoing song. Each side gets its own BPM/phase confidence. The output
 * is deliberately a beat GRID, not just a single BPM number, so the transition
 * planner can align the actual phase of the overlap.
 */
internal data class BeatGrid(
    val bpmAtStart: Double,
    val bpmAtEnd: Double,
    val beatsMs: LongArray,
    val confidenceAtStart: Double,
    val confidenceAtEnd: Double,
    val tempoStability: Double
) {
    val bpm: Double get() = bpmAtStart
    val firstBeatMs: Long get() = beatsMs.firstOrNull() ?: 0L
}

internal object BeatGridAnalyzer {
    private const val SEGMENT_MS = 12_000L
    private const val FRAME = 1024
    private const val HOP = 512
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 190.0
    private const val MAX_DECODE_MS = 20_000L
    private const val DECODE_GUARD_MS = 4_000L

    fun analyze(context: Context, uri: Uri): BeatGrid? {
        val first = decodeSegment(context, uri, 0L) ?: return null
        val firstGrid = analyzeSegment(first.samples, first.sampleRate)

        val durationMs = first.durationMs
        // The decoder may start on an earlier codec sync frame. The segment
        // carries the requested logical start so the resulting beat positions
        // remain anchored to the track timeline rather than to decoder startup.
        val lastStart = (durationMs - SEGMENT_MS).coerceAtLeast(0L)
        val last = if (lastStart > 1_000L) decodeSegment(context, uri, lastStart) ?: first else first
        val lastGrid = if (last === first) firstGrid else analyzeSegment(last.samples, last.sampleRate)

        if (firstGrid == null && lastGrid == null) return null

        val merged = ArrayList<Long>()
        if (firstGrid != null) merged.addAll(firstGrid.beatsMs.map { it + first.startMs })
        if (lastGrid != null) merged.addAll(lastGrid.beatsMs.map { it + last.startMs })
        merged.sort()
        val deduped = ArrayList<Long>(merged.size)
        for (beat in merged) {
            if (deduped.isEmpty() || beat - deduped.last() >= 80L) deduped += beat
        }
        if (deduped.size < 4) return null

        val startBpm = firstGrid?.bpm ?: lastGrid!!.bpm
        val endBpm = lastGrid?.bpm ?: firstGrid!!.bpm
        val startConfidence = firstGrid?.confidence ?: 0.0
        val endConfidence = lastGrid?.confidence ?: 0.0
        val stability = if (firstGrid != null && lastGrid != null) {
            (1.0 - abs(firstGrid.bpm - lastGrid.bpm) / max(firstGrid.bpm, lastGrid.bpm)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return BeatGrid(
            bpmAtStart = startBpm,
            bpmAtEnd = endBpm,
            beatsMs = deduped.toLongArray(),
            confidenceAtStart = startConfidence,
            confidenceAtEnd = endConfidence,
            tempoStability = stability
        )
    }

    private data class PcmSegment(
        val sampleRate: Int,
        val samples: FloatArray,
        val startMs: Long,
        val durationMs: Long
    )

    private data class SegmentGrid(
        val bpm: Double,
        val beatsMs: LongArray,
        val confidence: Double
    )

    private fun decodeSegment(context: Context, uri: Uri, requestedStartMs: Long): PcmSegment? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
            else 0L

            // Respect the caller's requested logical position. For the end
            // analysis this is the final analysis window; for the beginning
            // analysis it is zero. The old implementation ignored the argument
            // and silently chose the tail for every call, which could make the
            // start and end grids identical and corrupt phase planning.
            val startMs = requestedStartMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
                .let { requested ->
                    if (durationMs > SEGMENT_MS) {
                        requested.coerceAtMost((durationMs - SEGMENT_MS).coerceAtLeast(0L))
                    } else 0L
                }
            if (startMs > 0L) extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // A codec seek can land before the requested position. Decode a
            // guard window beyond the logical segment so the requested 12s
            // window is still fully covered even when the sync point is early.
            val decodeWindowMs = SEGMENT_MS + DECODE_GUARD_MS
            val targetFrames = (sampleRate * (decodeWindowMs / 1000L).toInt()).coerceAtLeast(sampleRate)
            val out = FloatArray(targetFrames)
            var outCount = 0
            var inputDone = false
            var outputDone = false
            var firstOutputPtsUs: Long? = null
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + MAX_DECODE_MS

            while (!outputDone && System.currentTimeMillis() < deadline && outCount < out.size) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: break
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    if (info.size > 0 && firstOutputPtsUs == null && info.presentationTimeUs >= 0L) {
                        firstOutputPtsUs = info.presentationTimeUs
                    }
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else android.media.AudioFormat.ENCODING_PCM_16BIT
                        val produced = if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                            readFloatPcm(buffer, channels, out, outCount)
                        } else {
                            read16BitPcm(buffer, channels, out, outCount)
                        }
                        outCount += produced
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }

            if (outCount <= sampleRate / 2) return null

            // MediaExtractor seeks to the nearest codec sync frame, which can be
            // earlier than the requested logical start. Anchor the PCM to the
            // decoder's first presentation timestamp instead of assuming the
            // first decoded sample is exactly at requestedStartMs. Without this,
            // the end-of-track beat grid can be shifted by hundreds of ms on
            // codecs with sparse keyframes, which breaks phase alignment.
            val decodedStartMs = ((firstOutputPtsUs ?: (startMs * 1000L)) / 1000L)
                .coerceIn(0L, durationMs.coerceAtLeast(0L))

            // If the decoder started before the logical window, discard the
            // guard samples before requestedStartMs. This is critical for the
            // END analysis: analyzing the codec's earlier sync region can select
            // a beat that is several seconds before the actual transition zone.
            val trimFrames = if (startMs > decodedStartMs) {
                (((startMs - decodedStartMs).toDouble() * sampleRate) / 1000.0)
                    .toInt()
                    .coerceIn(0, outCount)
            } else 0
            val logicalCount = (outCount - trimFrames).coerceAtLeast(0)
            if (logicalCount <= sampleRate / 2) return null
            val logicalSamples = out.copyOfRange(trimFrames, trimFrames + logicalCount)
            return PcmSegment(sampleRate, logicalSamples, startMs, durationMs)
        } catch (_: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun analyzeSegment(samples: FloatArray, sampleRate: Int): SegmentGrid? {
        if (sampleRate <= 0 || samples.size < sampleRate / 2) return null
        val energies = onsetEnvelope(samples, sampleRate)
        if (energies.size < 32) return null
        val bpm = estimateBpm(energies, sampleRate) ?: return null
        val beatPeriodFrames = (60_000.0 / bpm) / (HOP * 1000.0 / sampleRate)
        val peaks = pickPeaks(energies)
        if (peaks.size < 4) return null
        val phase = bestPhase(peaks, beatPeriodFrames)
        if (phase.second < 0.22) return null

        // Reject BPM estimates that do not explain the actual spacing of the
        // detected onsets. Autocorrelation can legitimately lock to a harmonic
        // (for example half/double tempo), so phase confidence alone is not
        // enough. A stable interval pattern makes the generated beat grid much
        // safer for phase-locked crossfades; uncertain material simply falls
        // back to Smart Crossfade.
        val intervalConsistency = beatIntervalConsistency(peaks, beatPeriodFrames)
        if (intervalConsistency < 0.48) return null

        val frameMs = HOP * 1000.0 / sampleRate
        val beats = ArrayList<Long>()
        var beatFrame = phase.first
        while (beatFrame >= 0 && beatFrame < energies.size) beatFrame -= beatPeriodFrames
        beatFrame += beatPeriodFrames
        while (beatFrame < energies.size) {
            if (beatFrame >= 0) beats += max(0L, (beatFrame * frameMs).toLong())
            beatFrame += beatPeriodFrames
        }
        if (beats.size < 4) return null
        return SegmentGrid(bpm, beats.toLongArray(), (phase.second * intervalConsistency).coerceIn(0.0, 1.0))
    }


    private fun beatIntervalConsistency(peaks: IntArray, period: Double): Double {
        if (peaks.size < 5 || period <= 0.0) return 0.0
        var considered = 0
        var consistent = 0.0
        for (i in 1 until peaks.size) {
            val interval = (peaks[i] - peaks[i - 1]).toDouble()
            if (interval <= 0.0) continue
            val ratio = interval / period
            // Onset detectors often see every second beat (or an occasional
            // missing beat), so allow 0.5/1/2 beat spacing without treating it
            // as a contradiction. Very large or irregular jumps reduce trust.
            val nearest = when {
                ratio < 0.75 -> 0.5
                ratio < 1.5 -> 1.0
                ratio < 3.0 -> 2.0
                else -> 0.0
            }
            if (nearest == 0.0) continue
            val error = abs(ratio - nearest) / nearest
            considered++
            consistent += (1.0 - (error / 0.22)).coerceIn(0.0, 1.0)
        }
        return if (considered == 0) 0.0 else consistent / considered.toDouble()
    }

    private fun read16BitPcm(buffer: java.nio.ByteBuffer, channels: Int, out: FloatArray, offset: Int): Int {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = min((buffer.remaining() / 2) / channels, out.size - offset)
        for (i in 0 until frames) {
            var sum = 0f
            repeat(channels) { sum += buffer.short / 32768f }
            out[offset + i] = sum / channels
        }
        return frames
    }

    private fun readFloatPcm(buffer: java.nio.ByteBuffer, channels: Int, out: FloatArray, offset: Int): Int {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = min((buffer.remaining() / 4) / channels, out.size - offset)
        for (i in 0 until frames) {
            var sum = 0f
            repeat(channels) { sum += buffer.float }
            out[offset + i] = (sum / channels).coerceIn(-1f, 1f)
        }
        return frames
    }

    private fun onsetEnvelope(samples: FloatArray, sampleRate: Int): DoubleArray {
        // Spectral flux is substantially more reliable for music beat detection
        // than raw sample-to-sample amplitude differences: kick/snare/hat energy
        // can be detected even when the waveform itself has a lot of sustained
        // harmonic content.  A small low/mid frequency weighting keeps vocals and
        // cymbal tails from dominating the beat grid.
        val count = (samples.size - FRAME).coerceAtLeast(0) / HOP
        if (count <= 0) return DoubleArray(0)
        val env = DoubleArray(count)
        val previous = DoubleArray(FRAME / 2 + 1)
        val real = DoubleArray(FRAME)
        val imag = DoubleArray(FRAME)
        val window = DoubleArray(FRAME) { i ->
            0.5 - 0.5 * cos(2.0 * PI * i / (FRAME - 1).toDouble())
        }
        for (f in 0 until count) {
            val start = f * HOP
            for (i in 0 until FRAME) {
                real[i] = samples[start + i].toDouble() * window[i]
                imag[i] = 0.0
            }
            fft(real, imag)
            var flux = 0.0
            var bandEnergy = 0.0
            for (k in 1 until previous.size) {
                val hz = k.toDouble() * sampleRate.toDouble() / FRAME.toDouble()
                // Sample-rate independent weighting is applied approximately below;
                // the dominant onset range is deliberately limited to useful music
                // frequencies rather than hiss/ultrasonic content.
                val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
                val weighted = when {
                    hz < 45.0 -> 0.15
                    hz < 180.0 -> 1.35
                    hz < 2_500.0 -> 1.0
                    hz < 8_000.0 -> 0.45
                    else -> 0.12
                }
                flux += max(0.0, mag - previous[k]) * weighted
                bandEnergy += mag * weighted
                previous[k] = mag
            }
            env[f] = flux / max(1.0, bandEnergy * 0.02)
        }

        // Robust local normalization suppresses long-term loudness differences.
        val smooth = DoubleArray(env.size)
        for (i in env.indices) {
            var sum = 0.0
            var weight = 0.0
            for (d in -2..2) {
                val j = i + d
                if (j in env.indices) {
                    val w = exp(-abs(d) / 1.5)
                    sum += env[j] * w
                    weight += w
                }
            }
            smooth[i] = if (weight > 0.0) sum / weight else 0.0
        }
        return smooth
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= real.size) {
            val angle = -2.0 * PI / len.toDouble()
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < real.size) {
                var wr = 1.0
                var wi = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val even = i + k
                    val odd = even + half
                    val tr = real[odd] * wr - imag[odd] * wi
                    val ti = real[odd] * wi + imag[odd] * wr
                    val er = real[even]
                    val ei = imag[even]
                    real[even] = er + tr
                    imag[even] = ei + ti
                    real[odd] = er - tr
                    imag[odd] = ei - ti
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun estimateBpm(env: DoubleArray, sampleRate: Int): Double? {
        val hopSec = HOP.toDouble() / sampleRate
        val mean = env.average()
        val centered = DoubleArray(env.size) { max(0.0, env[it] - mean) }
        var bestBpm = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        for (bpm in MIN_BPM.toInt()..MAX_BPM.toInt()) {
            val lag = (60.0 / bpm / hopSec).toInt().coerceAtLeast(1)
            if (lag >= centered.size) continue
            var score = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in lag until centered.size) {
                val a = centered[i]
                val b = centered[i - lag]
                score += a * b
                normA += a * a
                normB += b * b
            }
            if (normA > 0.0 && normB > 0.0) {
                score /= sqrt(normA * normB)
                if (score > bestScore) {
                    bestScore = score
                    bestBpm = bpm.toDouble()
                }
            }
        }
        return if (bestBpm > 0.0) bestBpm else null
    }

    private fun pickPeaks(env: DoubleArray): IntArray {
        if (env.size < 5) return IntArray(0)
        val sorted = env.sorted()
        val threshold = sorted[(sorted.size * 0.72).toInt().coerceAtMost(sorted.lastIndex)]
        val peaks = ArrayList<Int>()
        for (i in 2 until env.lastIndex - 2) {
            if (env[i] >= threshold && env[i] >= env[i - 1] && env[i] >= env[i + 1]) {
                if (peaks.isEmpty() || i - peaks.last() >= 3) peaks += i
                else if (env[i] > env[peaks.last()]) peaks[peaks.lastIndex] = i
            }
        }
        return peaks.toIntArray()
    }

    private fun bestPhase(peaks: IntArray, period: Double): Pair<Double, Double> {
        val first = peaks.firstOrNull()?.toDouble() ?: return 0.0 to 0.0
        var bestPhase = first
        var bestScore = 0.0
        val step = max(1.0, period / 24.0)
        var phase = first
        while (phase < first + period) {
            var near = 0.0
            for (p in peaks) {
                val k = floor((p - phase) / period)
                val expected = phase + k * period
                val d = abs(p - expected)
                if (d <= period * 0.16) near += 1.0 - d / (period * 0.16)
            }
            val score = near / sqrt(max(1.0, peaks.size.toDouble()))
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
            phase += step
        }
        return bestPhase to (bestScore / 3.0).coerceIn(0.0, 1.0)
    }
}
