package com.example.metadata

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.model.MetadataWriteState
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.CompleteTagPayload
import com.example.storage.StorageWritePermissionHelper
import com.example.storage.TagWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    data class AlreadyInSync(val verifiedTags: EmbeddedAudioMetadata) : MetadataWriteResult
    data class Partial(val verifiedTags: EmbeddedAudioMetadata, val unverifiedFields: List<String>) : MetadataWriteResult
    data class Skipped(val reason: String) : MetadataWriteResult
    data class Unsupported(val reason: String) : MetadataWriteResult
    data class Failed(val reason: String, val cause: Throwable? = null) : MetadataWriteResult
    data class VerificationFailed(val field: String, val expected: String, val actual: String) : MetadataWriteResult
    data class PermissionRequired(
        val path: String,
        val intentSender: IntentSender? = null,
        val uri: Uri? = null,
        val reason: String = "Permission required to access file or content URI",
        val exception: Throwable? = null
    ) : MetadataWriteResult
    data class ReadOnlyFile(val path: String) : MetadataWriteResult

    val writeState: MetadataWriteState
        get() = when (this) {
            is Written, is AlreadyInSync -> MetadataWriteState.FILE_WRITE_SUCCESS
            is Partial -> MetadataWriteState.FILE_WRITE_PARTIAL
            is Skipped -> MetadataWriteState.DATABASE_ONLY
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
        return StorageWritePermissionHelper.isDirectlyWritableFile(file)
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
        var ext: String = ""
        var mimeType: String = "audio/mpeg"
        var targetWritePath = path

        if (isContentUri) {
            val uri = Uri.parse(path)
            try {
                mimeType = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) {
                                ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission required for SAF URI: $path", e)
                val intentSender = StorageWritePermissionHelper.createSingleWriteRequest(context, uri, e)
                PhysicalTagWriteLogger.logFailure(
                    tag = TAG,
                    track = track,
                    uri = path,
                    filePath = null,
                    mimeType = mimeType,
                    extension = ext.ifBlank { "unknown" },
                    isWritable = false,
                    exception = e
                )
                val res = MetadataWriteResult.PermissionRequired(path, intentSender, uri, e.message ?: "Permission required")
                updateDbState(track.id, res.writeState)
                return@withContext res
            } catch (_: Exception) {}

            if (ext.isBlank()) {
                ext = when {
                    mimeType.contains("wav") -> "wav"
                    mimeType.contains("flac") -> "flac"
                    mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
                    mimeType.contains("aac") -> "aac"
                    mimeType.contains("ogg") -> "ogg"
                    mimeType.contains("opus") -> "opus"
                    mimeType.contains("aiff") || mimeType.contains("aif") -> "aiff"
                    else -> "mp3"
                }
            }
        } else {
            val file = File(path)
            ext = file.extension.lowercase(Locale.ROOT)
            mimeType = when (ext) {
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "m4a", "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg" -> "audio/ogg"
                "opus" -> "audio/opus"
                "aif", "aiff" -> "audio/x-aiff"
                else -> "audio/mpeg"
            }

            if (!file.exists()) {
                val ex = java.io.FileNotFoundException("File does not exist: $path")
                PhysicalTagWriteLogger.logFailure(
                    tag = TAG,
                    track = track,
                    uri = path,
                    filePath = path,
                    mimeType = mimeType,
                    extension = ext,
                    isWritable = false,
                    exception = ex
                )
                val res = MetadataWriteResult.Failed("File does not exist: $path", ex)
                updateDbState(track.id, res.writeState)
                return@withContext res
            }

            if (!isFileWritable(file)) {
                Log.w(TAG, "Direct file write not permitted for $path, probing MediaStore URI fallback...")
                val mediaStoreUri = StorageWritePermissionHelper.resolveTargetUri(context, track)
                if (mediaStoreUri != null) {
                    targetWritePath = mediaStoreUri.toString()
                    Log.i(TAG, "Resolved MediaStore content URI fallback: $targetWritePath")
                } else {
                    val ex = SecurityException("File is not writable (read-only on storage): $path")
                    PhysicalTagWriteLogger.logFailure(
                        tag = TAG,
                        track = track,
                        uri = path,
                        filePath = path,
                        mimeType = mimeType,
                        extension = ext,
                        isWritable = false,
                        exception = ex
                    )
                    val res = MetadataWriteResult.ReadOnlyFile(path)
                    updateDbState(track.id, res.writeState)
                    return@withContext res
                }
            }
        }

        if (ext !in SUPPORTED_EXTENSIONS) {
            Log.w(TAG, "Format .$ext not currently supported by tag writer.")
            PhysicalTagWriteLogger.logFailure(
                tag = TAG,
                track = track,
                uri = targetWritePath,
                filePath = path.takeIf { !it.startsWith("content://") },
                mimeType = mimeType,
                extension = ext,
                isWritable = true,
                exception = UnsupportedOperationException("Unsupported container .$ext")
            )
            val res = MetadataWriteResult.Unsupported(
                "A format-preserving tag writer is not available for .$ext files; audio preserved untouched."
            )
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        // Mark track as writing to file in DB
        updateDbState(track.id, MetadataWriteState.WRITING_TO_FILE)

        // Sensible merge: read existing embedded metadata before overwriting
        val existing = AudioEmbeddedMetadataReader.read(context, targetWritePath)

        val mergedTitle = track.title.takeIf { it.isNotBlank() && it != "Unknown Title" }
            ?: existing.title?.takeIf { it.isNotBlank() }
            ?: track.title

        val mergedArtist = track.artist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            ?: existing.artist?.takeIf { it.isNotBlank() }
            ?: track.artist

        val mergedAlbumArtist = track.albumArtist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            ?: existing.albumArtist?.takeIf { it.isNotBlank() }
            ?: mergedArtist

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
            ?: try {
                ArtworkCache(context).getCachedArtworkFile(track.artist, track.album)?.let { f ->
                    if (f.exists() && f.canRead()) f.readBytes() else null
                }
            } catch (_: Throwable) { null }

        val activeArtworkMime = activeArtworkBytes?.let { bytes ->
            if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                "image/png"
            } else {
                "image/jpeg"
            }
        } ?: artworkMimeType

        val payload = CompleteTagPayload(
            title = mergedTitle,
            artist = mergedArtist,
            album = mergedAlbum,
            albumArtist = mergedAlbumArtist,
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
            artworkMimeType = activeArtworkMime
        )

        val writeTagResult = try {
            AudioTagWriter.writeCompleteTagsWithResult(context, targetWritePath, payload)
        } catch (e: SecurityException) {
            val uri = if (targetWritePath.startsWith("content://")) Uri.parse(targetWritePath) else null
            val intentSender = uri?.let { StorageWritePermissionHelper.createSingleWriteRequest(context, it, e) }
            PhysicalTagWriteLogger.logFailure(
                tag = TAG,
                track = track,
                uri = targetWritePath,
                filePath = path.takeIf { !it.startsWith("content://") },
                mimeType = mimeType,
                extension = ext,
                isWritable = false,
                exception = e
            )
            val res = MetadataWriteResult.PermissionRequired(targetWritePath, intentSender, uri, e.message ?: "Permission required", e)
            updateDbState(track.id, res.writeState)
            return@withContext res
        } catch (e: Exception) {
            PhysicalTagWriteLogger.logFailure(
                tag = TAG,
                track = track,
                uri = targetWritePath,
                filePath = path.takeIf { !it.startsWith("content://") },
                mimeType = mimeType,
                extension = ext,
                isWritable = false,
                exception = e
            )
            val res = MetadataWriteResult.Failed("Exception writing tags: ${e.message}", e)
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        when (writeTagResult) {
            is TagWriteResult.PermissionRequired -> {
                PhysicalTagWriteLogger.logFailure(
                    tag = TAG,
                    track = track,
                    uri = targetWritePath,
                    filePath = path.takeIf { !it.startsWith("content://") },
                    mimeType = mimeType,
                    extension = ext,
                    isWritable = false,
                    exception = writeTagResult.cause
                )
                val res = MetadataWriteResult.PermissionRequired(
                    path = targetWritePath,
                    intentSender = writeTagResult.intentSender,
                    uri = writeTagResult.uri,
                    reason = writeTagResult.cause.message ?: "Permission required to access file or content URI"
                )
                updateDbState(track.id, res.writeState)
                return@withContext res
            }
            is TagWriteResult.Unsupported -> {
                PhysicalTagWriteLogger.logFailure(
                    tag = TAG,
                    track = track,
                    uri = targetWritePath,
                    filePath = path.takeIf { !it.startsWith("content://") },
                    mimeType = mimeType,
                    extension = ext,
                    isWritable = true,
                    exception = UnsupportedOperationException(writeTagResult.message)
                )
                val res = MetadataWriteResult.Unsupported(writeTagResult.message)
                updateDbState(track.id, res.writeState)
                return@withContext res
            }
            is TagWriteResult.Failed -> {
                PhysicalTagWriteLogger.logFailure(
                    tag = TAG,
                    track = track,
                    uri = targetWritePath,
                    filePath = path.takeIf { !it.startsWith("content://") },
                    mimeType = mimeType,
                    extension = ext,
                    isWritable = false,
                    exception = writeTagResult.cause ?: Exception(writeTagResult.message)
                )
                val res = MetadataWriteResult.Failed(writeTagResult.message, writeTagResult.cause)
                updateDbState(track.id, res.writeState)
                return@withContext res
            }
            is TagWriteResult.Success -> {
                // Proceed to readback verification
            }
        }

        Log.d(TAG, "File write completed successfully for $targetWritePath; starting read-back verification...")

        // Mandatory read-back verification: close writer, reopen with reader
        val verified = AudioEmbeddedMetadataReader.read(context, targetWritePath)
        Log.d(TAG, "File reread result: title=\"${verified.title}\", artist=\"${verified.artist}\", album=\"${verified.album}\", bpm=${verified.bpm}, key=\"${verified.musicalKey}\", artwork=${verified.hasEmbeddedArtwork} (${verified.embeddedArtworkSize} bytes)")

        // 1. Verify title
        if (!mergedTitle.isNullOrBlank() && (verified.title == null || !verified.title.trim().equals(mergedTitle.trim(), ignoreCase = true))) {
            val ex = IllegalStateException("Write verification failed on title: expected \"$mergedTitle\", found \"${verified.title}\"")
            PhysicalTagWriteLogger.logFailure(
                tag = TAG,
                track = track,
                uri = targetWritePath,
                filePath = path.takeIf { !it.startsWith("content://") },
                mimeType = mimeType,
                extension = ext,
                isWritable = true,
                exception = ex
            )
            val res = MetadataWriteResult.VerificationFailed("title", mergedTitle, verified.title ?: "<null>")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        // 2. Verify artist
        if (!mergedArtist.isNullOrBlank() && (verified.artist == null || !verified.artist.trim().equals(mergedArtist.trim(), ignoreCase = true))) {
            val ex = IllegalStateException("Write verification failed on artist: expected \"$mergedArtist\", found \"${verified.artist}\"")
            PhysicalTagWriteLogger.logFailure(
                tag = TAG,
                track = track,
                uri = targetWritePath,
                filePath = path.takeIf { !it.startsWith("content://") },
                mimeType = mimeType,
                extension = ext,
                isWritable = true,
                exception = ex
            )
            val res = MetadataWriteResult.VerificationFailed("artist", mergedArtist, verified.artist ?: "<null>")
            updateDbState(track.id, res.writeState)
            return@withContext res
        }

        // Check optional fields for partial verification
        val unverifiedFields = mutableListOf<String>()

        // 3. Verify album
        if (!mergedAlbum.isNullOrBlank() && mergedAlbum != "Single" && mergedAlbum != "Unknown Album") {
            if (verified.album == null || !verified.album.trim().equals(mergedAlbum.trim(), ignoreCase = true)) {
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
            Log.d(TAG, "Full file write and read-back verification PASSED for $targetWritePath")
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
