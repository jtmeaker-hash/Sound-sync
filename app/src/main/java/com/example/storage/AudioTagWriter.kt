package com.example.storage

import android.content.Context
import android.graphics.BitmapFactory
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

data class CompleteTagPayload(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val totalTracks: Int? = null,
    val discNumber: Int? = null,
    val totalDiscs: Int? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val artworkBytes: ByteArray? = null,
    val artworkMimeType: String = "image/jpeg"
)

/**
 * Authoritative format-preserving audio file tag and artwork writer (Sections 13, 14, 15).
 *
 * Requirements:
 * - Does NOT transcode or re-encode audio.
 * - Does NOT alter sample rate, channels, bitrate, or audio duration.
 * - Writes canonical textual tags and front cover artwork into the audio container.
 * - Supports MP3 (ID3v2.3) and FLAC (Vorbis Comment + Picture Metadata Block).
 * - Utilizes atomic temporary file replacement to prevent file corruption.
 */
object AudioTagWriter {

    private const val TAG = "AudioTagWriter"

    /**
     * Legacy helper to write confirmed BPM & Key.
     */
    suspend fun writeConfirmedBpmAndKey(
        context: Context?,
        filePathOrUri: String,
        bpm: Double,
        musicalKey: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (filePathOrUri.isBlank()) return@withContext false
        if (bpm <= 0.0 && musicalKey.isBlank()) return@withContext false

        writeCompleteTags(
            context = context,
            filePathOrUri = filePathOrUri,
            payload = CompleteTagPayload(bpm = bpm, musicalKey = musicalKey)
        )
    }

    /**
     * Atomically writes complete textual metadata and front cover artwork
     * into the local audio file.
     */
    suspend fun writeCompleteTags(
        context: Context?,
        filePathOrUri: String,
        payload: CompleteTagPayload
    ): Boolean = withContext(Dispatchers.IO) {
        if (filePathOrUri.isBlank() || filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("http")) {
            Log.w(TAG, "Cannot write tags: invalid or SAF path: $filePathOrUri")
            return@withContext false
        }

        val file = File(filePathOrUri)
        if (!file.exists() || !file.canWrite() || !file.isFile) {
            Log.w(TAG, "Cannot write tags: file is inaccessible or read-only: ${file.absolutePath}")
            return@withContext false
        }

        val ext = file.extension.lowercase(Locale.ROOT)
        return@withContext when (ext) {
            "mp3" -> writeMp3Tags(file, payload)
            "flac" -> writeFlacTags(file, payload)
            else -> {
                Log.d(TAG, "Tag writing not supported for container extension .$ext; file preserved unmodified.")
                false
            }
        }
    }

    // =========================================================================
    // MP3 (ID3v2.3) IMPLEMENTATION
    // =========================================================================

    private fun writeMp3Tags(file: File, payload: CompleteTagPayload): Boolean {
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
                parseId3Frames(tagBuffer, majorVersion, existingFrames)
            } else {
                audioDataStartOffset = 0L
            }
            inputStream.close()

