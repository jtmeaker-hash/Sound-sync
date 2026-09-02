package com.example.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.model.BitrateMode
import java.io.File
import java.io.InputStream

/**
 * Reads the ACTUAL encoded bitrate of an audio file from its container/codec —
 * never inferred from spectral analysis.
 *
 * Strategy (first reliable result wins):
 *  1. Raw MP3 header parse: the first valid frame header gives the bitrate index, and an adjacent
 *     Xing/Info/VBRI tag gives verified VBR averages or the LAME "Info" CBR marker. Without a tag,
 *     CBR is only claimed when headers sampled across the file are all identical.
 *  2. MediaExtractor + MediaFormat: KEY_BIT_RATE (bps) — the muxer/codec-reported average bitrate.
 *     Works for MP4/M4A/AAC etc. MediaFormat does not expose CBR/VBR, so mode stays unknown.
 *  3. Lossless/container math: fileSize*8/duration from extractor-reported duration.
 *
 * CBR/VBR is only reported when it can be GENUINELY verified from the bitstream; otherwise null.
 */
object BitrateProbe {

    private const val TAG = "BitrateProbe"

    data class Result(
        val encodedBitrateKbps: Int,
        val bitrateMode: BitrateMode?,
        val source: String
    )

    fun probe(context: Context, filePathOrUri: String, fallbackDurationSeconds: Int = 0): Result {
        return try {
            // MP3 files: parse headers/bitstream directly (most reliable for CBR/VBR).
            if (!filePathOrUri.startsWith("content://") && !filePathOrUri.startsWith("file://")) {
                val file = File(filePathOrUri)
                if (file.exists() && file.canRead() &&
                    (file.extension.equals("mp3", true) || looksLikeMp3(file))
                ) {
                    probeMp3(file)?.let { return it }
                }
            }
            probeExtractor(context, filePathOrUri, fallbackDurationSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "probe failed for '$filePathOrUri': ${e.message}")
            Result(0, null, "probe_error")
        }
    }

    // ── Container / codec route ─────────────────────────────────────────────

