package com.example.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

/**
 * Authoritative embedded audio metadata reader.
 *
 * Extracts standard metadata alongside embedded MusicBrainz catalog tags
 * (MBIDs for recording, release, artist, release group, plus ISRC, barcode,
 * label, BPM, musical key, and track numbers) from:
 * 1. MediaMetadataRetriever (standard platform extractor)
 * 2. Raw ID3v2 frames (ID3v2.2, ID3v2.3, ID3v2.4)
 * 3. Raw FLAC / OGG Vorbis comment metadata blocks
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
    val musicBrainzRecordingId: String? = null,
    val musicBrainzReleaseId: String? = null,
    val musicBrainzArtistId: String? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val musicBrainzReleaseTrackId: String? = null,
    val releaseCountry: String? = null,
    val releaseStatus: String? = null
) {
    val hasBpm: Boolean get() = bpm != null && bpm in 30.0..300.0
    val hasKey: Boolean get() = !musicalKey.isNullOrBlank() && musicalKey != "—" && musicalKey != "-" && !musicalKey.equals("Unknown", ignoreCase = true)
    val hasEmbeddedMusicBrainz: Boolean get() = !musicBrainzRecordingId.isNullOrBlank() || !musicBrainzReleaseId.isNullOrBlank()
}

object AudioEmbeddedMetadataReader {
    private const val TAG = "AudioEmbeddedMetadata"
    private const val MAX_TAG_HEADER_READ = 512 * 1024 // Read up to 512KB for embedded tags

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

            EmbeddedAudioMetadata(
                title = mTitle?.takeIf(String::isNotBlank),
                artist = mArtist?.takeIf(String::isNotBlank),
                album = mAlbum?.takeIf(String::isNotBlank),
                albumArtist = mAlbumArtist?.takeIf(String::isNotBlank),
                genre = mGenre?.takeIf(String::isNotBlank),
                durationSeconds = mDuration?.let { (it / 1000).toInt().coerceAtLeast(1) } ?: 0,
                bitrateKbps = mBitrate?.let { it / 1000 } ?: 0,
                sampleRate = mSampleRate,
                bitDepth = mBitDepth,
                trackNumber = mTrackNumber,
                discNumber = mDiscNumber,
                releaseDate = mDate?.takeIf(String::isNotBlank),
                releaseYear = mYear,
                bpm = mBpm
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
                val header = ByteArray(10)
                val readHeader = input.read(header)
                if (readHeader < 4) return EmbeddedAudioMetadata()

                if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    return parseId3Tags(header, input)
                } else if (header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()) {
                    return parseFlacVorbisComment(input)
                }
            }
            EmbeddedAudioMetadata()
        } catch (e: Exception) {
            Log.v(TAG, "Direct stream tag extraction skipped for $filePathOrUri: ${e.message}")
            EmbeddedAudioMetadata()
        }
    }

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
        var musicBrainzRecordingId: String? = null
        var musicBrainzReleaseId: String? = null
        var musicBrainzArtistId: String? = null
        var musicBrainzReleaseGroupId: String? = null
        var musicBrainzReleaseTrackId: String? = null
        var releaseCountry: String? = null
        var releaseStatus: String? = null

        val isV24 = versionMajor >= 4

        while (buffer.remaining() >= 10) {
            val frameIdBytes = ByteArray(4)
            buffer.get(frameIdBytes)
            if (frameIdBytes[0].toInt() == 0) break // End of frames, hit padding
            val frameId = String(frameIdBytes, StandardCharsets.ISO_8859_1)

            val frameSize = if (isV24) {
                // Synchsafe integer
                val b0 = buffer.get().toInt() and 0x7F
                val b1 = buffer.get().toInt() and 0x7F
                val b2 = buffer.get().toInt() and 0x7F
                val b3 = buffer.get().toInt() and 0x7F
                (b0 shl 21) or (b1 shl 14) or (b2 shl 7) or b3
            } else {
                buffer.int
            }
            buffer.short // Skip 2 flags bytes

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
                    "TBPM" -> {
                        val bpmStr = decodeTextFrame(framePayload).filter { it.isDigit() || it == '.' }
                        bpm = bpmStr.toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
                    }
                    "TKEY" -> {
                        val rawKey = decodeTextFrame(framePayload).trim()
                        if (rawKey.isNotBlank()) musicalKey = rawKey
                    }
                    "UFID" -> {
                        // UFID structure: null-terminated Owner identifier, followed by Identifier
                        val zeroIdx = framePayload.indexOf(0.toByte())
                        if (zeroIdx > 0) {
                            val owner = String(framePayload, 0, zeroIdx, StandardCharsets.ISO_8859_1)
                            if (owner.contains("musicbrainz.org", ignoreCase = true)) {
                                val idLen = framePayload.size - (zeroIdx + 1)
                                if (idLen > 0) {
                                    val id = String(framePayload, zeroIdx + 1, idLen, StandardCharsets.ISO_8859_1).trim()
                                    if (id.isNotBlank()) musicBrainzRecordingId = id
                                }
                            }
                        }
                    }
                    "TXXX" -> {
                        // User-defined text frame: encoding (1 byte) + description + 0x00 + value
                        val txxx = decodeTxxxFrame(framePayload)
                        if (txxx != null) {
                            val desc = txxx.first.trim().lowercase(Locale.ROOT)
                            val value = txxx.second.trim()
                            when {
                                desc == "musicbrainz track id" || desc == "musicbrainz recording id" -> musicBrainzRecordingId = value
                                desc == "musicbrainz album id" -> musicBrainzReleaseId = value
                                desc == "musicbrainz artist id" -> musicBrainzArtistId = value
                                desc == "musicbrainz release group id" -> musicBrainzReleaseGroupId = value
                                desc == "musicbrainz release track id" -> musicBrainzReleaseTrackId = value
                                desc == "barcode" -> barcode = value
                                desc == "initialkey" -> if (musicalKey.isNullOrBlank()) musicalKey = value
                                desc == "musicbrainz album release country" -> releaseCountry = value
                                desc == "musicbrainz album status" -> releaseStatus = value
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
            musicBrainzRecordingId = musicBrainzRecordingId?.takeIf(String::isNotBlank),
            musicBrainzReleaseId = musicBrainzReleaseId?.takeIf(String::isNotBlank),
            musicBrainzArtistId = musicBrainzArtistId?.takeIf(String::isNotBlank),
            musicBrainzReleaseGroupId = musicBrainzReleaseGroupId?.takeIf(String::isNotBlank),
            musicBrainzReleaseTrackId = musicBrainzReleaseTrackId?.takeIf(String::isNotBlank),
            releaseCountry = releaseCountry?.takeIf(String::isNotBlank),
            releaseStatus = releaseStatus?.takeIf(String::isNotBlank)
        )
    }

    private fun parseFlacVorbisComment(stream: InputStream): EmbeddedAudioMetadata {
        var isLast = false
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

                return parseVorbisCommentBytes(commentBytes, total)
            } else {
                stream.skip(blockLength.toLong())
            }
        }
        return EmbeddedAudioMetadata()
    }

    private fun parseVorbisCommentBytes(bytes: ByteArray, length: Int): EmbeddedAudioMetadata {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        if (buffer.remaining() < 4) return EmbeddedAudioMetadata()
        val vendorLen = buffer.int
        if (vendorLen < 0 || vendorLen > buffer.remaining()) return EmbeddedAudioMetadata()
        buffer.position(buffer.position() + vendorLen) // Skip vendor string

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
        var musicBrainzRecordingId: String? = null
        var musicBrainzReleaseId: String? = null
        var musicBrainzArtistId: String? = null
        var musicBrainzReleaseGroupId: String? = null

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
                    "DATE" -> {
                        releaseDate = value
                        releaseYear = value.take(4).toIntOrNull()
                    }
                    "ISRC" -> isrc = value
                    "ORGANIZATION", "LABEL" -> recordLabel = value
                    "BARCODE" -> barcode = value
                    "BPM" -> bpm = value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.takeIf { it in 30.0..300.0 }
                    "KEY", "INITIALKEY" -> musicalKey = value
                    "MUSICBRAINZ_TRACKID" -> musicBrainzRecordingId = value
                    "MUSICBRAINZ_ALBUMID" -> musicBrainzReleaseId = value
                    "MUSICBRAINZ_ARTISTID" -> musicBrainzArtistId = value
                    "MUSICBRAINZ_RELEASEGROUPID" -> musicBrainzReleaseGroupId = value
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
            musicBrainzRecordingId = musicBrainzRecordingId?.takeIf(String::isNotBlank),
            musicBrainzReleaseId = musicBrainzReleaseId?.takeIf(String::isNotBlank),
            musicBrainzArtistId = musicBrainzArtistId?.takeIf(String::isNotBlank),
            musicBrainzReleaseGroupId = musicBrainzReleaseGroupId?.takeIf(String::isNotBlank)
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

        return EmbeddedAudioMetadata(
            title = stream.title ?: retriever.title,
            artist = stream.artist ?: retriever.artist,
            album = stream.album ?: retriever.album,
            albumArtist = stream.albumArtist ?: retriever.albumArtist,
            genre = stream.genre ?: retriever.genre,
            durationSeconds = if (retriever.durationSeconds > 0) retriever.durationSeconds else stream.durationSeconds,
            bitrateKbps = if (retriever.bitrateKbps > 0) retriever.bitrateKbps else stream.bitrateKbps,
            sampleRate = retriever.sampleRate ?: stream.sampleRate,
            bitDepth = retriever.bitDepth ?: stream.bitDepth,
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
            musicBrainzRecordingId = stream.musicBrainzRecordingId ?: retriever.musicBrainzRecordingId,
            musicBrainzReleaseId = stream.musicBrainzReleaseId ?: retriever.musicBrainzReleaseId,
            musicBrainzArtistId = stream.musicBrainzArtistId ?: retriever.musicBrainzArtistId,
            musicBrainzReleaseGroupId = stream.musicBrainzReleaseGroupId ?: retriever.musicBrainzReleaseGroupId,
            musicBrainzReleaseTrackId = stream.musicBrainzReleaseTrackId ?: retriever.musicBrainzReleaseTrackId,
            releaseCountry = stream.releaseCountry ?: retriever.releaseCountry,
            releaseStatus = stream.releaseStatus ?: retriever.releaseStatus
        )
    }
}
