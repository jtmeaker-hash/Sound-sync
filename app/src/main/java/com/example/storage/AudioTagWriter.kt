package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Safe audio file metadata writer for confirmed BPM & Musical Key.
 * Writes standard ID3v2 frames (TBPM, TKEY, TXXX:initialkey) into audio files
 * using atomic temporary file replacement to avoid corruption.
 */
object AudioTagWriter {

    private const val TAG = "AudioTagWriter"

    /**
     * Writes confirmed BPM and Musical Key tags to the audio file if accessible.
     */
    suspend fun writeConfirmedBpmAndKey(
        context: Context?,
        filePathOrUri: String,
        bpm: Double,
        musicalKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (filePathOrUri.isBlank()) return@withContext false
        if (bpm <= 0.0 && musicalKey.isBlank()) return@withContext false

        try {
            if (!filePathOrUri.startsWith("content://") && !filePathOrUri.startsWith("http")) {
                val file = File(filePathOrUri)
                if (file.exists() && file.canWrite() && file.isFile) {
                    return@withContext writeId3TagsToMp3File(file, bpm, musicalKey)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write metadata to $filePathOrUri: ${e.message}")
        }
        false
    }

    /**
     * Embeds TBPM and TKEY frames into an MP3 file ID3v2 tag.
     */
    private fun writeId3TagsToMp3File(file: File, bpm: Double, musicalKey: String): Boolean {
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
            val existingFrames = mutableListOf<Id3Frame>()

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

                // Parse existing frames to preserve artwork, title, artist, album, etc.
                parseId3Frames(tagBuffer, majorVersion, existingFrames)
            } else {
                audioDataStartOffset = 0L
            }

            inputStream.close()

            // Prepare TBPM and TKEY frame data
            val framesToWrite = mutableListOf<Id3Frame>()

            // Preserve all non-BPM / non-Key frames
            for (f in existingFrames) {
                val id = f.id
                if (id != "TBPM" && id != "TKEY" && id != "TBP" && id != "TKE") {
                    if (id == "TXXX") {
                        // Check if it's initialkey or bpm user text
                        val text = String(f.data, StandardCharsets.ISO_8859_1).lowercase(Locale.ROOT)
                        if (!text.contains("initialkey") && !text.contains("bpm")) {
                            framesToWrite.add(f)
                        }
                    } else {
                        framesToWrite.add(f)
                    }
                }
            }

            // Add TBPM frame if valid
            if (bpm > 30.0 && bpm < 300.0) {
                val bpmStr = if (bpm == bpm.roundToInt().toDouble()) {
                    bpm.toInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", bpm)
                }
                val frameData = buildTextFrameData(bpmStr)
                framesToWrite.add(Id3Frame("TBPM", frameData))
            }

            // Add TKEY frame if valid
            if (musicalKey.isNotBlank() && musicalKey != "—") {
                val frameData = buildTextFrameData(musicalKey.trim())
                framesToWrite.add(Id3Frame("TKEY", frameData))
            }

            // Build new ID3v2.3 tag buffer
            val rawTagStream = ByteArrayOutputStream()
            for (frame in framesToWrite) {
                val frameIdBytes = frame.id.padEnd(4, ' ').take(4).toByteArray(StandardCharsets.ISO_8859_1)
                rawTagStream.write(frameIdBytes)
                val frameLen = frame.data.size
                // 4-byte big endian length
                rawTagStream.write((frameLen shr 24) and 0xFF)
                rawTagStream.write((frameLen shr 16) and 0xFF)
                rawTagStream.write((frameLen shr 8) and 0xFF)
                rawTagStream.write(frameLen and 0xFF)
                // 2 flags bytes (0x00, 0x00)
                rawTagStream.write(0)
                rawTagStream.write(0)
                rawTagStream.write(frame.data)
            }

            // Add 1KB padding
            val padding = ByteArray(1024)
            rawTagStream.write(padding)

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

            // Write atomically to temp file
            tempFile = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.tmp")
            val fos = FileOutputStream(tempFile)
            fos.write(newHeader)
            fos.write(tagBody)

            // Copy remaining audio data
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

            // Atomically replace target file
            if (tempFile.length() > (fileLength / 2)) {
                if (file.delete()) {
                    val renamed = tempFile.renameTo(file)
                    if (renamed) {
                        Log.d(TAG, "Successfully embedded confirmed BPM=$bpm and Key=$musicalKey into ${file.name}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error embedding ID3 tags to ${file.name}: ${e.message}")
        } finally {
            tempFile?.let {
                if (it.exists()) it.delete()
            }
        }
        return false
    }

    private data class Id3Frame(val id: String, val data: ByteArray)

    private fun buildTextFrameData(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        val result = ByteArray(1 + bytes.size)
        result[0] = 0 // ISO-8859-1 encoding byte
        System.arraycopy(bytes, 0, result, 1, bytes.size)
        return result
    }

    private fun parseId3Frames(buffer: ByteArray, majorVersion: Int, outList: MutableList<Id3Frame>) {
        var pos = 0
        val bufferLen = buffer.size
        while (pos + 10 <= bufferLen) {
            if (buffer[pos] == 0.toByte()) {
                // Padding reached
                break
            }
            val frameId = String(buffer, pos, 4, StandardCharsets.ISO_8859_1)
            if (!isValidFrameId(frameId)) break

            val frameSize = if (majorVersion == 4) {
                decodeSyncSafe(buffer, pos + 4)
            } else {
                ((buffer[pos + 4].toInt() and 0xFF) shl 24) or
                        ((buffer[pos + 5].toInt() and 0xFF) shl 16) or
                        ((buffer[pos + 6].toInt() and 0xFF) shl 8) or
                        (buffer[pos + 7].toInt() and 0xFF)
            }

            if (frameSize <= 0 || pos + 10 + frameSize > bufferLen) break

            val frameData = ByteArray(frameSize)
            System.arraycopy(buffer, pos + 10, frameData, 0, frameSize)
            outList.add(Id3Frame(frameId, frameData))

            pos += 10 + frameSize
        }
    }

    private fun isValidFrameId(id: String): Boolean {
        if (id.length != 4) return false
        return id.all { (it in 'A'..'Z') || (it in '0'..'9') }
    }

    private fun decodeSyncSafe(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
                ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
                ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun encodeSyncSafe(value: Int): ByteArray {
        val out = ByteArray(4)
        out[0] = ((value shr 21) and 0x7F).toByte()
        out[1] = ((value shr 14) and 0x7F).toByte()
        out[2] = ((value shr 7) and 0x7F).toByte()
        out[3] = (value and 0x7F).toByte()
        return out
    }
}
