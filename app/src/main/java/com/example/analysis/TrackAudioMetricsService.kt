package com.example.analysis

import android.content.Context
import com.example.audio.AudioDecoder
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrackAudioMetrics(
    val trackId: String,
    val peakDb: Float,
    val peakAmplitude: Float,
    val isClipping: Boolean,
    val clippedSampleCount: Long,
    val clippingPercentage: Float,
    val rmsDb: Float,
    val dynamicRangeScore: Int,
    val crestFactorDb: Float,
    val dynamicRangeDescription: String,
    val totalSamplesAnalyzed: Long,
    val durationSeconds: Long
)

object TrackAudioMetricsService {

    private val cache = ConcurrentHashMap<String, TrackAudioMetrics>()

    fun getCached(trackId: String): TrackAudioMetrics? = cache[trackId]

    suspend fun analyzeTrack(context: Context, track: Track): TrackAudioMetrics? = withContext(Dispatchers.Default) {
        val cached = cache[track.id]
        if (cached != null) return@withContext cached

        val decoded = AudioDecoder.decodeToMonoPcm(
            context = context,
            filePathOrUri = track.filePath,
            maxDurationSeconds = 300
        ) ?: return@withContext null

        val samples = decoded.samples
        computeMetrics(
            trackId = track.id,
            samples = decoded.samples,
            sampleRate = decoded.sampleRate
        ).also {
            cache[track.id] = it
        }
    }

    fun computeMetricsForPcm(trackId: String, monoPcm: ShortArray, sampleRate: Int): TrackAudioMetrics {
        val floatSamples = FloatArray(monoPcm.size) { i ->
            (monoPcm[i].toFloat() / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        return computeMetrics(trackId, floatSamples, sampleRate)
    }

    fun computeMetrics(trackId: String, samples: FloatArray, sampleRate: Int): TrackAudioMetrics {
        if (samples.isEmpty()) {
            return TrackAudioMetrics(
                trackId = trackId,
                peakDb = -96f,
                peakAmplitude = 0f,
                isClipping = false,
                clippedSampleCount = 0L,
                clippingPercentage = 0f,
                rmsDb = -96f,
                dynamicRangeScore = 1,
                crestFactorDb = 0f,
                dynamicRangeDescription = "No audio data",
                totalSamplesAnalyzed = 0L,
                durationSeconds = 0L
            )
        }

        var maxPeak = 0.0f
        var sumSquares = 0.0
        var clippedCount = 0L

        // Digital clipping threshold: 0.999f is within 0.01 dBFS of absolute digital limit 1.0f
        val clipThreshold = 0.999f

        for (i in samples.indices) {
            val sample = samples[i]
            val absSample = abs(sample)
            if (absSample > maxPeak) {
                maxPeak = absSample
            }
            if (absSample >= clipThreshold) {
                clippedCount++
            }
            sumSquares += sample * sample
        }

        val totalSamples = samples.size.toLong()
        val clippingPercentage = (clippedCount.toDouble() / totalSamples.toDouble() * 100.0).toFloat()
        val isClipping = clippedCount > 0 && maxPeak >= clipThreshold

        val overallRms = sqrt(sumSquares / totalSamples)
        val rmsDb = if (overallRms > 0.0) (20.0 * log10(overallRms)).toFloat().coerceIn(-96f, 0f) else -96f
        val peakDb = if (maxPeak > 0.0f) (20.0 * log10(maxPeak.toDouble())).toFloat().coerceIn(-96f, 0f) else -96f

        // Standard TT Dynamic Range calculation:
        // Divide into 3-second (or ~132300 samples at 44.1kHz) blocks
        val blockSize = (sampleRate * 3).coerceAtLeast(1024)
        val numBlocks = (samples.size / blockSize).coerceAtLeast(1)
        val blockRmsList = ArrayList<Double>(numBlocks)

        for (b in 0 until numBlocks) {
            val start = b * blockSize
            val end = (start + blockSize).coerceAtMost(samples.size)
            var blockSumSq = 0.0
            val count = end - start
            if (count > 0) {
                for (j in start until end) {
                    val s = samples[j]
                    blockSumSq += s * s
                }
                val bRms = sqrt(blockSumSq / count)
                if (bRms > 0.00001) {
                    blockRmsList.add(bRms)
                }
            }
        }

        val drScore: Int
        val crestFactorDb: Float

        if (blockRmsList.isNotEmpty()) {
            blockRmsList.sortDescending()
            // Top 20% loudest blocks
            val top20Count = (blockRmsList.size * 0.20).roundToInt().coerceAtLeast(1)
            var top20SumSq = 0.0
            for (k in 0 until top20Count) {
                val r = blockRmsList[k]
                top20SumSq += r * r
            }
            val top20Rms = sqrt(top20SumSq / top20Count)
            val top20RmsDb = (20.0 * log10(top20Rms)).toFloat()
            val drRaw = peakDb - top20RmsDb
            drScore = drRaw.roundToInt().coerceIn(1, 24)
            crestFactorDb = peakDb - rmsDb
        } else {
            val cf = (peakDb - rmsDb).coerceAtLeast(0f)
            drScore = cf.roundToInt().coerceIn(1, 24)
            crestFactorDb = cf
        }

        val description = when {
            drScore <= 5 -> "Very heavily compressed / brickwalled (minimal dynamics)"
            drScore in 6..8 -> "Loud modern commercial master (heavy limiting)"
            drScore in 9..11 -> "Balanced master (good punch and moderate dynamics)"
            drScore in 12..14 -> "High dynamic range / natural uncompressed master"
            else -> "Audiophile / wide dynamic range (classical, jazz, acoustic)"
        }

        val result = TrackAudioMetrics(
            trackId = trackId,
            peakDb = peakDb,
            peakAmplitude = maxPeak,
            isClipping = isClipping,
            clippedSampleCount = clippedCount,
            clippingPercentage = clippingPercentage,
            rmsDb = rmsDb,
            dynamicRangeScore = drScore,
            crestFactorDb = crestFactorDb,
            dynamicRangeDescription = description,
            totalSamplesAnalyzed = totalSamples,
            durationSeconds = (totalSamples / sampleRate).coerceAtLeast(1)
        )

        cache[trackId] = result
        return result
    }
}
