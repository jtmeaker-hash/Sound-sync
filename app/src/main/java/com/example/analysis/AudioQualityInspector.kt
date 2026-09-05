package com.example.analysis

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.collection.LruCache
import com.example.audio.BitrateProbe
import com.example.audio.SpectrogramEngine
import com.example.model.BitrateMode
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

enum class QualityClassification {
    HIGH_RES_LOSSLESS,
    TRUE_LOSSLESS,
    HIGH_QUALITY_LOSSY,
    STANDARD_LOSSY,
    LOW_QUALITY_LOSSY,
    UNKNOWN
}

data class AudioQualityReport(
    val trackId: String,
    val filePath: String,
    val container: String,
    val codec: String,
    val bitrateKbps: Int,
    val bitrateMode: BitrateMode?,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val channelCount: Int,
    val durationSeconds: Int,
    val fileSizeBytes: Long,
    val spectralCutoffKhz: Double?,
    val classification: QualityClassification,
    val isSuspiciousTranscode: Boolean,
    val transcodeWarningReason: String?,
    val summary: String,
    val analyzedTimestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("trackId", trackId)
        put("filePath", filePath)
        put("container", container)
        put("codec", codec)
        put("bitrateKbps", bitrateKbps)
        put("bitrateMode", bitrateMode?.name)
        put("sampleRateHz", sampleRateHz)
        put("bitDepth", bitDepth ?: JSONObject.NULL)
        put("channelCount", channelCount)
        put("durationSeconds", durationSeconds)
        put("fileSizeBytes", fileSizeBytes)
        put("spectralCutoffKhz", spectralCutoffKhz ?: JSONObject.NULL)
        put("classification", classification.name)
        put("isSuspiciousTranscode", isSuspiciousTranscode)
        put("transcodeWarningReason", transcodeWarningReason.orEmpty())
        put("summary", summary)
        put("analyzedTimestamp", analyzedTimestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): AudioQualityReport {
            return AudioQualityReport(
                trackId = json.optString("trackId"),
                filePath = json.optString("filePath"),
                container = json.optString("container", "Unknown"),
                codec = json.optString("codec", "Unknown"),
                bitrateKbps = json.optInt("bitrateKbps", 0),
                bitrateMode = runCatching { BitrateMode.valueOf(json.optString("bitrateMode")) }.getOrNull(),
                sampleRateHz = json.optInt("sampleRateHz", 44100),
                bitDepth = if (json.has("bitDepth") && !json.isNull("bitDepth")) json.optInt("bitDepth") else null,
                channelCount = json.optInt("channelCount", 2),
                durationSeconds = json.optInt("durationSeconds", 0),
                fileSizeBytes = json.optLong("fileSizeBytes", 0L),
                spectralCutoffKhz = if (json.has("spectralCutoffKhz") && !json.isNull("spectralCutoffKhz")) json.optDouble("spectralCutoffKhz") else null,
                classification = runCatching { QualityClassification.valueOf(json.optString("classification")) }.getOrDefault(QualityClassification.UNKNOWN),
                isSuspiciousTranscode = json.optBoolean("isSuspiciousTranscode", false),
                transcodeWarningReason = json.optString("transcodeWarningReason").takeIf { it.isNotBlank() },
                summary = json.optString("summary", ""),
                analyzedTimestamp = json.optLong("analyzedTimestamp", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Audio Quality Inspector implementing Step 2 Part D:
 * - Inspects container, codec, bitstream bitrate, CBR/VBR mode, sample rate, bit depth, channels, size.
 * - Extracts acoustic spectral cutoff from real FFT analysis.
 * - Accurately categorizes lossless vs lossy formats.
 * - Detects suspicious transcodes / fake upscales (e.g. 128 kbps MP3 up-encoded to FLAC or 320 kbps MP3).
 * - Caches and persists analysis results.
 */
object AudioQualityInspector {

    private const val TAG = "AudioQualityInspector"
    private val memoryCache = LruCache<String, AudioQualityReport>(60)

    suspend fun inspectTrack(
        context: Context,
        track: Track,
        forceRefresh: Boolean = false
    ): AudioQualityReport = withContext(Dispatchers.IO) {
        val cacheKey = "${track.id}_${track.filePath.hashCode()}_${track.durationSeconds}"
        if (!forceRefresh) {
            memoryCache.get(cacheKey)?.let { return@withContext it }
            readFromDiskCache(context, track.id)?.let { cached ->
                memoryCache.put(cacheKey, cached)
                return@withContext cached
            }
        }

        val file = if (!track.filePath.startsWith("content://") && !track.filePath.startsWith("file://")) {
            File(track.filePath)
        } else null

        val fileSizeBytes = file?.length() ?: 0L
        val ext = file?.extension?.uppercase() ?: track.format.uppercase()

        // 1. Bitstream Probing for accurate bit rate & CBR/VBR mode
        val bitrateResult = BitrateProbe.probe(context, track.filePath, track.durationSeconds)
        val probedBitrate = if (bitrateResult.encodedBitrateKbps > 0) bitrateResult.encodedBitrateKbps else track.bitrateKbps

        // 2. MediaExtractor probing for codec, sample rate, channels, bit depth
        var codecMime = "audio/unknown"
        var sampleRate = 44100
        var channels = 2
        var bitDepth: Int? = null

        val extractor = MediaExtractor()
        try {
            if (track.filePath.startsWith("content://") || track.filePath.startsWith("file://")) {
                val uri = Uri.parse(track.filePath)
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: extractor.setDataSource(context, uri, null)
            } else {
                extractor.setDataSource(track.filePath)
            }

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    codecMime = mime
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (format.containsKey("bit-width")) {
                        bitDepth = format.getInteger("bit-width")
                    } else if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        bitDepth = 16
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor failed probing '${track.title}': ${e.message}")
        } finally {
            runCatching { extractor.release() }
        }

        // 3. Spectral Cutoff Analysis via SpectrogramEngine
        var spectralCutoffKhz: Double? = null
        try {
            val analysis = SpectrogramEngine.analyzeTrack(context, track)
            if (analysis.cutoffKhz > 0f) {
                spectralCutoffKhz = (analysis.cutoffKhz * 10.0).toInt() / 10.0 // rounded to 0.1 kHz
            }
        } catch (e: Exception) {
            Log.v(TAG, "Spectrogram acoustic probing skipped/failed: ${e.message}")
        }

        val container = when {
            ext.contains("FLAC") -> "FLAC"
            ext.contains("MP3") -> "MPEG Audio"
            ext.contains("M4A") || ext.contains("AAC") -> "MPEG-4 Audio"
            ext.contains("WAV") -> "RIFF WAVE"
            ext.contains("OGG") -> "Ogg Vorbis"
            ext.contains("AIFF") -> "AIFF"
            else -> ext.ifBlank { "Audio Container" }
        }

        val isLosslessContainer = ext in listOf("FLAC", "WAV", "AIFF", "ALAC")

        // 4. Quality Classification & Suspicious Transcode Detection
        var isSuspicious = false
        var warningReason: String? = null
        val classification: QualityClassification

        if (isLosslessContainer) {
            // Check for fake lossless (transcoded from low-bitrate MP3)
            if (spectralCutoffKhz != null && spectralCutoffKhz < 17.0) {
                isSuspicious = true
                warningReason = "Possible lossy source / transcode: Lossless file has sharp frequency shelf at ~${spectralCutoffKhz} kHz (typical of <=160 kbps MP3)."
                classification = QualityClassification.STANDARD_LOSSY
            } else if (sampleRate >= 88200 || (bitDepth != null && bitDepth >= 24)) {
                classification = QualityClassification.HIGH_RES_LOSSLESS
            } else {
                classification = QualityClassification.TRUE_LOSSLESS
            }
        } else {
            // Lossy container (MP3, AAC, OGG)
            if (probedBitrate >= 320 && spectralCutoffKhz != null && spectralCutoffKhz < 16.0) {
                isSuspicious = true
                warningReason = "Possible low-bitrate upscale: 320 kbps file exhibits steep low-pass cutoff at ~${spectralCutoffKhz} kHz."
                classification = QualityClassification.STANDARD_LOSSY
            } else if (probedBitrate >= 256) {
                classification = QualityClassification.HIGH_QUALITY_LOSSY
            } else if (probedBitrate >= 192) {
                classification = QualityClassification.STANDARD_LOSSY
            } else {
                classification = QualityClassification.LOW_QUALITY_LOSSY
            }
        }

        val summary = buildString {
            append("$container • $codecMime • ")
            if (bitrateResult.bitrateMode != null) {
                append("${bitrateResult.bitrateMode.name} ")
            }
            append("${probedBitrate} kbps • ")
            append("${(sampleRate / 1000.0)} kHz • ")
            if (bitDepth != null && bitDepth > 0) {
                append("${bitDepth}-bit • ")
            }
            append(if (channels == 1) "Mono" else "Stereo")
            if (spectralCutoffKhz != null) {
                append(" • Shelf: ~${spectralCutoffKhz} kHz")
            }
        }

        val report = AudioQualityReport(
            trackId = track.id,
            filePath = track.filePath,
            container = container,
            codec = codecMime,
            bitrateKbps = probedBitrate,
            bitrateMode = bitrateResult.bitrateMode,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            channelCount = channels,
            durationSeconds = track.durationSeconds,
            fileSizeBytes = fileSizeBytes,
            spectralCutoffKhz = spectralCutoffKhz,
            classification = classification,
            isSuspiciousTranscode = isSuspicious,
            transcodeWarningReason = warningReason,
            summary = summary
        )

        memoryCache.put(cacheKey, report)
        writeToDiskCache(context, track.id, report)
        report
    }

    private fun getCacheDir(context: Context): File {
        return File(context.filesDir, "quality_reports").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun writeToDiskCache(context: Context, trackId: String, report: AudioQualityReport) {
        try {
            val file = File(getCacheDir(context), "${trackId}.json")
            file.writeText(report.toJson().toString(2), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.v(TAG, "Failed caching quality report to disk: ${e.message}")
        }
    }

    private fun readFromDiskCache(context: Context, trackId: String): AudioQualityReport? {
        return try {
            val file = File(getCacheDir(context), "${trackId}.json")
            if (file.exists() && file.length() > 0) {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                AudioQualityReport.fromJson(json)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
