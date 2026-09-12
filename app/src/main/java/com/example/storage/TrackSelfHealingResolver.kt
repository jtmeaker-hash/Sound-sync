package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.audio.WaveformCache
import com.example.data.TrackDao
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class PlaybackIssueType {
    NONE,
    STALE_URI_AFTER_METADATA_REWRITE,
    CORRUPTED_FILE,
    DISCONNECTED_STORAGE,
    FILE_NOT_FOUND
}

data class PlaybackDiagnostic(
    val trackId: String,
    val issueType: PlaybackIssueType,
    val message: String,
    val details: String,
    val canAutoRepair: Boolean,
    val suggestedPath: String? = null
)

data class TrackRepairResult(
    val success: Boolean,
    val issueType: PlaybackIssueType,
    val message: String,
    val healedTrack: Track? = null
)

/**
 * Self-healing reference resolver for audio tracks whose stored file path or content URI
 * has become stale, invalidated, or re-indexed by MediaStore / the filesystem.
 *
 * Provides a resilient multi-tier resolution pipeline:
 *  1. MediaStore ID direct lookup & DATA column check
 *  2. Cross-reference conversion (content:// <-> absolute file path)
 *  3. Persisted SAF DocumentFile / tree URI lookup
 *  4. MediaStore query by title, artist, duration, and display name
 *  5. Local filesystem search across standard audio folders & content fingerprint matching
 *
 * When a valid reference is recovered, it verifies readability, updates the database,
 * and returns the healed track so playback proceeds without interruption or false errors.
 */
object TrackSelfHealingResolver {

    private const val TAG = "TrackSelfHealing"

    /**
     * Resolves and heals an inaccessible or stale track reference, persisting the valid path
     * to the database when [trackDao] is provided.
     *
     * @return The healed [Track] with a verified, accessible [Track.filePath], or null if unresolvable.
     */
    suspend fun healTrack(
        context: Context,
        track: Track,
        trackDao: TrackDao? = null
    ): Track? = withContext(Dispatchers.IO) {
        val originalPath = track.filePath

        // Quick check: if already accessible, return immediately
        if (StorageAvailabilityHelper.isTrackPathAvailable(context, originalPath)) {
            Log.d(TAG, "[TrackSelfHealing] Track '${track.title}' (id=${track.id}) is already accessible at $originalPath")
            return@withContext track
        }

        Log.i(TAG, "[TrackSelfHealing] Starting self-healing for '${track.title}' (id=${track.id}, stale path='$originalPath')")

        // Strategy 0: Central TrackSourceResolver multi-tier resolution
        val sourceResolution = TrackSourceResolver.resolveTrackSource(context, track, persistToDb = (trackDao != null), trackDao = trackDao)
        if (sourceResolution.isPlayable && sourceResolution.resolvedUriOrPath != null && sourceResolution.resolvedUriOrPath != originalPath) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via TrackSourceResolver: healed '$originalPath' -> '${sourceResolution.resolvedUriOrPath}' (type=${sourceResolution.sourceType})")
            return@withContext applyHealedPath(context, track, sourceResolution.resolvedUriOrPath, trackDao)
        }

