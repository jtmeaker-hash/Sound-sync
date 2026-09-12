package com.example.analysis

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Result of header sniffing on an audio candidate file or stream.
 */
data class AudioSniffResult(
    val isRecognizedAudio: Boolean,
    val containerFormat: String?, // "WAV", "MP3", "FLAC", "OGG", "M4A", "AAC", "AIFF"
    val detectedMime: String?,
    val headerHex: String,
    val streamLength: Long = 0L
)

/**
 * Robust audio format sniffer that determines audio container types from magic bytes
 * rather than trusting file extensions alone.
 */
object AudioFormatSniffer {

    /**
     * Sniffs format from a URI or filesystem path.
     */
    fun sniffFormat(context: Context, uriOrPath: String): AudioSniffResult {
        return try {
            if (uriOrPath.startsWith("content://")) {
                val uri = Uri.parse(uriOrPath)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    sniffStream(stream)
                } ?: AudioSniffResult(false, null, null, "CANNOT_OPEN_STREAM")
            } else {
                val clean = uriOrPath.removePrefix("file://")
                val f = File(clean)
                if (f.exists() && f.canRead()) {
                    FileInputStream(f).use { stream ->
                        sniffStream(stream, f.length())
                    }
                } else {
                    AudioSniffResult(false, null, null, "FILE_NOT_READABLE")
                }
            }
        } catch (e: Exception) {
            AudioSniffResult(false, null, null, "ERROR: ${e.message}")
        }
    }

    /**
     * Sniffs format directly from an InputStream, reading up to 64 bytes.
     */
    fun sniffStream(stream: InputStream, totalLength: Long = 0L): AudioSniffResult {
        val header = ByteArray(64)
        var bytesRead = 0
        while (bytesRead < 64) {
            val count = stream.read(header, bytesRead, 64 - bytesRead)
            if (count <= 0) break
            bytesRead += count
        }
        val actualBytes = header.copyOf(bytesRead)
        val hex = actualBytes.joinToString(" ") { "%02X".format(it) }

        if (bytesRead < 4) {
            return AudioSniffResult(false, null, null, hex, totalLength)
        }

        // 1. WAV / RIFF: "RIFF" .... "WAVE"
        if (bytesRead >= 12 &&
            actualBytes[0] == 'R'.code.toByte() && actualBytes[1] == 'I'.code.toByte() &&
            actualBytes[2] == 'F'.code.toByte() && actualBytes[3] == 'F'.code.toByte() &&
            actualBytes[8] == 'W'.code.toByte() && actualBytes[9] == 'A'.code.toByte() &&
            actualBytes[10] == 'V'.code.toByte() && actualBytes[11] == 'E'.code.toByte()
        ) {
            return AudioSniffResult(true, "WAV", "audio/wav", hex, totalLength)
        }

        // 2. FLAC: "fLaC"
        if (actualBytes[0] == 'f'.code.toByte() && actualBytes[1] == 'L'.code.toByte() &&
            actualBytes[2] == 'a'.code.toByte() && actualBytes[3] == 'C'.code.toByte()
        ) {
            return AudioSniffResult(true, "FLAC", "audio/flac", hex, totalLength)
        }

        // 3. MP3: "ID3" or sync word 0xFF 0xFB / 0xFA / 0xF3 / 0xF2
        if (actualBytes[0] == 'I'.code.toByte() && actualBytes[1] == 'D'.code.toByte() && actualBytes[2] == '3'.code.toByte()) {
            return AudioSniffResult(true, "MP3", "audio/mpeg", hex, totalLength)
        }
        if (actualBytes[0] == 0xFF.toByte() && (actualBytes[1].toInt() and 0xE0) == 0xE0) {
            return AudioSniffResult(true, "MP3", "audio/mpeg", hex, totalLength)
        }

        // 4. OGG: "OggS"
        if (actualBytes[0] == 'O'.code.toByte() && actualBytes[1] == 'g'.code.toByte() &&
            actualBytes[2] == 'g'.code.toByte() && actualBytes[3] == 'S'.code.toByte()
        ) {
            return AudioSniffResult(true, "OGG", "audio/ogg", hex, totalLength)
        }

        // 5. MP4 / M4A: "ftyp" at offset 4
        if (bytesRead >= 8 &&
            actualBytes[4] == 'f'.code.toByte() && actualBytes[5] == 't'.code.toByte() &&
            actualBytes[6] == 'y'.code.toByte() && actualBytes[7] == 'p'.code.toByte()
        ) {
            return AudioSniffResult(true, "M4A", "audio/mp4", hex, totalLength)
        }

        // 6. ADTS AAC: 0xFF 0xF1 or 0xFF 0xF9
        if (actualBytes[0] == 0xFF.toByte() && (actualBytes[1].toInt() and 0xF6) == 0xF0) {
            return AudioSniffResult(true, "AAC", "audio/aac", hex, totalLength)
        }

        // 7. AIFF: "FORM" .... "AIFF" or "AIFC"
        if (bytesRead >= 12 &&
            actualBytes[0] == 'F'.code.toByte() && actualBytes[1] == 'O'.code.toByte() &&
            actualBytes[2] == 'R'.code.toByte() && actualBytes[3] == 'M'.code.toByte() &&
            actualBytes[8] == 'A'.code.toByte() && actualBytes[9] == 'I'.code.toByte() &&
            actualBytes[10] == 'F'.code.toByte()
        ) {
            return AudioSniffResult(true, "AIFF", "audio/x-aiff", hex, totalLength)
        }

        return AudioSniffResult(false, null, null, hex, totalLength)
    }
}
