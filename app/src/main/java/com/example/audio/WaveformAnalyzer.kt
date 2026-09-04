package com.example.audio

import android.content.Context
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * Analyzes audio tracks and generates detailed waveform amplitude and 3-band frequency peaks.
 * Runs completely on background dispatcher without blocking UI.
 *
 * Utilizes two-tier caching (Memory LRU + Disk Binary Storage) with cache identity:
 * "trackId + fileModifiedTime + fileSize + waveformAnalysisVersion"
 */
object WaveformAnalyzer {

    private const val TAG = "WaveformAnalyzer"
    private const val MIN_BINS = 1200
    private const val MAX_BINS = 7200

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
        val cacheKey = WaveformCache.getCacheKey(track, context)

        // 1. Check two-tier cache (Memory -> Disk)
        WaveformCache.get(cacheKey, context)?.let { cached ->
            Log.d(TAG, "Waveform cache HIT for '${track.title}' (key='$cacheKey') in ${System.currentTimeMillis() - startTime}ms")
            onProgress(100)
            return@withContext cached
        }

        onProgress(10)

        val durationSec = track.durationSeconds.coerceAtLeast(10)
        val targetBins = (durationSec * 32).coerceIn(MIN_BINS, MAX_BINS)
        val durationMs = durationSec * 1000L

        try {
            // 2. Decode raw PCM from audio file on background IO thread
            val decodedWaveform = AudioDecoder.decodeRealWaveformPcm(
                context = context,
                track = track,
                targetBins = targetBins,
                onProgress = onProgress
            )

            onProgress(80)

            val waveformData = if (decodedWaveform != null && decodedWaveform.samplePoints >= 100) {
                Log.d(TAG, "Real PCM waveform computed (${decodedWaveform.samplePoints} bins) for '${track.title}'")
                decodedWaveform
            } else {
                Log.d(TAG, "Generating unique deterministic structural waveform for '${track.title}' (PCM not directly decodable)")
                computeDeterministicWaveform(track.id, targetBins, durationMs, track.bpm, track.title, track.artist)
            }

            onProgress(100)
            WaveformCache.put(cacheKey, waveformData, context)
            Log.d(TAG, "Waveform analysis complete for '${track.title}' in ${System.currentTimeMillis() - startTime}ms (${waveformData.samplePoints} bins, isReal=${waveformData.isRealAudioData})")
            waveformData
        } catch (e: CancellationException) {
            Log.d(TAG, "Waveform analysis cancelled for '${track.title}'")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Waveform analysis error for '${track.title}': ${e.message}", e)
            val fallback = computeDeterministicWaveform(track.id, targetBins, durationMs, track.bpm, track.title, track.artist)
            WaveformCache.put(cacheKey, fallback, context)
            fallback
        }
    }

    /**
     * Generates a deterministic structural DJ waveform based on song identity hash
     * when raw PCM bytes cannot be accessed.
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
        val seed = (trackId.hashCode().toLong() xor (title.hashCode().toLong() shl 16) xor (artist.hashCode().toLong() shl 32))
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
                i < introEnd -> 0.15f + progress * 0.25f
                i < verse1End -> 0.45f
                i < build1End -> 0.40f + ((i - verse1End).toFloat() / (build1End - verse1End)) * 0.50f
                i < drop1End -> 0.95f
                i < breakdownEnd -> 0.15f + 0.12f * sin(progress * 15f)
                i < build2End -> 0.45f + ((i - breakdownEnd).toFloat() / (build2End - breakdownEnd)) * 0.50f
                i < drop2End -> 0.98f
                else -> 0.70f * (1.0f - (i - drop2End).toFloat() / (binCount - drop2End)).coerceAtLeast(0.05f)
            }

            val noise = (random.nextFloat() - 0.5f) * 0.10f
            val kickBoost = if (isKick && sectionBase > 0.35f) 0.25f else 0.0f
            val hatBoost = if (isHiHat) 0.15f else 0.0f

            val totalPeak = (sectionBase + noise + kickBoost + hatBoost).coerceIn(0.0f, 1.0f)
            val low = (if (isKick) sectionBase * 1.1f else sectionBase * 0.60f + noise).coerceIn(0.0f, 1.0f)
            val mid = (sectionBase * 0.75f + noise * 0.5f).coerceIn(0.0f, 1.0f)
            val high = (if (isHiHat) 0.85f else sectionBase * 0.5f + hatBoost).coerceIn(0.0f, 1.0f)

            peaks[i] = totalPeak
            lowBand[i] = low
            midBand[i] = mid
            highBand[i] = high
        }

        val rms = FloatArray(binCount) { i -> (peaks[i] * 0.70f).coerceIn(0f, 1f) }

        return WaveformData(
            trackId = trackId,
            durationMs = durationMs,
            samplePoints = binCount,
            peaks = peaks,
            lowBand = lowBand,
            midBand = midBand,
            highBand = highBand,
            bpm = safeBpm,
            isRealAudioData = false,
            rms = rms
        )
    }
}
