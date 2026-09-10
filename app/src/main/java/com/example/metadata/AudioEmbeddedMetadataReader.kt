package com.example.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

/**
 * Authoritative embedded audio metadata reader.
 *
 * Extracts standard metadata (title, artist, album, genre, ISRC, barcode,
 * label, BPM, musical key, artwork, and track numbers) from:
 * 1. MediaMetadataRetriever (standard platform extractor)
 * 2. Raw ID3v2 frames (MP3, WAV id3 chunk, etc.)
 * 3. RIFF WAVE INFO chunks (INAM, IART, IPRD, IGNR, ICRD, ITRK, ICMT)
 * 4. Raw FLAC / OGG / OPUS Vorbis comment metadata blocks
 * 5. M4A / AAC MP4 atoms (moov/udta/meta/ilst)
 */
data class EmbeddedAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val durationSeconds: Int = 0,
    val bitrateKbps: Int = 0,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val barcode: String? = null,
    val isrc: String? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val camelotKey: String? = null,
    val releaseCountry: String? = null,
    val releaseStatus: String? = null,
    val hasEmbeddedArtwork: Boolean = false,
    val embeddedArtworkSize: Int = 0,
    val embeddedArtworkBytes: ByteArray? = null
) {
    val hasBpm: Boolean get() = bpm != null && bpm in 30.0..300.0
    val hasKey: Boolean get() = !musicalKey.isNullOrBlank() && musicalKey != "—" && musicalKey != "-" && !musicalKey.equals("Unknown", ignoreCase = true)
}

object AudioEmbeddedMetadataReader {
    private const val TAG = "AudioEmbeddedMetadata"
    private const val MAX_TAG_HEADER_READ = 1024 * 1024 // Read up to 1MB for embedded tags

    fun read(context: Context? = null, filePathOrUri: String): EmbeddedAudioMetadata {
        if (filePathOrUri.isBlank()) return EmbeddedAudioMetadata()

        val retrieverMetadata = readWithRetriever(context, filePathOrUri)
        val streamMetadata = readFromStream(context, filePathOrUri)

        return mergeMetadata(retrieverMetadata, streamMetadata)
    }

    private fun readWithRetriever(context: Context?, filePathOrUri: String): EmbeddedAudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                if (context == null) return EmbeddedAudioMetadata()
                context.contentResolver.openFileDescriptor(Uri.parse(filePathOrUri), "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: return EmbeddedAudioMetadata()
            } else {
                val f = File(filePathOrUri)
                if (f.exists() && f.canRead()) {
                    retriever.setDataSource(f.absolutePath)
                } else {
                    return EmbeddedAudioMetadata()
                }
            }

            val mTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val mArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val mAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val mAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val mGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val mDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val mBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()

