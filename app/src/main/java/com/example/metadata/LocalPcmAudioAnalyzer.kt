package com.example.metadata

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.model.Track
import kotlin.math.PI
import kotlin.math.abs
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
        val durationSec = track.durationSeconds
        val sampleWindows = mutableListOf<AudioDecoder.DecodedAudioData>()

        // Sample up to 3 representative windows across the track body to bypass intros/outros
        if (durationSec >= 60) {
            val offsets = listOf(
                max(10, (durationSec * 0.22).toInt()),
                max(25, (durationSec * 0.50).toInt()),
                max(40, (durationSec * 0.72).toInt())
            )
            for (offset in offsets) {
                val decoded = AudioDecoder.decodeToMonoPcm(
                    context = context,
                    filePathOrUri = track.filePath,
                    maxDurationSeconds = 18,
                    startOffsetSeconds = offset
                )
                if (decoded != null && decoded.samples.size >= decoded.sampleRate * 6) {
                    sampleWindows.add(decoded)
                }
            }
        }

        // Fallback to start of track if offsets yielded nothing or track is short
        if (sampleWindows.isEmpty()) {
            val fallback = AudioDecoder.decodeToMonoPcm(
                context = context,
                filePathOrUri = track.filePath,
                maxDurationSeconds = 45,
                startOffsetSeconds = 0
            )
            if (fallback != null && fallback.samples.size >= fallback.sampleRate * 6) {
                sampleWindows.add(fallback)
            }
        }

        if (sampleWindows.isEmpty()) {
            return AudioAnalysisResult()
        }

        // Multi-window analysis: calculate BPM per window and aggregate chroma
        val windowBpmResults = mutableListOf<Pair<Double, Double>>()
        val accumulatedChroma = DoubleArray(12)
        var chromaFramesTotal = 0

        for (w in sampleWindows) {
            val bpmRes = detectBpm(w.samples, w.sampleRate)
            if (bpmRes != null && bpmRes.second > 0.10) {
                windowBpmResults.add(bpmRes)
            }
            val frames = accumulateChroma(w.samples, w.sampleRate, accumulatedChroma)
            chromaFramesTotal += frames
        }

        // Multi-window consensus for BPM
        val finalBpm: Pair<Double, Double>? = if (windowBpmResults.isNotEmpty()) {
            val clusters = mutableListOf<MutableList<Pair<Double, Double>>>()
            for (res in windowBpmResults) {
                var foundCluster = false
                for (cluster in clusters) {
                    val clusterMean = cluster.map { it.first }.average()
                    if (abs(clusterMean - res.first) <= 1.5) {
                        cluster.add(res)
                        foundCluster = true
                        break
                    }
                }
                if (!foundCluster) {
                    clusters.add(mutableListOf(res))
                }
            }
            val winningCluster = clusters.maxByOrNull { c -> c.sumOf { it.second } } ?: windowBpmResults
            val weightedBpmSum = winningCluster.sumOf { it.first * it.second }
            val weightSum = winningCluster.sumOf { it.second }.coerceAtLeast(0.001)
            val consensusBpm = (weightedBpmSum / weightSum).let { (it * 10.0).roundToInt() / 10.0 }
            val agreementRatio = (winningCluster.size.toDouble() / windowBpmResults.size.toDouble()).coerceIn(0.5, 1.0)
            val avgConf = (winningCluster.map { it.second }.average() * agreementRatio).coerceIn(0.0, 1.0)
            consensusBpm to avgConf
        } else {
            val primary = sampleWindows.first()
            detectBpm(primary.samples, primary.sampleRate)
        }

        // Musical key from aggregated chroma across all windows
        val finalKey = if (chromaFramesTotal > 0) {
            resolveKeyFromChroma(accumulatedChroma)
        } else {
            val primary = sampleWindows.first()
            detectKey(primary.samples, primary.sampleRate)
        }

        return AudioAnalysisResult(
            bpm = finalBpm?.first,
            bpmConfidence = finalBpm?.second ?: 0.0,
            musicalKey = finalKey?.first,
            camelotKey = finalKey?.second,
            keyConfidence = finalKey?.third ?: 0.0
        )
    }

    /**
     * Rock-solid single-window BPM detection with kick-transient tempo-octave disambiguation.
     * Prevents Hardstyle/Hard Dance (150-180 BPM) from collapsing to half-time (75-90 BPM)
     * while preserving genuine slow-tempo music (70-90 BPM).
     */
    fun detectBpm(samples: FloatArray, sampleRate: Int): Pair<Double, Double>? {
        if (samples.size < sampleRate * 4) return null

        val lowPassSamples = applyLowPass180Hz(samples, sampleRate)
        val envelopeRate = 200 // 5ms time resolution for precise lag detection
        val hop = max(1, sampleRate / envelopeRate)
        val numFrames = samples.size / hop
        if (numFrames < 300) return null

        val envFull = FloatArray(numFrames)
        val envLow = FloatArray(numFrames)

        for (i in 0 until numFrames) {
            val start = i * hop
            val end = min(samples.size, start + hop)
            var sumF = 0.0
            var sumL = 0.0
            for (j in start until end) {
                sumF += samples[j] * samples[j]
                sumL += lowPassSamples[j] * lowPassSamples[j]
            }
            val count = max(1, end - start)
            envFull[i] = sqrt(sumF / count).toFloat()
            envLow[i] = sqrt(sumL / count).toFloat()
        }

        // Onset novelty curves (half-wave rectified first difference)
        val noveltyFull = FloatArray(numFrames)
        val noveltyLow = FloatArray(numFrames)
        for (i in 1 until numFrames) {
            noveltyFull[i] = (envFull[i] - envFull[i - 1]).coerceAtLeast(0f)
            noveltyLow[i] = (envLow[i] - envLow[i - 1]).coerceAtLeast(0f)
        }

        normalizeCurve(noveltyFull)
        normalizeCurve(noveltyLow)

        val minLag = (envelopeRate * 60.0 / 240.0).roundToInt().coerceAtLeast(35) // ~240 BPM = 50 lags
        val maxLag = (envelopeRate * 60.0 / 55.0).roundToInt().coerceAtMost(numFrames / 2) // ~55 BPM = 218 lags

        val meanF = noveltyFull.average().toFloat()
        val meanL = noveltyLow.average().toFloat()
        val zeroMeanF = FloatArray(numFrames) { noveltyFull[it] - meanF }
        val zeroMeanL = FloatArray(numFrames) { noveltyLow[it] - meanL }

        var varF = 0.0
        var varL = 0.0
        for (i in 0 until numFrames) {
            varF += zeroMeanF[i] * zeroMeanF[i]
            varL += zeroMeanL[i] * zeroMeanL[i]
        }
        val denom = (0.65 * varL + 0.35 * varF).coerceAtLeast(1e-6)

        val autoCorr = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var corrF = 0.0
            var corrL = 0.0
            val count = numFrames - lag
            for (i in 0 until count) {
                corrF += zeroMeanF[i] * zeroMeanF[i + lag]
                corrL += zeroMeanL[i] * zeroMeanL[i + lag]
            }
            autoCorr[lag] = ((0.65 * corrL + 0.35 * corrF) / denom).coerceAtLeast(0.0)
        }

        data class Peak(val lag: Int, val value: Double)
        val localPeaks = mutableListOf<Peak>()
        for (lag in (minLag + 1) until maxLag) {
            if (autoCorr[lag] > autoCorr[lag - 1] && autoCorr[lag] > autoCorr[lag + 1] && autoCorr[lag] > 0.0) {
                localPeaks.add(Peak(lag, autoCorr[lag]))
            }
        }
        if (localPeaks.isEmpty()) return null

        val topPeak = localPeaks.maxByOrNull { it.value } ?: return null
        var chosenLag = topPeak.lag
        val primaryBpm = envelopeRate * 60.0 / chosenLag

        // ── Tempo-Octave Disambiguation ──
        // Case 1: Primary candidate is in half-time/third-time zone (< 100 BPM)
        // In Hardstyle/Hard Dance/EDM (130..196 BPM), autocorrelation peaks at lag 2T or 3T can rival lag T.
        // We disambiguate by examining kick transient intervals and grid pulse density.
        if (primaryBpm < 100.0) {
            val kickIntervalStats = analyzeKickIntervals(noveltyLow, envelopeRate)

            // Check 2x tempo (half lag)
            val halfLag = (chosenLag / 2.0).roundToInt()
            if (halfLag in minLag..maxLag) {
                val searchRange = (halfLag - 3)..(halfLag + 3)
                val peakHalf = localPeaks.filter { it.lag in searchRange }.maxByOrNull { it.value }
                    ?: Peak(halfLag, autoCorr[halfLag])

                val peakRatio = if (topPeak.value > 0.0) peakHalf.value / topPeak.value else 0.0
                val expectedDoubleInterval = 60.0 / (primaryBpm * 2.0)
                val expectedHalfInterval = 60.0 / primaryBpm

                val countDoubleBpmKicks = kickIntervalStats.countInRange(expectedDoubleInterval * 0.82, expectedDoubleInterval * 1.18)
                val countHalfBpmKicks = kickIntervalStats.countInRange(expectedHalfInterval * 0.82, expectedHalfInterval * 1.18)
                val doubleBpmGridConsistency = evaluateGridConsistency(noveltyLow, halfLag)

                val isDoubleTimeHardDance = (peakRatio >= 0.48 && (
                    (countDoubleBpmKicks >= countHalfBpmKicks && countDoubleBpmKicks >= 4) ||
                    (doubleBpmGridConsistency >= 0.55) ||
                    (peakRatio >= 0.78)
                ))

                if (isDoubleTimeHardDance) {
                    chosenLag = peakHalf.lag
                }
            }

            // Check 3x tempo (third lag, e.g. 56.6 BPM -> 170 BPM)
            if (chosenLag == topPeak.lag) {
                val thirdLag = (chosenLag / 3.0).roundToInt()
                if (thirdLag in minLag..maxLag) {
                    val searchRange = (thirdLag - 3)..(thirdLag + 3)
                    val peakThird = localPeaks.filter { it.lag in searchRange }.maxByOrNull { it.value }
                        ?: Peak(thirdLag, autoCorr[thirdLag])

                    val peakRatio = if (topPeak.value > 0.0) peakThird.value / topPeak.value else 0.0
                    val expectedTripleInterval = 60.0 / (primaryBpm * 3.0)
                    val countTripleBpmKicks = kickIntervalStats.countInRange(expectedTripleInterval * 0.82, expectedTripleInterval * 1.18)
                    val tripleBpmGridConsistency = evaluateGridConsistency(noveltyLow, thirdLag)

                    val isTripleTimeHardDance = (peakRatio >= 0.45 && (
                        countTripleBpmKicks >= 4 || tripleBpmGridConsistency >= 0.55
                    ))

                    if (isTripleTimeHardDance) {
                        chosenLag = peakThird.lag
                    }
                }
            }
        } else if (primaryBpm in 130.0..196.0) {
            // Case 2: Primary candidate is fast (130..196 BPM), verify it is not slow 65..98 BPM music with fast percussion
            val doubleLag = chosenLag * 2
            if (doubleLag in minLag..maxLag) {
                val peakDouble = localPeaks.filter { it.lag in (doubleLag - 3)..(doubleLag + 3) }.maxByOrNull { it.value }
                if (peakDouble != null && peakDouble.value > topPeak.value * 0.70) {
                    val kickIntervalStats = analyzeKickIntervals(noveltyLow, envelopeRate)
                    val expectedDoubleInterval = 60.0 / primaryBpm
                    val expectedHalfInterval = 60.0 / (primaryBpm / 2.0)
                    val countDoubleKicks = kickIntervalStats.countInRange(expectedDoubleInterval * 0.82, expectedDoubleInterval * 1.18)
                    val countHalfKicks = kickIntervalStats.countInRange(expectedHalfInterval * 0.82, expectedHalfInterval * 1.18)
                    val gridConsistency = evaluateGridConsistency(noveltyLow, chosenLag)

                    if (countHalfKicks > countDoubleKicks * 2 && gridConsistency < 0.35) {
                        chosenLag = peakDouble.lag
                    }
                }
            }
        }

        // Sub-sample parabolic interpolation around chosenLag
        val refinedLag = if (chosenLag > minLag && chosenLag < maxLag) {
            val y0 = autoCorr[chosenLag - 1]
            val y1 = autoCorr[chosenLag]
            val y2 = autoCorr[chosenLag + 1]
            val denom = 2.0 * (2.0 * y1 - y0 - y2)
            if (abs(denom) > 1e-6) {
                chosenLag + ((y0 - y2) / denom).coerceIn(-0.5, 0.5)
            } else chosenLag.toDouble()
        } else chosenLag.toDouble()

        var finalBpm = envelopeRate * 60.0 / refinedLag
        while (finalBpm < bpmRange.first) finalBpm *= 2.0
        while (finalBpm > bpmRange.last) finalBpm /= 2.0

        val maxCorr = autoCorr.maxOrNull() ?: 1.0
        val chosenCorr = autoCorr[chosenLag]
        val secondHighest = localPeaks.filter { abs(it.lag - chosenLag) > 5 }.maxByOrNull { it.value }?.value ?: 0.0
        val peakProminence = if (chosenCorr > 0.0) ((chosenCorr - secondHighest) / chosenCorr).coerceIn(0.0, 1.0) else 0.5
        val confidence = (0.5 * (chosenCorr / maxCorr.coerceAtLeast(1e-6)) + 0.5 * peakProminence).coerceIn(0.1, 1.0)

        val roundedBpm = (finalBpm * 10.0).roundToInt() / 10.0
        return roundedBpm to confidence
    }

    /**
     * 2nd-order Butterworth low-pass filter at cutoff = 180 Hz to isolate kick drums.
     */
    private fun applyLowPass180Hz(samples: FloatArray, sampleRate: Int): FloatArray {
        val cutoff = 180.0
        val ff = cutoff / sampleRate.toDouble()
        val ita = 1.0 / kotlin.math.tan(PI * ff)
        val q = sqrt(2.0)
        val b0 = (1.0 / (1.0 + q * ita + ita * ita)).toFloat()
        val b1 = (2.0 * b0).toFloat()
        val b2 = b0
        val a1 = (-2.0 * (ita * ita - 1.0) * b0).toFloat()
        val a2 = ((1.0 - q * ita + ita * ita) * b0).toFloat()

        val out = FloatArray(samples.size)
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in samples.indices) {
            val x0 = samples[i]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
        return out
    }

    private fun normalizeCurve(curve: FloatArray) {
        var sum = 0.0
        for (v in curve) sum += v
        val mean = sum / curve.size
        var variance = 0.0
        for (v in curve) {
            val diff = v - mean
            variance += diff * diff
        }
        val stdDev = sqrt(variance / curve.size).coerceAtLeast(1e-5)
        for (i in curve.indices) {
            curve[i] = ((curve[i] - mean) / stdDev).coerceAtLeast(0.0).toFloat()
        }
    }

    private class KickIntervalStats(val intervals: List<Double>) {
        fun countInRange(minSec: Double, maxSec: Double): Int {
            return intervals.count { it in minSec..maxSec }
        }
    }

    private fun analyzeKickIntervals(noveltyLow: FloatArray, envelopeRate: Int): KickIntervalStats {
        val kickTimes = mutableListOf<Double>()
        var lastKickFrame = -100

        var sum = 0.0
        for (v in noveltyLow) sum += v
        val mean = sum / noveltyLow.size
        val threshold = mean * 1.5

        val minRefractoryFrames = (envelopeRate * 0.15).toInt() // 150ms minimum spacing

        for (i in 1 until noveltyLow.size - 1) {
            if (noveltyLow[i] > noveltyLow[i - 1] && noveltyLow[i] > noveltyLow[i + 1] && noveltyLow[i] >= threshold) {
                if (i - lastKickFrame >= minRefractoryFrames) {
                    kickTimes.add(i.toDouble() / envelopeRate.toDouble())
                    lastKickFrame = i
                }
            }
        }

        val intervals = mutableListOf<Double>()
        for (i in 1 until kickTimes.size) {
            intervals.add(kickTimes[i] - kickTimes[i - 1])
        }
        return KickIntervalStats(intervals)
    }

    private fun evaluateGridConsistency(noveltyLow: FloatArray, lag: Int): Double {
        if (lag <= 0) return 0.0
        val kickFrames = mutableSetOf<Int>()
        for (i in 1 until noveltyLow.size - 1) {
            if (noveltyLow[i] > noveltyLow[i - 1] && noveltyLow[i] > noveltyLow[i + 1] && noveltyLow[i] > 0.5) {
                kickFrames.add(i)
            }
        }

        var maxMatch = 0
        var totalGridPoints = 0
        val testPhases = min(lag, 12)
        val tolerance = max(2, (lag * 0.08).toInt())
        for (phase in 0 until testPhases) {
            var gridIdx = phase
            var matches = 0
            var count = 0
            while (gridIdx < noveltyLow.size) {
                count++
                val hasKickNearby = (-tolerance..tolerance).any { delta -> (gridIdx + delta) in kickFrames }
                if (hasKickNearby) matches++
                gridIdx += lag
            }
            if (count > 0 && matches > maxMatch) {
                maxMatch = matches
                totalGridPoints = count
            }
        }
        return if (totalGridPoints > 0) maxMatch.toDouble() / totalGridPoints.toDouble() else 0.0
    }

    fun detectKey(samples: FloatArray, sampleRate: Int): Triple<String, String, Double>? {
        val chroma = DoubleArray(12)
        val frames = accumulateChroma(samples, sampleRate, chroma)
        if (frames == 0) return null
        return resolveKeyFromChroma(chroma)
    }

    private fun accumulateChroma(samples: FloatArray, sampleRate: Int, outChroma: DoubleArray): Int {
        val windowSize = WINDOW_SIZE
        val hop = HOP_SIZE
        if (samples.size < windowSize * 4) return 0

        val frames = (samples.size - windowSize) / hop
        val fftReal = FloatArray(windowSize)
        val fftImag = FloatArray(windowSize)

        val binToPitchClass = IntArray(windowSize / 2) { bin ->
            val frequency = bin * sampleRate.toDouble() / windowSize
            if (frequency in 65.0..2000.0) {
                val midi = (12.0 * (ln(frequency / 440.0) / ln(2.0)) + 69.0).roundToInt()
                ((midi % 12) + 12) % 12
            } else {
                -1
            }
        }

        for (frame in 0 until frames) {
            val start = frame * hop
            for (i in 0 until windowSize) {
                fftReal[i] = samples[start + i] * HANN_WINDOW[i]
                fftImag[i] = 0.0f
            }
            fftRadix2(fftReal, fftImag, windowSize)

            for (bin in 1 until windowSize / 2) {
                val pitchClass = binToPitchClass[bin]
                if (pitchClass >= 0) {
                    val r = fftReal[bin]
                    val im = fftImag[bin]
                    val mag = sqrt(r * r + im * im)
                    outChroma[pitchClass] += mag.toDouble()
                }
            }
        }
        return frames
    }

    private fun resolveKeyFromChroma(chroma: DoubleArray): Triple<String, String, Double>? {
        val sum = chroma.sum()
        if (sum <= 0.0) return null
        val normalized = DoubleArray(12) { chroma[it] / sum }

        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        data class Candidate(val isMinor: Boolean, val score: Double, val root: Int)
        val candidates = (0 until 12).flatMap { root ->
            listOf(
                Candidate(false, correlation(normalized, major, root), root),
                Candidate(true, correlation(normalized, minor, root), root)
            )
        }.sortedByDescending { it.score }

        val best = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)?.score ?: 0.0
        val confidence = ((best.score - second) / 2.0).coerceIn(0.0, 1.0)
        val name = "${names[best.root]} ${if (best.isMinor) "minor" else "major"}"
        return Triple(name, CamelotKey.fromPitchClass(best.root, best.isMinor), confidence)
    }

    companion object {
        private const val WINDOW_SIZE = 2048
        private const val HOP_SIZE = 2048

        private val HANN_WINDOW = FloatArray(WINDOW_SIZE) { n ->
            (0.5 * (1.0 - cos(2.0 * PI * n / (WINDOW_SIZE - 1)))).toFloat()
        }

        private fun fftRadix2(real: FloatArray, imag: FloatArray, n: Int) {
            var j = 0
            for (i in 0 until n - 1) {
                if (i < j) {
                    val tr = real[i]
                    val ti = imag[i]
                    real[i] = real[j]
                    imag[i] = imag[j]
                    real[j] = tr
                    imag[j] = ti
                }
                var k = n shr 1
                while (k <= j) {
                    j -= k
                    k = k shr 1
                }
                j += k
            }

            var len = 2
            while (len <= n) {
                val halfLen = len shr 1
                val angle = -2.0 * PI / len
                val wStepR = cos(angle).toFloat()
                val wStepI = sin(angle).toFloat()

                var i = 0
                while (i < n) {
                    var wR = 1.0f
                    var wI = 0.0f
                    for (k in 0 until halfLen) {
                        val uR = real[i + k]
                        val uI = imag[i + k]
                        val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                        val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR

                        real[i + k] = uR + vR
                        imag[i + k] = uI + vI
                        real[i + k + halfLen] = uR - vR
                        imag[i + k + halfLen] = uI - vI

                        val nextWR = wR * wStepR - wI * wStepI
                        val nextWI = wR * wStepI + wI * wStepR
                        wR = nextWR
                        wI = nextWI
                    }
                    i += len
                }
                len = len shl 1
            }
        }
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
