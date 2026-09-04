package com.example.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.collection.LruCache
import com.example.model.Track
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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
    val isRealAudioData: Boolean = true,
    /** RMS / body power per bin [0.0f .. 1.0f] */
    val rms: FloatArray = FloatArray(0)
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
 * High-performance two-tier (Memory + Disk) Waveform Cache.
 *
 * Cache Identity Scheme:
 * "trackId + fileModifiedTime + fileSize + waveformAnalysisVersion"
 * Ensures replaced/modified files immediately invalidate older waveform data.
 */
object WaveformCache {
    private const val TAG = "WaveformCache"
    const val WAVEFORM_ANALYSIS_VERSION = 6
    private const val MAGIC_HEADER = 0x53594E43 // "SYNC"

    // Memory LRU Cache (up to 100 waveform objects in RAM ~5MB max)
    private val memoryCache = LruCache<String, WaveformData>(100)

    /**
     * Constructs a unique cache identity based on:
     * trackId + fileModifiedTime + fileSize + waveformAnalysisVersion
     */
    fun getCacheKey(track: Track, context: Context? = null): String {
        var fileSize: Long = (track.fileSizeMb * 1024 * 1024).toLong()
        var fileModified: Long = track.dateAdded

        if (track.filePath.isNotBlank()) {
            try {
                if (track.filePath.startsWith("content://") && context != null) {
                    val uri = Uri.parse(track.filePath)
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0) {
                                val sz = cursor.getLong(sizeIdx)
                                if (sz > 0) fileSize = sz
                            }
                        }
                    }
                } else if (!track.filePath.startsWith("http")) {
                    val file = File(track.filePath)
                    if (file.exists()) {
                        val sz = file.length()
                        val mod = file.lastModified()
                        if (sz > 0) fileSize = sz
                        if (mod > 0) fileModified = mod
                    }
                }
            } catch (ignored: Throwable) {}
        }

        val sanitizedTrackId = track.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${sanitizedTrackId}_${fileModified}_${fileSize}_v${WAVEFORM_ANALYSIS_VERSION}"
    }

    /**
     * Retrieves waveform data from memory cache, or falls back to fast disk cache.
     */
    fun get(cacheKey: String, context: Context? = null): WaveformData? {
        // 1. Check Memory Cache
        memoryCache.get(cacheKey)?.let {
            return it
        }

        // 2. Check Disk Cache
        if (context != null) {
            val diskData = readFromDisk(cacheKey, context)
            if (diskData != null) {
                memoryCache.put(cacheKey, diskData)
                return diskData
            }

            // Backward compatibility fallback: check for previous version 5 cache
            if (cacheKey.contains("_v$WAVEFORM_ANALYSIS_VERSION")) {
                val v5Key = cacheKey.replace("_v$WAVEFORM_ANALYSIS_VERSION", "_v5")
                val v5Data = readFromDisk(v5Key, context)
                if (v5Data != null) {
                    memoryCache.put(cacheKey, v5Data)
                    return v5Data
                }
            }
        }

        return null
    }

    /**
     * Saves waveform data to both memory cache and disk cache.
     */
    fun put(cacheKey: String, data: WaveformData, context: Context? = null) {
        memoryCache.put(cacheKey, data)
        if (context != null) {
            writeToDisk(cacheKey, data, context)
        }
        Log.d(TAG, "Cached waveform for key='$cacheKey' (${data.samplePoints} points, isReal=${data.isRealAudioData})")
    }

    fun contains(cacheKey: String, context: Context? = null): Boolean {
        if (memoryCache.get(cacheKey) != null) return true
        if (context != null) {
            val file = getDiskFile(cacheKey, context)
            return file.exists() && file.length() > 0
        }
        return false
    }

    fun remove(cacheKey: String, context: Context? = null) {
        memoryCache.remove(cacheKey)
        if (context != null) {
            val file = getDiskFile(cacheKey, context)
            if (file.exists()) file.delete()
        }
    }

    fun clear(context: Context? = null) {
        memoryCache.evictAll()
        if (context != null) {
            val dir = getCacheDir(context)
            dir.listFiles()?.forEach { it.delete() }
        }
        Log.d(TAG, "Waveform memory and disk cache cleared")
    }

    private fun getCacheDir(context: Context, version: String = WAVEFORM_ANALYSIS_VERSION.toString()): File {
        val dir = File(context.cacheDir, "waveforms_v$version")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getDiskFile(cacheKey: String, context: Context): File {
        val safeFileName = "${cacheKey.hashCode().toUInt().toString(16)}_${cacheKey.take(32)}.bin"
        val versionMatch = Regex("_v(\\d+)").find(cacheKey)
        val version = versionMatch?.groupValues?.get(1) ?: WAVEFORM_ANALYSIS_VERSION.toString()
        return File(getCacheDir(context, version), safeFileName)
    }

    private fun writeToDisk(cacheKey: String, data: WaveformData, context: Context) {
        try {
            val file = getDiskFile(cacheKey, context)
            val tmpFile = File(file.parentFile, "${file.name}.tmp")
            DataOutputStream(BufferedOutputStream(tmpFile.outputStream())).use { out ->
                out.writeInt(MAGIC_HEADER)
                out.writeUTF(data.trackId)
                out.writeLong(data.durationMs)
                out.writeInt(data.samplePoints)
                out.writeDouble(data.bpm)
                out.writeBoolean(data.isRealAudioData)

                // Write arrays
                for (i in 0 until data.samplePoints) out.writeFloat(data.peaks.getOrElse(i) { 0f })
                for (i in 0 until data.samplePoints) out.writeFloat(data.lowBand.getOrElse(i) { 0f })
                for (i in 0 until data.samplePoints) out.writeFloat(data.midBand.getOrElse(i) { 0f })
                for (i in 0 until data.samplePoints) out.writeFloat(data.highBand.getOrElse(i) { 0f })

                // Write rms array
                out.writeInt(data.rms.size)
                for (i in 0 until data.rms.size) out.writeFloat(data.rms[i])
            }
            if (tmpFile.renameTo(file)) {
                // success
            } else {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing waveform to disk cache: ${e.message}")
        }
    }

    private fun readFromDisk(cacheKey: String, context: Context): WaveformData? {
        val file = getDiskFile(cacheKey, context)
        if (!file.exists() || file.length() < 32) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { inStream ->
                val magic = inStream.readInt()
                if (magic != MAGIC_HEADER) return null
                val trackId = inStream.readUTF()
                val durationMs = inStream.readLong()
                val samplePoints = inStream.readInt()
                val bpm = inStream.readDouble()
                val isReal = inStream.readBoolean()

                val peaks = FloatArray(samplePoints) { inStream.readFloat() }
                val lowBand = FloatArray(samplePoints) { inStream.readFloat() }
                val midBand = FloatArray(samplePoints) { inStream.readFloat() }
                val highBand = FloatArray(samplePoints) { inStream.readFloat() }

                val rms = if (inStream.available() >= 4) {
                    val rmsCount = inStream.readInt()
                    if (rmsCount in 1..samplePoints && inStream.available() >= rmsCount * 4) {
                        FloatArray(rmsCount) { inStream.readFloat() }
                    } else FloatArray(0)
                } else FloatArray(0)

                WaveformData(
                    trackId = trackId,
                    durationMs = durationMs,
                    samplePoints = samplePoints,
                    peaks = peaks,
                    lowBand = lowBand,
                    midBand = midBand,
                    highBand = highBand,
                    bpm = bpm,
                    isRealAudioData = isReal,
                    rms = rms
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading waveform from disk cache: ${e.message}")
            try { file.delete() } catch (ignored: Exception) {}
            null
        }
    }
}
