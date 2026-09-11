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
import com.example.metadata.AlbumValidator
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
    data class LibraryOnly(val reason: String) : MetadataWriteResult
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
            is Skipped, is LibraryOnly -> MetadataWriteState.DATABASE_ONLY
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

        val preCapturedPhysicalPath: String? = if (isContentUri) {
            try {
                context.contentResolver.query(
                    Uri.parse(path),
                    arrayOf(android.provider.MediaStore.Audio.Media.DATA),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val col = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                        if (col >= 0) c.getString(col) else null
                    } else null
                }
            } catch (_: Exception) { null }
        } else {
            path
        }

        val beforeSizeBytes = if (preCapturedPhysicalPath != null && File(preCapturedPhysicalPath).exists()) {
            File(preCapturedPhysicalPath).length()
        } else {
            try {
                if (isContentUri) {
                    context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize } ?: 0L
                } else File(path).length()
            } catch (_: Throwable) { 0L }
        }
        val isPlayableBefore = com.example.storage.StorageAvailabilityHelper.isTrackPathAvailable(context, path)

        if (isContentUri) {
            val uri = Uri.parse(path)
            if (!StorageWritePermissionHelper.hasUriWritePermission(context, uri)) {
                val intentSender = StorageWritePermissionHelper.createSingleWriteRequest(context, uri)
                val res = MetadataWriteResult.PermissionRequired(
                    path = path,
                    intentSender = intentSender,
                    uri = uri,
                    reason = "Permission required to access content URI: $path"
                )
                updateDbState(track.id, res.writeState)
                return@withContext res
            }
            val (uExt, uMime) = resolveUriMimeAndExt(uri)
            ext = uExt
            mimeType = uMime
        } else {
            // Check canonical storage representation (handles Scoped Storage / emulated paths)
            val canonical = StorageWritePermissionHelper.resolveCanonicalStorage(context, track, trackDao)
            if (canonical == null) {
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

            if (canonical.isDirectFile && canonical.directFile != null) {
                val file = canonical.directFile
                targetWritePath = file.absolutePath
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

                if (!canonical.isWritable) {
                    Log.w(TAG, "Direct file write not permitted for $path, probing MediaStore/SAF URI fallback...")
                    val fallbackUri = StorageWritePermissionHelper.resolveTargetUri(context, track)
                    if (fallbackUri != null && fallbackUri.scheme != "file") {
                        targetWritePath = fallbackUri.toString()
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
            } else {
                // Canonical storage is a content:// URI (from MediaStore or SAF)
                targetWritePath = canonical.uri.toString()
                val (uExt, uMime) = resolveUriMimeAndExt(canonical.uri)
                ext = uExt.ifBlank { File(path).extension.lowercase(Locale.ROOT) }
                mimeType = uMime.ifBlank { "audio/mpeg" }

                if (!canonical.isWritable) {
                    val intentSender = StorageWritePermissionHelper.createSingleWriteRequest(context, canonical.uri)
                    val res = MetadataWriteResult.PermissionRequired(
                        path = targetWritePath,
                        intentSender = intentSender,
                        uri = canonical.uri,
                        reason = "Write permission required for $targetWritePath"
                    )
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

        com.example.storage.SoundSyncMetadataRewriteDebug.logBefore(
            path = preCapturedPhysicalPath ?: path,
            uri = path,
            sizeBytes = beforeSizeBytes,
            durationMs = (track.durationSeconds * 1000).toLong(),
            isPlayable = isPlayableBefore,
            tags = mapOf(
                "title" to existing.title,
                "artist" to existing.artist,
                "album" to existing.album,
                "bpm" to existing.bpm?.toString(),
                "key" to existing.musicalKey
            )
        )

        val mergedTitle = track.title.takeIf { it.isNotBlank() && it != "Unknown Title" }
            ?: existing.title?.takeIf { it.isNotBlank() }
            ?: track.title

        val mergedArtist = track.artist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            ?: existing.artist?.takeIf { it.isNotBlank() }
            ?: track.artist

        val mergedAlbumArtist = track.albumArtist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            ?: existing.albumArtist?.takeIf { it.isNotBlank() }
            ?: mergedArtist

        var mergedAlbum = track.album.takeIf { it.isNotBlank() && it != "Single" && it != "Unknown Album" }
            ?: existing.album?.takeIf { it.isNotBlank() && it != "Single" && it != "Unknown Album" }
            ?: track.album.takeIf { it.isNotBlank() }
            ?: existing.album
        // Validate mergedAlbum to ensure it is not a folder-derived or generic invalid name.
        if (!AlbumValidator.isValidAlbum(mergedAlbum, track.filePath)) {
            Log.w(TAG, "AlbumRejected during write merge: $mergedAlbum")
            mergedAlbum = ""
        }

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

        com.example.storage.SoundSyncMetadataRewriteDebug.logWrite(
            path = targetWritePath,
            fieldsWritten = listOfNotNull(
                "title".takeIf { !mergedTitle.isNullOrBlank() },
                "artist".takeIf { !mergedArtist.isNullOrBlank() },
                "album".takeIf { !mergedAlbum.isNullOrBlank() },
                "bpm".takeIf { mergedBpm != null },
                "key".takeIf { !mergedMusicalKey.isNullOrBlank() },
                "artwork".takeIf { activeArtworkBytes != null }
            ),
            artworkBytes = activeArtworkBytes?.size ?: 0,
            stagingSize = 0L,
            sizeDelta = 0L
        )

        val writeTagResult = try {
            com.example.storage.FileLockManager.withFileLock(targetWritePath) {
                AudioTagWriter.writeCompleteTagsWithResult(context, targetWritePath, payload)
            }
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
            is TagWriteResult.LibraryOnly -> {
                val res = MetadataWriteResult.LibraryOnly(writeTagResult.reason)
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
                val hasDiscoveredMetadata = (track.appleTrackId != null) ||
                        track.metadataScanState in listOf("COMPLETE", "VERIFIED", "IDENTIFIED", "REVIEW_REQUIRED") ||
                        (track.title.isNotBlank() && track.title != "Unknown Title" && track.artist.isNotBlank() && track.artist != "Unknown Artist")
                if (hasDiscoveredMetadata) {
                    Log.w(TAG, "File write engine failed (${writeTagResult.message}); preserved in SoundSync database as Library Only")
                    val res = MetadataWriteResult.LibraryOnly("File embedding unavailable: ${writeTagResult.message}; preserved in SoundSync library")
                    updateDbState(track.id, res.writeState)
                    return@withContext res
                } else {
                    val res = MetadataWriteResult.Failed(writeTagResult.message, writeTagResult.cause)
                    updateDbState(track.id, res.writeState)
                    return@withContext res
                }
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
                if (ext == "wav") {
                    Log.i(TAG, "WAV artwork preserved in SoundSync database/cache rather than bloated into RIFF container")
                    unverifiedFields.add("artwork (stored in library)")
                } else {
                    Log.w(TAG, "Write verification notice: embedded artwork not detected on disk after write")
                    unverifiedFields.add("embeddedArtwork")
                }
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

        // Post-write MediaStore URI reconciliation.
        // IMPORTANT: Only reconcile if the track's filePath is ALREADY a content:// URI.
        // Converting a direct file path to a content URI would cause the track to break
        // if MediaStore later re-indexes the file under a different media ID.
        // For direct file paths, the path itself is stable — no reconciliation needed.
        var finalTrackPath = track.filePath
        var uriChanged = false
        var mediaStoreIdChanged = false

        if (track.filePath.startsWith("content://") && track.id.isNotBlank()) {
            try {
                // Check if the existing content URI is still accessible
                val isUriStillValid = try {
                    context.contentResolver.openFileDescriptor(Uri.parse(track.filePath), "r")?.use { true } ?: false
                } catch (_: Exception) { false }

                if (!isUriStillValid) {
                    // Original content URI is stale. Use preCapturedPhysicalPath to scan and resolve current URI.
                    val physicalPath = preCapturedPhysicalPath ?: try {
                        context.contentResolver.query(
                            Uri.parse(track.filePath),
                            arrayOf(android.provider.MediaStore.Audio.Media.DATA),
                            null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val col = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                                if (col >= 0) c.getString(col) else null
                            } else null
                        }
                    } catch (_: Exception) { null }

                    if (physicalPath != null && File(physicalPath).exists()) {
                        try {
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(physicalPath), null, null)
                        } catch (_: Throwable) {}

                        val reconciledUri = com.example.storage.AudioTagWriter.getMediaStoreUriForPath(context, physicalPath)
                        if (reconciledUri != null) {
                            val reconciledStr = reconciledUri.toString()
                            if (reconciledStr != track.filePath) {
                                val dao = trackDao ?: AppDatabase.getDatabase(context).trackDao()
                                dao.updateFilePath(track.id, reconciledStr)
                                finalTrackPath = reconciledStr
                                uriChanged = true
                                mediaStoreIdChanged = true
                                Log.i(TAG, "[PostWriteReconcile] Stale content URI fixed for track=${track.id}: '${track.filePath}' -> '$reconciledStr'")
                            }
                        } else {
                            val dao = trackDao ?: AppDatabase.getDatabase(context).trackDao()
                            dao.updateFilePath(track.id, physicalPath)
                            finalTrackPath = physicalPath
                            uriChanged = true
                            Log.i(TAG, "[PostWriteReconcile] Content URI stale, falling back to physical path for track=${track.id}: '$physicalPath'")
                        }
                    } else {
                        Log.w(TAG, "[PostWriteReconcile] Could not resolve physical path for stale content URI, track=${track.id}: '${track.filePath}'")
                    }
                } else {
                    Log.d(TAG, "[PostWriteReconcile] Content URI still valid for track=${track.id}: '${track.filePath}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[PostWriteReconcile] Failed for track ${track.id}: ${e.message}")
            }
        }

        // Cache invalidation and engine notification
        try {
            com.example.audio.WaveformCache.remove(com.example.audio.WaveformCache.getCacheKey(track, context), context)
        } catch (_: Throwable) {}

        try {
            com.example.audio.DjAudioEngine.getInstance(context).onTrackFileModified(track.id, track.filePath, finalTrackPath)
        } catch (_: Throwable) {}

        val afterSizeBytes = if (preCapturedPhysicalPath != null && File(preCapturedPhysicalPath).exists()) {
            File(preCapturedPhysicalPath).length()
        } else beforeSizeBytes

        val isPlayableAfter = com.example.storage.StorageAvailabilityHelper.isTrackPathAvailable(context, finalTrackPath)

        com.example.storage.SoundSyncMetadataRewriteDebug.logAfter(
            oldPath = track.filePath,
            newPath = finalTrackPath,
            oldSize = beforeSizeBytes,
            newSize = afterSizeBytes,
            uriChanged = uriChanged,
            mediaStoreIdChanged = mediaStoreIdChanged,
            isPlayable = isPlayableAfter
        )

        result
    }

    fun write(
        track: Track,
        artworkBytes: ByteArray? = null,
        artworkMimeType: String = "image/jpeg"
    ): MetadataWriteResult {
        return runBlocking { writeAsync(track, artworkBytes, artworkMimeType) }
    }

    private fun resolveUriMimeAndExt(uri: Uri): Pair<String, String> {
        var mime = try { context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT) } catch (_: Exception) { "" }
        var extension = ""
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) {
                            extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (extension.isBlank()) {
            extension = when {
                mime.contains("wav") -> "wav"
                mime.contains("flac") -> "flac"
                mime.contains("mp4") || mime.contains("m4a") -> "m4a"
                mime.contains("aac") -> "aac"
                mime.contains("ogg") -> "ogg"
                mime.contains("opus") -> "opus"
                mime.contains("aiff") || mime.contains("aif") -> "aiff"
                else -> "mp3"
            }
        }
        return Pair(extension, mime)
    }
}
