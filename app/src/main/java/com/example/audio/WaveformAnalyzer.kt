package com.example.audio

import android.content.Context
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-analyzes audio tracks and generates detailed waveform amplitude and 3-band frequency peaks.
 * Runs completely on background dispatcher without blocking UI.
 */
object WaveformAnalyzer {

    private const val TAG = "WaveformAnalyzer"
    // Optimal point resolution: ~16 points per second of audio (yielding ~3200 points for a 3.5 min track)
    private const val DEFAULT_TARGET_BINS = 1800
    private const val MIN_BINS = 800
    private const val MAX_BINS = 3600

    /**
     * Asynchronously generates waveform peak data for [track].
     * Returns cached data immediately if available.
     */
    suspend fun analyze(
        context: Context,
        track: Track,
        onProgress: (percent: Int) -> Unit = {}
    ): WaveformData = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val trackId = track.id

        // 1. Check cache
        WaveformCache.get(trackId)?.let { cached ->
            Log.d(TAG, "Retrieved waveform for '${track.title}' from WaveformCache in ${System.currentTimeMillis() - startTime}ms")
            onProgress(100)
            return@withContext cached
        }

        onProgress(10)

        val durationSec = track.durationSeconds.coerceAtLeast(10)
        val targetBins = (durationSec * 16).coerceIn(MIN_BINS, MAX_BINS)
        val durationMs = durationSec * 1000L

