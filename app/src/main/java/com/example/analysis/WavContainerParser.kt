package com.example.analysis

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Parsed container information for a RIFF WAVE file.
 */
data class WavContainerInfo(
    val isValid: Boolean,
    val audioFormat: Int = 1, // 1 = WAVE_FORMAT_PCM, 3 = WAVE_FORMAT_IEEE_FLOAT
    val numChannels: Int = 2,
    val sampleRate: Int = 44100,
    val byteRate: Int = 176400,
    val blockAlign: Int = 4,
    val bitsPerSample: Int = 16,
    val dataOffset: Long = 0L,
    val dataSize: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
) {
    val bitrateKbps: Int
        get() = if (byteRate > 0) {
            (byteRate * 8) / 1000
        } else {
            (sampleRate * numChannels * bitsPerSample) / 1000
        }
}

/**
 * Standards-compliant RIFF WAVE container parser.
 *
 * Implements the Microsoft/IBM RIFF specification:
 * Each chunk with an odd payload length is followed by a 0x00 padding byte.
 * Unlike Android AOSP's native WAVExtractor.cpp (which has a known bug skipping
 * odd-sized chunks before 'data' without advancing the pad byte), this parser
 * correctly advances the 1-byte pad, successfully discovering the 'data' chunk
 * and audio parameters on any valid WAV file.
 */
object WavContainerParser {

    private const val TAG = "WavContainerParser"

    fun parse(context: Context?, uriOrPath: String): WavContainerInfo {
        return try {
            if (uriOrPath.startsWith("content://") && context != null) {
                val uri = Uri.parse(uriOrPath)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    parseStream(stream)
                } ?: WavContainerInfo(false, errorMessage = "Cannot open content stream")
            } else {
                val clean = uriOrPath.removePrefix("file://")
                val file = File(clean)
                if (file.exists() && file.canRead()) {
                    FileInputStream(file).use { stream ->
                        parseStream(stream)
                    }
                } else {
                    WavContainerInfo(false, errorMessage = "File not readable: $clean")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing WAV container: ${e.message}")
            WavContainerInfo(false, errorMessage = e.message)
        }
    }

    fun parseStream(stream: InputStream): WavContainerInfo {
        val header = ByteArray(12)
        if (readFully(stream, header, 12) < 12) {
            return WavContainerInfo(false, errorMessage = "File too small (< 12 bytes)")
        }

        if (header[0] != 'R'.code.toByte() || header[1] != 'I'.code.toByte() ||
            header[2] != 'F'.code.toByte() || header[3] != 'F'.code.toByte() ||
            header[8] != 'W'.code.toByte() || header[9] != 'A'.code.toByte() ||
            header[10] != 'V'.code.toByte() || header[11] != 'E'.code.toByte()
        ) {
            return WavContainerInfo(false, errorMessage = "Invalid RIFF/WAVE header")
        }

        var currentOffset = 12L
        var validFmt = false
        var audioFormat = 1
        var numChannels = 2
        var sampleRate = 44100
        var byteRate = 176400
        var blockAlign = 4
        var bitsPerSample = 16

        var dataOffset = 0L
        var dataSize = 0L

        val chunkHeader = ByteArray(8)

        while (true) {
            val r = readFully(stream, chunkHeader, 8)
            if (r < 8) break
            currentOffset += 8

            val cid = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val csize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val pad = if (csize % 2L != 0L) 1L else 0L

            if (cid == "fmt ") {
                if (csize < 16) {
                    return WavContainerInfo(false, errorMessage = "Corrupt fmt chunk (size < 16)")
                }
                val fmtData = ByteArray(csize.toInt())
                if (readFully(stream, fmtData, csize.toInt()) < csize.toInt()) {
                    return WavContainerInfo(false, errorMessage = "Truncated fmt chunk")
                }
                currentOffset += csize
                if (pad > 0) {
                    skipFully(stream, pad)
                    currentOffset += pad
                }

                val buf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                audioFormat = buf.short.toInt() and 0xFFFF
                numChannels = buf.short.toInt() and 0xFFFF
                sampleRate = buf.int
                byteRate = buf.int
                blockAlign = buf.short.toInt() and 0xFFFF
                bitsPerSample = buf.short.toInt() and 0xFFFF
                validFmt = true
            } else if (cid == "data") {
                dataOffset = currentOffset
                dataSize = csize
                // Found data chunk!
                break
            } else {
                // Unknown or metadata chunk (e.g. 'id3 ', 'LIST', 'bext')
                // Skip payload + pad byte according to RIFF specification
                skipFully(stream, csize + pad)
                currentOffset += csize + pad
            }
        }

        if (!validFmt || dataOffset == 0L || dataSize == 0L) {
            return WavContainerInfo(false, errorMessage = "Missing fmt or data chunk (validFmt=$validFmt, dataOffset=$dataOffset)")
        }

        val bytesPerSec = if (byteRate > 0) byteRate else (sampleRate * numChannels * (bitsPerSample / 8)).coerceAtLeast(1)
        val durationMs = (dataSize * 1000L) / bytesPerSec

        return WavContainerInfo(
            isValid = true,
            audioFormat = audioFormat,
            numChannels = numChannels,
            sampleRate = sampleRate,
            byteRate = bytesPerSec,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize,
            durationMs = durationMs
        )
    }

    internal fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val count = stream.read(buffer, total, length - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    internal fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buf = ByteArray(minOf(4096L, remaining).toInt())
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = stream.read(buf, 0, toRead)
            if (r < 0) break
            remaining -= r
        }
    }
}