            // Frames to replace
            val updatingFrameIds = mutableSetOf<String>()
            if (!payload.title.isNullOrBlank()) updatingFrameIds.addAll(listOf("TIT2", "TT2"))
            if (!payload.artist.isNullOrBlank()) updatingFrameIds.addAll(listOf("TPE1", "TP1"))
            if (!payload.album.isNullOrBlank()) updatingFrameIds.addAll(listOf("TALB", "TAL"))
            if (!payload.genre.isNullOrBlank()) updatingFrameIds.addAll(listOf("TCON", "TCO"))
            if (payload.trackNumber != null) updatingFrameIds.addAll(listOf("TRCK", "TRK"))
            if (payload.discNumber != null) updatingFrameIds.addAll(listOf("TPOS", "TPA"))
            if (payload.releaseYear != null || !payload.releaseDate.isNullOrBlank()) updatingFrameIds.addAll(listOf("TYER", "TYE", "TDRC"))
            if (payload.bpm != null && payload.bpm > 0) updatingFrameIds.addAll(listOf("TBPM", "TBP"))
            if (!payload.musicalKey.isNullOrBlank()) updatingFrameIds.addAll(listOf("TKEY", "TKE"))
            if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) updatingFrameIds.addAll(listOf("APIC", "PIC"))

            val framesToWrite = mutableListOf<Id3Frame>()
            for (f in existingFrames) {
                if (!updatingFrameIds.contains(f.id)) {
                    if (f.id == "TXXX" && (!payload.musicalKey.isNullOrBlank() || (payload.bpm != null && payload.bpm > 0))) {
                        val text = String(f.data, StandardCharsets.ISO_8859_1).lowercase(Locale.ROOT)
                        if (!text.contains("initialkey") && !text.contains("bpm")) {
                            framesToWrite.add(f)
                        }
                    } else {
                        framesToWrite.add(f)
                    }
                }
            }

            // Append updated frames
            if (!payload.title.isNullOrBlank()) {
                framesToWrite.add(Id3Frame("TIT2", buildTextFrameData(payload.title)))
            }
            if (!payload.artist.isNullOrBlank()) {
                framesToWrite.add(Id3Frame("TPE1", buildTextFrameData(payload.artist)))
            }
            if (!payload.album.isNullOrBlank()) {
                framesToWrite.add(Id3Frame("TALB", buildTextFrameData(payload.album)))
            }
            if (!payload.genre.isNullOrBlank()) {
                framesToWrite.add(Id3Frame("TCON", buildTextFrameData(payload.genre)))
            }
            if (payload.trackNumber != null && payload.trackNumber > 0) {
                val trkStr = if (payload.totalTracks != null && payload.totalTracks > 0) {
                    "${payload.trackNumber}/${payload.totalTracks}"
                } else {
                    "${payload.trackNumber}"
                }
                framesToWrite.add(Id3Frame("TRCK", buildTextFrameData(trkStr)))
            }
            if (payload.discNumber != null && payload.discNumber > 0) {
                val discStr = if (payload.totalDiscs != null && payload.totalDiscs > 0) {
                    "${payload.discNumber}/${payload.totalDiscs}"
                } else {
                    "${payload.discNumber}"
                }
                framesToWrite.add(Id3Frame("TPOS", buildTextFrameData(discStr)))
            }
            if (payload.releaseYear != null && payload.releaseYear > 0) {
                framesToWrite.add(Id3Frame("TYER", buildTextFrameData(payload.releaseYear.toString())))
            } else if (!payload.releaseDate.isNullOrBlank()) {
                val yr = payload.releaseDate.take(4).toIntOrNull()
                if (yr != null && yr > 0) {
                    framesToWrite.add(Id3Frame("TYER", buildTextFrameData(yr.toString())))
                }
            }
            if (payload.bpm != null && payload.bpm in 30.0..300.0) {
                val bpmStr = if (payload.bpm == payload.bpm.roundToInt().toDouble()) {
                    payload.bpm.toInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", payload.bpm)
                }
                framesToWrite.add(Id3Frame("TBPM", buildTextFrameData(bpmStr)))
            }
            if (!payload.musicalKey.isNullOrBlank() && payload.musicalKey != "—") {
                framesToWrite.add(Id3Frame("TKEY", buildTextFrameData(payload.musicalKey.trim())))
            }

            // APIC Attached Picture Frame
            if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) {
                val apicStream = ByteArrayOutputStream()
                apicStream.write(0x00) // ISO-8859-1 encoding byte
                val mime = payload.artworkMimeType.ifBlank { "image/jpeg" }
                apicStream.write(mime.toByteArray(StandardCharsets.ISO_8859_1))
                apicStream.write(0x00) // Null terminator
                apicStream.write(0x03) // Picture type: Front Cover
                apicStream.write(0x00) // Empty description null terminator
                apicStream.write(payload.artworkBytes)
                framesToWrite.add(Id3Frame("APIC", apicStream.toByteArray()))
            }

            // Build ID3v2.3 tag
            val rawTagStream = ByteArrayOutputStream()
            for (frame in framesToWrite) {
                val frameIdBytes = frame.id.padEnd(4, ' ').take(4).toByteArray(StandardCharsets.ISO_8859_1)
                rawTagStream.write(frameIdBytes)
                val frameLen = frame.data.size
                rawTagStream.write((frameLen shr 24) and 0xFF)
                rawTagStream.write((frameLen shr 16) and 0xFF)
                rawTagStream.write((frameLen shr 8) and 0xFF)
                rawTagStream.write(frameLen and 0xFF)
                rawTagStream.write(0) // Flags byte 1
                rawTagStream.write(0) // Flags byte 2
                rawTagStream.write(frame.data)
            }

            // 1KB padding
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
                        Log.d(TAG, "Successfully wrote complete ID3v2 tags and artwork to ${file.name}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing ID3 tags to ${file.name}: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return false
    }

    // =========================================================================
    // FLAC (VORBIS_COMMENT + PICTURE) IMPLEMENTATION
    // =========================================================================

    private fun writeFlacTags(file: File, payload: CompleteTagPayload): Boolean {
        var tempFile: File? = null
        try {
            val fileLength = file.length()
            if (fileLength < 4) return false

            val inputStream = FileInputStream(file)
            val magic = ByteArray(4)
            val readMagic = inputStream.read(magic)
            if (readMagic < 4 || magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() || magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) {
                inputStream.close()
                Log.w(TAG, "File ${file.name} is not a valid FLAC stream")
                return false
            }

            // Read and preserve metadata blocks (STREAMINFO, SEEKTABLE, etc.), skipping old Vorbis/Picture/Padding
            data class PreservedBlock(val blockType: Int, val data: ByteArray)
            val preservedBlocks = mutableListOf<PreservedBlock>()
            var isLast = false

            while (!isLast) {
                val blockHeader = ByteArray(4)
                val readHdr = inputStream.read(blockHeader)
                if (readHdr < 4) break

                isLast = (blockHeader[0].toInt() and 0x80) != 0
                val blockType = blockHeader[0].toInt() and 0x7F
                val blockLength = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                        ((blockHeader[2].toInt() and 0xFF) shl 8) or
                        (blockHeader[3].toInt() and 0xFF)

                val blockData = ByteArray(blockLength)
                var total = 0
                while (total < blockLength) {
                    val r = inputStream.read(blockData, total, blockLength - total)
                    if (r <= 0) break
                    total += r
                }

                // Preserve STREAMINFO (0) and any other metadata blocks except VORBIS_COMMENT (4), PICTURE (6), PADDING (1)
                if (blockType != 4 && blockType != 6 && blockType != 1) {
                    preservedBlocks.add(PreservedBlock(blockType, blockData))
                }
            }

            // Build new VORBIS_COMMENT block
            val commentStream = ByteArrayOutputStream()
            val vendorString = "SoundSync"
            val vendorBytes = vendorString.toByteArray(StandardCharsets.UTF_8)
            writeLittleEndianInt(commentStream, vendorBytes.size)
            commentStream.write(vendorBytes)

            val comments = mutableListOf<String>()
            payload.title?.takeIf { it.isNotBlank() }?.let { comments.add("TITLE=$it") }
            payload.artist?.takeIf { it.isNotBlank() }?.let { comments.add("ARTIST=$it") }
            payload.album?.takeIf { it.isNotBlank() }?.let { comments.add("ALBUM=$it") }
            payload.genre?.takeIf { it.isNotBlank() }?.let { comments.add("GENRE=$it") }
            payload.trackNumber?.takeIf { it > 0 }?.let {
                if (payload.totalTracks != null && payload.totalTracks > 0) {
                    comments.add("TRACKNUMBER=$it")
                    comments.add("TRACKTOTAL=${payload.totalTracks}")
                } else {
                    comments.add("TRACKNUMBER=$it")
                }
            }
            payload.discNumber?.takeIf { it > 0 }?.let {
                if (payload.totalDiscs != null && payload.totalDiscs > 0) {
                    comments.add("DISCNUMBER=$it")
                    comments.add("DISCTOTAL=${payload.totalDiscs}")
                } else {
                    comments.add("DISCNUMBER=$it")
                }
            }
            payload.releaseYear?.takeIf { it > 0 }?.let { comments.add("DATE=$it") }
                ?: payload.releaseDate?.takeIf { it.isNotBlank() }?.let { comments.add("DATE=$it") }
            payload.bpm?.takeIf { it in 30.0..300.0 }?.let {
                val bStr = if (it == it.roundToInt().toDouble()) it.toInt().toString() else String.format(Locale.US, "%.1f", it)
                comments.add("BPM=$bStr")
            }
            payload.musicalKey?.takeIf { it.isNotBlank() && it != "—" }?.let {
                comments.add("KEY=$it")
                comments.add("INITIALKEY=$it")
            }

            writeLittleEndianInt(commentStream, comments.size)
            for (c in comments) {
                val cBytes = c.toByteArray(StandardCharsets.UTF_8)
                writeLittleEndianInt(commentStream, cBytes.size)
                commentStream.write(cBytes)
            }
            val vorbisCommentBytes = commentStream.toByteArray()

            // Build new PICTURE block if artwork provided
            var pictureBlockBytes: ByteArray? = null
            if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) {
                val picStream = ByteArrayOutputStream()
                writeBigEndianInt(picStream, 3) // Picture type: Front Cover
                val mime = payload.artworkMimeType.ifBlank { "image/jpeg" }
                val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
                writeBigEndianInt(picStream, mimeBytes.size)
                picStream.write(mimeBytes)
                writeBigEndianInt(picStream, 0) // Description string length = 0

                var width = 0
                var height = 0
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(payload.artworkBytes, 0, payload.artworkBytes.size, opts)
                    width = opts.outWidth
                    height = opts.outHeight
                } catch (_: Exception) {}

                writeBigEndianInt(picStream, width)
                writeBigEndianInt(picStream, height)
                writeBigEndianInt(picStream, 24) // Bits per pixel
                writeBigEndianInt(picStream, 0)  // Number of indexed colors
                writeBigEndianInt(picStream, payload.artworkBytes.size)
                picStream.write(payload.artworkBytes)
                pictureBlockBytes = picStream.toByteArray()
            }

            // Write new FLAC file to temp file
            tempFile = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.tmp")
            val fos = FileOutputStream(tempFile)
            fos.write(magic) // "fLaC"

            // 1. Write preserved blocks (STREAMINFO, etc.)
            for (p in preservedBlocks) {
                writeFlacBlockHeader(fos, isLast = false, blockType = p.blockType, length = p.data.size)
                fos.write(p.data)
            }

            // 2. Write VORBIS_COMMENT block
            writeFlacBlockHeader(fos, isLast = false, blockType = 4, length = vorbisCommentBytes.size)
            fos.write(vorbisCommentBytes)

            // 3. Write PICTURE block if present
            if (pictureBlockBytes != null) {
                writeFlacBlockHeader(fos, isLast = false, blockType = 6, length = pictureBlockBytes.size)
                fos.write(pictureBlockBytes)
            }

            // 4. Write PADDING block (1KB) as last metadata block
            val paddingBytes = ByteArray(1024)
            writeFlacBlockHeader(fos, isLast = true, blockType = 1, length = paddingBytes.size)
            fos.write(paddingBytes)

            // 5. Copy remaining audio data
            val copyBuffer = ByteArray(64 * 1024)
            var bytes = inputStream.read(copyBuffer)
            while (bytes > 0) {
                fos.write(copyBuffer, 0, bytes)
                bytes = inputStream.read(copyBuffer)
            }

            fos.flush()
            fos.close()
            inputStream.close()

            if (tempFile.length() > (fileLength / 2)) {
                if (file.delete()) {
                    val renamed = tempFile.renameTo(file)
                    if (renamed) {
                        Log.d(TAG, "Successfully wrote complete FLAC tags and artwork to ${file.name}")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing FLAC tags to ${file.name}: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return false
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private data class Id3Frame(val id: String, val data: ByteArray)

    private fun buildTextFrameData(text: String): ByteArray {
        val isAscii = text.all { it.code in 0..127 }
        return if (isAscii) {
            val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            val result = ByteArray(1 + bytes.size)
            result[0] = 0 // ISO-8859-1 encoding byte
            System.arraycopy(bytes, 0, result, 1, bytes.size)
            result
        } else {
            // UTF-16 with BOM
            val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
            val result = ByteArray(1 + 2 + textBytes.size)
            result[0] = 1 // UTF-16 with BOM
            result[1] = 0xFF.toByte()
            result[2] = 0xFE.toByte()
            System.arraycopy(textBytes, 0, result, 3, textBytes.size)
            result
        }
    }

    private fun parseId3Frames(buffer: ByteArray, majorVersion: Int, outList: MutableList<Id3Frame>) {
        var pos = 0
        val bufferLen = buffer.size
        while (pos + 10 <= bufferLen) {
            if (buffer[pos] == 0.toByte()) break
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

    private fun writeFlacBlockHeader(out: FileOutputStream, isLast: Boolean, blockType: Int, length: Int) {
        val b0 = (if (isLast) 0x80 else 0x00) or (blockType and 0x7F)
        out.write(b0)
        out.write((length shr 16) and 0xFF)
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
    }

    private fun writeLittleEndianInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeBigEndianInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
