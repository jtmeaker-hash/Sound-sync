package com.example.metadata

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.model.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class LocalPcmAudioAnalyzer(
    private val context: Context
) : LocalAudioAnalyzer {
    /** Inclusive BPM search window; the detector folds candidates into it. */
    var bpmRange: IntRange = 60..260

    override suspend fun analyze(track: Track): AudioAnalysisResult {
        val decoded = AudioDecoder.decodeToMonoPcm(
            context = context,
            filePathOrUri = track.filePath,
            maxDurationSeconds = 180
        ) ?: return AudioAnalysisResult()
        if (decoded.samples.size < decoded.sampleRate * 8) return AudioAnalysisResult()

        val bpm = detectBpm(decoded.samples, decoded.sampleRate)
        val key = detectKey(decoded.samples, decoded.sampleRate)
        return AudioAnalysisResult(
            bpm = bpm?.first,
            bpmConfidence = bpm?.second ?: 0.0,
            musicalKey = key?.first,
            camelotKey = key?.second,
            keyConfidence = key?.third ?: 0.0
        )
    }

    private fun detectBpm(samples: FloatArray, sampleRate: Int): Pair<Double, Double>? {
        val envelopeRate = 100
        val hop = max(1, sampleRate / envelopeRate)
        val envelope = FloatArray(samples.size / hop)
        for (i in envelope.indices) {
            val start = i * hop
            val end = min(samples.size, start + hop)
            var sum = 0.0
            for (j in start until end) sum += samples[j] * samples[j]
            envelope[i] = sqrt(sum / max(1, end - start)).toFloat()
        }
        val novelty = FloatArray(envelope.size)
        for (i in 1 until envelope.size) novelty[i] = (envelope[i] - envelope[i - 1]).coerceAtLeast(0f)
        val minLag = (envelopeRate * 60.0 / bpmRange.last).roundToInt()
        val maxLag = (envelopeRate * 60.0 / bpmRange.first).roundToInt()
        val candidates = (minLag..maxLag).map { lag ->
            var correlation = 0.0
            var count = 0
            for (i in 0 until novelty.size - lag step 2) {
                correlation += novelty[i] * novelty[i + lag]
                count++
            }
            lag to if (count == 0) 0.0 else correlation / count
        }.sortedByDescending { it.second }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)?.second ?: 0.0
        if (best.second <= 0.0) return null
        var bpm = envelopeRate * 60.0 / best.first
        // Fold half/double-time candidates into the configured window. The
        // fold is monotonic (never oscillates): each step moves strictly
        // closer to the window, so the loop terminates.
        while (bpm < bpmRange.first) bpm *= 2.0
        while (bpm > bpmRange.last) bpm /= 2.0
        val confidence = ((best.second - second) / best.second).coerceIn(0.0, 1.0)
        return bpm to confidence
    }

    private fun detectKey(samples: FloatArray, sampleRate: Int): Triple<String, String, Double>? {
        val windowSize = 4096
        val hop = 2048
        if (samples.size < windowSize * 5) return null
        val chroma = DoubleArray(12)
        val frames = ((samples.size - windowSize) / hop).coerceAtMost(120)
        for (frame in 0 until frames) {
            val start = frame * hop
            for (bin in 1 until windowSize / 2) {
                val frequency = bin * sampleRate.toDouble() / windowSize
                if (frequency !in 65.0..2000.0) continue
                var real = 0.0
                var imaginary = 0.0
                for (i in 0 until windowSize step 4) {
                    val sample = samples[start + i] * (0.5 - 0.5 * cos(2.0 * PI * i / windowSize))
                    val angle = 2.0 * PI * bin * i / windowSize
                    real += sample * cos(angle)
                    imaginary -= sample * sin(angle)
                }
                val midi = (12.0 * (ln(frequency / 440.0) / ln(2.0)) + 69.0).roundToInt()
                chroma[((midi % 12) + 12) % 12] += sqrt(real * real + imaginary * imaginary)
            }
        }
        val sum = chroma.sum()
        if (sum <= 0.0) return null
        for (i in chroma.indices) chroma[i] /= sum
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        data class Candidate(val isMinor: Boolean, val score: Double, val root: Int)
        val candidates = (0 until 12).flatMap { root ->
            listOf(
                Candidate(false, correlation(chroma, major, root), root),
                Candidate(true, correlation(chroma, minor, root), root)
            )
        }.sortedByDescending { it.score }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)?.score ?: 0.0
        val confidence = ((best.score - second) / 2.0).coerceIn(0.0, 1.0)
        val name = "${names[best.root]} ${if (best.isMinor) "minor" else "major"}"
        return Triple(name, CamelotKey.fromPitchClass(best.root, best.isMinor) ?: "", confidence)
    }

    private fun correlation(values: DoubleArray, profile: DoubleArray, shift: Int): Double {
        val meanValues = values.average()
        val meanProfile = profile.average()
        var numerator = 0.0
        var left = 0.0
        var right = 0.0
        for (i in 0 until 12) {
            val a = values[(i + shift) % 12] - meanValues
            val b = profile[i] - meanProfile
            numerator += a * b
            left += a * a
            right += b * b
        }
        return if (left > 0.0 && right > 0.0) numerator / sqrt(left * right) else 0.0
    }
}