            val mSampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            } else null

            val mBitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            } else null

            val mTrackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let(::parseIndexNumber)
            val mDiscNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let(::parseIndexNumber)
            val mDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val mYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
                ?: mDate?.take(4)?.toIntOrNull()

            val mBpm = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?.takeIf { it in 30.0..300.0 }

            val embeddedPic = retriever.embeddedPicture
            val hasArt = embeddedPic != null && embeddedPic.isNotEmpty()
            val artSize = embeddedPic?.size ?: 0

            EmbeddedAudioMetadata(
                title = mTitle?.takeIf(String::isNotBlank),
                artist = mArtist?.takeIf(String::isNotBlank),
                album = mAlbum?.takeIf(String::isNotBlank),
                albumArtist = mAlbumArtist?.takeIf(String::isNotBlank),
                genre = mGenre?.takeIf(String::isNotBlank),
                durationSeconds = mDuration?.let { if (it > 1000L) (it / 1000).toInt() else 0 } ?: 0,
                bitrateKbps = mBitrate?.let { it / 1000 } ?: 0,
                sampleRate = mSampleRate,
                bitDepth = mBitDepth,
                trackNumber = mTrackNumber,
                discNumber = mDiscNumber,
                releaseDate = mDate?.takeIf(String::isNotBlank),
                releaseYear = mYear,
                bpm = mBpm,
                hasEmbeddedArtwork = hasArt,
                embeddedArtworkSize = artSize,
                embeddedArtworkBytes = embeddedPic
            )
        } catch (e: Exception) {
            Log.v(TAG, "MediaMetadataRetriever skipped for $filePathOrUri: ${e.message}")
            EmbeddedAudioMetadata()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun readFromStream(context: Context?, filePathOrUri: String): EmbeddedAudioMetadata {
        return try {
            val stream: InputStream? = if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                context?.contentResolver?.openInputStream(Uri.parse(filePathOrUri))
            } else {
                val f = File(filePathOrUri)
                if (f.exists() && f.canRead()) f.inputStream() else null
            }

            stream?.use { input ->
                val header = ByteArray(12)
                val readHeader = input.read(header)
                if (readHeader < 4) return EmbeddedAudioMetadata()

                when {
                    header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> {
                        val combinedStream = if (readHeader > 10) {
                            SequenceInputStream(ByteArrayInputStream(header, 10, readHeader - 10), input)
                        } else {
                            input
                        }
                        parseId3OrPrependedWav(header, combinedStream)
                    }
                    header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> {
                        val combinedStream = if (readHeader > 4) {
                            SequenceInputStream(ByteArrayInputStream(header, 4, readHeader - 4), input)
                        } else {
                            input
                        }
                        parseFlacVorbisComment(combinedStream)
                    }
                    (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) ||
                    (header[0] == 'R'.code.toByte() && header[1] == 'F'.code.toByte() && header[2] == '6'.code.toByte() && header[3] == '4'.code.toByte()) ||
                    (header[0] == 'B'.code.toByte() && header[1] == 'W'.code.toByte() && header[2] == '6'.code.toByte() && header[3] == '4'.code.toByte()) -> {
                        parseWavStream(header, readHeader, input)
                    }
                    header[0] == 'F'.code.toByte() && header[1] == 'O'.code.toByte() && header[2] == 'R'.code.toByte() && header[3] == 'M'.code.toByte() -> {
                        parseAiffStream(header, readHeader, input)
                    }
                    header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() && header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() -> {
                        parseOggStream(header, readHeader, input)
                    }
                    readHeader >= 8 && (String(header, 4, 4, StandardCharsets.ISO_8859_1) == "ftyp" || String(header, 4, 4, StandardCharsets.ISO_8859_1) == "moov") -> {
                        val combinedStream = SequenceInputStream(ByteArrayInputStream(header, 0, readHeader), input)
                        parseM4aStream(combinedStream)
                    }
                    else -> EmbeddedAudioMetadata()
                }
            } ?: EmbeddedAudioMetadata()
        } catch (e: Exception) {
            Log.v(TAG, "Direct stream tag extraction skipped for $filePathOrUri: ${e.message}")
            EmbeddedAudioMetadata()
        }
    }

    // =========================================================================
    // WAV (RIFF WAVE / RF64 / BW64) PARSER
    // Accurately extracts fmt chunk (channels, sample rate, bit depth, byte rate),
    // data chunk (audio payload size), duration, bitrate, and embedded ID3 / INFO tags.
    // =========================================================================

    private fun safeSkipStream(stream: InputStream, totalToSkip: Long) {
        var remaining = totalToSkip
        val buf = ByteArray(minOf(remaining, 8192L).toInt())
        while (remaining > 0) {
            val s = stream.skip(remaining)
            if (s > 0) {
                remaining -= s
            } else {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val r = stream.read(buf, 0, toRead)
                if (r <= 0) break
                remaining -= r
            }
        }
    }

    private fun parseId3OrPrependedWav(header: ByteArray, stream: InputStream): EmbeddedAudioMetadata {
        val flags = header[5].toInt()
        val hasFooter = (flags and 0x10) != 0
        val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)

        val bytesToRead = min(tagSize, MAX_TAG_HEADER_READ)
        val tagBytes = ByteArray(bytesToRead)
        var totalRead = 0
        while (totalRead < bytesToRead) {
            val r = stream.read(tagBytes, totalRead, bytesToRead - totalRead)
            if (r <= 0) break
            totalRead += r
        }

        val id3Meta = if (totalRead > 0) {
            parseId3Tags(header, ByteArrayInputStream(tagBytes, 0, totalRead))
        } else {
            EmbeddedAudioMetadata()
        }

        val skipRemaining = (tagSize - totalRead).toLong() + (if (hasFooter) 10L else 0L)
        if (skipRemaining > 0) {
            safeSkipStream(stream, skipRemaining)
        }

        // Check if RIFF WAVE follows prepended ID3
        val nextHeader = ByteArray(12)
        var nhRead = 0
        while (nhRead < 12) {
            val c = stream.read(nextHeader, nhRead, 12 - nhRead)
            if (c <= 0) break
            nhRead += c
        }

        if (nhRead >= 12) {
            val magic = String(nextHeader, 0, 4, StandardCharsets.US_ASCII)
            val format = String(nextHeader, 8, 4, StandardCharsets.US_ASCII)
            if ((magic.equals("RIFF", ignoreCase = true) || magic.equals("RF64", ignoreCase = true) || magic.equals("BW64", ignoreCase = true)) &&
                format.equals("WAVE", ignoreCase = true)
            ) {
                val wavMeta = parseWavStream(nextHeader, nhRead, stream)
                return mergeMetadata(id3Meta, wavMeta)
            }
        }

        return id3Meta
    }

    private fun parseWavStream(header: ByteArray, readHeader: Int, stream: InputStream): EmbeddedAudioMetadata {
        val fullHdr = ByteArray(12)
        System.arraycopy(header, 0, fullHdr, 0, min(readHeader, 12))
        var need = 12 - readHeader
        var off = readHeader
        while (need > 0) {
            val c = stream.read(fullHdr, off, need)
            if (c <= 0) break
            off += c
            need -= c
        }

        if (fullHdr[8] != 'W'.code.toByte() || fullHdr[9] != 'A'.code.toByte() ||
            fullHdr[10] != 'V'.code.toByte() || fullHdr[11] != 'E'.code.toByte()) {
            return EmbeddedAudioMetadata()
        }

        var infoMeta: EmbeddedAudioMetadata? = null
        var id3Meta: EmbeddedAudioMetadata? = null

        var sampleRate = 0
        var numChannels = 0
        var bitsPerSample = 0
        var byteRate = 0L
        var totalDataBytes = 0L

        val chunkHdr = ByteArray(8)
        while (true) {
            var r = 0
            while (r < 8) {
                val c = stream.read(chunkHdr, r, 8 - r)
                if (c <= 0) break
                r += c
            }
            if (r < 8) break

            val chunkId = String(chunkHdr, 0, 4, StandardCharsets.US_ASCII)
            val chunkSize = (chunkHdr[4].toLong() and 0xFFL) or
                    ((chunkHdr[5].toLong() and 0xFFL) shl 8) or
                    ((chunkHdr[6].toLong() and 0xFFL) shl 16) or
                    ((chunkHdr[7].toLong() and 0xFFL) shl 24)
            val pad = if (chunkSize % 2L != 0L) 1L else 0L

            when {
                chunkId.equals("fmt ", ignoreCase = true) -> {
                    if (chunkSize in 14..1048576) {
                        val fmtBuf = ByteArray(chunkSize.toInt())
                        var readTotal = 0
                        while (readTotal < fmtBuf.size) {
                            val c = stream.read(fmtBuf, readTotal, fmtBuf.size - readTotal)
                            if (c <= 0) break
                            readTotal += c
                        }
                        if (readTotal >= 14) {
                            numChannels = (fmtBuf[2].toInt() and 0xFF) or ((fmtBuf[3].toInt() and 0xFF) shl 8)
                            sampleRate = (fmtBuf[4].toInt() and 0xFF) or
                                    ((fmtBuf[5].toInt() and 0xFF) shl 8) or
                                    ((fmtBuf[6].toInt() and 0xFF) shl 16) or
                                    ((fmtBuf[7].toInt() and 0xFF) shl 24)
                            byteRate = (fmtBuf[8].toLong() and 0xFFL) or
                                    ((fmtBuf[9].toLong() and 0xFFL) shl 8) or
                                    ((fmtBuf[10].toLong() and 0xFFL) shl 16) or
                                    ((fmtBuf[11].toLong() and 0xFFL) shl 24)
                            if (readTotal >= 16) {
                                bitsPerSample = (fmtBuf[14].toInt() and 0xFF) or ((fmtBuf[15].toInt() and 0xFF) shl 8)
                            }
                        }
                        if (pad > 0) safeSkipStream(stream, pad)
                    } else {
                        safeSkipStream(stream, chunkSize + pad)
                    }
                }
                chunkId.equals("data", ignoreCase = true) -> {
                    totalDataBytes += chunkSize
                    safeSkipStream(stream, chunkSize + pad)
                }
                chunkId.equals("id3 ", ignoreCase = true) || chunkId.equals("ID3 ", ignoreCase = true) -> {
                    val bufSize = minOf(chunkSize, MAX_TAG_HEADER_READ.toLong()).toInt()
                    val id3Buf = ByteArray(bufSize)
                    var readTotal = 0
                    while (readTotal < id3Buf.size) {
                        val c = stream.read(id3Buf, readTotal, id3Buf.size - readTotal)
                        if (c <= 0) break
                        readTotal += c
                    }
                    val skipRemaining = chunkSize - readTotal + pad
                    if (skipRemaining > 0) {
                        safeSkipStream(stream, skipRemaining)
                    }
                    if (id3Buf.size >= 10 && id3Buf[0] == 'I'.code.toByte() && id3Buf[1] == 'D'.code.toByte() && id3Buf[2] == '3'.code.toByte()) {
                        id3Meta = parseId3Tags(id3Buf.copyOfRange(0, 10), ByteArrayInputStream(id3Buf, 10, id3Buf.size - 10))
                    }
                }
                chunkId.equals("LIST", ignoreCase = true) -> {
                    val bufSize = minOf(chunkSize, MAX_TAG_HEADER_READ.toLong()).toInt()
                    val listBuf = ByteArray(bufSize)
                    var readTotal = 0
                    while (readTotal < listBuf.size) {
                        val c = stream.read(listBuf, readTotal, listBuf.size - readTotal)
                        if (c <= 0) break
                        readTotal += c
                    }
                    val skipRemaining = chunkSize - readTotal + pad
                    if (skipRemaining > 0) {
                        safeSkipStream(stream, skipRemaining)
                    }
                    if (listBuf.size >= 4 && String(listBuf, 0, 4, StandardCharsets.US_ASCII).equals("INFO", ignoreCase = true)) {
                        infoMeta = parseRiffInfoChunk(listBuf, readTotal)
                    }
                }
                else -> {
                    safeSkipStream(stream, chunkSize + pad)
                }
            }
        }

        val effectiveByteRate = when {
            byteRate > 0L -> byteRate
            sampleRate > 0 && numChannels > 0 && bitsPerSample > 0 -> {
                sampleRate.toLong() * numChannels.toLong() * (bitsPerSample.toLong() / 8L)
            }
            else -> 0L
        }

        val durationSec = if (effectiveByteRate > 0L && totalDataBytes > 0L) {
            (totalDataBytes / effectiveByteRate).toInt()
        } else 0

        val bitrateKbps = when {
            effectiveByteRate > 0L -> ((effectiveByteRate * 8L) / 1000L).toInt()
            durationSec > 0 && totalDataBytes > 0L -> ((totalDataBytes * 8L) / (durationSec.toLong() * 1000L)).toInt()
            sampleRate > 0 -> 1411
            else -> 0
        }

        val baseInfo = infoMeta ?: EmbeddedAudioMetadata()
        val baseId3 = id3Meta ?: EmbeddedAudioMetadata()

        return EmbeddedAudioMetadata(
            title = baseId3.title ?: baseInfo.title,
            artist = baseId3.artist ?: baseInfo.artist,
            album = baseId3.album ?: baseInfo.album,
            albumArtist = baseId3.albumArtist ?: baseInfo.albumArtist,
            genre = baseId3.genre ?: baseInfo.genre,
            durationSeconds = durationSec,
            bitrateKbps = bitrateKbps,
            sampleRate = if (sampleRate > 0) sampleRate else null,
            bitDepth = if (bitsPerSample > 0) bitsPerSample else null,
            trackNumber = baseId3.trackNumber ?: baseInfo.trackNumber,
            discNumber = baseId3.discNumber ?: baseInfo.discNumber,
            releaseDate = baseId3.releaseDate ?: baseInfo.releaseDate,
            releaseYear = baseId3.releaseYear ?: baseInfo.releaseYear,
            bpm = baseId3.bpm ?: baseInfo.bpm,
            musicalKey = baseId3.musicalKey ?: baseInfo.musicalKey,
            camelotKey = baseId3.camelotKey ?: baseInfo.camelotKey,
            hasEmbeddedArtwork = baseId3.hasEmbeddedArtwork,
            embeddedArtworkSize = baseId3.embeddedArtworkSize,
            embeddedArtworkBytes = baseId3.embeddedArtworkBytes
        )
    }

    private fun parseRiffInfoChunk(buffer: ByteArray, length: Int): EmbeddedAudioMetadata {
        var pos = 4 // skip "INFO"
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var trackNumber: Int? = null
        var releaseDate: String? = null
        var releaseYear: Int? = null

        while (pos + 8 <= length) {
            val subId = String(buffer, pos, 4, StandardCharsets.US_ASCII)
            val subLen = (buffer[pos + 4].toInt() and 0xFF) or
                    ((buffer[pos + 5].toInt() and 0xFF) shl 8) or
                    ((buffer[pos + 6].toInt() and 0xFF) shl 16) or
                    ((buffer[pos + 7].toInt() and 0xFF) shl 24)
            val pad = if (subLen % 2 != 0) 1 else 0
            val dataStart = pos + 8
            if (subLen <= 0 || dataStart + subLen > length) break

            val text = String(buffer, dataStart, subLen, StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            when (subId) {
                "INAM" -> title = text
                "IART" -> artist = text
                "IPRD" -> album = text
                "IGNR" -> genre = text
                "ITRK" -> trackNumber = parseIndexNumber(text)
                "ICRD", "IYEAR" -> {
                    releaseDate = text
                    releaseYear = text.take(4).toIntOrNull()
                }
            }
            pos = dataStart + subLen + pad
        }

        return EmbeddedAudioMetadata(
            title = title?.takeIf(String::isNotBlank),
            artist = artist?.takeIf(String::isNotBlank),
            album = album?.takeIf(String::isNotBlank),
            genre = genre?.takeIf(String::isNotBlank),
            trackNumber = trackNumber,
            releaseDate = releaseDate,
            releaseYear = releaseYear
        )
    }

    // =========================================================================
    // AIFF / AIFC (EA IFF 85) PARSER: Parses 'ID3 ' and text chunks
    // =========================================================================

    private fun parseAiffStream(header: ByteArray, readHeader: Int, stream: InputStream): EmbeddedAudioMetadata {
        val fullHdr = ByteArray(12)
        System.arraycopy(header, 0, fullHdr, 0, min(readHeader, 12))
        var need = 12 - readHeader
        var off = readHeader
        while (need > 0) {
            val c = stream.read(fullHdr, off, need)
            if (c <= 0) break
            off += c
            need -= c
        }

        val subtype = String(fullHdr, 8, 4, StandardCharsets.US_ASCII)
        if (subtype != "AIFF" && subtype != "AIFC") {
            return EmbeddedAudioMetadata()
        }

        var id3Meta: EmbeddedAudioMetadata? = null
        var nameText: String? = null
        var authText: String? = null

        val chunkHdr = ByteArray(8)
        while (true) {
            var r = 0
            while (r < 8) {
                val c = stream.read(chunkHdr, r, 8 - r)
                if (c <= 0) break
                r += c
            }
            if (r < 8) break

            val chunkId = String(chunkHdr, 0, 4, StandardCharsets.US_ASCII)
            val chunkSize = ((chunkHdr[4].toInt() and 0xFF) shl 24) or
                    ((chunkHdr[5].toInt() and 0xFF) shl 16) or
                    ((chunkHdr[6].toInt() and 0xFF) shl 8) or
                    (chunkHdr[7].toInt() and 0xFF)
            val pad = if (chunkSize % 2 != 0) 1 else 0

            when {
                chunkId.equals("id3 ", ignoreCase = true) -> {
                    val id3Buf = ByteArray(minOf(chunkSize, MAX_TAG_HEADER_READ))
                    var readTotal = 0
                    while (readTotal < id3Buf.size) {
                        val c = stream.read(id3Buf, readTotal, id3Buf.size - readTotal)
                        if (c <= 0) break
                        readTotal += c
                    }
                    val skipRemaining = chunkSize - readTotal + pad
                    if (skipRemaining > 0) stream.skip(skipRemaining.toLong())

                    if (id3Buf.size >= 10 && id3Buf[0] == 'I'.code.toByte() && id3Buf[1] == 'D'.code.toByte() && id3Buf[2] == '3'.code.toByte()) {
                        id3Meta = parseId3Tags(id3Buf.copyOfRange(0, 10), ByteArrayInputStream(id3Buf, 10, id3Buf.size - 10))
                    }
                }
                chunkId == "NAME" -> {
                    val buf = ByteArray(minOf(chunkSize, 1024))
                    var readTotal = 0
                    while (readTotal < buf.size) {
                        val c = stream.read(buf, readTotal, buf.size - readTotal)
                        if (c <= 0) break
                        readTotal += c
                    }
                    val skipRemaining = chunkSize - readTotal + pad
                    if (skipRemaining > 0) stream.skip(skipRemaining.toLong())
                    nameText = String(buf, StandardCharsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }
                }
                chunkId == "AUTH" -> {
                    val buf = ByteArray(minOf(chunkSize, 1024))
                    var readTotal = 0
                    while (readTotal < buf.size) {
                        val c = stream.read(buf, readTotal, buf.size - readTotal)
                        if (c <= 0) break
                        readTotal += c
                    }
                    val skipRemaining = chunkSize - readTotal + pad
                    if (skipRemaining > 0) stream.skip(skipRemaining.toLong())
                    authText = String(buf, StandardCharsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }
                }
                else -> {
                    var toSkip = chunkSize.toLong() + pad
                    while (toSkip > 0) {
                        val skipped = stream.skip(toSkip)
                        if (skipped <= 0) {
                            if (stream.read() == -1) break
                            toSkip--
                        } else {
                            toSkip -= skipped
                        }
                    }
                }
            }
        }

        val base = id3Meta ?: EmbeddedAudioMetadata()
        return base.copy(
            title = base.title ?: nameText?.takeIf(String::isNotBlank),
            artist = base.artist ?: authText?.takeIf(String::isNotBlank)
        )
    }

    // =========================================================================
    // OGG VORBIS & OPUS PARSER
    // =========================================================================

    private fun parseOggStream(header: ByteArray, readHeader: Int, stream: InputStream): EmbeddedAudioMetadata {
        try {
            // Read Ogg pages looking for comment packet
            var pageIndex = 0
            var buf = header.copyOf(readHeader)

            while (pageIndex < 10) {
                // Ensure page header (27 bytes) is read
                val pageHdr = ByteArray(27)
                if (buf.size >= 27) {
                    System.arraycopy(buf, 0, pageHdr, 0, 27)
                } else {
                    System.arraycopy(buf, 0, pageHdr, 0, buf.size)
                    var r = buf.size
                    while (r < 27) {
                        val c = stream.read(pageHdr, r, 27 - r)
                        if (c <= 0) break
                        r += c
                    }
                    if (r < 27) break
                }

                if (pageHdr[0] != 'O'.code.toByte() || pageHdr[1] != 'g'.code.toByte() || pageHdr[2] != 'g'.code.toByte() || pageHdr[3] != 'S'.code.toByte()) {
                    break
                }

                val numSegments = pageHdr[26].toInt() and 0xFF
                val segTable = ByteArray(numSegments)
                var r = 0
                while (r < numSegments) {
                    val c = stream.read(segTable, r, numSegments - r)
                    if (c <= 0) break
                    r += c
                }
                val payloadLen = segTable.sumOf { it.toInt() and 0xFF }

                if (pageIndex == 0) {
                    // Skip BOS page payload
                    stream.skip(payloadLen.toLong())
                } else {
                    // Page 1 should contain comment header
                    val payload = ByteArray(minOf(payloadLen, MAX_TAG_HEADER_READ))
                    var rPay = 0
                    while (rPay < payload.size) {
                        val c = stream.read(payload, rPay, payload.size - rPay)
                        if (c <= 0) break
                        rPay += c
                    }
                    val rem = payloadLen - rPay
                    if (rem > 0) stream.skip(rem.toLong())

                    if (payload.size > 7 && payload[0] == 0x03.toByte() && String(payload, 1, 6, StandardCharsets.US_ASCII) == "vorbis") {
                        // Vorbis comments start after 7 bytes
                        return parseVorbisCommentBytes(payload.copyOfRange(7, payload.size), payload.size - 7)
                    } else if (payload.size > 8 && String(payload, 0, 8, StandardCharsets.US_ASCII) == "OpusTags") {
                        // OpusTags comments start after 8 bytes
                        return parseVorbisCommentBytes(payload.copyOfRange(8, payload.size), payload.size - 8)
                    }
                }
                buf = ByteArray(0)
                pageIndex++
            }
        } catch (_: Exception) {}
        return EmbeddedAudioMetadata()
    }

    // =========================================================================
    // M4A / AAC (MP4 ISO BOXES) PARSER
    // =========================================================================

    private fun parseM4aStream(stream: InputStream): EmbeddedAudioMetadata {
        try {
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var albumArtist: String? = null
            var genre: String? = null
            var trackNumber: Int? = null
            var discNumber: Int? = null
            var releaseDate: String? = null
            var releaseYear: Int? = null
            var bpm: Double? = null
            var musicalKey: String? = null
            var hasArt = false
            var artSize = 0
            var artBytes: ByteArray? = null

            // Search for moov box
            val boxHdr = ByteArray(8)
            var currentPos = 0L

            while (currentPos < 10 * 1024 * 1024) {
                var r = 0
                while (r < 8) {
                    val c = stream.read(boxHdr, r, 8 - r)
                    if (c <= 0) break
                    r += c
                }
                if (r < 8) break

                var boxLen = ((boxHdr[0].toLong() and 0xFF) shl 24) or
                        ((boxHdr[1].toLong() and 0xFF) shl 16) or
                        ((boxHdr[2].toLong() and 0xFF) shl 8) or
                        (boxHdr[3].toLong() and 0xFF)
                val boxType = String(boxHdr, 4, 4, StandardCharsets.ISO_8859_1)

                var headerSize = 8L
                if (boxLen == 1L) {
                    val ext = ByteArray(8)
                    stream.read(ext)
                    boxLen = 0L
                    for (b in ext) {
                        boxLen = (boxLen shl 8) or (b.toLong() and 0xFF)
                    }
                    headerSize = 16L
                } else if (boxLen < 8L) {
                    break
                }

                val payloadLen = boxLen - headerSize

                if (boxType == "moov") {
                    val moovBytes = ByteArray(minOf(payloadLen, MAX_TAG_HEADER_READ.toLong()).toInt())
                    var rMoov = 0
                    while (rMoov < moovBytes.size) {
                        val c = stream.read(moovBytes, rMoov, moovBytes.size - rMoov)
                        if (c <= 0) break
                        rMoov += c
                    }

                    // Scan inside moovBytes for ilst
                    val ilstPos = findSubsequence(moovBytes, "ilst".toByteArray(StandardCharsets.ISO_8859_1))
                    if (ilstPos >= 4) {
                        val ilstBoxLen = ((moovBytes[ilstPos - 4].toInt() and 0xFF) shl 24) or
                                ((moovBytes[ilstPos - 3].toInt() and 0xFF) shl 16) or
                                ((moovBytes[ilstPos - 2].toInt() and 0xFF) shl 8) or
                                (moovBytes[ilstPos - 1].toInt() and 0xFF)
                        val ilstEnd = minOf(ilstPos - 4 + ilstBoxLen, moovBytes.size)
                        var itemPos = ilstPos + 4

                        while (itemPos + 8 <= ilstEnd) {
                            val itemLen = ((moovBytes[itemPos].toInt() and 0xFF) shl 24) or
                                    ((moovBytes[itemPos + 1].toInt() and 0xFF) shl 16) or
                                    ((moovBytes[itemPos + 2].toInt() and 0xFF) shl 8) or
                                    (moovBytes[itemPos + 3].toInt() and 0xFF)
                            val itemType = String(moovBytes, itemPos + 4, 4, StandardCharsets.ISO_8859_1)
                            if (itemLen < 8 || itemPos + itemLen > ilstEnd) break

                            // Find data box inside item
                            val dataPos = findSubsequenceInRange(moovBytes, "data".toByteArray(StandardCharsets.ISO_8859_1), itemPos + 8, itemPos + itemLen)
                            if (dataPos >= 4) {
                                val dataLen = ((moovBytes[dataPos - 4].toInt() and 0xFF) shl 24) or
                                        ((moovBytes[dataPos - 3].toInt() and 0xFF) shl 16) or
                                        ((moovBytes[dataPos - 2].toInt() and 0xFF) shl 8) or
                                        (moovBytes[dataPos - 1].toInt() and 0xFF)
                                val typeFlag = ((moovBytes[dataPos + 4].toInt() and 0xFF) shl 24) or
                                        ((moovBytes[dataPos + 5].toInt() and 0xFF) shl 16) or
                                        ((moovBytes[dataPos + 6].toInt() and 0xFF) shl 8) or
                                        (moovBytes[dataPos + 7].toInt() and 0xFF)
                                val valStart = dataPos + 12
                                val valLen = dataLen - 16

                                if (valLen > 0 && valStart + valLen <= moovBytes.size) {
                                    val strVal = String(moovBytes, valStart, valLen, StandardCharsets.UTF_8).trim()
                                    when (itemType) {
                                        "©nam" -> title = strVal
                                        "©ART" -> artist = strVal
                                        "aART" -> albumArtist = strVal
                                        "©alb" -> album = strVal
                                        "©gen" -> genre = strVal
                                        "©day" -> {
                                            releaseDate = strVal
                                            releaseYear = strVal.take(4).toIntOrNull()
                                        }
                                        "trkn" -> {
                                            if (valLen >= 4) {
                                                trackNumber = ((moovBytes[valStart + 2].toInt() and 0xFF) shl 8) or (moovBytes[valStart + 3].toInt() and 0xFF)
                                            }
                                        }
                                        "disk" -> {
                                            if (valLen >= 4) {
                                                discNumber = ((moovBytes[valStart + 2].toInt() and 0xFF) shl 8) or (moovBytes[valStart + 3].toInt() and 0xFF)
                                            }
                                        }
                                        "tmpo" -> {
                                            if (valLen >= 2) {
                                                val intBpm = ((moovBytes[valStart].toInt() and 0xFF) shl 8) or (moovBytes[valStart + 1].toInt() and 0xFF)
                                                if (intBpm in 30..300) bpm = intBpm.toDouble()
                                            }
                                        }
                                        "covr" -> {
                                            hasArt = true
                                            artSize = valLen
                                            artBytes = moovBytes.copyOfRange(valStart, valStart + valLen)
                                        }
                                        "----" -> {
                                            // Freeform check for initialkey
                                            val freeformText = String(moovBytes, itemPos, itemLen, StandardCharsets.ISO_8859_1)
                                            if (freeformText.contains("initialkey", ignoreCase = true)) {
                                                musicalKey = strVal
                                            }
                                        }
                                    }
                                }
                            }
                            itemPos += itemLen
                        }
                    }
                    break
                } else {
                    var skipped = 0L
                    while (skipped < payloadLen) {
                        val s = stream.skip(payloadLen - skipped)
                        if (s <= 0) {
                            if (stream.read() == -1) break
                            skipped++
                        } else {
                            skipped += s
                        }
                    }
                }
                currentPos += headerSize + payloadLen
            }

            return EmbeddedAudioMetadata(
                title = title?.takeIf(String::isNotBlank),
                artist = artist?.takeIf(String::isNotBlank),
                album = album?.takeIf(String::isNotBlank),
                albumArtist = albumArtist?.takeIf(String::isNotBlank),
                genre = genre?.takeIf(String::isNotBlank),
                trackNumber = trackNumber,
                discNumber = discNumber,
                releaseDate = releaseDate,
                releaseYear = releaseYear,
                bpm = bpm,
                musicalKey = musicalKey,
                camelotKey = CamelotKey.fromMusicalKey(musicalKey),
                hasEmbeddedArtwork = hasArt,
                embeddedArtworkSize = artSize,
                embeddedArtworkBytes = artBytes
            )
        } catch (_: Exception) {
            return EmbeddedAudioMetadata()
        }
    }

    private fun findSubsequence(source: ByteArray, target: ByteArray): Int {
        return findSubsequenceInRange(source, target, 0, source.size)
    }

    private fun findSubsequenceInRange(source: ByteArray, target: ByteArray, start: Int, end: Int): Int {
        if (target.isEmpty() || end - start < target.size) return -1
        val max = end - target.size
        for (i in start..max) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    // =========================================================================
    // ID3 & FLAC PARSERS
    // =========================================================================

    private fun parseId3Tags(header: ByteArray, stream: InputStream): EmbeddedAudioMetadata {
        val versionMajor = header[3].toInt() and 0xFF
        val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)

        val bytesToRead = min(tagSize, MAX_TAG_HEADER_READ)
        val tagBytes = ByteArray(bytesToRead)
        var totalRead = 0
        while (totalRead < bytesToRead) {
            val r = stream.read(tagBytes, totalRead, bytesToRead - totalRead)
            if (r <= 0) break
            totalRead += r
        }
        if (totalRead <= 0) return EmbeddedAudioMetadata()

        val buffer = ByteBuffer.wrap(tagBytes, 0, totalRead)
        buffer.order(ByteOrder.BIG_ENDIAN)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var releaseDate: String? = null
        var releaseYear: Int? = null
        var recordLabel: String? = null
        var barcode: String? = null
        var isrc: String? = null
        var bpm: Double? = null
        var musicalKey: String? = null
        var releaseCountry: String? = null
        var releaseStatus: String? = null
        var hasArtwork = false
        var artworkSize = 0
        var artworkBytes: ByteArray? = null

        val isV24 = versionMajor >= 4

        while (buffer.remaining() >= 10) {
            val frameIdBytes = ByteArray(4)
            buffer.get(frameIdBytes)
            if (frameIdBytes[0].toInt() == 0) break
            val frameId = String(frameIdBytes, StandardCharsets.ISO_8859_1)

            val frameSize = if (isV24) {
                val b0 = buffer.get().toInt() and 0x7F
                val b1 = buffer.get().toInt() and 0x7F
                val b2 = buffer.get().toInt() and 0x7F
                val b3 = buffer.get().toInt() and 0x7F
                (b0 shl 21) or (b1 shl 14) or (b2 shl 7) or b3
            } else {
                buffer.int
            }
            buffer.short // Skip flags

            if (frameSize <= 0 || frameSize > buffer.remaining()) break

            val framePayload = ByteArray(frameSize)
            buffer.get(framePayload)

            try {
                when (frameId) {
                    "TIT2" -> title = decodeTextFrame(framePayload)
                    "TPE1" -> artist = decodeTextFrame(framePayload)
                    "TALB" -> album = decodeTextFrame(framePayload)
                    "TPE2" -> albumArtist = decodeTextFrame(framePayload)
                    "TCON" -> genre = decodeTextFrame(framePayload)
                    "TRCK" -> trackNumber = parseIndexNumber(decodeTextFrame(framePayload))
                    "TPOS" -> discNumber = parseIndexNumber(decodeTextFrame(framePayload))
                    "TSRC" -> isrc = decodeTextFrame(framePayload).trim()
                    "TPUB" -> recordLabel = decodeTextFrame(framePayload).trim()
                    "TYER", "TDRC" -> {
                        val d = decodeTextFrame(framePayload).trim()
                        if (d.isNotBlank()) {
                            releaseDate = d
                            releaseYear = d.take(4).toIntOrNull()
                        }
                    }
                    "APIC", "PIC" -> {
                        hasArtwork = true
                        artworkSize = framePayload.size
                        artworkBytes = extractApicImageBytes(framePayload)
                    }
                    "TBPM" -> {
                        val bpmStr = decodeTextFrame(framePayload).filter { it.isDigit() || it == '.' }
                        bpm = bpmStr.toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
                    }
                    "TKEY" -> {
                        val rawKey = decodeTextFrame(framePayload).trim()
                        if (rawKey.isNotBlank()) musicalKey = rawKey
                    }
                    "TXXX" -> {
                        val txxx = decodeTxxxFrame(framePayload)
                        if (txxx != null) {
                            val desc = txxx.first.trim().lowercase(Locale.ROOT)
                            val value = txxx.second.trim()
                            when {
                                desc == "barcode" -> barcode = value
                                desc == "initialkey" -> if (musicalKey.isNullOrBlank()) musicalKey = value
                                desc == "releasecountry" -> releaseCountry = value
                                desc == "releasestatus" -> releaseStatus = value
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val camelot = CamelotKey.fromMusicalKey(musicalKey)

        return EmbeddedAudioMetadata(
            title = title?.takeIf(String::isNotBlank),
            artist = artist?.takeIf(String::isNotBlank),
            album = album?.takeIf(String::isNotBlank),
            albumArtist = albumArtist?.takeIf(String::isNotBlank),
            genre = genre?.takeIf(String::isNotBlank),
            trackNumber = trackNumber,
            discNumber = discNumber,
            releaseDate = releaseDate,
            releaseYear = releaseYear,
            recordLabel = recordLabel?.takeIf(String::isNotBlank),
            barcode = barcode?.takeIf(String::isNotBlank),
            isrc = isrc?.takeIf(String::isNotBlank),
            bpm = bpm,
            musicalKey = musicalKey?.takeIf(String::isNotBlank),
            camelotKey = camelot,
            releaseCountry = releaseCountry?.takeIf(String::isNotBlank),
            releaseStatus = releaseStatus?.takeIf(String::isNotBlank),
            hasEmbeddedArtwork = hasArtwork,
            embeddedArtworkSize = artworkSize,
            embeddedArtworkBytes = artworkBytes
        )
    }

    private fun extractApicImageBytes(payload: ByteArray): ByteArray? {
        if (payload.size < 4) return null
        val encoding = payload[0].toInt()
        var pos = 1
        // Skip MIME type
        while (pos < payload.size && payload[pos] != 0.toByte()) {
            pos++
        }
        pos++ // skip null
        if (pos >= payload.size) return null
        pos++ // skip picture type
        // Skip description
        if (encoding == 1 || encoding == 2) {
            while (pos + 1 < payload.size && !(payload[pos] == 0.toByte() && payload[pos + 1] == 0.toByte())) {
                pos += 2
            }
            pos += 2
        } else {
            while (pos < payload.size && payload[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }
        if (pos < payload.size) {
            return payload.copyOfRange(pos, payload.size)
        }
        return null
    }

    private fun parseFlacVorbisComment(stream: InputStream): EmbeddedAudioMetadata {
        var isLast = false
        var parsedMetadata: EmbeddedAudioMetadata? = null
        var hasFlacPicture = false
        var flacPictureSize = 0
        var flacPictureBytes: ByteArray? = null

        while (!isLast) {
            val blockHeader = ByteArray(4)
            val read = stream.read(blockHeader)
            if (read < 4) break

            isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockLength = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                    ((blockHeader[2].toInt() and 0xFF) shl 8) or
                    (blockHeader[3].toInt() and 0xFF)

            if (blockType == 4) { // VORBIS_COMMENT
                val commentBytes = ByteArray(min(blockLength, MAX_TAG_HEADER_READ))
                var total = 0
                while (total < commentBytes.size) {
                    val r = stream.read(commentBytes, total, commentBytes.size - total)
                    if (r <= 0) break
                    total += r
                }
                val skipRemaining = blockLength - total
                if (skipRemaining > 0) stream.skip(skipRemaining.toLong())

                parsedMetadata = parseVorbisCommentBytes(commentBytes, total)
            } else if (blockType == 6) { // PICTURE
                hasFlacPicture = true
                flacPictureSize = blockLength
                if (blockLength in 1..1048576) {
                    val picBuf = ByteArray(blockLength)
                    var readP = 0
                    while (readP < blockLength) {
                        val c = stream.read(picBuf, readP, blockLength - readP)
                        if (c <= 0) break
                        readP += c
                    }
                    flacPictureBytes = extractFlacPictureBytes(picBuf)
                } else {
                    stream.skip(blockLength.toLong())
                }
            } else {
                stream.skip(blockLength.toLong())
            }
        }
        val base = parsedMetadata ?: EmbeddedAudioMetadata()
        return base.copy(
            hasEmbeddedArtwork = hasFlacPicture || base.hasEmbeddedArtwork,
            embeddedArtworkSize = if (flacPictureSize > 0) flacPictureSize else base.embeddedArtworkSize,
            embeddedArtworkBytes = flacPictureBytes ?: base.embeddedArtworkBytes
        )
    }

    private fun extractFlacPictureBytes(buf: ByteArray): ByteArray? {
        if (buf.size < 32) return null
        var pos = 4 // skip picture type
        val mimeLen = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4 + mimeLen
        if (pos + 4 > buf.size) return null
        val descLen = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4 + descLen
        pos += 16 // width (4), height (4), depth (4), colors (4)
        if (pos + 4 > buf.size) return null
        val dataLen = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        if (dataLen > 0 && pos + dataLen <= buf.size) {
            return buf.copyOfRange(pos, pos + dataLen)
        }
        return null
    }

    private fun parseVorbisCommentBytes(bytes: ByteArray, length: Int): EmbeddedAudioMetadata {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        if (buffer.remaining() < 4) return EmbeddedAudioMetadata()
        val vendorLen = buffer.int
        if (vendorLen < 0 || vendorLen > buffer.remaining()) return EmbeddedAudioMetadata()
        buffer.position(buffer.position() + vendorLen)

        if (buffer.remaining() < 4) return EmbeddedAudioMetadata()
        val userCommentListLen = buffer.int

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var releaseDate: String? = null
        var releaseYear: Int? = null
        var recordLabel: String? = null
        var barcode: String? = null
        var isrc: String? = null
        var bpm: Double? = null
        var musicalKey: String? = null
        var hasArt = false
        var artSize = 0
        var artBytes: ByteArray? = null

        var count = 0
        while (count < userCommentListLen && buffer.remaining() >= 4) {
            val commentLen = buffer.int
            if (commentLen <= 0 || commentLen > buffer.remaining()) break
            val commentBytes = ByteArray(commentLen)
            buffer.get(commentBytes)
            count++

            val commentStr = String(commentBytes, StandardCharsets.UTF_8)
            val eqIdx = commentStr.indexOf('=')
            if (eqIdx > 0) {
                val key = commentStr.substring(0, eqIdx).trim().uppercase(Locale.ROOT)
                val value = commentStr.substring(eqIdx + 1).trim()
                when (key) {
                    "TITLE" -> title = value
                    "ARTIST" -> artist = value
                    "ALBUM" -> album = value
                    "ALBUMARTIST" -> albumArtist = value
                    "GENRE" -> genre = value
                    "TRACKNUMBER" -> trackNumber = parseIndexNumber(value)
                    "DISCNUMBER" -> discNumber = parseIndexNumber(value)
                    "DATE", "YEAR" -> {
                        releaseDate = value
                        releaseYear = value.take(4).toIntOrNull()
                    }
                    "ISRC" -> isrc = value
                    "ORGANIZATION", "LABEL" -> recordLabel = value
                    "BARCODE" -> barcode = value
                    "BPM" -> bpm = value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
                    "KEY", "INITIALKEY" -> musicalKey = value
                    "METADATA_BLOCK_PICTURE" -> {
                        try {
                            val decoded = Base64.decode(value, Base64.DEFAULT)
                            if (decoded != null && decoded.size > 32) {
                                hasArt = true
                                artSize = decoded.size
                                artBytes = extractFlacPictureBytes(decoded)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        val camelot = CamelotKey.fromMusicalKey(musicalKey)

        return EmbeddedAudioMetadata(
            title = title?.takeIf(String::isNotBlank),
            artist = artist?.takeIf(String::isNotBlank),
            album = album?.takeIf(String::isNotBlank),
            albumArtist = albumArtist?.takeIf(String::isNotBlank),
            genre = genre?.takeIf(String::isNotBlank),
            trackNumber = trackNumber,
            discNumber = discNumber,
            releaseDate = releaseDate,
            releaseYear = releaseYear,
            recordLabel = recordLabel?.takeIf(String::isNotBlank),
            barcode = barcode?.takeIf(String::isNotBlank),
            isrc = isrc?.takeIf(String::isNotBlank),
            bpm = bpm,
            musicalKey = musicalKey?.takeIf(String::isNotBlank),
            camelotKey = camelot,
            hasEmbeddedArtwork = hasArt,
            embeddedArtworkSize = artSize,
            embeddedArtworkBytes = artBytes
        )
    }

    private fun decodeTextFrame(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val encodingByte = payload[0].toInt()
        val charset: Charset = when (encodingByte) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        val textBytes = payload.copyOfRange(1, payload.size)
        return String(textBytes, charset).trim { it <= ' ' || it == '\u0000' }
    }

    private fun decodeTxxxFrame(payload: ByteArray): Pair<String, String>? {
        if (payload.size < 2) return null
        val encodingByte = payload[0].toInt()
        val charset: Charset = when (encodingByte) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }

        val isUtf16 = encodingByte == 1 || encodingByte == 2
        var delimiterIdx = -1
        var i = 1
        while (i < payload.size) {
            if (isUtf16) {
                if (i + 1 < payload.size && payload[i] == 0.toByte() && payload[i + 1] == 0.toByte()) {
                    delimiterIdx = i
                    break
                }
                i += 2
            } else {
                if (payload[i] == 0.toByte()) {
                    delimiterIdx = i
                    break
                }
                i++
            }
        }

        if (delimiterIdx < 0) return null

        val descBytes = payload.copyOfRange(1, delimiterIdx)
        val valueStart = if (isUtf16) delimiterIdx + 2 else delimiterIdx + 1
        if (valueStart > payload.size) return null
        val valBytes = payload.copyOfRange(valueStart, payload.size)

        val desc = String(descBytes, charset).trim { it <= ' ' || it == '\u0000' }
        val value = String(valBytes, charset).trim { it <= ' ' || it == '\u0000' }
        return Pair(desc, value)
    }

    private fun parseIndexNumber(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().substringBefore('/').filter { it.isDigit() }
        return clean.toIntOrNull()
    }

    private fun mergeMetadata(retriever: EmbeddedAudioMetadata, stream: EmbeddedAudioMetadata): EmbeddedAudioMetadata {
        val musicalKey = stream.musicalKey ?: retriever.musicalKey
        val camelotKey = stream.camelotKey ?: CamelotKey.fromMusicalKey(musicalKey)

        val finalDuration = when {
            stream.durationSeconds > 1 -> stream.durationSeconds
            retriever.durationSeconds > 1 -> retriever.durationSeconds
            stream.durationSeconds > 0 -> stream.durationSeconds
            else -> 0
        }
        val finalBitrate = when {
            stream.bitrateKbps > 0 -> stream.bitrateKbps
            retriever.bitrateKbps > 0 -> retriever.bitrateKbps
            else -> 0
        }
        val finalSampleRate = stream.sampleRate ?: retriever.sampleRate
        val finalBitDepth = stream.bitDepth ?: retriever.bitDepth

        return EmbeddedAudioMetadata(
            title = stream.title ?: retriever.title,
            artist = stream.artist ?: retriever.artist,
            album = stream.album ?: retriever.album,
            albumArtist = stream.albumArtist ?: retriever.albumArtist,
            genre = stream.genre ?: retriever.genre,
            durationSeconds = finalDuration,
            bitrateKbps = finalBitrate,
            sampleRate = finalSampleRate,
            bitDepth = finalBitDepth,
            trackNumber = stream.trackNumber ?: retriever.trackNumber,
            discNumber = stream.discNumber ?: retriever.discNumber,
            releaseDate = stream.releaseDate ?: retriever.releaseDate,
            releaseYear = stream.releaseYear ?: retriever.releaseYear,
            recordLabel = stream.recordLabel ?: retriever.recordLabel,
            barcode = stream.barcode ?: retriever.barcode,
            isrc = stream.isrc ?: retriever.isrc,
            bpm = stream.bpm ?: retriever.bpm,
            musicalKey = musicalKey,
            camelotKey = camelotKey,
            releaseCountry = stream.releaseCountry ?: retriever.releaseCountry,
            releaseStatus = stream.releaseStatus ?: retriever.releaseStatus,
            hasEmbeddedArtwork = stream.hasEmbeddedArtwork || retriever.hasEmbeddedArtwork,
            embeddedArtworkSize = if (stream.embeddedArtworkSize > 0) stream.embeddedArtworkSize else retriever.embeddedArtworkSize,
            embeddedArtworkBytes = stream.embeddedArtworkBytes ?: retriever.embeddedArtworkBytes
        )
    }
}
