package com.example.metadata

import android.content.Context
import android.util.Log
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.CompleteTagPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * File writing and read-back verification boundary (Sections 13, 15, 16, 17).
 *
 * Safely writes canonical tags and Cover Art Archive artwork to local audio files,
 * then closes and reopens the file to verify tags and artwork physically exist on disk.
 */
sealed interface MetadataWriteResult {
    data class Written(val verifiedTags: EmbeddedAudioMetadata) : MetadataWriteResult
    data class Unsupported(val reason: String) : MetadataWriteResult
    data class Failed(val reason: String) : MetadataWriteResult
    data class VerificationFailed(val field: String, val expected: String, val actual: String) : MetadataWriteResult
    data class PermissionRequired(val path: String) : MetadataWriteResult
}

class MetadataFileWriter(private val context: Context) {

    companion object {
        private const val TAG = "MetadataFileWriter"
    }

    private fun isFileWritable(file: File): Boolean {
        if (!file.canWrite()) return false
        return try {
            val perms = Files.getPosixFilePermissions(file.toPath())
            perms.contains(PosixFilePermission.OWNER_WRITE)
        } catch (e: Throwable) {
            file.canWrite()
        }
    }

    suspend fun writeAsync(
        track: Track,
        artworkBytes: ByteArray? = null,
        artworkMimeType: String = "image/jpeg"
    ): MetadataWriteResult = withContext(Dispatchers.IO) {
        val path = track.filePath
        Log.d(TAG, "file write started for track id=${track.id} path=\"$path\"")

        if (path.startsWith("content://")) {
            Log.w(TAG, "Path is SAF URI; permission required for direct writes: $path")
            return@withContext MetadataWriteResult.PermissionRequired(path)
        }
        if (path.startsWith("demo://") || path.isBlank()) {
            return@withContext MetadataWriteResult.Unsupported("This track has no writable local file.")
        }

        val file = File(path)
        if (!file.exists()) {
            return@withContext MetadataWriteResult.Failed("File does not exist: $path")
        }
        if (!isFileWritable(file)) {
            Log.w(TAG, "File is not writable (permission required): $path")
            return@withContext MetadataWriteResult.PermissionRequired(path)
        }

        val ext = file.extension.lowercase()
        if (ext != "mp3" && ext != "flac") {
            Log.w(TAG, "Format .$ext not currently supported by lossless tag writer.")
            return@withContext MetadataWriteResult.Unsupported(
                "A format-preserving tag writer is not available for $ext files; preserved audio untouched."
            )
        }

        val payload = CompleteTagPayload(
            title = track.title,
            artist = track.artist,
            album = track.album,
            genre = track.genre,
            trackNumber = track.trackNumber.takeIf { it > 0 },
            discNumber = track.discNumber.takeIf { it > 0 },
            releaseYear = track.releaseYear,
            releaseDate = track.releaseDate,
            bpm = track.bpm.takeIf { it > 0 },
            musicalKey = track.musicalKey.takeIf { it.isNotBlank() },
            artworkBytes = artworkBytes,
            artworkMimeType = artworkMimeType
        )

        val writeSuccess = AudioTagWriter.writeCompleteTags(context, path, payload)
        if (!writeSuccess) {
            Log.e(TAG, "AudioTagWriter failed to write tags to $path")
            return@withContext MetadataWriteResult.Failed("AudioTagWriter failed writing tags to file")
        }

        Log.d(TAG, "file write successful for ${file.name}")

        // Section 17: Read-back verification
        // File has been closed by AudioTagWriter. Now reopen and verify from disk.
        val verified = AudioEmbeddedMetadataReader.read(context, path)
        Log.d(TAG, "file reread successful: title=\"${verified.title}\", artist=\"${verified.artist}\", album=\"${verified.album}\", artwork=${verified.hasEmbeddedArtwork} (${verified.embeddedArtworkSize} bytes)")

        // 1. Verify title
        if (!track.title.isNullOrBlank() && (verified.title == null || !verified.title.equals(track.title, ignoreCase = true))) {
            Log.e(TAG, "Write verification failed on title: expected \"${track.title}\", found \"${verified.title}\"")
            return@withContext MetadataWriteResult.VerificationFailed("title", track.title, verified.title ?: "<null>")
        }

        // 2. Verify artist
        if (!track.artist.isNullOrBlank() && (verified.artist == null || !verified.artist.equals(track.artist, ignoreCase = true))) {
            Log.e(TAG, "Write verification failed on artist: expected \"${track.artist}\", found \"${verified.artist}\"")
            return@withContext MetadataWriteResult.VerificationFailed("artist", track.artist, verified.artist ?: "<null>")
        }

        // 3. Verify album
        if (!track.album.isNullOrBlank() && track.album != "Single" && track.album != "Unknown Album") {
            if (verified.album == null || !verified.album.equals(track.album, ignoreCase = true)) {
                Log.e(TAG, "Write verification failed on album: expected \"${track.album}\", found \"${verified.album}\"")
                return@withContext MetadataWriteResult.VerificationFailed("album", track.album, verified.album ?: "<null>")
            }
        }

        // 4. Verify genre
        if (!track.genre.isNullOrBlank() && track.genre != "DJ Library" && track.genre != "Club") {
            if (verified.genre == null || !verified.genre.equals(track.genre, ignoreCase = true)) {
                Log.w(TAG, "Genre mismatch in verification (expected \"${track.genre}\", found \"${verified.genre}\")")
            }
        }

        // 5. Verify track number
        if (track.trackNumber > 0 && verified.trackNumber != null && verified.trackNumber != track.trackNumber) {
            Log.e(TAG, "Write verification failed on trackNumber: expected ${track.trackNumber}, found ${verified.trackNumber}")
            return@withContext MetadataWriteResult.VerificationFailed("trackNumber", track.trackNumber.toString(), verified.trackNumber.toString())
        }

        // 6. Verify embedded artwork
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            if (!verified.hasEmbeddedArtwork || verified.embeddedArtworkSize <= 0) {
                Log.e(TAG, "Write verification failed: embedded artwork not detected on disk after write")
                return@withContext MetadataWriteResult.VerificationFailed("embeddedArtwork", "size > 0", "0 bytes / none detected")
            }
            Log.d(TAG, "embedded artwork verified: ${verified.embeddedArtworkSize} bytes on disk")
        }

        Log.d(TAG, "Full file write and read-back verification PASSED for ${file.name}")
        MetadataWriteResult.Written(verified)
    }

    fun write(
        track: Track,
        artworkBytes: ByteArray? = null,
        artworkMimeType: String = "image/jpeg"
    ): MetadataWriteResult {
        return runBlocking { writeAsync(track, artworkBytes, artworkMimeType) }
    }
}
