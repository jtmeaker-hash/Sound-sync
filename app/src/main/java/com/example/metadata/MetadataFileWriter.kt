package com.example.metadata

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.model.MetadataWriteState
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.CompleteTagPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale

/**
 * File writing and read-back verification boundary.
 *
 * Safely writes format-preserving tags and artwork to local audio files and content:// URIs,
 * then closes and reopens the file to verify tags and artwork physically exist on disk.
 * Persists track write status to Room database.
 */
sealed interface MetadataWriteResult {
    data class Written(val verifiedTags: EmbeddedAudioMetadata) : MetadataWriteResult
    data class Partial(val verifiedTags: EmbeddedAudioMetadata, val unverifiedFields: List<String>) : MetadataWriteResult
    data class Unsupported(val reason: String) : MetadataWriteResult
    data class Failed(val reason: String) : MetadataWriteResult
    data class VerificationFailed(val field: String, val expected: String, val actual: String) : MetadataWriteResult
    data class PermissionRequired(val path: String) : MetadataWriteResult
    data class ReadOnlyFile(val path: String) : MetadataWriteResult

    val writeState: MetadataWriteState
        get() = when (this) {
            is Written -> MetadataWriteState.FILE_WRITE_SUCCESS
            is Partial -> MetadataWriteState.FILE_WRITE_PARTIAL
            is Unsupported -> MetadataWriteState.FORMAT_WRITE_UNSUPPORTED
            is PermissionRequired -> MetadataWriteState.PERMISSION_REQUIRED
            is ReadOnlyFile -> MetadataWriteState.READ_ONLY_FILE
            is VerificationFailed, is Failed -> MetadataWriteState.FILE_WRITE_FAILED
        }
}