        // Strategy 1: MediaStore ID lookup
        val fromMediaStoreId = resolveFromMediaStoreId(context, track)
        if (fromMediaStoreId != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromMediaStoreId)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via MediaStore ID: healed '$originalPath' -> '$fromMediaStoreId'")
            return@withContext applyHealedPath(context, track, fromMediaStoreId, trackDao)
        }

        // Strategy 2: Cross-reference conversion (content:// <-> file path)
        val fromCrossReference = resolveFromCrossReference(context, track)
        if (fromCrossReference != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromCrossReference)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via cross-reference: healed '$originalPath' -> '$fromCrossReference'")
            return@withContext applyHealedPath(context, track, fromCrossReference, trackDao)
        }

        // Strategy 2b: Canonical Storage Resolution (SAF URI <-> /storage/emulated/0/...)
        val fromCanonical = CanonicalStorageHelper.resolvePlayableReference(context, originalPath)
        if (fromCanonical != null && fromCanonical != originalPath && StorageAvailabilityHelper.isTrackPathAvailable(context, fromCanonical)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via CanonicalStorageHelper: healed '$originalPath' -> '$fromCanonical'")
            return@withContext applyHealedPath(context, track, fromCanonical, trackDao)
        }

        // Strategy 3: Persisted SAF DocumentFile / tree URI
        val fromSaf = resolveFromSaf(context, track)
        if (fromSaf != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromSaf)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via SAF document: healed '$originalPath' -> '$fromSaf'")
            return@withContext applyHealedPath(context, track, fromSaf, trackDao)
        }

        // Strategy 4: MediaStore query by title, artist, duration, filename
        val fromMediaStoreQuery = resolveFromMediaStoreQuery(context, track)
        if (fromMediaStoreQuery != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromMediaStoreQuery)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via MediaStore query: healed '$originalPath' -> '$fromMediaStoreQuery'")
            return@withContext applyHealedPath(context, track, fromMediaStoreQuery, trackDao)
        }

        // Strategy 5: Filesystem search in standard directories & fingerprint matching
        val fromFilesystem = resolveFromFilesystem(context, track)
        if (fromFilesystem != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromFilesystem)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via filesystem search: healed '$originalPath' -> '$fromFilesystem'")
            return@withContext applyHealedPath(context, track, fromFilesystem, trackDao)
        }

        Log.w(TAG, "[TrackSelfHealing] FAILED to heal reference for '${track.title}' (id=${track.id}). Path remains inaccessible: '$originalPath'")
        null
    }

    /**
     * Synchronous / lightweight check to find any accessible path for [track] without updating DB.
     */
    fun resolveAnyPlayablePath(context: Context, track: Track): String? {
        if (StorageAvailabilityHelper.isTrackPathAvailable(context, track.filePath)) {
            return track.filePath
        }

        // Fast lookups via TrackSourceResolver
        if (!track.filePath.startsWith("content://") && !track.filePath.startsWith("demo://") && !track.filePath.startsWith("http")) {
            val cleanPath = track.filePath.removePrefix("file://")
            val mediaUri = TrackSourceResolver.findMediaStoreUriForPath(context, cleanPath)
            if (mediaUri != null && isUriReadable(context, Uri.parse(mediaUri))) {
                return mediaUri
            }
            val safUri = CanonicalStorageHelper.findAccessibleSafUriForPath(context, cleanPath)
            if (safUri != null && isUriReadable(context, Uri.parse(safUri))) {
                return safUri
            }
        }

        // Try fast lookups
        resolveFromMediaStoreId(context, track)?.let {
            if (StorageAvailabilityHelper.isTrackPathAvailable(context, it)) return it
        }

        resolveFromCrossReference(context, track)?.let {
            if (StorageAvailabilityHelper.isTrackPathAvailable(context, it)) return it
        }

        resolveFromSaf(context, track)?.let {
            if (StorageAvailabilityHelper.isTrackPathAvailable(context, it)) return it
        }

        resolveFromMediaStoreQuery(context, track)?.let {
            if (StorageAvailabilityHelper.isTrackPathAvailable(context, it)) return it
        }

        resolveFromFilesystem(context, track)?.let {
            if (StorageAvailabilityHelper.isTrackPathAvailable(context, it)) return it
        }

        return null
    }

    // ── Strategy 1: MediaStore ID Lookup ───────────────────────────────────

    private fun resolveFromMediaStoreId(context: Context, track: Track): String? {
        val mediaId = extractMediaId(track) ?: return null
        val volumeUuid = TrackSourceResolver.extractVolumeUuid(track.filePath)
            ?: TrackSourceResolver.extractVolumeUuid(track.resolvedUri.orEmpty())
        val baseCollection = if (!volumeUuid.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { MediaStore.Audio.Media.getContentUri(volumeUuid.lowercase(Locale.ROOT)) }
            catch (_: Exception) { MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val mediaUri = ContentUris.withAppendedId(baseCollection, mediaId)

        // 1a. Check if the direct file path recorded in MediaStore exists on disk
        try {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
            context.contentResolver.query(mediaUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (dataIdx != -1) {
                        val diskPath = cursor.getString(dataIdx)
                        if (!diskPath.isNullOrBlank()) {
                            val f = File(diskPath)
                            if (f.exists() && f.canRead()) {
                                return diskPath
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 1b. Check if the MediaStore content URI is itself readable
        if (isUriReadable(context, mediaUri) && TrackSourceResolver.testContentUriWithMediaExtractor(context, mediaUri)) {
            return mediaUri.toString()
        }

        return null
    }

    // ── Strategy 2: Cross-Reference Conversion ─────────────────────────────

    private fun resolveFromCrossReference(context: Context, track: Track): String? {
        val path = track.filePath

        if (path.startsWith("content://")) {
            // Content URI -> Extract underlying file path from MediaStore or document URI
            try {
                val uri = Uri.parse(path)
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx != -1) {
                            val diskPath = cursor.getString(idx)
                            if (!diskPath.isNullOrBlank()) {
                                val f = File(diskPath)
                                if (f.exists() && f.canRead()) {
                                    return diskPath
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        } else if (path.isNotBlank() && !path.startsWith("demo://") && !path.startsWith("http")) {
            // File path -> Look up corresponding MediaStore content URI
            val cleanPath = path.removePrefix("file://")
            val mediaUri = AudioTagWriter.getMediaStoreUriForPath(context, cleanPath)
            if (mediaUri != null && isUriReadable(context, mediaUri)) {
                return mediaUri.toString()
            }
        }

        return null
    }

    // ── Strategy 3: Persisted SAF DocumentFile ─────────────────────────────

    private fun resolveFromSaf(context: Context, track: Track): String? {
        return try {
            val doc = SafStorageManager.findDocumentForTrack(context, track)
            if (doc != null && doc.exists() && doc.canRead()) {
                if (TrackSourceResolver.testContentUriWithMediaExtractor(context, doc.uri)) {
                    doc.uri.toString()
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ── Strategy 4: MediaStore Query by Title / Artist / Filename ──────────

    private fun resolveFromMediaStoreQuery(context: Context, track: Track): String? {
        val fileName = extractFileName(track)
        val contentResolver = context.contentResolver

        val volumeUuid = TrackSourceResolver.extractVolumeUuid(track.filePath)
            ?: TrackSourceResolver.extractVolumeUuid(track.resolvedUri.orEmpty())
        val collections = mutableListOf<Uri>()
        if (!volumeUuid.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                collections.add(MediaStore.Audio.Media.getContentUri(volumeUuid.lowercase(Locale.ROOT)))
            } catch (_: Exception) {}
        }
        collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        for (collectionUri in collections.distinct()) {
            // 4a. Query MediaStore by DISPLAY_NAME
            if (fileName.isNotBlank()) {
                try {
                    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
                    val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
                    val selectionArgs = arrayOf(fileName)
                    contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        val matched = pickBestCursorMatch(context, cursor, collectionUri, track)
                        if (matched != null) return matched
                    }
                } catch (_: Throwable) {}
            }

            // 4b. Query MediaStore by TITLE and ARTIST
            if (track.title.isNotBlank() && track.title != "<unknown>") {
                try {
                    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
                    val selection: String
                    val selectionArgs: Array<String>
                    if (track.artist.isNotBlank() && track.artist != "Unknown Artist") {
                        selection = "${MediaStore.Audio.Media.TITLE} = ? AND ${MediaStore.Audio.Media.ARTIST} = ?"
                        selectionArgs = arrayOf(track.title, track.artist)
                    } else {
                        selection = "${MediaStore.Audio.Media.TITLE} = ?"
                        selectionArgs = arrayOf(track.title)
                    }

                    contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        val matched = pickBestCursorMatch(context, cursor, collectionUri, track)
                        if (matched != null) return matched
                    }
                } catch (_: Throwable) {}
            }

            // 4c. Query MediaStore by _DATA suffix
            if (fileName.isNotBlank()) {
                try {
                    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
                    val selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
                    val selectionArgs = arrayOf("%/$fileName")
                    contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        val matched = pickBestCursorMatch(context, cursor, collectionUri, track)
                        if (matched != null) return matched
                    }
                } catch (_: Throwable) {}
            }
        }

        return null
    }

    private fun pickBestCursorMatch(context: Context, cursor: Cursor, collectionUri: Uri, track: Track): String? {
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = if (idCol != -1) cursor.getLong(idCol) else -1L
            val data = if (dataCol != -1) cursor.getString(dataCol) else null
            val durationMs = if (durCol != -1) cursor.getLong(durCol) else 0L

            // Duration check: within 5 seconds tolerance if track duration is known
            if (track.durationSeconds > 0 && durationMs > 0) {
                val durationSec = (durationMs / 1000).toInt()
                if (kotlin.math.abs(durationSec - track.durationSeconds) > 5) {
                    continue
                }
            }

            // Prefer verified disk file path if genuinely readable at raw byte level
            if (!data.isNullOrBlank()) {
                val f = File(data)
                if (TrackSourceResolver.isGenuinelyRawReadable(f)) {
                    return data
                }
            }

            // Fallback to verified MediaStore content URI
            if (id > 0L) {
                val uri = ContentUris.withAppendedId(collectionUri, id)
                if (isUriReadable(context, uri) && TrackSourceResolver.testContentUriWithMediaExtractor(context, uri)) {
                    return uri.toString()
                }
            }
        }
        return null
    }

    // ── Strategy 5: Filesystem Search in Standard Folders ──────────────────

    private fun resolveFromFilesystem(context: Context, track: Track): String? {
        val fileName = extractFileName(track)
        if (fileName.isBlank()) return null

        val candidateDirs = mutableListOf<File>()

        // Check track directory if stored
        if (track.directoryPath.isNotBlank()) {
            candidateDirs.add(File(track.directoryPath))
        }

        // Check parent of original file
        if (!track.filePath.startsWith("content://") && track.filePath.isNotBlank()) {
            File(track.filePath).parentFile?.let { candidateDirs.add(it) }
        }

        // Standard storage locations
        candidateDirs.add(File("/storage/emulated/0/Music"))
        candidateDirs.add(File("/storage/emulated/0/Download"))
        candidateDirs.add(File("/storage/emulated/0/Audio"))
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.let { candidateDirs.add(it) }
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { candidateDirs.add(it) }
        } catch (_: Throwable) {}

        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val candidateFile = File(dir, fileName)
            if (candidateFile.exists() && candidateFile.canRead()) {
                // If duration or size check is possible, verify
                if (track.fileSizeMb > 0) {
                    val candidateSizeMb = candidateFile.length().toDouble() / (1024.0 * 1024.0)
                    if (kotlin.math.abs(candidateSizeMb - track.fileSizeMb) < 1.0) {
                        return candidateFile.absolutePath
                    }
                } else {
                    return candidateFile.absolutePath
                }
            }
        }

        // 5b. Renamed file discovery: Match by content fingerprint in candidate directories
        if (track.contentFingerprint.isNotBlank() && track.contentFingerprint.startsWith("fp_")) {
            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val audioFiles = dir.listFiles { f ->
                    f.isFile && f.canRead() && listOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff").contains(f.extension.lowercase(Locale.ROOT))
                } ?: continue

                for (audioFile in audioFiles) {
                    // Fast filter by file size
                    val sizeDiff = kotlin.math.abs((audioFile.length() / (1024.0 * 1024.0)) - track.fileSizeMb)
                    if (track.fileSizeMb <= 0 || sizeDiff < 0.2) {
                        val fp = AudioFingerprintUtil.generateFingerprint(context, audioFile.absolutePath, audioFile.length(), track.durationSeconds)
                        if (fp == track.contentFingerprint) {
                            Log.i(TAG, "[TrackSelfHealing] Recovered renamed file via fingerprint: '${audioFile.absolutePath}'")
                            return audioFile.absolutePath
                        }
                    }
                }
            }
        }

        return null
    }

    // ── Persistence & Helpers ──────────────────────────────────────────────

    private suspend fun applyHealedPath(
        context: Context,
        track: Track,
        newPath: String,
        trackDao: TrackDao?
    ): Track {
        val relPath = CanonicalStorageHelper.toStorageRelativePath(newPath)
        val dirPath = if (newPath.startsWith("content://")) {
            if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""
        } else {
            File(newPath).parent ?: ""
        }

        // Artwork verification on self-healing:
        var validArtPath = track.artworkCachePath
        var validArtUrl = track.artworkUrl
        var validArtSource = track.artworkSource

        if (!validArtPath.isNullOrBlank()) {
            val f = File(validArtPath)
            if (!f.exists() || f.length() == 0L) {
                validArtPath = null
            }
        }

        if (validArtPath == null) {
            val artCache = com.example.metadata.ArtworkCache(context)
            val cached = artCache.getCachedArtworkFileForTrack(track.id)
                ?: if (track.artist.isNotBlank() && track.album.isNotBlank()) {
                    artCache.getCachedArtworkFile(track.artist, track.album)
                } else null
            if (cached != null && cached.exists() && cached.length() > 0) {
                validArtPath = cached.absolutePath
                if (validArtUrl.isNullOrBlank()) validArtUrl = cached.absolutePath
                if (validArtSource.isNullOrBlank()) validArtSource = "Artwork Cache"
            }
        }

        val healed = track.copy(
            filePath = newPath,
            storageRelativePath = relPath,
            directoryPath = dirPath,
            isAvailable = true,
            artworkCachePath = validArtPath,
            artworkUrl = validArtUrl,
            artworkSource = validArtSource
        )
        if (trackDao != null && track.id.isNotBlank()) {
            try {
                val entity = trackDao.getTrackById(track.id)
                if (entity != null) {
                    trackDao.updateTrack(
                        entity.copy(
                            filePath = newPath,
                            storageRelativePath = relPath,
                            artworkCachePath = validArtPath,
                            artworkUrl = validArtUrl,
                            artworkSource = validArtSource
                        )
                    )
                } else {
                    trackDao.updateFilePath(track.id, newPath)
                }
                Log.d(TAG, "[TrackSelfHealing] Persisted healed path to database for track ${track.id}: '$newPath'")
            } catch (e: Exception) {
                Log.w(TAG, "[TrackSelfHealing] Failed persisting healed path to database: ${e.message}")
            }
        }
        return healed
    }

    private fun extractMediaId(track: Track): Long? {
        if (track.id.startsWith("media_")) {
            return track.id.removePrefix("media_").toLongOrNull()
        }
        if (track.filePath.startsWith("content://media/")) {
            return try {
                ContentUris.parseId(Uri.parse(track.filePath))
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun extractFileName(track: Track): String {
        val path = track.filePath.removePrefix("file://")
        if (!path.startsWith("content://") && File(path).name.contains('.')) {
            return File(path).name
        }
        val fromRel = track.storageRelativePath.substringAfterLast('/').takeIf { it.contains('.') }
        if (!fromRel.isNullOrBlank()) {
            return fromRel
        }
        val ext = track.format.lowercase(Locale.ROOT).ifBlank { "mp3" }
        return if (track.title.isNotBlank() && track.title != "<unknown>") {
            "${track.title}.$ext"
        } else {
            ""
        }
    }

    private fun isUriReadable(context: Context, uri: Uri): Boolean {
        TrackSourceResolver.contentUriPlayableCheckerForTesting?.let { return it(context, uri) }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { true }
                ?: context.contentResolver.openInputStream(uri)?.use { true }
                ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Diagnoses why a track is unplayable or unavailable, classifying the root cause
     * into STALE_URI_AFTER_METADATA_REWRITE, CORRUPTED_FILE, DISCONNECTED_STORAGE, or FILE_NOT_FOUND.
     */
    fun diagnoseTrack(context: Context, track: Track): PlaybackDiagnostic {
        val path = track.filePath
        if (path.isBlank()) {
            return PlaybackDiagnostic(
                trackId = track.id,
                issueType = PlaybackIssueType.FILE_NOT_FOUND,
                message = "Track file path is empty",
                details = "No file path or URI is associated with this track.",
                canAutoRepair = false
            )
        }

        val isDirectFile = !path.startsWith("content://") && !path.startsWith("http")
        val cleanPath = path.removePrefix("file://")
        val directFile = if (isDirectFile) File(cleanPath) else null

        // 1. If direct file exists on disk, check genuine raw readability and structural integrity
        if (directFile != null && directFile.exists()) {
            if (TrackSourceResolver.isGenuinelyRawReadable(directFile)) {
                val validation = AudioTagWriter.validateRewrittenAudio(directFile, directFile.extension, null)
                if (validation is AudioValidationResult.Invalid) {
                    // Check if a restorable backup file exists
                    val parent = directFile.parentFile
                    val backupFile = parent?.listFiles { _, name ->
                        name.startsWith(".${directFile.name}.") && name.endsWith(".bak")
                    }?.firstOrNull { it.length() > 0 }

                    return PlaybackDiagnostic(
                        trackId = track.id,
                        issueType = PlaybackIssueType.CORRUPTED_FILE,
                        message = "Audio file is corrupted (${validation.reason})",
                        details = "The audio file container or stream was damaged during a previous write. Backup available: ${backupFile != null}.",
                        canAutoRepair = backupFile != null
                    )
                }
                // File is valid and playable
                return PlaybackDiagnostic(
                    trackId = track.id,
                    issueType = PlaybackIssueType.NONE,
                    message = "Track is playable",
                    details = "Direct file is intact and decodable.",
                    canAutoRepair = false
                )
            } else {
                // Direct file exists on filesystem but cannot be opened via FileInputStream (e.g. Scoped Storage on removable volume)
                val isDisconnected = StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track)
                if (isDisconnected) {
                    return PlaybackDiagnostic(
                        trackId = track.id,
                        issueType = PlaybackIssueType.DISCONNECTED_STORAGE,
                        message = "Storage device is disconnected",
                        details = "The volume containing this audio file is currently not mounted.",
                        canAutoRepair = false
                    )
                }
                val mediaUri = TrackSourceResolver.findMediaStoreUriForPath(context, cleanPath)
                if (mediaUri != null && isUriReadable(context, Uri.parse(mediaUri))) {
                    return PlaybackDiagnostic(
                        trackId = track.id,
                        issueType = PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE,
                        message = "Raw file blocked by Scoped Storage; MediaStore URI available",
                        details = "Raw filesystem access to external volume blocked, but track is indexed in MediaStore.",
                        canAutoRepair = true,
                        suggestedPath = mediaUri
                    )
                }
                val safUri = CanonicalStorageHelper.findAccessibleSafUriForPath(context, cleanPath)
                if (safUri != null && isUriReadable(context, Uri.parse(safUri))) {
                    return PlaybackDiagnostic(
                        trackId = track.id,
                        issueType = PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE,
                        message = "Raw file blocked by Scoped Storage; SAF Document available",
                        details = "Raw filesystem access blocked, but track is accessible via SAF permission grant.",
                        canAutoRepair = true,
                        suggestedPath = safUri
                    )
                }
            }
        }

        // 2. If content:// URI, check readability
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val isReadable = isUriReadable(context, uri)
            if (isReadable) {
                return PlaybackDiagnostic(
                    trackId = track.id,
                    issueType = PlaybackIssueType.NONE,
                    message = "Content URI is readable",
                    details = "URI is active and accessible.",
                    canAutoRepair = false
                )
            }

            // URI is NOT readable. Check if the underlying physical file exists on disk
            val physicalPath = resolveFromCrossReference(context, track) ?: resolveFromMediaStoreId(context, track)
            if (physicalPath != null && File(physicalPath.removePrefix("file://")).exists()) {
                val f = File(physicalPath.removePrefix("file://"))
                val valResult = AudioTagWriter.validateRewrittenAudio(f, f.extension, null)
                if (valResult is AudioValidationResult.Valid) {
                    return PlaybackDiagnostic(
                        trackId = track.id,
                        issueType = PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE,
                        message = "Content URI became stale after metadata rewrite",
                        details = "MediaStore ID or URI was invalidated following file replacement, but the audio file on disk is valid and playable.",
                        canAutoRepair = true,
                        suggestedPath = physicalPath
                    )
                }
            }
        }

        // 3. File not found: check if storage root is disconnected
        val isDisconnected = StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track)
        if (isDisconnected) {
            return PlaybackDiagnostic(
                trackId = track.id,
                issueType = PlaybackIssueType.DISCONNECTED_STORAGE,
                message = "Storage device is disconnected",
                details = "The volume containing this audio file is currently not mounted.",
                canAutoRepair = false
            )
        }

        return PlaybackDiagnostic(
            trackId = track.id,
            issueType = PlaybackIssueType.FILE_NOT_FOUND,
            message = "Audio file not found",
            details = "The audio file could not be located at the stored path.",
            canAutoRepair = false
        )
    }

    /**
     * Attempts to repair an unplayable track based on its diagnosed failure condition.
     */
    suspend fun repairTrack(
        context: Context,
        track: Track,
        trackDao: TrackDao? = null
    ): TrackRepairResult = withContext(Dispatchers.IO) {
        val diag = diagnoseTrack(context, track)

        when (diag.issueType) {
            PlaybackIssueType.NONE -> {
                TrackRepairResult(true, PlaybackIssueType.NONE, "Track is already playable", track)
            }

            PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE -> {
                Log.i(TAG, "[repairTrack] Repairing STALE_URI_AFTER_METADATA_REWRITE for '${track.title}'")
                val healed = healTrack(context, track, trackDao)
                if (healed != null) {
                    try {
                        WaveformCache.remove(WaveformCache.getCacheKey(track, context), context)
                    } catch (_: Throwable) {}
                    try {
                        com.example.audio.DjAudioEngine.getInstance(context).onTrackFileModified(track.id, track.filePath, healed.filePath)
                    } catch (_: Throwable) {}
                    TrackRepairResult(true, PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE, "Reconciled stale URI with valid audio file: ${healed.filePath}", healed)
                } else {
                    TrackRepairResult(false, PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE, "Failed to resolve valid audio path", null)
                }
            }

            PlaybackIssueType.CORRUPTED_FILE -> {
                Log.i(TAG, "[repairTrack] Attempting repair for CORRUPTED_FILE for '${track.title}'")
                val cleanPath = track.filePath.removePrefix("file://")
                val file = File(cleanPath)
                val parent = file.parentFile
                val backupFile = parent?.listFiles { _, name ->
                    name.startsWith(".${file.name}.") && name.endsWith(".bak")
                }?.maxByOrNull { it.lastModified() }

                if (backupFile != null && backupFile.exists() && backupFile.length() > 0) {
                    val valBackup = AudioTagWriter.validateRewrittenAudio(backupFile, file.extension, null)
                    if (valBackup is AudioValidationResult.Valid) {
                        try {
                            backupFile.copyTo(file, overwrite = true)
                            val valRestored = AudioTagWriter.validateRewrittenAudio(file, file.extension, null)
                            if (valRestored is AudioValidationResult.Valid) {
                                backupFile.delete()
                                try {
                                    WaveformCache.remove(WaveformCache.getCacheKey(track, context), context)
                                } catch (_: Throwable) {}
                                Log.i(TAG, "[repairTrack] Successfully restored corrupted file from backup for '${track.title}'")
                                return@withContext TrackRepairResult(true, PlaybackIssueType.CORRUPTED_FILE, "Restored original audio from pre-rewrite backup.", track.copy(isAvailable = true))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[repairTrack] Failed copying backup file: ${e.message}")
                        }
                    }
                }

                TrackRepairResult(false, PlaybackIssueType.CORRUPTED_FILE, "File is damaged and no restorable backup was found.", null)
            }

            PlaybackIssueType.DISCONNECTED_STORAGE -> {
                TrackRepairResult(false, PlaybackIssueType.DISCONNECTED_STORAGE, "Storage volume is disconnected.", null)
            }

            PlaybackIssueType.FILE_NOT_FOUND -> {
                val healed = healTrack(context, track, trackDao)
                if (healed != null) {
                    TrackRepairResult(true, PlaybackIssueType.FILE_NOT_FOUND, "Located audio file at ${healed.filePath}", healed)
                } else {
                    TrackRepairResult(false, PlaybackIssueType.FILE_NOT_FOUND, "File could not be found.", null)
                }
            }
        }
    }

    /**
     * Scans database for broken or unavailable tracks and attempts automated self-healing.
     */
    suspend fun scanAndRepairAllBrokenRewriteTracks(
        context: Context,
        trackDao: TrackDao
    ): List<TrackRepairResult> = withContext(Dispatchers.IO) {
        val tracks = try {
            trackDao.getAllTracksSync()
        } catch (_: Throwable) {
            emptyList()
        }

        val results = mutableListOf<TrackRepairResult>()
        for (entity in tracks) {
            val track = entity.toTrack()
            val diag = diagnoseTrack(context, track)
            if (diag.canAutoRepair || diag.issueType == PlaybackIssueType.STALE_URI_AFTER_METADATA_REWRITE) {
                val res = repairTrack(context, track, trackDao)
                results.add(res)
            }
        }
        results
    }
}
