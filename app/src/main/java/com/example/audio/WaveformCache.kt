package com.example.audio

import android.util.Log
import androidx.collection.LruCache

/**
 * Compact data structure holding precomputed waveform peak and frequency energy arrays for a track.
 * Designed for memory-efficient 60fps rendering without heap allocations per frame.
 */
data class WaveformData(
    val trackId: String,
    val durationMs: Long,
    val samplePoints: Int,
    /** Primary peak amplitudes [0.0f .. 1.0f] */
    val peaks: FloatArray,
    /** Low frequency / Bass energy [0.0f .. 1.0f] (Rekordbox Blue band) */
    val lowBand: FloatArray,
    /** Mid frequency energy [0.0f .. 1.0f] (Rekordbox Amber/Orange band) */
    val midBand: FloatArray,
    /** High frequency energy [0.0f .. 1.0f] (Rekordbox White/Cyan band) */
    val highBand: FloatArray,
    val bpm: Double = 126.0,
    val isRealAudioData: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WaveformData
        return trackId == other.trackId &&
                durationMs == other.durationMs &&
                peaks.contentEquals(other.peaks)
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + peaks.contentHashCode()
        return result
    }
}

/**
 * High-performance, thread-safe LRU cache for audio waveforms.
 * Retains pre-analyzed track peaks in memory so switching back to a track instantly restores its waveform.
 */
object WaveformCache {
    private const val TAG = "WaveformCache"
    // Maximum 50 full track waveforms in memory (~2.5 MB total RAM footprint)
    private val cache = LruCache<String, WaveformData>(50)

    fun get(trackId: String): WaveformData? {
        val result = cache.get(trackId)
        if (result != null) {
            Log.d(TAG, "Cache HIT for trackId=$trackId (${result.samplePoints} points)")
        }
        return result
    }

    fun put(trackId: String, data: WaveformData) {
        cache.put(trackId, data)
        Log.d(TAG, "Cached waveform for trackId=$trackId (${data.samplePoints} points, duration=${data.durationMs}ms)")
    }

    fun contains(trackId: String): Boolean = cache.get(trackId) != null

    fun remove(trackId: String) {
        cache.remove(trackId)
    }

    fun clear() {
        cache.evictAll()
        Log.d(TAG, "Waveform cache cleared")
    }
}