/**
 * Direct PCM stream reader for WAV files.
 * Streams decoded 16-bit PCM stereo frames into ShortArray buffers with seek capability.
 * Completely independent of Android's native MediaExtractor / MediaCodec stack.
 */
class WavPcmReader(
    private val context: Context?,
    private val uriOrPath: String,
    val info: WavContainerInfo
) : AutoCloseable {

    private var pfd: ParcelFileDescriptor? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var fileChannel: FileChannel? = null
    private var inputStream: InputStream? = null
    private var currentDataOffset: Long = 0L // relative to dataOffset (0 .. dataSize)
    var isEos: Boolean = false
        private set

    init {
        openStream(0L)
    }

    private fun openStream(startRelativeOffset: Long) {
        closeHandles()
        currentDataOffset = startRelativeOffset.coerceIn(0L, info.dataSize)
        val targetAbsoluteOffset = info.dataOffset + currentDataOffset

        if (uriOrPath.startsWith("content://") && context != null) {
            val uri = Uri.parse(uriOrPath)
            var opened = false
            try {
                val openedPfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (openedPfd != null) {
                    pfd = openedPfd
                    val fis = FileInputStream(openedPfd.fileDescriptor)
                    val channel = fis.channel
                    channel.position(targetAbsoluteOffset)
                    fileChannel = channel
                    opened = true
                }
            } catch (_: Throwable) {}

            if (!opened) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        WavContainerParser.skipFully(stream, targetAbsoluteOffset)
                        inputStream = stream
                        opened = true
                    }
                } catch (_: Throwable) {}
            }
        } else {
            val clean = uriOrPath.removePrefix("file://")
            val file = File(clean)
            if (file.exists() && file.canRead()) {
                val raf = RandomAccessFile(file, "r")
                raf.seek(targetAbsoluteOffset)
                randomAccessFile = raf
            }
        }
        isEos = currentDataOffset >= info.dataSize
    }

    fun seekToMs(positionMs: Long) {
        val targetBytes = ((positionMs * info.byteRate) / 1000L).coerceIn(0L, info.dataSize)
        val alignedBytes = if (info.blockAlign > 0) (targetBytes / info.blockAlign) * info.blockAlign else targetBytes
        val targetAbs = info.dataOffset + alignedBytes

        try {
            if (randomAccessFile != null) {
                randomAccessFile?.seek(targetAbs)
                currentDataOffset = alignedBytes
                isEos = currentDataOffset >= info.dataSize
                return
            }
            if (fileChannel != null) {
                fileChannel?.position(targetAbs)
                currentDataOffset = alignedBytes
                isEos = currentDataOffset >= info.dataSize
                return
            }
        } catch (_: Throwable) {}

        // Fallback for sequential streams
        openStream(alignedBytes)
    }

    fun readStereoFrames(outputStereo: ShortArray, maxFrames: Int): Int {
        if (isEos || currentDataOffset >= info.dataSize) {
            isEos = true
            return -1
        }
        val framesToRead = minOf(maxFrames, outputStereo.size / 2)
        if (framesToRead <= 0) return 0

        val bytesNeeded = framesToRead * info.blockAlign
        val bytesAvailable = (info.dataSize - currentDataOffset).coerceAtLeast(0L)
        val bytesToRead = minOf(bytesNeeded.toLong(), bytesAvailable).toInt()
        if (bytesToRead <= 0) {
            isEos = true
            return -1
        }

        val rawBytes = ByteArray(bytesToRead)
        val actuallyRead = readRawBytes(rawBytes, 0, bytesToRead)
        if (actuallyRead <= 0) {
            isEos = true
            return -1
        }
        currentDataOffset += actuallyRead

        val framesRead = unpackPcmToStereo(rawBytes, actuallyRead, outputStereo)
        if (currentDataOffset >= info.dataSize) {
            isEos = true
        }
        return framesRead
    }

    fun readMonoFloats(maxSamples: Int): FloatArray? {
        if (isEos || currentDataOffset >= info.dataSize) return null
        val stereoBuf = ShortArray(maxSamples * 2)
        val frames = readStereoFrames(stereoBuf, maxSamples)
        if (frames <= 0) return null
        val floats = FloatArray(frames)
        for (i in 0 until frames) {
            val l = stereoBuf[i * 2].toFloat() / 32768f
            val r = stereoBuf[i * 2 + 1].toFloat() / 32768f
            floats[i] = (l + r) * 0.5f
        }
        return floats
    }

    private fun readRawBytes(target: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        try {
            if (randomAccessFile != null) {
                while (total < length) {
                    val r = randomAccessFile?.read(target, offset + total, length - total) ?: -1
                    if (r < 0) break
                    total += r
                }
                return if (total > 0) total else -1
            }
            if (fileChannel != null) {
                val bb = ByteBuffer.wrap(target, offset, length)
                while (bb.hasRemaining()) {
                    val r = fileChannel?.read(bb) ?: -1
                    if (r < 0) break
                    total += r
                }
                return if (total > 0) total else -1
            }
            if (inputStream != null) {
                while (total < length) {
                    val r = inputStream?.read(target, offset + total, length - total) ?: -1
                    if (r < 0) break
                    total += r
                }
                return if (total > 0) total else -1
            }
        } catch (_: Throwable) {}
        return if (total > 0) total else -1
    }

    private fun unpackPcmToStereo(rawBytes: ByteArray, numBytes: Int, outputStereo: ShortArray): Int {
        val channels = info.numChannels
        val blockAlign = info.blockAlign.coerceAtLeast(1)
        val fullFrames = numBytes / blockAlign
        val framesToConvert = minOf(fullFrames, outputStereo.size / 2)

        if (info.audioFormat == 1) {
            // PCM Integer
            when (info.bitsPerSample) {
                16 -> {
                    val bb = ByteBuffer.wrap(rawBytes, 0, framesToConvert * blockAlign).order(ByteOrder.LITTLE_ENDIAN)
                    if (channels == 2) {
                        val sb = bb.asShortBuffer()
                        val count = framesToConvert * 2
                        sb.get(outputStereo, 0, count)
                        return framesToConvert
                    } else if (channels == 1) {
                        for (i in 0 until framesToConvert) {
                            val s = bb.short
                            val outIdx = i * 2
                            outputStereo[outIdx] = s
                            outputStereo[outIdx + 1] = s
                        }
                        return framesToConvert
                    } else {
                        // Multi-channel (> 2) -> downmix first 2 channels
                        for (i in 0 until framesToConvert) {
                            val left = bb.short
                            val right = bb.short
                            for (c in 2 until channels) bb.short // skip remaining channels
                            val outIdx = i * 2
                            outputStereo[outIdx] = left
                            outputStereo[outIdx + 1] = right
                        }
                        return framesToConvert
                    }
                }
                24 -> {
                    for (i in 0 until framesToConvert) {
                        val base = i * blockAlign
                        val l = ((rawBytes[base + 1].toInt() and 0xFF) or (rawBytes[base + 2].toInt() shl 8)).toShort()
                        val r = if (channels >= 2) {
                            ((rawBytes[base + 4].toInt() and 0xFF) or (rawBytes[base + 5].toInt() shl 8)).toShort()
                        } else l
                        outputStereo[i * 2] = l
                        outputStereo[i * 2 + 1] = r
                    }
                    return framesToConvert
                }
                32 -> {
                    for (i in 0 until framesToConvert) {
                        val base = i * blockAlign
                        val l = ((rawBytes[base + 2].toInt() and 0xFF) or (rawBytes[base + 3].toInt() shl 8)).toShort()
                        val r = if (channels >= 2) {
                            ((rawBytes[base + 6].toInt() and 0xFF) or (rawBytes[base + 7].toInt() shl 8)).toShort()
                        } else l
                        outputStereo[i * 2] = l
                        outputStereo[i * 2 + 1] = r
                    }
                    return framesToConvert
                }
                8 -> {
                    for (i in 0 until framesToConvert) {
                        val base = i * blockAlign
                        val l = (((rawBytes[base].toInt() and 0xFF) - 128) shl 8).toShort()
                        val r = if (channels >= 2) {
                            (((rawBytes[base + 1].toInt() and 0xFF) - 128) shl 8).toShort()
                        } else l
                        outputStereo[i * 2] = l
                        outputStereo[i * 2 + 1] = r
                    }
                    return framesToConvert
                }
            }
        } else if (info.audioFormat == 3) {
            // IEEE Float 32-bit
            val bb = ByteBuffer.wrap(rawBytes, 0, framesToConvert * blockAlign).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until framesToConvert) {
                val l = (bb.float * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                val r = if (channels >= 2) {
                    (bb.float * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                } else l
                for (c in 2 until channels) bb.float // skip
                outputStereo[i * 2] = l
                outputStereo[i * 2 + 1] = r
            }
            return framesToConvert
        }

        return 0
    }

    private fun closeHandles() {
        try { randomAccessFile?.close() } catch (_: Throwable) {}
        try { fileChannel?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        try { inputStream?.close() } catch (_: Throwable) {}
        randomAccessFile = null
        fileChannel = null
        pfd = null
        inputStream = null
    }

    override fun close() {
        closeHandles()
    }
}