class MetadataFileWriter(
    private val context: Context,
    private val trackDao: TrackDao? = null
) {

    companion object {
        private const val TAG = "MetadataFileWriter"
        private val SUPPORTED_EXTENSIONS = setOf("wav", "mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "aif", "aiff")
    }

    private fun isFileWritable(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.canWrite()) return true
        return try {
            FileOutputStream(file, true).use {}
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun updateDbState(trackId: String, state: MetadataWriteState) {
        if (trackId.isBlank()) return
        try {
            val dao = trackDao ?: AppDatabase.getDatabase(context).trackDao()
            dao.updateMetadataWriteState(trackId, state.name)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not update track write state in DB: ${e.message}")
        }
    }

    suspend fun writeAsync(
        track: Track,
        artworkBytes: ByteArray? = null,
        artworkMimeType: String = "image/jpeg"
    ): MetadataWriteResult = withContext(Dispatchers.IO) {
        val path = track.filePath
        Log.d(TAG, "File write started for track id=${track.id} path=\"$path\"")

        if (path.isBlank() || path.startsWith("demo://") || path.startsWith("http")) {
            val res = MetadataWriteResult.Unsupported("This track has no writable local file.")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        val isContentUri = path.startsWith("content://")
        val ext: String
        var targetWritePath = path

        if (isContentUri) {
            var detectedExt = ""
            try {
                val uri = Uri.parse(path)
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) {
                                detectedExt = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            }
                        }
                    }
                }
                if (detectedExt.isBlank()) {
                    val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                    detectedExt = when {
                        mime.contains("wav") -> "wav"
                        mime.contains("flac") -> "flac"
                        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
                        mime.contains("ogg") -> "ogg"
                        mime.contains("opus") -> "opus"
                        else -> "mp3"
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission required for SAF URI: $path", e)
                val res = MetadataWriteResult.PermissionRequired(path)
                updateDbState(track.id, res.writeState)
                return@withContext res
            } catch (_: Exception) {}
            ext = detectedExt.ifBlank { "mp3" }
        } else {
            val file = File(path)
            if (!file.exists()) {
                val res = MetadataWriteResult.Failed("File does not exist: $path")
                updateDbState(track.id, res.writeState)
                return@withContext res
            }
            if (!isFileWritable(file)) {
                Log.w(TAG, "Direct file write not permitted for $path, probing MediaStore URI fallback...")
                val mediaStoreUri = AudioTagWriter.getMediaStoreUriForPath(context, file.absolutePath)
                if (mediaStoreUri != null) {
                    targetWritePath = mediaStoreUri.toString()
                    Log.i(TAG, "Resolved MediaStore content URI fallback: $targetWritePath")
                } else {
                    Log.w(TAG, "File is not writable (read-only): $path")
                    val res = MetadataWriteResult.ReadOnlyFile(path)
                    updateDbState(track.id, res.writeState)
                    return@withContext res
                }
            }
            ext = file.extension.lowercase(Locale.ROOT)
        }

        if (ext !in SUPPORTED_EXTENSIONS) {
            Log.w(TAG, "Format .$ext not currently supported by tag writer.")
            val res = MetadataWriteResult.Unsupported(
                "A format-preserving tag writer is not available for .$ext files; audio preserved untouched."
            )
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        // Mark track as writing to file in DB
        updateDbState(track.id, MetadataWriteState.WRITING_TO_FILE)

        // Sensible merge: read existing embedded metadata before overwriting
        val existing = AudioEmbeddedMetadataReader.read(context, path)

        val mergedTitle = track.title.takeIf { it.isNotBlank() && it != "Unknown Title" }
            ?: existing.title?.takeIf { it.isNotBlank() }
            ?: track.title

        val mergedArtist = track.artist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            ?: existing.artist?.takeIf { it.isNotBlank() }
            ?: track.artist

        val mergedAlbum = track.album.takeIf { it.isNotBlank() && it != "Single" && it != "Unknown Album" }
            ?: existing.album?.takeIf { it.isNotBlank() && it != "Single" && it != "Unknown Album" }
            ?: track.album.takeIf { it.isNotBlank() }
            ?: existing.album

        val mergedGenre = track.genre.takeIf { it.isNotBlank() && it != "DJ Library" && it != "Club" && it != "Unknown Genre" }
            ?: existing.genre?.takeIf { it.isNotBlank() && it != "DJ Library" && it != "Club" }
            ?: track.genre.takeIf { it.isNotBlank() }
            ?: existing.genre

        val mergedTrackNumber = track.trackNumber.takeIf { it > 0 } ?: existing.trackNumber
        val mergedDiscNumber = track.discNumber.takeIf { it > 0 } ?: existing.discNumber
        val mergedReleaseYear = track.releaseYear?.takeIf { it > 0 } ?: existing.releaseYear
        val mergedReleaseDate = track.releaseDate?.takeIf { it.isNotBlank() } ?: existing.releaseDate ?: mergedReleaseYear?.toString()
        val mergedBpm = track.bpm.takeIf { it > 0.0 } ?: existing.bpm
        val mergedMusicalKey = track.musicalKey.takeIf { it.isNotBlank() && it != "—" && it != "-" } ?: existing.musicalKey
        val mergedComposer = track.composer.takeIf { it.isNotBlank() } ?: existing.recordLabel
        val mergedComment = track.notes.takeIf { it.isNotBlank() }

        val activeArtworkBytes = artworkBytes?.takeIf { it.isNotEmpty() }
            ?: track.artworkCachePath?.let { cachePath ->
                try {
                    val f = File(cachePath)
                    if (f.exists() && f.canRead()) f.readBytes() else null
                } catch (_: Exception) { null }
            }

        val payload = CompleteTagPayload(
            title = mergedTitle,
            artist = mergedArtist,
            album = mergedAlbum,
            albumArtist = mergedArtist,
            genre = mergedGenre,
            trackNumber = mergedTrackNumber,
            discNumber = mergedDiscNumber,
            releaseYear = mergedReleaseYear,
            releaseDate = mergedReleaseDate,
            bpm = mergedBpm,
            musicalKey = mergedMusicalKey,
            composer = mergedComposer,
            comment = mergedComment,
            artworkBytes = activeArtworkBytes,
            artworkMimeType = artworkMimeType
        )

        val writeSuccess = try {
            AudioTagWriter.writeCompleteTags(context, targetWritePath, payload)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException writing tags to $path", e)
            val res = MetadataWriteResult.PermissionRequired(path)
            updateDbState(track.id, res.writeState)
            return@withContext res
        } catch (e: Exception) {
            Log.e(TAG, "Exception writing tags to $path: ${e.message}", e)
            val res = MetadataWriteResult.Failed("Exception writing tags: ${e.message}")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        if (!writeSuccess) {
            Log.e(TAG, "AudioTagWriter returned false for $path")
            val res = MetadataWriteResult.Failed("AudioTagWriter failed writing tags to file")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        Log.d(TAG, "File write completed successfully for $path; starting read-back verification...")

        // Mandatory read-back verification: close writer, reopen with reader
        val verified = AudioEmbeddedMetadataReader.read(context, path)
        Log.d(TAG, "File reread result: title=\"${verified.title}\", artist=\"${verified.artist}\", album=\"${verified.album}\", bpm=${verified.bpm}, key=\"${verified.musicalKey}\", artwork=${verified.hasEmbeddedArtwork} (${verified.embeddedArtworkSize} bytes)")

        // 1. Verify title
        if (!mergedTitle.isNullOrBlank() && (verified.title == null || !verified.title.equals(mergedTitle, ignoreCase = true))) {
            Log.e(TAG, "Write verification failed on title: expected \"$mergedTitle\", found \"${verified.title}\"")
            val res = MetadataWriteResult.VerificationFailed("title", mergedTitle, verified.title ?: "<null>")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        // 2. Verify artist
        if (!mergedArtist.isNullOrBlank() && (verified.artist == null || !verified.artist.equals(mergedArtist, ignoreCase = true))) {
            Log.e(TAG, "Write verification failed on artist: expected \"$mergedArtist\", found \"${verified.artist}\"")
            val res = MetadataWriteResult.VerificationFailed("artist", mergedArtist, verified.artist ?: "<null>")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        // Check optional fields for partial verification
        val unverifiedFields = mutableListOf<String>()

        // 3. Verify album
        if (!mergedAlbum.isNullOrBlank() && mergedAlbum != "Single" && mergedAlbum != "Unknown Album") {
            if (verified.album == null || !verified.album.equals(mergedAlbum, ignoreCase = true)) {
                Log.w(TAG, "Write verification notice on album: expected \"$mergedAlbum\", found \"${verified.album}\"")
                unverifiedFields.add("album")
            }
        }

        // 4. Verify track number
        if (mergedTrackNumber != null && mergedTrackNumber > 0) {
            if (verified.trackNumber == null || verified.trackNumber != mergedTrackNumber) {
                Log.w(TAG, "Write verification notice on trackNumber: expected $mergedTrackNumber, found ${verified.trackNumber}")
                unverifiedFields.add("trackNumber")
            }
        }

        // 5. Verify BPM
        if (mergedBpm != null && mergedBpm > 0.0) {
            if (verified.bpm == null || kotlin.math.abs(verified.bpm - mergedBpm) > 1.0) {
                Log.w(TAG, "Write verification notice on BPM: expected $mergedBpm, found ${verified.bpm}")
                unverifiedFields.add("bpm")
            }
        }

        // 6. Verify musical key
        if (!mergedMusicalKey.isNullOrBlank() && mergedMusicalKey != "—" && mergedMusicalKey != "-") {
            if (verified.musicalKey == null || !verified.musicalKey.equals(mergedMusicalKey, ignoreCase = true)) {
                Log.w(TAG, "Write verification notice on key: expected \"$mergedMusicalKey\", found \"${verified.musicalKey}\"")
                unverifiedFields.add("musicalKey")
            }
        }

        // 7. Verify embedded artwork
        if (activeArtworkBytes != null && activeArtworkBytes.isNotEmpty()) {
            if (!verified.hasEmbeddedArtwork || verified.embeddedArtworkSize <= 0) {
                Log.w(TAG, "Write verification notice: embedded artwork not detected on disk after write")
                unverifiedFields.add("embeddedArtwork")
            } else {
                Log.d(TAG, "Embedded artwork verified: ${verified.embeddedArtworkSize} bytes on disk")
            }
        }

        val result = if (unverifiedFields.isEmpty()) {
            Log.d(TAG, "Full file write and read-back verification PASSED for $path")
            MetadataWriteResult.Written(verified)
        } else {
            Log.w(TAG, "File write succeeded with unverified optional fields: $unverifiedFields")
            MetadataWriteResult.Partial(verified, unverifiedFields)
        }

        updateDbState(track.id, result.writeState)
        result
    }

    fun write(
        track: Track,
        artworkBytes: ByteArray? = null,
        artworkMimeType: String = "image/jpeg"
    ): MetadataWriteResult {
        return runBlocking { writeAsync(track, artworkBytes, artworkMimeType) }
    }
}
