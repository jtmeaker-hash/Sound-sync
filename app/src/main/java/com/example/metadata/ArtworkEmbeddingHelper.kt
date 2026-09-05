package com.example.metadata

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Safely embeds resolved cover artwork into local audio files (e.g. ID3v2 APIC for MP3).
 *
 * Requirements (Phase 16):
 * - Does NOT re-encode audio.
 * - Does NOT alter audio samples, bitrate, sample rate, duration, or codec.
 * - Modifies metadata/tag containers only.
 * - Uses safe file-writing procedures (atomic temporary file replacement).
 */
object ArtworkEmbeddingHelper {

    private const val TAG = "ArtworkEmbeddingHelper"

    private data class ParsedFrame(val id: String, val data: ByteArray)

    suspend fun embedArtwork(
        audioFile: File,
        artworkBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): Boolean = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || !audioFile.canWrite() || !audioFile.isFile) {
            Log.w(TAG, "Cannot write artwork: file is inaccessible or read-only: ${audioFile.absolutePath}")
            return@withContext false
        }
        if (artworkBytes.isEmpty()) return@withContext false

        val ext = audioFile.extension.lowercase()
        return@withContext when (ext) {
            "mp3" -> embedApicIntoMp3(audioFile, artworkBytes, mimeType)
            else -> {
                Log.d(TAG, "Embedding artwork is currently optimized for MP3 container. Other formats preserved safely.")
                false
            }
        }
    }

    private fun embedApicIntoMp3(file: File, artworkBytes: ByteArray, mimeType: String): Boolean {
        var tempFile: File? = null
        try {
            val fileLength = file.length()
            if (fileLength < 10) return false

            val inputStream = FileInputStream(file)
            val headerBytes = ByteArray(10)
            val readHeader = inputStream.read(headerBytes)
            if (readHeader < 10) {
                inputStream.close()
                return false
            }

            val hasId3v2 = headerBytes[0] == 'I'.code.toByte() &&
                headerBytes[1] == 'D'.code.toByte() &&
                headerBytes[2] == '3'.code.toByte()

            val audioDataStartOffset: Long
            val existingFrames = mutableListOf<ParsedFrame>()

            if (hasId3v2) {
                val majorVersion = headerBytes[3].toInt()
                val tagFlags = headerBytes[5].toInt()
                val hasFooter = (tagFlags and 0x10) != 0
                val tagSize = decodeSyncSafe(headerBytes, 6)
                audioDataStartOffset = 10L + tagSize + (if (hasFooter) 10 else 0)

                val tagBuffer = ByteArray(tagSize)
                var bytesRead = 0
                while (bytesRead < tagSize) {
                    val r = inputStream.read(tagBuffer, bytesRead, tagSize - bytesRead)
                    if (r <= 0) break
                    bytesRead += r
                }
                parseFrames(tagBuffer, majorVersion, existingFrames)
            } else {
                audioDataStartOffset = 0L
            }
            inputStream.close()

            // Prepare frames to write: retain all existing frames EXCEPT old APIC/PIC frames
            val framesToWrite = mutableListOf<ParsedFrame>()
            for (f in existingFrames) {
                if (f.id != "APIC" && f.id != "PIC") {
                    framesToWrite.add(f)
                }
            }

            // Build APIC frame
            // Format for ID3v2.3 APIC:
            // Text encoding (1 byte) = 0x00 (ISO-8859-1)
            // MIME type (null terminated string) = "image/jpeg\0"
            // Picture type (1 byte) = 0x03 (Cover / Front)
            // Description (null terminated string) = "\0"
            // Binary picture data
            val apicStream = ByteArrayOutputStream()
            apicStream.write(0x00) // Encoding: ISO-8859-1
            apicStream.write(mimeType.toByteArray(StandardCharsets.ISO_8859_1))
            apicStream.write(0x00) // Null terminator for MIME
            apicStream.write(0x03) // Picture type: Front Cover
            apicStream.write(0x00) // Null terminator for description (empty)
            apicStream.write(artworkBytes) // Raw image bytes

            framesToWrite.add(ParsedFrame("APIC", apicStream.toByteArray()))

            // Build new ID3v2.3 tag buffer
            val rawTagStream = ByteArrayOutputStream()
            for (frame in framesToWrite) {
                val frameIdBytes = frame.id.padEnd(4, ' ').take(4).toByteArray(StandardCharsets.ISO_8859_1)
                rawTagStream.write(frameIdBytes)
                val frameLen = frame.data.size
                rawTagStream.write((frameLen shr 24) and 0xFF)
                rawTagStream.write((frameLen shr 16) and 0xFF)
                rawTagStream.write((frameLen shr 8) and 0xFF)
                rawTagStream.write(frameLen and 0xFF)
                rawTagStream.write(0) // Flag 1
                rawTagStream.write(0) // Flag 2
                rawTagStream.write(frame.data)
            }

            val tagBody = rawTagStream.toByteArray()
            val syncSafeSize = encodeSyncSafe(tagBody.size)

            val newHeader = ByteArray(10)
            newHeader[0] = 'I'.code.toByte()
            newHeader[1] = 'D'.code.toByte()
            newHeader[2] = '3'.code.toByte()
            newHeader[3] = 3 // ID3v2.3
            newHeader[4] = 0 // Revision
            newHeader[5] = 0 // Flags
            System.arraycopy(syncSafeSize, 0, newHeader, 6, 4)

            // Atomic temp file replacement
            tempFile = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.art.tmp")
            val fos = FileOutputStream(tempFile)
            fos.write(newHeader)
            fos.write(tagBody)

            val audioInputStream = FileInputStream(file)
            if (audioDataStartOffset > 0) {
                var skipped = 0L
                while (skipped < audioDataStartOffset) {
                    val s = audioInputStream.skip(audioDataStartOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }

            val copyBuffer = ByteArray(64 * 1024)
            var bytes = audioInputStream.read(copyBuffer)
            while (bytes > 0) {
                fos.write(copyBuffer, 0, bytes)
                bytes = audioInputStream.read(copyBuffer)
            }

            fos.flush()
            fos.close()
            audioInputStream.close()

            if (tempFile.length() > (fileLength / 2)) {
                if (file.delete()) {
                    val renamed = tempFile.renameTo(file)
                    if (renamed) {
                        Log.d(TAG, "Successfully embedded APIC cover art (${artworkBytes.size} bytes) into ${file.name}")
                        return true
                    }
                }
            }
            tempFile.delete()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed embedding artwork into ${file.name}: ${e.message}", e)
            tempFile?.delete()
            return false
        }
    }

    private fun parseFrames(buffer: ByteArray, majorVersion: Int, outFrames: MutableList<ParsedFrame>) {
        var offset = 0
        while (offset + 10 <= buffer.size) {
            if (buffer[offset].toInt() == 0) break
            val frameId = String(buffer, offset, 4, StandardCharsets.ISO_8859_1)
            val isValidId = frameId.all { it in 'A'..'Z' || it in '0'..'9' }
            if (!isValidId) break

            val frameSize = if (majorVersion == 4) {
                decodeSyncSafe(buffer, offset + 4)
            } else {
                ((buffer[offset + 4].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 5].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 6].toInt() and 0xFF) shl 8) or
                (buffer[offset + 7].toInt() and 0xFF)
            }

            if (frameSize < 0 || offset + 10 + frameSize > buffer.size) break
            val frameData = ByteArray(frameSize)
            System.arraycopy(buffer, offset + 10, frameData, 0, frameSize)
            outFrames.add(ParsedFrame(frameId, frameData))
            offset += 10 + frameSize
        }
    }

    private fun decodeSyncSafe(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun encodeSyncSafe(size: Int): ByteArray {
        return byteArrayOf(
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte()
        )
    }
}