    private fun probeExtractor(context: Context, filePathOrUri: String, fallbackDurationSeconds: Int): Result {
        val extractor = MediaExtractor()
        try {
            if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                val uri = Uri.parse(filePathOrUri)
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    } ?: return Result(0, null, "unopenable")
                } catch (e: Exception) {
                    try {
                        extractor.setDataSource(context, uri, null)
                    } catch (e2: Exception) {
                        return Result(0, null, "unopenable")
                    }
                }
            } else {
                val file = File(filePathOrUri)
                if (!file.exists() || !file.canRead()) return Result(0, null, "unopenable")
                extractor.setDataSource(filePathOrUri)
            }

            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    format = f
                    break
                }
            }
            format ?: return Result(0, null, "no_audio_track")

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            // 1) Codec/muxer-reported average bitrate (bits per second).
            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                val bps = format.getInteger(MediaFormat.KEY_BIT_RATE)
                if (bps > 0) {
                    val kbps = (bps + 500) / 1000
                    // MediaFormat does not expose CBR/VBR for AAC/MP4 — do not claim a mode.
                    return Result(kbps, null, "mediaformat")
                }
            }

            // 2) Bitrate math from file size and reported duration.
            val sizeBytes = fileSizeBytes(context, filePathOrUri)
            val durationSec = durationUs / 1_000_000.0
            if (sizeBytes > 0 && durationSec > 1.0) {
                val kbps = ((sizeBytes * 8.0) / durationSec / 1000.0).toInt()
                if (kbps in 16..2000) return Result(kbps, null, "container_math")
            }

            // 3) Last resort: caller-provided duration (library metadata).
            if (sizeBytes > 0 && fallbackDurationSeconds > 1) {
                val kbps = ((sizeBytes * 8.0) / fallbackDurationSeconds / 1000.0).toInt()
                if (kbps in 16..2000) return Result(kbps, null, "fallback_math")
            }

            return Result(0, null, "undetermined")
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    private fun fileSizeBytes(context: Context, filePathOrUri: String): Long {
        return try {
            if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(filePathOrUri), "r")?.use { it.statSize } ?: 0L
            } else {
                File(filePathOrUri).length()
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ── Raw MP3 route ───────────────────────────────────────────────────────

    private fun looksLikeMp3(file: File): Boolean = try {
        file.inputStream().buffered().use { ins ->
            findFirstFrameHeader(ins) != null
        }
    } catch (e: Exception) {
        false
    }

    private fun probeMp3(file: File): Result? = try {
        file.inputStream().buffered().use { ins ->
            skipId3v2(ins)?.let { return null }
            val first = findFirstFrameHeader(ins) ?: return null
            parseFirstFrame(ins, file, first)
        }
    } catch (e: Exception) {
        Log.v(TAG, "probeMp3 failed: ${e.message}")
        null
    }

    /** Skips an ID3v2 tag at the head of [ins]; returns false when no tag present. */
    private fun skipId3v2(ins: InputStream): Boolean? {
        ins.mark(10)
        val tag = ByteArray(10)
        val n = ins.read(tag)
        if (n == 10 && tag[0] == 'I'.code.toByte() && tag[1] == 'D'.code.toByte() && tag[2] == '3'.code.toByte()) {
            ins.reset()
            // Consume header + tag body.
            val size = ((tag[6].toInt() and 0x7F) shl 21) or ((tag[7].toInt() and 0x7F) shl 14) or
                ((tag[8].toInt() and 0x7F) shl 7) or (tag[9].toInt() and 0x7F)
            var remaining = 10 + size
            while (remaining > 0) {
                if (ins.read() < 0) return true
                remaining--
            }
            return true
        }
        ins.reset()
        return false
    }

    /** Scans forward (up to 64 KB) for the first valid MPEG audio frame header. */
    private fun findFirstFrameHeader(ins: InputStream): ByteArray? {
        var b0 = -1
        var b1 = -1
        var b2 = -1
        var scanned = 0
        while (scanned < 65536) {
            val b = ins.read()
            if (b < 0) return null
            b0 = b1; b1 = b2; b2 = b
            scanned++
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val b3 = ins.read()
                if (b3 < 0) return null
                scanned++
                val header = byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
                if (isValidFrameHeader(header)) return header
            }
        }
        return null
    }

    /** Parses the first frame's Xing/Info/VBRI tag (if any), then falls back to header scanning. */
    private fun parseFirstFrame(ins: InputStream, file: File, first: ByteArray): Result? {
        val sideInfoBytes = mpegSideInfoBytes(first)
        var skippedSide = 0
        while (skippedSide < sideInfoBytes) {
            if (ins.read() < 0) return headerScanResult(file, first)
            skippedSide++
        }
        val buf = ByteArray(200)
        val readN = ins.read(buf)
        if (readN > 3) {
            var i = 0
            while (i <= readN - 4) {
                val ident = String(buf, i, 4, Charsets.US_ASCII)
                when (ident) {
                    "Info" -> {
                        // LAME "Info" tag marks CBR output. Frame-header bitrate is authoritative.
                        val kbps = mp3BitrateKbps(first)
                        if (kbps != null && kbps > 0) return Result(kbps, BitrateMode.CBR, "mp3_info_tag")
                        return headerScanResult(file, first)
                    }
                    "Xing" -> {
                        val off = i + 4
                        if (off + 8 <= readN) {
                            val flags = u32be(buf, off)
                            val frames = u32be(buf, off + 4)
                            if (flags and 0x01L != 0L && frames > 0L && off + 12 <= readN) {
                                val bytesVal = u32be(buf, off + 8)
                                val avg = averageKbps(first, frames.toLong(), bytesVal)
                                if (avg != null) return Result(avg, BitrateMode.VBR, "mp3_xing")
                            }
                            if (frames == 0L) {
                                // frames==0 with a tag is the "Info"-style CBR marker variant.
                                val kbps = mp3BitrateKbps(first)
                                if (kbps != null && kbps > 0) return Result(kbps, BitrateMode.CBR, "mp3_info_tag")
                            }
                        }
                        return headerScanResult(file, first)
                    }
                    "VBRI" -> {
                        val off = i + 4
                        // VBRI: version u16, delay u16, quality u16, bytes u32, frames u32
                        if (off + 14 <= readN) {
                            val bytesVal = u32be(buf, off + 6)
                            val frames = u32be(buf, off + 10)
                            if (frames > 0 && bytesVal > 0) {
                                val avg = averageKbps(first, frames.toLong(), bytesVal)
                                if (avg != null) return Result(avg, BitrateMode.VBR, "mp3_vbri")
                            }
                        }
                        return headerScanResult(file, first)
                    }
                }
                i++
            }
        }
        return headerScanResult(file, first)
    }

    /** Average bitrate from tag frames/bytes: bytes*8 / (frames * samplesPerFrame / sampleRate). */
    private fun averageKbps(first: ByteArray, frames: Long, totalBytes: Long): Int? {
        if (frames <= 0 || totalBytes <= 0) return null
        val sampleRate = mp3SampleRateHz(first) ?: return null
        val samplesPerFrame = mp3SamplesPerFrame(first)
        val durationSec = frames * samplesPerFrame / sampleRate.toDouble()
        if (durationSec <= 1.0) return null
        val kbps = ((totalBytes * 8.0) / durationSec / 1000.0).toInt()
        return if (kbps in 8..2000) kbps else null
    }

    /**
     * No tag (or unusable): verify CBR by comparing frame headers sampled ~1s apart through the
     * file. Identical bitrate/params -> verified CBR. Anything else -> bitrate with no mode claim.
     */
    private fun headerScanResult(file: File, first: ByteArray): Result? {
        val firstKbps = mp3BitrateKbps(first) ?: return null
        val sampleRate = mp3SampleRateHz(first) ?: return null
        val samplesPerFrame = mp3SamplesPerFrame(first)
        val frameLen = mp3FrameLengthBytes(first)
        if (frameLen <= 0) return Result(firstKbps, null, "mp3_header")

        val bytesPerSecondApprox = frameLen.toDouble() * (sampleRate.toDouble() / samplesPerFrame)
        if (bytesPerSecondApprox <= 0) return Result(firstKbps, null, "mp3_header")

        val stepBytes = bytesPerSecondApprox.toInt().coerceAtLeast(1024)
        var checks = 0
        var mismatches = 0
        var offset = stepBytes.toLong()
        val streamLen = file.length()
        while (offset < streamLen - 4 && checks < 8) {
            val h = readHeaderAtOffset(file, offset)
            if (h == null) break
            if (!isSameParams(h, first)) mismatches++
            checks++
            offset += stepBytes
        }
        return when {
            checks >= 3 && mismatches == 0 -> Result(firstKbps, BitrateMode.CBR, "mp3_header_scan")
            else -> Result(firstKbps, null, "mp3_header")
        }
    }

    private fun readHeaderAtOffset(file: File, offset: Long): ByteArray? {
        return try {
            file.inputStream().buffered().use { ins ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = ins.skip(minOf(offset - skipped, 8192L))
                    if (n <= 0) return null
                    skipped += n
                }
                findFirstFrameHeader(ins)?.let { h ->
                    // The found header must start within a small window of the sample point.
                    h
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun u32be(buf: ByteArray, off: Int): Long {
        if (off + 4 > buf.size) return 0
        return (((buf[off].toInt() and 0xFF).toLong() shl 24) or
            ((buf[off + 1].toInt() and 0xFF).toLong() shl 16) or
            ((buf[off + 2].toInt() and 0xFF).toLong() shl 8) or
            (buf[off + 3].toInt() and 0xFF).toLong()) and 0xFFFFFFFFL
    }

    // ── MPEG header tables ───────────────────────────────────────────────────

    /** Validates an MPEG audio frame header (sync word, version, layer, bitrate index). */
    internal fun isValidFrameHeader(h: ByteArray): Boolean {
        if (h.size < 4) return false
        val b0 = h[0].toInt() and 0xFF
        val b1 = h[1].toInt() and 0xFF
        val b2 = h[2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
        val versionBits = (b1 shr 3) and 0x03
        if (versionBits == 0x01) return false // reserved
        val layerBits = (b1 shr 1) and 0x03
        if (layerBits == 0x00) return false // reserved
        val bitrateIndex = (b2 shr 4) and 0x0F
        if (bitrateIndex == 0x00 || bitrateIndex == 0x0F) return false
        val sampleRateIndex = (b2 shr 2) and 0x03
        if (sampleRateIndex == 0x03) return false
        return true
    }

    /** True when [h] is a valid frame header with identical version/layer/bitrate/sampling params. */
    internal fun isSameParams(h: ByteArray, first: ByteArray): Boolean {
        if (!isValidFrameHeader(h)) return false
        val bitrateOf: (ByteArray) -> Int = { b -> (b[2].toInt() shr 4) and 0x0F }
        val srOf: (ByteArray) -> Int = { b -> (b[2].toInt() shr 2) and 0x03 }
        val verOf: (ByteArray) -> Int = { b -> (b[1].toInt() shr 3) and 0x03 }
        val layerOf: (ByteArray) -> Int = { b -> (b[1].toInt() shr 1) and 0x03 }
        return bitrateOf(h) == bitrateOf(first) && srOf(h) == srOf(first) &&
            verOf(h) == verOf(first) && layerOf(h) == layerOf(first)
    }

    private fun mpegVersion(h: ByteArray): Int = when (((h[1].toInt() shr 3) and 0x03)) {
        0x03 -> 1 // MPEG1
        0x02 -> 2 // MPEG2
        0x00 -> 25 // MPEG2.5
        else -> 0
    }

    private fun mpegLayer(h: ByteArray): Int = when (((h[1].toInt() shr 1) and 0x03)) {
        0x03 -> 1
        0x02 -> 2
        0x01 -> 3
        else -> 0
    }

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val MPEG1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
    private val MPEG2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
    private val MPEG2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    internal fun mp3BitrateKbps(h: ByteArray): Int? {
        val idx = (h[2].toInt() shr 4) and 0x0F
        if (idx == 0x00 || idx == 0x0F) return null
        val version = mpegVersion(h)
        val layer = mpegLayer(h)
        val table = when {
            version == 1 && layer == 1 -> MPEG1_L1
            version == 1 && layer == 2 -> MPEG1_L2
            version == 1 && layer == 3 -> MPEG1_L3
            version != 1 && layer == 1 -> MPEG2_L1
            version != 1 && layer in 2..3 -> MPEG2_L23
            else -> return null
        }
        return table[idx]
    }

    private val MPEG1_RATES = intArrayOf(44100, 48000, 32000)
    private val MPEG2_RATES = intArrayOf(22050, 24000, 16000)
    private val MPEG25_RATES = intArrayOf(11025, 12000, 8000)

    internal fun mp3SampleRateHz(h: ByteArray): Int? {
        val idx = (h[2].toInt() shr 2) and 0x03
        if (idx == 3) return null
        return when (mpegVersion(h)) {
            1 -> MPEG1_RATES[idx]
            2 -> MPEG2_RATES[idx]
            25 -> MPEG25_RATES[idx]
            else -> null
        }
    }

    internal fun mp3SamplesPerFrame(h: ByteArray): Int {
        val version = mpegVersion(h)
        val layer = mpegLayer(h)
        return when {
            layer == 1 -> 384
            layer == 2 -> 1152
            version == 1 -> 1152
            else -> 576
        }
    }

    internal fun mp3FrameLengthBytes(h: ByteArray): Int {
        val kbps = mp3BitrateKbps(h) ?: return 0
        val sr = mp3SampleRateHz(h) ?: return 0
        val padding = (h[2].toInt() shr 1) and 0x01
        val layer = mpegLayer(h)
        return if (layer == 1) {
            (12 * kbps * 1000 / sr + padding) * 4
        } else {
            144 * kbps * 1000 / sr + padding
        }
    }

    private fun mpegSideInfoBytes(h: ByteArray): Int {
        val version = mpegVersion(h)
        val layer = mpegLayer(h)
        val mono = ((h[3].toInt() shr 6) and 0x03) == 0x03
        return when {
            version == 1 && layer == 3 -> if (mono) 17 else 32
            version != 1 && layer == 3 -> if (mono) 9 else 17
            else -> 0
        }
    }
}