        try {
            // 2. Decode raw PCM from audio file on IO thread
            val decodedAudio = AudioDecoder.decodeToMonoPcm(
                context = context,
                filePathOrUri = track.filePath,
                maxDurationSeconds = durationSec.coerceAtMost(360)
            )

            onProgress(60)

            val waveformData = if (decodedAudio != null && decodedAudio.samples.size >= targetBins) {
                val samples = decodedAudio.samples
                val actualDurationMs = if (decodedAudio.durationMs > 0) decodedAudio.durationMs else durationMs
                Log.d(TAG, "Processing real PCM (${samples.size} samples) into $targetBins bins for '${track.title}'")
                computeRealWaveform(trackId, samples, targetBins, actualDurationMs, track.bpm)
            } else {
                Log.d(TAG, "Generating deterministic DJ waveform for '${track.title}' (PCM source not directly accessible)")
                computeDeterministicWaveform(trackId, targetBins, durationMs, track.bpm, track.title, track.artist)
            }

            onProgress(100)
            WaveformCache.put(trackId, waveformData)
            Log.d(TAG, "Waveform analysis complete for '${track.title}' in ${System.currentTimeMillis() - startTime}ms (${waveformData.samplePoints} bins)")
            waveformData
        } catch (e: CancellationException) {
            Log.d(TAG, "Waveform analysis cancelled for '${track.title}'")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Waveform analysis error for '${track.title}': ${e.message}", e)
            val fallback = computeDeterministicWaveform(trackId, targetBins, durationMs, track.bpm, track.title, track.artist)
            WaveformCache.put(trackId, fallback)
            fallback
        }
    }

    /**
     * Computes RMS peaks and simplified 3-band (Bass, Mid, High) energy envelopes from raw PCM.
     */
    private fun computeRealWaveform(
        trackId: String,
        samples: FloatArray,
        binCount: Int,
        durationMs: Long,
        bpm: Double
    ): WaveformData {
        val totalSamples = samples.size
        val chunkSize = max(1, totalSamples / binCount)

        val peaks = FloatArray(binCount)
        val lowBand = FloatArray(binCount)
        val midBand = FloatArray(binCount)
        val highBand = FloatArray(binCount)

        var globalMaxPeak = 0.001f
        var globalMaxLow = 0.001f
        var globalMaxMid = 0.001f
        var globalMaxHigh = 0.001f

        // Simple IIR filter states for 3-band separation
        var lowFilterState = 0.0f
        var midFilterState = 0.0f

        for (bin in 0 until binCount) {
            val startIdx = bin * chunkSize
            val endIdx = min(startIdx + chunkSize, totalSamples)
            val count = endIdx - startIdx
            if (count <= 0) continue

            var sumSquares = 0.0f
            var maxAbsolute = 0.0f
            var lowEnergySum = 0.0f
            var midEnergySum = 0.0f
            var highEnergySum = 0.0f

            for (i in startIdx until endIdx) {
                val s = samples[i]
                val absS = abs(s)
                if (absS > maxAbsolute) maxAbsolute = absS
                sumSquares += s * s

                // 3-Band IIR filter simulation:
                // Low-pass (< ~300 Hz)
                lowFilterState += 0.08f * (s - lowFilterState)
                val lowSample = lowFilterState

                // Bandpass (300 Hz - 3 kHz)
                midFilterState += 0.25f * (s - midFilterState)
                val midSample = midFilterState - lowFilterState

                // High-pass (> 3 kHz)
                val highSample = s - midFilterState

                lowEnergySum += lowSample * lowSample
                midEnergySum += midSample * midSample
                highEnergySum += highSample * highSample
            }

            val rms = sqrt(sumSquares / count)
            // Combine RMS and Max Peak for DJ punch (transient preservation)
            val combinedPeak = (rms * 0.65f + maxAbsolute * 0.35f).coerceIn(0f, 1.5f)
            val lowRms = sqrt(lowEnergySum / count)
            val midRms = sqrt(midEnergySum / count)
            val highRms = sqrt(highEnergySum / count)

            peaks[bin] = combinedPeak
            lowBand[bin] = lowRms
            midBand[bin] = midRms
            highBand[bin] = highRms

            if (combinedPeak > globalMaxPeak) globalMaxPeak = combinedPeak
            if (lowRms > globalMaxLow) globalMaxLow = lowRms
            if (midRms > globalMaxMid) globalMaxMid = midRms
            if (highRms > globalMaxHigh) globalMaxHigh = highRms
        }

        // Normalize arrays for crisp visualization
        val peakDiv = if (globalMaxPeak > 0.001f) globalMaxPeak else 1.0f
        val lowDiv = if (globalMaxLow > 0.001f) globalMaxLow else 1.0f
        val midDiv = if (globalMaxMid > 0.001f) globalMaxMid else 1.0f
        val highDiv = if (globalMaxHigh > 0.001f) globalMaxHigh else 1.0f

        for (i in 0 until binCount) {
            peaks[i] = (peaks[i] / peakDiv).coerceIn(0.08f, 1.0f)
            lowBand[i] = (lowBand[i] / lowDiv).coerceIn(0.05f, 1.0f)
            midBand[i] = (midBand[i] / midDiv).coerceIn(0.05f, 1.0f)
            highBand[i] = (highBand[i] / highDiv).coerceIn(0.05f, 1.0f)
        }

        return WaveformData(
            trackId = trackId,
            durationMs = durationMs,
            samplePoints = binCount,
            peaks = peaks,
            lowBand = lowBand,
            midBand = midBand,
            highBand = highBand,
            bpm = bpm,
            isRealAudioData = true
        )
    }

    /**
     * Generates a realistic DJ track structural waveform based on musical phrasing and BPM.
     */
    private fun computeDeterministicWaveform(
        trackId: String,
        binCount: Int,
        durationMs: Long,
        bpm: Double,
        title: String,
        artist: String
    ): WaveformData {
        val safeBpm = if (bpm > 40.0) bpm else 126.0
        val seed = (trackId.hashCode().toLong() xor title.hashCode().toLong() xor (artist.hashCode().toLong() shl 16))
        val random = kotlin.random.Random(seed)

        val peaks = FloatArray(binCount)
        val lowBand = FloatArray(binCount)
        val midBand = FloatArray(binCount)
        val highBand = FloatArray(binCount)

        // Musical phrasing structure: Intro -> Verse -> Build-up -> Drop -> Breakdown -> Drop 2 -> Outro
        val introEnd = (binCount * 0.12f).toInt()
        val verse1End = (binCount * 0.28f).toInt()
        val build1End = (binCount * 0.35f).toInt()
        val drop1End = (binCount * 0.52f).toInt()
        val breakdownEnd = (binCount * 0.65f).toInt()
        val build2End = (binCount * 0.72f).toInt()
        val drop2End = (binCount * 0.88f).toInt()

        val beatPeriodBins = ((60.0 / safeBpm) * (binCount.toDouble() / (durationMs / 1000.0))).coerceAtLeast(2.0)

        for (i in 0 until binCount) {
            val progress = i.toFloat() / binCount
            val beatPhase = (i % beatPeriodBins) / beatPeriodBins
            val isKick = beatPhase < 0.22
            val isHiHat = beatPhase in 0.45..0.60

            val sectionBase = when {
                i < introEnd -> 0.35f + progress * 0.2f
                i < verse1End -> 0.60f
                i < build1End -> 0.50f + ((i - verse1End).toFloat() / (build1End - verse1End)) * 0.45f
                i < drop1End -> 0.95f
                i < breakdownEnd -> 0.30f + 0.15f * sin(progress * 15f)
                i < build2End -> 0.55f + ((i - breakdownEnd).toFloat() / (build2End - breakdownEnd)) * 0.45f
                i < drop2End -> 0.98f
                else -> 0.80f * (1.0f - (i - drop2End).toFloat() / (binCount - drop2End)).coerceAtLeast(0.15f)
            }

            val noise = (random.nextFloat() - 0.5f) * 0.15f
            val kickBoost = if (isKick && sectionBase > 0.45f) 0.25f else 0.0f
            val hatBoost = if (isHiHat) 0.15f else 0.0f

            val totalPeak = (sectionBase + noise + kickBoost + hatBoost).coerceIn(0.12f, 1.0f)
            val low = (if (isKick) sectionBase * 1.1f else sectionBase * 0.65f + noise).coerceIn(0.08f, 1.0f)
            val mid = (sectionBase * 0.75f + noise * 0.5f).coerceIn(0.08f, 1.0f)
            val high = (if (isHiHat) 0.85f else sectionBase * 0.5f + hatBoost).coerceIn(0.05f, 1.0f)

            peaks[i] = totalPeak
            lowBand[i] = low
            midBand[i] = mid
            highBand[i] = high
        }

        return WaveformData(
            trackId = trackId,
            durationMs = durationMs,
            samplePoints = binCount,
            peaks = peaks,
            lowBand = lowBand,
            midBand = midBand,
            highBand = highBand,
            bpm = safeBpm,
            isRealAudioData = false
        )
    }
}
