package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.roundToInt

data class CompleteTagPayload(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val totalTracks: Int? = null,
    val discNumber: Int? = null,
    val totalDiscs: Int? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    val artworkBytes: ByteArray? = null,
    val artworkMimeType: String = "image/jpeg"
)

/**
 * Authoritative format-preserving audio file tag and artwork writer.
 *
 * Requirements:
 * - Does NOT transcode or re-encode audio.
 * - Does NOT alter sample rate, channels, bitrate, or audio duration.
 * - Writes canonical textual tags and front cover artwork into the audio container.
 * - Supports WAV (RIFF INFO + id3 chunk), MP3 (ID3v2.3), FLAC (Vorbis Comment + Picture),
 *   M4A/AAC (MP4 atoms), OGG (Vorbis), and OPUS (Ogg Opus).
 * - Supports content:// URIs via SAF/MediaStore streams.
 * - Utilizes atomic temporary file replacement to prevent file corruption.
 */
object AudioTagWriter {

    private const val TAG = "AudioTagWriter"

    private val OGG_CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        for (j in 0 until 8) {
            r = if ((r and -0x80000000) != 0) (r shl 1) xor 0x04c11db7 else (r shl 1)
        }
        r
    }

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
     * into the local audio file or content:// URI.
     */
    suspend fun writeCompleteTags(
        context: Context?,
        filePathOrUri: String,
        payload: CompleteTagPayload
    ): Boolean = withContext(Dispatchers.IO) {
        if (filePathOrUri.isBlank() || filePathOrUri.startsWith("demo://") || filePathOrUri.startsWith("http")) {
            Log.w(TAG, "Cannot write tags: invalid or virtual path: $filePathOrUri")
            return@withContext false
        }

        if (filePathOrUri.startsWith("content://")) {
            return@withContext writeContentUriTags(context, filePathOrUri, payload)
        }

        val file = File(filePathOrUri)
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "Cannot write tags: file does not exist: ${file.absolutePath}")
            return@withContext false
        }

        if (!isFileDirectlyWritable(file)) {
            Log.w(TAG, "Direct file write not permitted for ${file.absolutePath}, checking MediaStore fallback...")
            if (context != null) {
                val mediaUri = getMediaStoreUriForPath(context, file.absolutePath)
                if (mediaUri != null) {
                    Log.i(TAG, "Using MediaStore URI fallback: $mediaUri for ${file.absolutePath}")
                    return@withContext writeContentUriTags(context, mediaUri.toString(), payload)
                }
            }
            Log.w(TAG, "Cannot write tags: file is inaccessible or read-only: ${file.absolutePath}")
            return@withContext false
        }

        val ext = file.extension.lowercase(Locale.ROOT)
        val success = writeTagsToFile(file, ext, payload)
        if (success && context != null) {
            try {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            } catch (_: Exception) {}
        }
        return@withContext success
    }

    private fun writeContentUriTags(
        context: Context?,
        uriString: String,
        payload: CompleteTagPayload
    ): Boolean {
        if (context == null) return false
        val uri = Uri.parse(uriString)
        val contentResolver = context.contentResolver

        var fileName = "temp_audio"
        var ext = "mp3"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameCol != -1) {
                        fileName = cursor.getString(nameCol) ?: fileName
                        ext = fileName.substringAfterLast('.', "mp3").lowercase(Locale.ROOT)
                    }
                }
            }
        } catch (_: Exception) {}

        if (ext.isBlank() || ext == "temp_audio") {
            val mime = contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
            ext = when {
                mime.contains("wav") -> "wav"
                mime.contains("flac") -> "flac"
                mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
                mime.contains("ogg") -> "ogg"
                mime.contains("opus") -> "opus"
                else -> "mp3"
            }
        }

        var tempFile: File? = null
        return try {
            tempFile = File(context.cacheDir, "soundsync_saf_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { inStream ->
                FileOutputStream(tempFile).use { outStream ->
                    inStream.copyTo(outStream, 64 * 1024)
                }
            } ?: return false

            val success = writeTagsToFile(tempFile, ext, payload)
            if (!success) {
                Log.e(TAG, "Tag writing failed on SAF temp file")
                return false
            }

            contentResolver.openOutputStream(uri, "wt")?.use { outStream ->
                FileInputStream(tempFile).use { inStream ->
                    inStream.copyTo(outStream, 64 * 1024)
                }
            } ?: run {
                Log.e(TAG, "Could not open output stream for SAF URI: $uriString")
                return false
            }

            Log.d(TAG, "Successfully wrote tags back to SAF URI: $uriString")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SAF URI write error for $uriString: ${e.message}", e)
            false
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
    }

    /**
     * Checks if a file can be written to directly, probing via FileOutputStream if needed.
     */
    fun isFileDirectlyWritable(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.canWrite()) return true
        return try {
            FileOutputStream(file, true).use {}
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Queries MediaStore for a content:// URI matching the file's absolute path.
     */
    fun getMediaStoreUriForPath(context: Context, path: String): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Audio.Media._ID)
            val selection = "${MediaStore.Audio.Media.DATA} = ?"
            val selectionArgs = arrayOf(path)
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val id = cursor.getLong(idCol)
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve MediaStore URI for path $path: ${e.message}")
            null
        }
    }

    /**
     * Safely creates a temporary staging file for atomic tag writing.
     * Prefers the same directory to allow atomic rename, falling back to cache if unwritable.
     */
    fun createTempStagingFile(file: File): File {
        return try {
            val parent = file.parentFile
            if (parent != null && parent.exists() && parent.canWrite()) {
                File(parent, ".${file.name}.${System.currentTimeMillis()}.tmp")
            } else {
                File.createTempFile("ss_tag_", ".tmp")
            }
        } catch (_: Throwable) {
            File.createTempFile("ss_tag_", ".tmp")
        }
    }

    /**
     * Multi-tiered file replacement ensuring writes succeed on Android FAT/FUSE emulated storage:
     * Tier 1: Java NIO Files.move (ATOMIC_MOVE)
     * Tier 2: Java NIO Files.move (REPLACE_EXISTING)
     * Tier 3: Direct File.renameTo
     * Tier 4: Original File.delete followed by renameTo
     * Tier 5: In-place FileOutputStream overwrite with sync() (essential fallback on Android FUSE)
     */
    fun replaceOriginalFile(originalFile: File, tempFile: File): Boolean {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            Log.e(TAG, "replaceOriginalFile: Temp staging file is missing or empty")
            return false
        }

        // Tier 1: Atomic move via NIO
        try {
            Files.move(
                tempFile.toPath(),
                originalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return true
        } catch (_: Throwable) {}

        // Tier 2: Replace existing via NIO
        try {
            Files.move(
                tempFile.toPath(),
                originalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            return true
        } catch (_: Throwable) {}

        // Tier 3: Direct renameTo
        try {
            if (tempFile.renameTo(originalFile)) {
                return true
            }
        } catch (_: Throwable) {}

        // Tier 4: Delete original and rename
        try {
            if (originalFile.delete() && tempFile.renameTo(originalFile)) {
                return true
            }
        } catch (_: Throwable) {}

        // Tier 5: In-place content stream overwrite
        return try {
            tempFile.inputStream().buffered().use { src ->
                FileOutputStream(originalFile, false).use { dst ->
                    src.copyTo(dst, 64 * 1024)
                    dst.fd.sync()
                }
            }
            tempFile.delete()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "replaceOriginalFile: All replacement tiers failed for ${originalFile.name}: ${e.message}", e)
            false
        }
    }

    private fun writeTagsToFile(file: File, ext: String, payload: CompleteTagPayload): Boolean {
        return when (ext) {
            "wav" -> writeWavTags(file, payload)
            "mp3" -> writeMp3Tags(file, payload)
            "flac" -> writeFlacTags(file, payload)
            "m4a", "mp4" -> writeM4aTags(file, payload)
            "aac" -> {
                if (writeM4aTags(file, payload)) true
                else writeMp3Tags(file, payload)
            }
            "ogg" -> writeOggVorbisTags(file, payload)
            "opus" -> writeOggOpusTags(file, payload)
            "aif", "aiff" -> writeAiffTags(file, payload)
            else -> {
                Log.w(TAG, "Tag writing not supported for extension .$ext; file preserved unmodified.")
                false
            }
        }
    }

    // =========================================================================
    // WAV (RIFF WAVE) IMPLEMENTATION: Writes BOTH 'id3 ' chunk and 'LIST INFO'
    // =========================================================================

    private fun writeWavTags(file: File, payload: CompleteTagPayload): Boolean {
        var tempFile: File? = null
        try {
            val fileLength = file.length()
            if (fileLength < 12) return false

            val inputStream = FileInputStream(file)
            val header = ByteArray(12)
            if (inputStream.read(header) < 12) {
                inputStream.close()
                return false
            }

            if (header[0] != 'R'.code.toByte() || header[1] != 'I'.code.toByte() ||
                header[2] != 'F'.code.toByte() || header[3] != 'F'.code.toByte() ||
                header[8] != 'W'.code.toByte() || header[9] != 'A'.code.toByte() ||
                header[10] != 'V'.code.toByte() || header[11] != 'E'.code.toByte()) {
                inputStream.close()
                Log.w(TAG, "File ${file.name} is not a valid RIFF WAVE file")
                return false
            }

            data class RiffChunk(val id: String, val data: ByteArray)
            var fmtChunk: RiffChunk? = null
            var dataChunkOffset: Long = 0L
            var dataChunkSize: Long = 0L
            val preservedChunks = mutableListOf<RiffChunk>()
            val existingId3Frames = mutableListOf<Id3Frame>()
            val existingInfoSubchunks = mutableMapOf<String, ByteArray>()

            var currentOffset = 12L
            val chunkHdr = ByteArray(8)

            while (currentOffset + 8 <= fileLength) {
                val r = inputStream.read(chunkHdr)
                if (r < 8) break
                currentOffset += 8

                val chunkId = String(chunkHdr, 0, 4, StandardCharsets.US_ASCII)
                val chunkSize = (chunkHdr[4].toInt() and 0xFF) or
                        ((chunkHdr[5].toInt() and 0xFF) shl 8) or
                        ((chunkHdr[6].toInt() and 0xFF) shl 16) or
                        ((chunkHdr[7].toInt() and 0xFF) shl 24)
                val padSize = if (chunkSize % 2 != 0) 1 else 0

                when {
                    chunkId == "fmt " -> {
                        val fmtData = ByteArray(chunkSize)
                        var readTotal = 0
                        while (readTotal < chunkSize) {
                            val count = inputStream.read(fmtData, readTotal, chunkSize - readTotal)
                            if (count <= 0) break
                            readTotal += count
                        }
                        if (padSize > 0) inputStream.skip(padSize.toLong())
                        currentOffset += chunkSize + padSize
                        fmtChunk = RiffChunk(chunkId, fmtData)
                    }
                    chunkId == "data" -> {
                        dataChunkOffset = currentOffset
                        dataChunkSize = chunkSize.toLong() and 0xFFFFFFFFL
                        skipFully(inputStream, dataChunkSize + padSize)
                        currentOffset += dataChunkSize + padSize
                    }
                    chunkId.equals("id3 ", ignoreCase = true) -> {
                        val id3Data = ByteArray(chunkSize)
                        var readTotal = 0
                        while (readTotal < chunkSize) {
                            val count = inputStream.read(id3Data, readTotal, chunkSize - readTotal)
                            if (count <= 0) break
                            readTotal += count
                        }
                        if (padSize > 0) inputStream.skip(padSize.toLong())
                        currentOffset += chunkSize + padSize

                        if (id3Data.size >= 10 && id3Data[0] == 'I'.code.toByte() && id3Data[1] == 'D'.code.toByte() && id3Data[2] == '3'.code.toByte()) {
                            val majorVer = id3Data[3].toInt()
                            val tagBodySize = decodeSyncSafe(id3Data, 6)
                            if (tagBodySize > 0 && 10 + tagBodySize <= id3Data.size) {
                                val bodyBuf = ByteArray(tagBodySize)
                                System.arraycopy(id3Data, 10, bodyBuf, 0, tagBodySize)
                                parseId3Frames(bodyBuf, majorVer, existingId3Frames)
                            }
                        }
                    }
                    chunkId == "LIST" -> {
                        val listData = ByteArray(chunkSize)
                        var readTotal = 0
                        while (readTotal < chunkSize) {
                            val count = inputStream.read(listData, readTotal, chunkSize - readTotal)
                            if (count <= 0) break
                            readTotal += count
                        }
                        if (padSize > 0) inputStream.skip(padSize.toLong())
                        currentOffset += chunkSize + padSize

                        val listType = if (listData.size >= 4) String(listData, 0, 4, StandardCharsets.US_ASCII) else ""
                        if (listType != "INFO") {
                            preservedChunks.add(RiffChunk(chunkId, listData))
                        } else {
                            var subOff = 4
                            while (subOff + 8 <= listData.size) {
                                val subId = String(listData, subOff, 4, StandardCharsets.US_ASCII)
                                val subSize = (listData[subOff + 4].toInt() and 0xFF) or
                                        ((listData[subOff + 5].toInt() and 0xFF) shl 8) or
                                        ((listData[subOff + 6].toInt() and 0xFF) shl 16) or
                                        ((listData[subOff + 7].toInt() and 0xFF) shl 24)
                                val subPad = if (subSize % 2 != 0) 1 else 0
                                if (subSize > 0 && subOff + 8 + subSize <= listData.size) {
                                    val subData = ByteArray(subSize)
                                    System.arraycopy(listData, subOff + 8, subData, 0, subSize)
                                    existingInfoSubchunks[subId] = subData
                                }
                                subOff += 8 + subSize + subPad
                            }
                        }
                    }
                    else -> {
                        if (chunkSize in 1..1048576) {
                            val chunkData = ByteArray(chunkSize)
                            var readTotal = 0
                            while (readTotal < chunkSize) {
                                val count = inputStream.read(chunkData, readTotal, chunkSize - readTotal)
                                if (count <= 0) break
                                readTotal += count
                            }
                            if (padSize > 0) inputStream.skip(padSize.toLong())
                            currentOffset += chunkSize + padSize
                            preservedChunks.add(RiffChunk(chunkId, chunkData))
                        } else {
                            skipFully(inputStream, chunkSize.toLong() + padSize)
                            currentOffset += chunkSize + padSize
                        }
                    }
                }
            }
            inputStream.close()

            if (fmtChunk == null || dataChunkOffset == 0L) {
                Log.e(TAG, "Cannot write WAV tags: missing fmt or data chunk in ${file.name}")
                return false
            }

            val infoStream = ByteArrayOutputStream()
            infoStream.write("INFO".toByteArray(StandardCharsets.US_ASCII))

            fun writeInfoSubchunk(id: String, text: String?) {
                if (text.isNullOrBlank()) return
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                val nullTerminated = ByteArray(bytes.size + 1)
                System.arraycopy(bytes, 0, nullTerminated, 0, bytes.size)
                nullTerminated[bytes.size] = 0

                infoStream.write(id.take(4).toByteArray(StandardCharsets.US_ASCII))
                writeLittleEndianInt(infoStream, nullTerminated.size)
                infoStream.write(nullTerminated)
                if (nullTerminated.size % 2 != 0) {
                    infoStream.write(0)
                }
            }

            val writtenInfoIds = mutableSetOf<String>()
            fun doWrite(id: String, value: String?) {
                if (!value.isNullOrBlank()) {
                    writeInfoSubchunk(id, value)
                    writtenInfoIds.add(id)
                } else if (existingInfoSubchunks.containsKey(id)) {
                    val raw = existingInfoSubchunks[id]!!
                    infoStream.write(id.take(4).toByteArray(StandardCharsets.US_ASCII))
                    writeLittleEndianInt(infoStream, raw.size)
                    infoStream.write(raw)
                    if (raw.size % 2 != 0) infoStream.write(0)
                    writtenInfoIds.add(id)
                }
            }

            doWrite("INAM", payload.title)
            doWrite("IART", payload.artist)
            doWrite("IPRD", payload.album)
            doWrite("IGNR", payload.genre)
            doWrite("ICMT", payload.comment)
            writeInfoSubchunk("ISFT", "SoundSync")
            writtenInfoIds.add("ISFT")
            doWrite("ITRK", payload.trackNumber?.takeIf { it > 0 }?.toString())
            val yearStr = payload.releaseYear?.takeIf { it > 0 }?.toString() ?: payload.releaseDate?.takeIf { it.isNotBlank() }
            doWrite("ICRD", yearStr)
            doWrite("IYEAR", yearStr)

            // Preserve any other existing INFO subchunks (ICOP, IENG, etc.)
            for ((subId, subData) in existingInfoSubchunks) {
                if (!writtenInfoIds.contains(subId)) {
                    infoStream.write(subId.take(4).toByteArray(StandardCharsets.US_ASCII))
                    writeLittleEndianInt(infoStream, subData.size)
                    infoStream.write(subData)
                    if (subData.size % 2 != 0) infoStream.write(0)
                }
            }

            val infoPayload = infoStream.toByteArray()
            val listChunkStream = ByteArrayOutputStream()
            listChunkStream.write("LIST".toByteArray(StandardCharsets.US_ASCII))
            writeLittleEndianInt(listChunkStream, infoPayload.size)
            listChunkStream.write(infoPayload)
            if (infoPayload.size % 2 != 0) {
                listChunkStream.write(0)
            }
            val listChunkBytes = listChunkStream.toByteArray()

            val id3TagBytes = buildId3v2Tag(payload, existingId3Frames)
            val id3ChunkStream = ByteArrayOutputStream()
            id3ChunkStream.write("id3 ".toByteArray(StandardCharsets.US_ASCII))
            writeLittleEndianInt(id3ChunkStream, id3TagBytes.size)
            id3ChunkStream.write(id3TagBytes)
            if (id3TagBytes.size % 2 != 0) {
                id3ChunkStream.write(0)
            }
            val id3ChunkBytes = id3ChunkStream.toByteArray()

            val fmtChunkTotalSize = 8L + fmtChunk.data.size + (if (fmtChunk.data.size % 2 != 0) 1 else 0)
            val preservedChunksTotalSize = preservedChunks.sumOf { 8L + it.data.size + (if (it.data.size % 2 != 0) 1 else 0) }
            val dataChunkTotalSize = 8L + dataChunkSize + (if (dataChunkSize % 2L != 0L) 1L else 0L)

            val totalRiffSize = 4L + fmtChunkTotalSize + id3ChunkBytes.size + listChunkBytes.size + preservedChunksTotalSize + dataChunkTotalSize

            tempFile = createTempStagingFile(file)
            val fos = FileOutputStream(tempFile)

            fos.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            val riffSizeBuf = ByteArray(4)
            riffSizeBuf[0] = (totalRiffSize and 0xFF).toByte()
            riffSizeBuf[1] = ((totalRiffSize shr 8) and 0xFF).toByte()
            riffSizeBuf[2] = ((totalRiffSize shr 16) and 0xFF).toByte()
            riffSizeBuf[3] = ((totalRiffSize shr 24) and 0xFF).toByte()
            fos.write(riffSizeBuf)
            fos.write("WAVE".toByteArray(StandardCharsets.US_ASCII))

            fos.write(fmtChunk.id.toByteArray(StandardCharsets.US_ASCII))
            val fmtLenBuf = ByteArray(4)
            fmtLenBuf[0] = (fmtChunk.data.size and 0xFF).toByte()
            fmtLenBuf[1] = ((fmtChunk.data.size shr 8) and 0xFF).toByte()
            fmtLenBuf[2] = ((fmtChunk.data.size shr 16) and 0xFF).toByte()
            fmtLenBuf[3] = ((fmtChunk.data.size shr 24) and 0xFF).toByte()
            fos.write(fmtLenBuf)
            fos.write(fmtChunk.data)
            if (fmtChunk.data.size % 2 != 0) fos.write(0)

            fos.write(id3ChunkBytes)
            fos.write(listChunkBytes)

            for (p in preservedChunks) {
                fos.write(p.id.toByteArray(StandardCharsets.US_ASCII))
                val pLenBuf = ByteArray(4)
                pLenBuf[0] = (p.data.size and 0xFF).toByte()
                pLenBuf[1] = ((p.data.size shr 8) and 0xFF).toByte()
                pLenBuf[2] = ((p.data.size shr 16) and 0xFF).toByte()
                pLenBuf[3] = ((p.data.size shr 24) and 0xFF).toByte()
                fos.write(pLenBuf)
                fos.write(p.data)
                if (p.data.size % 2 != 0) fos.write(0)
            }

            fos.write("data".toByteArray(StandardCharsets.US_ASCII))
            val dataLenBuf = ByteArray(4)
            dataLenBuf[0] = (dataChunkSize and 0xFF).toByte()
            dataLenBuf[1] = ((dataChunkSize shr 8) and 0xFF).toByte()
            dataLenBuf[2] = ((dataChunkSize shr 16) and 0xFF).toByte()
            dataLenBuf[3] = ((dataChunkSize shr 24) and 0xFF).toByte()
            fos.write(dataLenBuf)

            val audioIn = FileInputStream(file)
            skipFully(audioIn, dataChunkOffset)
            val copyBuf = ByteArray(64 * 1024)
            var bytesRemaining = dataChunkSize
            while (bytesRemaining > 0) {
                val toRead = minOf(copyBuf.size.toLong(), bytesRemaining).toInt()
                val read = audioIn.read(copyBuf, 0, toRead)
                if (read <= 0) break
                fos.write(copyBuf, 0, read)
                bytesRemaining -= read
            }
            if (dataChunkSize % 2L != 0L) {
                fos.write(0)
            }
            audioIn.close()

            fos.flush()
            fos.close()

            if (tempFile.length() >= (fileLength / 2)) {
                if (replaceOriginalFile(file, tempFile)) {
                    Log.d(TAG, "Successfully wrote RIFF INFO + ID3 tags to WAV ${file.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing WAV tags to ${file.name}: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return false
    }

    // =========================================================================
    // AIFF (EA IFF 85) IMPLEMENTATION: Writes 'ID3 ' chunk with ID3v2.3 tags
    // =========================================================================

    private fun writeAiffTags(file: File, payload: CompleteTagPayload): Boolean {
        var tempFile: File? = null
        try {
            val fileLength = file.length()
            if (fileLength < 12) return false

            val inputStream = FileInputStream(file)
            val header = ByteArray(12)
            if (inputStream.read(header) < 12) {
                inputStream.close()
                return false
            }

            if (header[0] != 'F'.code.toByte() || header[1] != 'O'.code.toByte() ||
                header[2] != 'R'.code.toByte() || header[3] != 'M'.code.toByte()) {
                inputStream.close()
                Log.w(TAG, "File ${file.name} is not a valid IFF FORM file")
                return false
            }

            val subtype = String(header, 8, 4, StandardCharsets.US_ASCII)
            if (subtype != "AIFF" && subtype != "AIFC") {
                inputStream.close()
                Log.w(TAG, "File ${file.name} is not an AIFF or AIFC file")
                return false
            }

            data class IffChunk(val id: String, val data: ByteArray)
            var commChunk: IffChunk? = null
            var ssndChunkOffset = 0L
            var ssndChunkSize = 0L
            val preservedChunks = mutableListOf<IffChunk>()
            val existingId3Frames = mutableListOf<Id3Frame>()

            var currentOffset = 12L
            val chunkHdr = ByteArray(8)

            while (currentOffset + 8 <= fileLength) {
                val r = inputStream.read(chunkHdr)
                if (r < 8) break
                currentOffset += 8

                val chunkId = String(chunkHdr, 0, 4, StandardCharsets.US_ASCII)
                val chunkSize = ((chunkHdr[4].toInt() and 0xFF) shl 24) or
                        ((chunkHdr[5].toInt() and 0xFF) shl 16) or
                        ((chunkHdr[6].toInt() and 0xFF) shl 8) or
                        (chunkHdr[7].toInt() and 0xFF)
                val padSize = if (chunkSize % 2 != 0) 1 else 0

                when {
                    chunkId == "COMM" -> {
                        val commData = ByteArray(chunkSize)
                        var readTotal = 0
                        while (readTotal < chunkSize) {
                            val count = inputStream.read(commData, readTotal, chunkSize - readTotal)
                            if (count <= 0) break
                            readTotal += count
                        }
                        if (padSize > 0) inputStream.skip(padSize.toLong())
                        currentOffset += chunkSize + padSize
                        commChunk = IffChunk(chunkId, commData)
                    }
                    chunkId == "SSND" -> {
                        ssndChunkOffset = currentOffset
                        ssndChunkSize = chunkSize.toLong() and 0xFFFFFFFFL
                        skipFully(inputStream, ssndChunkSize + padSize)
                        currentOffset += ssndChunkSize + padSize
                    }
                    chunkId.equals("id3 ", ignoreCase = true) -> {
                        val id3Data = ByteArray(chunkSize)
                        var readTotal = 0
                        while (readTotal < chunkSize) {
                            val count = inputStream.read(id3Data, readTotal, chunkSize - readTotal)
                            if (count <= 0) break
                            readTotal += count
                        }
                        if (padSize > 0) inputStream.skip(padSize.toLong())
                        currentOffset += chunkSize + padSize

                        if (id3Data.size >= 10 && id3Data[0] == 'I'.code.toByte() && id3Data[1] == 'D'.code.toByte() && id3Data[2] == '3'.code.toByte()) {
                            val majorVer = id3Data[3].toInt()
                            val tagBodySize = decodeSyncSafe(id3Data, 6)
                            if (tagBodySize > 0 && 10 + tagBodySize <= id3Data.size) {
                                val bodyBuf = ByteArray(tagBodySize)
                                System.arraycopy(id3Data, 10, bodyBuf, 0, tagBodySize)
                                parseId3Frames(bodyBuf, majorVer, existingId3Frames)
                            }
                        }
                    }
                    else -> {
                        if (chunkSize in 1..1048576) {
                            val otherData = ByteArray(chunkSize)
                            var readTotal = 0
                            while (readTotal < chunkSize) {
                                val count = inputStream.read(otherData, readTotal, chunkSize - readTotal)
                                if (count <= 0) break
                                readTotal += count
                            }
                            if (padSize > 0) inputStream.skip(padSize.toLong())
                            currentOffset += chunkSize + padSize
                            preservedChunks.add(IffChunk(chunkId, otherData))
                        } else {
                            skipFully(inputStream, chunkSize.toLong() + padSize)
                            currentOffset += chunkSize + padSize
                        }
                    }
                }
            }
            inputStream.close()

            if (commChunk == null || ssndChunkOffset <= 0L) {
                Log.w(TAG, "AIFF file ${file.name} missing essential COMM or SSND chunk")
                return false
            }

            val id3TagBytes = buildId3v2Tag(payload, existingId3Frames)
            val id3Pad = if (id3TagBytes.size % 2 != 0) 1 else 0

            var formPayloadLength = 4L // "AIFF" subtype
            formPayloadLength += 8L + commChunk.data.size + (if (commChunk.data.size % 2 != 0) 1 else 0)
            formPayloadLength += 8L + id3TagBytes.size + id3Pad
            for (p in preservedChunks) {
                formPayloadLength += 8L + p.data.size + (if (p.data.size % 2 != 0) 1 else 0)
            }
            formPayloadLength += 8L + ssndChunkSize + (if (ssndChunkSize % 2L != 0L) 1 else 0)

            tempFile = createTempStagingFile(file)
            val fos = FileOutputStream(tempFile)

            fos.write("FORM".toByteArray(StandardCharsets.US_ASCII))
            val formLenBuf = ByteArray(4)
            formLenBuf[0] = ((formPayloadLength shr 24) and 0xFF).toByte()
            formLenBuf[1] = ((formPayloadLength shr 16) and 0xFF).toByte()
            formLenBuf[2] = ((formPayloadLength shr 8) and 0xFF).toByte()
            formLenBuf[3] = (formPayloadLength and 0xFF).toByte()
            fos.write(formLenBuf)
            fos.write(subtype.toByteArray(StandardCharsets.US_ASCII))

            // Write COMM chunk
            fos.write("COMM".toByteArray(StandardCharsets.US_ASCII))
            val commLenBuf = ByteArray(4)
            commLenBuf[0] = ((commChunk.data.size shr 24) and 0xFF).toByte()
            commLenBuf[1] = ((commChunk.data.size shr 16) and 0xFF).toByte()
            commLenBuf[2] = ((commChunk.data.size shr 8) and 0xFF).toByte()
            commLenBuf[3] = (commChunk.data.size and 0xFF).toByte()
            fos.write(commLenBuf)
            fos.write(commChunk.data)
            if (commChunk.data.size % 2 != 0) fos.write(0)

            // Write ID3 chunk
            fos.write("ID3 ".toByteArray(StandardCharsets.US_ASCII))
            val id3LenBuf = ByteArray(4)
            id3LenBuf[0] = ((id3TagBytes.size shr 24) and 0xFF).toByte()
            id3LenBuf[1] = ((id3TagBytes.size shr 16) and 0xFF).toByte()
            id3LenBuf[2] = ((id3TagBytes.size shr 8) and 0xFF).toByte()
            id3LenBuf[3] = (id3TagBytes.size and 0xFF).toByte()
            fos.write(id3LenBuf)
            fos.write(id3TagBytes)
            if (id3Pad > 0) fos.write(0)

            // Write preserved chunks
            for (p in preservedChunks) {
                fos.write(p.id.toByteArray(StandardCharsets.US_ASCII))
                val pLenBuf = ByteArray(4)
                pLenBuf[0] = ((p.data.size shr 24) and 0xFF).toByte()
                pLenBuf[1] = ((p.data.size shr 16) and 0xFF).toByte()
                pLenBuf[2] = ((p.data.size shr 8) and 0xFF).toByte()
                pLenBuf[3] = (p.data.size and 0xFF).toByte()
                fos.write(pLenBuf)
                fos.write(p.data)
                if (p.data.size % 2 != 0) fos.write(0)
            }

            // Write SSND chunk
            fos.write("SSND".toByteArray(StandardCharsets.US_ASCII))
            val ssndLenBuf = ByteArray(4)
            ssndLenBuf[0] = ((ssndChunkSize shr 24) and 0xFF).toByte()
            ssndLenBuf[1] = ((ssndChunkSize shr 16) and 0xFF).toByte()
            ssndLenBuf[2] = ((ssndChunkSize shr 8) and 0xFF).toByte()
            ssndLenBuf[3] = (ssndChunkSize and 0xFF).toByte()
            fos.write(ssndLenBuf)

            val audioIn = FileInputStream(file)
            skipFully(audioIn, ssndChunkOffset)
            val copyBuf = ByteArray(64 * 1024)
            var bytesRemaining = ssndChunkSize
            while (bytesRemaining > 0) {
                val toRead = minOf(copyBuf.size.toLong(), bytesRemaining).toInt()
                val read = audioIn.read(copyBuf, 0, toRead)
                if (read <= 0) break
                fos.write(copyBuf, 0, read)
                bytesRemaining -= read
            }
            if (ssndChunkSize % 2L != 0L) {
                fos.write(0)
            }
            audioIn.close()

            fos.flush()
            fos.close()

            if (tempFile.length() >= (fileLength / 2)) {
                if (replaceOriginalFile(file, tempFile)) {
                    Log.d(TAG, "Successfully wrote ID3 chunk to AIFF ${file.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing AIFF tags to ${file.name}: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return false
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

            val id3TagBytes = buildId3v2Tag(payload, existingFrames)

            tempFile = createTempStagingFile(file)
            val fos = FileOutputStream(tempFile)
            fos.write(id3TagBytes)

            val audioInputStream = FileInputStream(file)
            if (audioDataStartOffset > 0) {
                skipFully(audioInputStream, audioDataStartOffset)
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
                if (replaceOriginalFile(file, tempFile)) {
                    Log.d(TAG, "Successfully wrote complete ID3v2 tags and artwork to ${file.name}")
                    return true
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

                if (blockType != 4 && blockType != 6 && blockType != 1) {
                    preservedBlocks.add(PreservedBlock(blockType, blockData))
                }
            }

            val vorbisCommentBytes = buildVorbisCommentBody(payload)
            val pictureBlockBytes = buildFlacPictureBlock(payload.artworkBytes, payload.artworkMimeType)

            tempFile = createTempStagingFile(file)
            val fos = FileOutputStream(tempFile)
            fos.write(magic)

            for (p in preservedBlocks) {
                writeFlacBlockHeader(fos, isLast = false, blockType = p.blockType, length = p.data.size)
                fos.write(p.data)
            }

            writeFlacBlockHeader(fos, isLast = false, blockType = 4, length = vorbisCommentBytes.size)
            fos.write(vorbisCommentBytes)

            if (pictureBlockBytes != null) {
                writeFlacBlockHeader(fos, isLast = false, blockType = 6, length = pictureBlockBytes.size)
                fos.write(pictureBlockBytes)
            }

            val paddingBytes = ByteArray(1024)
            writeFlacBlockHeader(fos, isLast = true, blockType = 1, length = paddingBytes.size)
            fos.write(paddingBytes)

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
                if (replaceOriginalFile(file, tempFile)) {
                    Log.d(TAG, "Successfully wrote complete FLAC tags and artwork to ${file.name}")
                    return true
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
    // M4A / AAC CONTAINER IMPLEMENTATION: moov -> udta -> meta -> ilst
    // =========================================================================

    private fun writeM4aTags(file: File, payload: CompleteTagPayload): Boolean {
        var tempFile: File? = null
        try {
            val fileLength = file.length()
            if (fileLength < 16) return false

            val inputStream = FileInputStream(file)

            data class Mp4Box(val type: String, val offset: Long, val headerSize: Int, val payloadSize: Long, val isExtended: Boolean)
            val boxes = mutableListOf<Mp4Box>()
            var currentPos = 0L

            while (currentPos + 8 <= fileLength) {
                val hdr = ByteArray(8)
                val read = inputStream.read(hdr)
                if (read < 8) break

                var boxLen = ((hdr[0].toLong() and 0xFF) shl 24) or
                        ((hdr[1].toLong() and 0xFF) shl 16) or
                        ((hdr[2].toLong() and 0xFF) shl 8) or
                        (hdr[3].toLong() and 0xFF)
                val boxType = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)

                var headerSize = 8
                var isExtended = false
                if (boxLen == 1L) {
                    val extHdr = ByteArray(8)
                    inputStream.read(extHdr)
                    boxLen = 0L
                    for (b in extHdr) {
                        boxLen = (boxLen shl 8) or (b.toLong() and 0xFF)
                    }
                    headerSize = 16
                    isExtended = true
                } else if (boxLen == 0L) {
                    boxLen = fileLength - currentPos
                }

                val payloadLen = boxLen - headerSize
                boxes.add(Mp4Box(boxType, currentPos, headerSize, payloadLen, isExtended))
                skipFully(inputStream, payloadLen)
                currentPos += boxLen
            }
            inputStream.close()

            val moovBox = boxes.find { it.type == "moov" }
            val mdatBox = boxes.find { it.type == "mdat" }

            if (moovBox == null || mdatBox == null) {
                Log.w(TAG, "File ${file.name} is missing moov or mdat box")
                return false
            }

            val moovInputStream = FileInputStream(file)
            skipFully(moovInputStream, moovBox.offset + moovBox.headerSize)
            val moovBytes = ByteArray(moovBox.payloadSize.toInt())
            var r = 0
            while (r < moovBytes.size) {
                val c = moovInputStream.read(moovBytes, r, moovBytes.size - r)
                if (c <= 0) break
                r += c
            }
            moovInputStream.close()

            val ilstPayload = buildMp4IlstPayload(payload)
            val ilstBox = buildMp4Box("ilst", ilstPayload)

            val hdlrBox = buildMp4Box("hdlr", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 'm'.code.toByte(), 'd'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0))
            val metaPayload = ByteArrayOutputStream()
            metaPayload.write(byteArrayOf(0, 0, 0, 0))
            metaPayload.write(hdlrBox)
            metaPayload.write(ilstBox)
            val metaBox = buildMp4Box("meta", metaPayload.toByteArray())

            val udtaBox = buildMp4Box("udta", metaBox)
            val updatedMoovPayload = replaceOrAppendBox(moovBytes, "udta", udtaBox)

            val oldMoovTotalSize = moovBox.headerSize + moovBox.payloadSize
            val newMoovTotalSize = 8 + updatedMoovPayload.size
            val sizeDelta = newMoovTotalSize - oldMoovTotalSize

            val finalMoovPayload = if (moovBox.offset < mdatBox.offset && sizeDelta != 0L) {
                adjustMp4ChunkOffsets(updatedMoovPayload, sizeDelta)
            } else {
                updatedMoovPayload
            }

            val finalMoovBox = buildMp4Box("moov", finalMoovPayload)

            tempFile = createTempStagingFile(file)
            val fos = FileOutputStream(tempFile)

            val srcIn = FileInputStream(file)
            for (box in boxes) {
                if (box.type == "moov") {
                    fos.write(finalMoovBox)
                    skipFully(srcIn, box.headerSize + box.payloadSize)
                } else {
                    val buf = ByteArray(64 * 1024)
                    var rem = box.headerSize + box.payloadSize
                    while (rem > 0) {
                        val toRead = minOf(buf.size.toLong(), rem).toInt()
                        val c = srcIn.read(buf, 0, toRead)
                        if (c <= 0) break
                        fos.write(buf, 0, c)
                        rem -= c
                    }
                }
            }
            srcIn.close()
            fos.flush()
            fos.close()

            if (tempFile.length() > (fileLength / 2)) {
                if (replaceOriginalFile(file, tempFile)) {
                    Log.d(TAG, "Successfully wrote M4A tags and artwork to ${file.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing M4A tags to ${file.name}: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return false
    }

    // =========================================================================
    // OGG VORBIS & OGG OPUS IMPLEMENTATIONS
    // =========================================================================

    private fun writeOggVorbisTags(file: File, payload: CompleteTagPayload): Boolean {
        return rewriteOggComments(file, isOpus = false, payload = payload)
    }

    private fun writeOggOpusTags(file: File, payload: CompleteTagPayload): Boolean {
        return rewriteOggComments(file, isOpus = true, payload = payload)
    }

    private fun rewriteOggComments(file: File, isOpus: Boolean, payload: CompleteTagPayload): Boolean {
        var tempFile: File? = null
        try {
            val fileLength = file.length()
            if (fileLength < 28) return false

            val inputStream = FileInputStream(file)

            data class RawOggPage(
                val headerType: Int,
                val granulePos: Long,
                val serial: Int,
                val seqNum: Int,
                val segments: ByteArray,
                val payload: ByteArray
            )

            fun readNextOggPage(): RawOggPage? {
                val hdr = ByteArray(27)
                val r = inputStream.read(hdr)
                if (r < 27) return null

                if (hdr[0] != 'O'.code.toByte() || hdr[1] != 'g'.code.toByte() || hdr[2] != 'g'.code.toByte() || hdr[3] != 'S'.code.toByte()) {
                    return null
                }

                val headerType = hdr[5].toInt() and 0xFF
                var granule = 0L
                for (i in 0 until 8) {
                    granule = granule or ((hdr[6 + i].toLong() and 0xFF) shl (i * 8))
                }
                val serial = (hdr[14].toInt() and 0xFF) or ((hdr[15].toInt() and 0xFF) shl 8) or ((hdr[16].toInt() and 0xFF) shl 16) or ((hdr[17].toInt() and 0xFF) shl 24)
                val seqNum = (hdr[18].toInt() and 0xFF) or ((hdr[19].toInt() and 0xFF) shl 8) or ((hdr[20].toInt() and 0xFF) shl 16) or ((hdr[21].toInt() and 0xFF) shl 24)
                val numSegments = hdr[26].toInt() and 0xFF

                val segTable = ByteArray(numSegments)
                if (numSegments > 0) {
                    var readSeg = 0
                    while (readSeg < numSegments) {
                        val count = inputStream.read(segTable, readSeg, numSegments - readSeg)
                        if (count <= 0) break
                        readSeg += count
                    }
                }

                val payloadLen = segTable.sumOf { it.toInt() and 0xFF }
                val pagePayload = ByteArray(payloadLen)
                var readPay = 0
                while (readPay < payloadLen) {
                    val count = inputStream.read(pagePayload, readPay, payloadLen - readPay)
                    if (count <= 0) break
                    readPay += count
                }

                return RawOggPage(headerType, granule, serial, seqNum, segTable, pagePayload)
            }

            val page0 = readNextOggPage()
            if (page0 == null || (page0.headerType and 0x02) == 0) {
                inputStream.close()
                Log.w(TAG, "File ${file.name} is not a valid Ogg stream")
                return false
            }

            val firstCommentPage = readNextOggPage()
            if (firstCommentPage != null) {
                var lastSeg = firstCommentPage.segments.lastOrNull()?.let { it.toInt() and 0xFF } ?: 0
                while (lastSeg == 255) {
                    val contPage = readNextOggPage() ?: break
                    lastSeg = contPage.segments.lastOrNull()?.let { it.toInt() and 0xFF } ?: 0
                    if ((contPage.headerType and 0x01) == 0) break
                }
            }

            val commentPacketStream = ByteArrayOutputStream()
            if (isOpus) {
                commentPacketStream.write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
            } else {
                commentPacketStream.write(byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
            }

            val commentsList = mutableListOf<String>()
            payload.title?.takeIf { it.isNotBlank() }?.let { commentsList.add("TITLE=$it") }
            payload.artist?.takeIf { it.isNotBlank() }?.let { commentsList.add("ARTIST=$it") }
            payload.album?.takeIf { it.isNotBlank() }?.let { commentsList.add("ALBUM=$it") }
            payload.albumArtist?.takeIf { it.isNotBlank() }?.let { commentsList.add("ALBUMARTIST=$it") }
            payload.genre?.takeIf { it.isNotBlank() }?.let { commentsList.add("GENRE=$it") }
            payload.composer?.takeIf { it.isNotBlank() }?.let { commentsList.add("COMPOSER=$it") }
            payload.comment?.takeIf { it.isNotBlank() }?.let { commentsList.add("COMMENT=$it") }
            payload.trackNumber?.takeIf { it > 0 }?.let {
                commentsList.add("TRACKNUMBER=$it")
                payload.totalTracks?.takeIf { tot -> tot > 0 }?.let { tot -> commentsList.add("TRACKTOTAL=$tot") }
            }
            payload.discNumber?.takeIf { it > 0 }?.let {
                commentsList.add("DISCNUMBER=$it")
                payload.totalDiscs?.takeIf { tot -> tot > 0 }?.let { tot -> commentsList.add("DISCTOTAL=$tot") }
            }
            payload.releaseYear?.takeIf { it > 0 }?.let {
                commentsList.add("DATE=$it")
                commentsList.add("YEAR=$it")
            } ?: payload.releaseDate?.takeIf { it.isNotBlank() }?.let {
                commentsList.add("DATE=$it")
                it.take(4).toIntOrNull()?.let { y -> commentsList.add("YEAR=$y") }
            }
            payload.bpm?.takeIf { it in 30.0..300.0 }?.let {
                val bStr = if (it == it.roundToInt().toDouble()) it.toInt().toString() else String.format(Locale.US, "%.1f", it)
                commentsList.add("BPM=$bStr")
            }
            payload.musicalKey?.takeIf { it.isNotBlank() && it != "—" }?.let {
                commentsList.add("KEY=$it")
                commentsList.add("INITIALKEY=$it")
            }

            if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) {
                val picBlock = buildFlacPictureBlock(payload.artworkBytes, payload.artworkMimeType)
                if (picBlock != null) {
                    val b64 = Base64.encodeToString(picBlock, Base64.NO_WRAP)
                    commentsList.add("METADATA_BLOCK_PICTURE=$b64")
                }
            }

            val vendorStr = "SoundSync"
            val vendorBytes = vendorStr.toByteArray(StandardCharsets.UTF_8)
            writeLittleEndianInt(commentPacketStream, vendorBytes.size)
            commentPacketStream.write(vendorBytes)

            writeLittleEndianInt(commentPacketStream, commentsList.size)
            for (c in commentsList) {
                val cBytes = c.toByteArray(StandardCharsets.UTF_8)
                writeLittleEndianInt(commentPacketStream, cBytes.size)
                commentPacketStream.write(cBytes)
            }

            if (!isOpus) {
                commentPacketStream.write(0x01)
            }

            val newCommentPacket = commentPacketStream.toByteArray()

            val newCommentPages = buildOggPagesFromPacket(
                packet = newCommentPacket,
                serial = page0.serial,
                startSeqNum = 1,
                granulePos = 0L
            )

            val seqDelta = newCommentPages.size - 1

            tempFile = createTempStagingFile(file)
            val fos = FileOutputStream(tempFile)

            writeOggPage(fos, page0.headerType, page0.granulePos, page0.serial, page0.seqNum, page0.segments, page0.payload)

            for (p in newCommentPages) {
                fos.write(p)
            }

            var page = readNextOggPage()
            while (page != null) {
                val adjustedSeq = page.seqNum + seqDelta
                writeOggPage(fos, page.headerType, page.granulePos, page.serial, adjustedSeq, page.segments, page.payload)
                page = readNextOggPage()
            }

            fos.flush()
            fos.close()
            inputStream.close()

            if (tempFile.length() > (fileLength / 2)) {
                if (replaceOriginalFile(file, tempFile)) {
                    Log.d(TAG, "Successfully wrote Ogg Vorbis/Opus comments and artwork to ${file.name}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing Ogg tags to ${file.name}: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return false
    }

    // =========================================================================
    // HELPER FUNCTIONS & BUILDERS
    // =========================================================================

    private data class Id3Frame(val id: String, val data: ByteArray)

    private fun buildId3v2Tag(payload: CompleteTagPayload, existingFrames: List<Id3Frame>): ByteArray {
        val updatingFrameIds = mutableSetOf<String>()
        if (!payload.title.isNullOrBlank()) updatingFrameIds.addAll(listOf("TIT2", "TT2"))
        if (!payload.artist.isNullOrBlank()) updatingFrameIds.addAll(listOf("TPE1", "TP1"))
        if (!payload.albumArtist.isNullOrBlank()) updatingFrameIds.addAll(listOf("TPE2", "TP2"))
        if (!payload.album.isNullOrBlank()) updatingFrameIds.addAll(listOf("TALB", "TAL"))
        if (!payload.genre.isNullOrBlank()) updatingFrameIds.addAll(listOf("TCON", "TCO"))
        if (!payload.composer.isNullOrBlank()) updatingFrameIds.addAll(listOf("TCOM", "TCM"))
        if (!payload.comment.isNullOrBlank()) updatingFrameIds.addAll(listOf("COMM", "COM"))
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

        if (!payload.title.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("TIT2", buildTextFrameData(payload.title)))
        }
        if (!payload.artist.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("TPE1", buildTextFrameData(payload.artist)))
        }
        if (!payload.albumArtist.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("TPE2", buildTextFrameData(payload.albumArtist)))
        }
        if (!payload.album.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("TALB", buildTextFrameData(payload.album)))
        }
        if (!payload.genre.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("TCON", buildTextFrameData(payload.genre)))
        }
        if (!payload.composer.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("TCOM", buildTextFrameData(payload.composer)))
        }
        if (!payload.comment.isNullOrBlank()) {
            framesToWrite.add(Id3Frame("COMM", buildCommentFrameData(payload.comment)))
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
            framesToWrite.add(Id3Frame("TXXX", buildUserTextFrameData("BPM", bpmStr)))
        }
        if (!payload.musicalKey.isNullOrBlank() && payload.musicalKey != "—" && payload.musicalKey != "-") {
            val keyStr = payload.musicalKey.trim()
            framesToWrite.add(Id3Frame("TKEY", buildTextFrameData(keyStr)))
            framesToWrite.add(Id3Frame("TXXX", buildUserTextFrameData("INITIALKEY", keyStr)))
        }

        if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) {
            val apicStream = ByteArrayOutputStream()
            apicStream.write(0x00) // ISO-8859-1
            val mime = payload.artworkMimeType.ifBlank { "image/jpeg" }
            apicStream.write(mime.toByteArray(StandardCharsets.ISO_8859_1))
            apicStream.write(0x00) // Null terminator
            apicStream.write(0x03) // Front Cover
            apicStream.write(0x00) // Empty description null terminator
            apicStream.write(payload.artworkBytes)
            framesToWrite.add(Id3Frame("APIC", apicStream.toByteArray()))
        }

        val rawTagStream = ByteArrayOutputStream()
        for (frame in framesToWrite) {
            val frameIdBytes = frame.id.padEnd(4, ' ').take(4).toByteArray(StandardCharsets.ISO_8859_1)
            rawTagStream.write(frameIdBytes)
            val frameLen = frame.data.size
            rawTagStream.write((frameLen shr 24) and 0xFF)
            rawTagStream.write((frameLen shr 16) and 0xFF)
            rawTagStream.write((frameLen shr 8) and 0xFF)
            rawTagStream.write(frameLen and 0xFF)
            rawTagStream.write(0)
            rawTagStream.write(0)
            rawTagStream.write(frame.data)
        }

        val padding = ByteArray(1024)
        rawTagStream.write(padding)

        val tagBody = rawTagStream.toByteArray()
        val syncSafeSize = encodeSyncSafe(tagBody.size)

        val newHeader = ByteArray(10)
        newHeader[0] = 'I'.code.toByte()
        newHeader[1] = 'D'.code.toByte()
        newHeader[2] = '3'.code.toByte()
        newHeader[3] = 3 // ID3v2.3
        newHeader[4] = 0
        newHeader[5] = 0
        System.arraycopy(syncSafeSize, 0, newHeader, 6, 4)

        val fullTag = ByteArray(newHeader.size + tagBody.size)
        System.arraycopy(newHeader, 0, fullTag, 0, newHeader.size)
        System.arraycopy(tagBody, 0, fullTag, newHeader.size, tagBody.size)
        return fullTag
    }

    private fun buildVorbisCommentBody(payload: CompleteTagPayload): ByteArray {
        val commentStream = ByteArrayOutputStream()
        val vendorString = "SoundSync"
        val vendorBytes = vendorString.toByteArray(StandardCharsets.UTF_8)
        writeLittleEndianInt(commentStream, vendorBytes.size)
        commentStream.write(vendorBytes)

        val comments = mutableListOf<String>()
        payload.title?.takeIf { it.isNotBlank() }?.let { comments.add("TITLE=$it") }
        payload.artist?.takeIf { it.isNotBlank() }?.let { comments.add("ARTIST=$it") }
        payload.albumArtist?.takeIf { it.isNotBlank() }?.let { comments.add("ALBUMARTIST=$it") }
        payload.album?.takeIf { it.isNotBlank() }?.let { comments.add("ALBUM=$it") }
        payload.genre?.takeIf { it.isNotBlank() }?.let { comments.add("GENRE=$it") }
        payload.composer?.takeIf { it.isNotBlank() }?.let { comments.add("COMPOSER=$it") }
        payload.comment?.takeIf { it.isNotBlank() }?.let { comments.add("COMMENT=$it") }
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
        payload.releaseYear?.takeIf { it > 0 }?.let {
            comments.add("DATE=$it")
            comments.add("YEAR=$it")
        } ?: payload.releaseDate?.takeIf { it.isNotBlank() }?.let {
            comments.add("DATE=$it")
            it.take(4).toIntOrNull()?.let { y -> comments.add("YEAR=$y") }
        }
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
        return commentStream.toByteArray()
    }

    private fun buildFlacPictureBlock(artworkBytes: ByteArray?, mimeType: String): ByteArray? {
        if (artworkBytes == null || artworkBytes.isEmpty()) return null
        val picStream = ByteArrayOutputStream()
        writeBigEndianInt(picStream, 3) // Picture type: Front Cover
        val mime = mimeType.ifBlank { "image/jpeg" }
        val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
        writeBigEndianInt(picStream, mimeBytes.size)
        picStream.write(mimeBytes)
        writeBigEndianInt(picStream, 0) // Description length = 0

        var width = 0
        var height = 0
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, opts)
            width = opts.outWidth
            height = opts.outHeight
        } catch (_: Exception) {}

        writeBigEndianInt(picStream, width)
        writeBigEndianInt(picStream, height)
        writeBigEndianInt(picStream, 24) // Bits per pixel
        writeBigEndianInt(picStream, 0)  // Number of indexed colors
        writeBigEndianInt(picStream, artworkBytes.size)
        picStream.write(artworkBytes)
        return picStream.toByteArray()
    }

    // =========================================================================
    // MP4 / M4A HELPER FUNCTIONS
    // =========================================================================

    private fun buildMp4Box(type: String, payload: ByteArray): ByteArray {
        val totalSize = 8 + payload.size
        val out = ByteArray(totalSize)
        out[0] = ((totalSize shr 24) and 0xFF).toByte()
        out[1] = ((totalSize shr 16) and 0xFF).toByte()
        out[2] = ((totalSize shr 8) and 0xFF).toByte()
        out[3] = (totalSize and 0xFF).toByte()
        val typeBytes = type.take(4).toByteArray(StandardCharsets.ISO_8859_1)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun buildMp4DataBox(typeFlag: Int, dataBytes: ByteArray): ByteArray {
        val payload = ByteArray(8 + dataBytes.size)
        payload[0] = ((typeFlag shr 24) and 0xFF).toByte()
        payload[1] = ((typeFlag shr 16) and 0xFF).toByte()
        payload[2] = ((typeFlag shr 8) and 0xFF).toByte()
        payload[3] = (typeFlag and 0xFF).toByte()
        System.arraycopy(dataBytes, 0, payload, 8, dataBytes.size)
        return buildMp4Box("data", payload)
    }

    private fun buildMp4TextTag(fourcc: String, text: String): ByteArray {
        val dataBox = buildMp4DataBox(1, text.toByteArray(StandardCharsets.UTF_8))
        return buildMp4Box(fourcc, dataBox)
    }

    private fun buildMp4IlstPayload(payload: CompleteTagPayload): ByteArray {
        val out = ByteArrayOutputStream()

        payload.title?.takeIf { it.isNotBlank() }?.let { out.write(buildMp4TextTag("©nam", it)) }
        payload.artist?.takeIf { it.isNotBlank() }?.let { out.write(buildMp4TextTag("©ART", it)) }
        payload.albumArtist?.takeIf { it.isNotBlank() }?.let { out.write(buildMp4TextTag("aART", it)) }
        payload.album?.takeIf { it.isNotBlank() }?.let { out.write(buildMp4TextTag("©alb", it)) }
        payload.genre?.takeIf { it.isNotBlank() }?.let { out.write(buildMp4TextTag("©gen", it)) }
        payload.comment?.takeIf { it.isNotBlank() }?.let { out.write(buildMp4TextTag("©cmt", it)) }

        val yearStr = payload.releaseYear?.toString() ?: payload.releaseDate
        if (!yearStr.isNullOrBlank()) {
            out.write(buildMp4TextTag("©day", yearStr))
        }

        if (payload.trackNumber != null && payload.trackNumber > 0) {
            val total = payload.totalTracks ?: 0
            val trkData = ByteArray(8)
            trkData[2] = ((payload.trackNumber shr 8) and 0xFF).toByte()
            trkData[3] = (payload.trackNumber and 0xFF).toByte()
            trkData[4] = ((total shr 8) and 0xFF).toByte()
            trkData[5] = (total and 0xFF).toByte()
            out.write(buildMp4Box("trkn", buildMp4DataBox(0, trkData)))
        }

        if (payload.discNumber != null && payload.discNumber > 0) {
            val total = payload.totalDiscs ?: 0
            val dskData = ByteArray(6)
            dskData[2] = ((payload.discNumber shr 8) and 0xFF).toByte()
            dskData[3] = (payload.discNumber and 0xFF).toByte()
            dskData[4] = ((total shr 8) and 0xFF).toByte()
            dskData[5] = (total and 0xFF).toByte()
            out.write(buildMp4Box("disk", buildMp4DataBox(0, dskData)))
        }

        if (payload.bpm != null && payload.bpm in 30.0..300.0) {
            val intBpm = payload.bpm.roundToInt()
            val tmpoData = ByteArray(2)
            tmpoData[0] = ((intBpm shr 8) and 0xFF).toByte()
            tmpoData[1] = (intBpm and 0xFF).toByte()
            out.write(buildMp4Box("tmpo", buildMp4DataBox(21, tmpoData)))
        }

        if (!payload.musicalKey.isNullOrBlank() && payload.musicalKey != "—") {
            val meanBox = buildMp4Box("mean", "com.apple.iTunes".toByteArray(StandardCharsets.UTF_8))
            val nameBox = buildMp4Box("name", "initialkey".toByteArray(StandardCharsets.UTF_8))
            val dataBox = buildMp4DataBox(1, payload.musicalKey.trim().toByteArray(StandardCharsets.UTF_8))
            val freeformPayload = ByteArrayOutputStream()
            freeformPayload.write(meanBox)
            freeformPayload.write(nameBox)
            freeformPayload.write(dataBox)
            out.write(buildMp4Box("----", freeformPayload.toByteArray()))
        }

        if (payload.artworkBytes != null && payload.artworkBytes.isNotEmpty()) {
            val typeFlag = if (payload.artworkMimeType.contains("png", ignoreCase = true)) 14 else 13
            val covrData = buildMp4DataBox(typeFlag, payload.artworkBytes)
            out.write(buildMp4Box("covr", covrData))
        }

        return out.toByteArray()
    }

    private fun replaceOrAppendBox(containerBytes: ByteArray, boxType: String, replacementBox: ByteArray): ByteArray {
        var pos = 0
        var foundStart = -1
        var foundLen = 0

        while (pos + 8 <= containerBytes.size) {
            val boxLen = ((containerBytes[pos].toInt() and 0xFF) shl 24) or
                    ((containerBytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((containerBytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (containerBytes[pos + 3].toInt() and 0xFF)
            val type = String(containerBytes, pos + 4, 4, StandardCharsets.ISO_8859_1)

            if (boxLen < 8 || pos + boxLen > containerBytes.size) break
            if (type == boxType) {
                foundStart = pos
                foundLen = boxLen
                break
            }
            pos += boxLen
        }

        val out = ByteArrayOutputStream()
        if (foundStart >= 0) {
            out.write(containerBytes, 0, foundStart)
            out.write(replacementBox)
            val after = foundStart + foundLen
            if (after < containerBytes.size) {
                out.write(containerBytes, after, containerBytes.size - after)
            }
        } else {
            out.write(containerBytes)
            out.write(replacementBox)
        }
        return out.toByteArray()
    }

    private fun adjustMp4ChunkOffsets(moovPayload: ByteArray, delta: Long): ByteArray {
        val result = moovPayload.clone()
        var pos = 0
        while (pos + 8 <= result.size) {
            val boxLen = ((result[pos].toInt() and 0xFF) shl 24) or
                    ((result[pos + 1].toInt() and 0xFF) shl 16) or
                    ((result[pos + 2].toInt() and 0xFF) shl 8) or
                    (result[pos + 3].toInt() and 0xFF)

            if (boxLen < 8 || pos + boxLen > result.size) {
                pos++
                continue
            }

            val type = String(result, pos + 4, 4, StandardCharsets.ISO_8859_1)
            if (type == "stco") {
                val entryCount = ((result[pos + 12].toInt() and 0xFF) shl 24) or
                        ((result[pos + 13].toInt() and 0xFF) shl 16) or
                        ((result[pos + 14].toInt() and 0xFF) shl 8) or
                        (result[pos + 15].toInt() and 0xFF)

                var offsetPos = pos + 16
                for (i in 0 until entryCount) {
                    if (offsetPos + 4 > pos + boxLen) break
                    val oldOffset = ((result[offsetPos].toLong() and 0xFF) shl 24) or
                            ((result[offsetPos + 1].toLong() and 0xFF) shl 16) or
                            ((result[offsetPos + 2].toLong() and 0xFF) shl 8) or
                            (result[offsetPos + 3].toLong() and 0xFF)

                    val newOffset = oldOffset + delta
                    result[offsetPos] = ((newOffset shr 24) and 0xFF).toByte()
                    result[offsetPos + 1] = ((newOffset shr 16) and 0xFF).toByte()
                    result[offsetPos + 2] = ((newOffset shr 8) and 0xFF).toByte()
                    result[offsetPos + 3] = (newOffset and 0xFF).toByte()
                    offsetPos += 4
                }
            }
            pos += boxLen
        }
        return result
    }

    // =========================================================================
    // OGG HELPERS
    // =========================================================================

    private fun buildOggPagesFromPacket(packet: ByteArray, serial: Int, startSeqNum: Int, granulePos: Long): List<ByteArray> {
        val pages = mutableListOf<ByteArray>()
        var offset = 0
        var currentSeq = startSeqNum

        while (offset < packet.size || pages.isEmpty()) {
            val rem = packet.size - offset
            val maxSegments = 255
            val maxBytes = maxSegments * 255

            val bytesInPage = minOf(rem, maxBytes)
            val segments = mutableListOf<Int>()
            var bytesLeft = bytesInPage
            while (bytesLeft >= 255) {
                segments.add(255)
                bytesLeft -= 255
            }
            if (bytesInPage < maxBytes || rem == bytesInPage) {
                segments.add(bytesLeft)
            }

            val segTable = ByteArray(segments.size) { segments[it].toByte() }
            val pagePayload = ByteArray(bytesInPage)
            System.arraycopy(packet, offset, pagePayload, 0, bytesInPage)
            offset += bytesInPage

            val headerType = if (currentSeq == startSeqNum) 0x00 else 0x01
            val pageBytes = createOggPageBytes(headerType, granulePos, serial, currentSeq, segTable, pagePayload)
            pages.add(pageBytes)
            currentSeq++
        }
        return pages
    }

    private fun createOggPageBytes(headerType: Int, granulePos: Long, serial: Int, seqNum: Int, segTable: ByteArray, payload: ByteArray): ByteArray {
        val totalSize = 27 + segTable.size + payload.size
        val page = ByteArray(totalSize)
        page[0] = 'O'.code.toByte()
        page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte()
        page[3] = 'S'.code.toByte()
        page[4] = 0
        page[5] = headerType.toByte()
        for (i in 0 until 8) {
            page[6 + i] = ((granulePos shr (i * 8)) and 0xFF).toByte()
        }
        page[14] = (serial and 0xFF).toByte()
        page[15] = ((serial shr 8) and 0xFF).toByte()
        page[16] = ((serial shr 16) and 0xFF).toByte()
        page[17] = ((serial shr 24) and 0xFF).toByte()

        page[18] = (seqNum and 0xFF).toByte()
        page[19] = ((seqNum shr 8) and 0xFF).toByte()
        page[20] = ((seqNum shr 16) and 0xFF).toByte()
        page[21] = ((seqNum shr 24) and 0xFF).toByte()

        page[26] = segTable.size.toByte()
        System.arraycopy(segTable, 0, page, 27, segTable.size)
        System.arraycopy(payload, 0, page, 27 + segTable.size, payload.size)

        val crc = computeOggCrc(page, 0, page.size)
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc shr 8) and 0xFF).toByte()
        page[24] = ((crc shr 16) and 0xFF).toByte()
        page[25] = ((crc shr 24) and 0xFF).toByte()
        return page
    }

    private fun writeOggPage(out: OutputStream, headerType: Int, granulePos: Long, serial: Int, seqNum: Int, segTable: ByteArray, payload: ByteArray) {
        val pageBytes = createOggPageBytes(headerType, granulePos, serial, seqNum, segTable, payload)
        out.write(pageBytes)
    }

    private fun computeOggCrc(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            val idx = ((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor OGG_CRC_TABLE[idx]
        }
        return crc
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var skipped = 0L
        while (skipped < bytesToSkip) {
            val s = stream.skip(bytesToSkip - skipped)
            if (s <= 0) {
                if (stream.read() == -1) break
                skipped++
            } else {
                skipped += s
            }
        }
    }

    private fun buildTextFrameData(text: String): ByteArray {
        val isAscii = text.all { it.code in 0..127 }
        return if (isAscii) {
            val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            val result = ByteArray(1 + bytes.size)
            result[0] = 0
            System.arraycopy(bytes, 0, result, 1, bytes.size)
            result
        } else {
            val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
            val result = ByteArray(1 + 2 + textBytes.size)
            result[0] = 1
            result[1] = 0xFF.toByte()
            result[2] = 0xFE.toByte()
            System.arraycopy(textBytes, 0, result, 3, textBytes.size)
            result
        }
    }

    private fun buildUserTextFrameData(description: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00) // ISO-8859-1 encoding
        out.write(description.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(0x00) // 0-terminator delimiter
        out.write(value.toByteArray(StandardCharsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun buildCommentFrameData(text: String): ByteArray {
        val isAscii = text.all { it.code in 0..127 }
        val lang = "eng".toByteArray(StandardCharsets.ISO_8859_1)
        return if (isAscii) {
            val textBytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            val out = ByteArrayOutputStream()
            out.write(0)
            out.write(lang)
            out.write(0)
            out.write(textBytes)
            out.toByteArray()
        } else {
            val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
            val out = ByteArrayOutputStream()
            out.write(1)
            out.write(lang)
            out.write(byteArrayOf(0, 0))
            out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            out.write(textBytes)
            out.toByteArray()
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
