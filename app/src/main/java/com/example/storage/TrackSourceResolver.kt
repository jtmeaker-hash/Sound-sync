package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.data.AppDatabase
import com.example.data.SourceFolderEntity
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.PlayabilityStatus
import com.example.model.StorageSourceType
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * Supported types of underlying media sources for SoundSync audio tracks.
 */
enum class ResolvedSourceType {
    MEDIASTORE,
    SAF_DOCUMENT,
    SAF_TREE,
    RAW_FILE_PATH,
    REMOVABLE_STORAGE_PATH,
    UNKNOWN
}

/**
 * Representation of an Android storage volume (internal or removable SD / USB OTG).
 */
data class StorageVolumeInfo(
    val uuid: String?,
    val description: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val state: String,
    val mountDirectory: File?,
    val isMounted: Boolean,
    val isReadOnly: Boolean
)

/**
 * Comprehensive 18-point diagnostic report for an audio track's physical storage access.
 */
data class StorageDiagnosticReport(
    val storedSource: String,
    val fileExists: Boolean,
    val fileIsFile: Boolean,
    val fileCanRead: Boolean,
    val fileLength: Long,
    val fileInputStreamOpened: Boolean,
    val fileInputStreamStatus: String,
    val parcelFileDescriptorOpened: Boolean,
    val parcelFileDescriptorStatus: String,
    val isVolumeMounted: Boolean,
    val isRemovableStorage: Boolean,
    val storageVolumeSummary: String,
    val mediaStoreAware: Boolean,
    val mediaStoreUri: String? = null,
    val hasReadMediaAudioPermission: Boolean,
    val hasSafTreeGrant: Boolean,
    val isScopedStorageBlockingRawAccess: Boolean,
    val isGenuinelyCorrupt: Boolean,
    val isStaleDatabasePath: Boolean,
    val hasVolumeOrPathChanged: Boolean,
    val originalExceptionClass: String? = null,
    val originalExceptionMessage: String? = null,
    val detectedFailureMode: String,
    val recommendedResolution: String? = null
) {
    fun formatDiagnostics(): String {
        return buildString {
            appendLine("=== SoundSync Storage Diagnostic Probe ===")
            appendLine("Stored Source: $storedSource")
            appendLine("File.exists(): $fileExists")
            appendLine("File.isFile(): $fileIsFile")
            appendLine("File.canRead(): $fileCanRead")
            appendLine("File.length(): $fileLength bytes")
            appendLine("FileInputStream: $fileInputStreamStatus")
            appendLine("ParcelFileDescriptor: $parcelFileDescriptorStatus")
            appendLine("Volume Mounted: $isVolumeMounted")
            appendLine("Removable Storage: $isRemovableStorage")
            appendLine("StorageVolume Info: $storageVolumeSummary")
            appendLine("MediaStore Aware: $mediaStoreAware${if (mediaStoreUri != null) " (URI: $mediaStoreUri)" else ""}")
            appendLine("READ_MEDIA_AUDIO Granted: $hasReadMediaAudioPermission")
            appendLine("SAF Tree Grant Exists: $hasSafTreeGrant")
            appendLine("Scoped Storage Blocking Raw Access: $isScopedStorageBlockingRawAccess")
            appendLine("File Genuinely Corrupt: $isGenuinelyCorrupt")
            appendLine("Stale Database Path: $isStaleDatabasePath")
            appendLine("Volume/Path Changed: $hasVolumeOrPathChanged")
            if (originalExceptionClass != null) {
                appendLine("Original Exception: $originalExceptionClass: $originalExceptionMessage")
            }
            appendLine("Failure Mode: $detectedFailureMode")
            if (recommendedResolution != null) {
                appendLine("Recommended Source: $recommendedResolution")
            }
        }
    }
}

/**
 * Result of resolving a stored track path into a verified playable media source.
 */
data class TrackSourceResolution(
    val trackId: String,
    val originalPath: String,
    val resolvedUriOrPath: String?,
    val sourceType: ResolvedSourceType,
    val isPlayable: Boolean,
    val volumeUuid: String? = null,
    val isRemovable: Boolean = false,
    val requiresFolderAccess: Boolean = false,
    val targetRemovableFolder: String? = null,
    val diagnostics: StorageDiagnosticReport
)

/**
 * Summary of batch track reconciliation after folder permission grant.
 */
data class FolderReconciliationSummary(
    val reconciledCount: Int,
    val failedCount: Int,
    val updatedTracks: List<TrackEntity>
)

/**
 * Group of tracks on an external/removable storage volume requiring folder SAF permission.
 */
data class VolumePermissionGroup(
    val volumeUuid: String,
    val folderPath: String,
    val displayName: String,
    val affectedTracks: List<Track>
)

/**
 * Central Android Media & Storage Source Resolver for SoundSync.
 *
 * Resolves raw filesystem paths, removable storage volumes (SD cards, USB OTG),
 * MediaStore content URIs, and SAF document trees into verified, accessible Android media sources.
 *
 * Implements a resilient 4-step resolution pipeline:
 *  Step 1: Genuine raw filesystem readability check (under current Android version & scoped storage)
 *  Step 2: MediaStore query by DISPLAY_NAME, RELATIVE_PATH, VOLUME_NAME, SIZE, DURATION, TITLE, ARTIST
 *  Step 3: Persisted SAF DocumentFile / tree URI resolution
 *  Step 4: Removable storage folder access request and batch track reconciliation
 */
object TrackSourceResolver {

    private const val TAG = "SoundSyncStorageResolver"

    /**
     * Resolves a stored audio track to an accessible Android media source.
     *
     * @param context Android context for StorageManager, ContentResolver, MediaStore.
     * @param track The audio track to resolve.
     * @param persistToDb If true and trackDao is provided, automatically updates the database when healed.
     * @param trackDao Optional DAO for persisting healed paths.
     */
    suspend fun resolveTrackSource(
        context: Context,
        track: Track,
        persistToDb: Boolean = false,
        trackDao: TrackDao? = null
    ): TrackSourceResolution = withContext(Dispatchers.IO) {
        val originalPath = track.filePath
        val trackId = track.id

        // Special case: built-in demo synthetic tracks
        if (originalPath.startsWith("demo://")) {
            val diag = runDiagnosticProbe(context, track)
            return@withContext TrackSourceResolution(
                trackId = trackId,
                originalPath = originalPath,
                resolvedUriOrPath = originalPath,
                sourceType = ResolvedSourceType.RAW_FILE_PATH,
                isPlayable = true,
                diagnostics = diag
            )
        }

        // Run full 18-point diagnostic probe
        val diag = runDiagnosticProbe(context, track)

        // Case A: Content URI already provided
        if (originalPath.startsWith("content://")) {
            val uri = Uri.parse(originalPath)
            if (isContentUriPlayable(context, uri)) {
                val isMediaStore = originalPath.startsWith("content://media/")
                return@withContext TrackSourceResolution(
                    trackId = trackId,
                    originalPath = originalPath,
                    resolvedUriOrPath = originalPath,
                    sourceType = if (isMediaStore) ResolvedSourceType.MEDIASTORE else ResolvedSourceType.SAF_DOCUMENT,
                    isPlayable = true,
                    diagnostics = diag
                )
            }
            // If content URI is stale, fall through to multi-step resolution
        }

        val volumeInfo = getStorageVolumeForPath(context, originalPath)
        val isRemovable = volumeInfo?.isRemovable ?: StorageAvailabilityHelper.isExternalStoragePath(originalPath)
        val volumeUuid = volumeInfo?.uuid ?: extractVolumeUuid(originalPath)

        val cleanPath = originalPath.removePrefix("file://")
        val directFile = File(cleanPath)

        // STEP 1: Search MediaStore for the same audio file across all mounted collections
        val mediaStoreUri = findMediaStoreUriForTrack(context, track)
        if (mediaStoreUri != null && isContentUriPlayable(context, mediaStoreUri)) {
            val uriString = mediaStoreUri.toString()
            Log.i(TAG, "STEP 1 SUCCESS: Resolved raw path to MediaStore URI: '$originalPath' -> '$uriString'")
            persistHealedPathIfRequested(track, uriString, persistToDb, trackDao)
            return@withContext TrackSourceResolution(
                trackId = trackId,
                originalPath = originalPath,
                resolvedUriOrPath = uriString,
                sourceType = ResolvedSourceType.MEDIASTORE,
                isPlayable = true,
                volumeUuid = volumeUuid,
                isRemovable = isRemovable,
                diagnostics = diag.copy(
                    mediaStoreAware = true,
                    mediaStoreUri = uriString,
                    detectedFailureMode = "Raw path resolved via MediaStore content URI.",
                    recommendedResolution = uriString
                )
            )
        }

        // STEP 2: Check if the file belongs to an SAF-authorised directory / tree
        val safDocUri = findSafDocumentUriForTrack(context, track)
        if (safDocUri != null && isContentUriPlayable(context, safDocUri)) {
            val uriString = safDocUri.toString()
            Log.i(TAG, "STEP 2 SUCCESS: Resolved raw path to SAF Document URI: '$originalPath' -> '$uriString'")
            persistHealedPathIfRequested(track, uriString, persistToDb, trackDao)
            return@withContext TrackSourceResolution(
                trackId = trackId,
                originalPath = originalPath,
                resolvedUriOrPath = uriString,
                sourceType = ResolvedSourceType.SAF_DOCUMENT,
                isPlayable = true,
                volumeUuid = volumeUuid,
                isRemovable = isRemovable,
                diagnostics = diag.copy(
                    hasSafTreeGrant = true,
                    detectedFailureMode = "Raw path resolved via granted SAF directory tree.",
                    recommendedResolution = uriString
                )
            )
        }

        // STEP 3: Genuine raw filesystem readability check (both FileInputStream & ParcelFileDescriptor)
        if (isGenuinelyRawReadable(directFile)) {
            Log.d(TAG, "STEP 3 SUCCESS: File is genuinely readable through raw path: $cleanPath")
            return@withContext TrackSourceResolution(
                trackId = trackId,
                originalPath = originalPath,
                resolvedUriOrPath = cleanPath,
                sourceType = if (isRemovable) ResolvedSourceType.REMOVABLE_STORAGE_PATH else ResolvedSourceType.RAW_FILE_PATH,
                isPlayable = true,
                volumeUuid = volumeUuid,
                isRemovable = isRemovable,
                diagnostics = diag.copy(
                    detectedFailureMode = "None (Raw filesystem path verified readable)",
                    recommendedResolution = cleanPath
                )
            )
        }

        // STEP 4: Relocate / Reinserted volume check
        val reinserted = findReinsertedVolumeForTrack(context, track)
        if (reinserted != null && isGenuinelyRawReadable(reinserted.second)) {
            val newPath = reinserted.second.absolutePath
            persistHealedPathIfRequested(track, newPath, persistToDb, trackDao)
            return@withContext TrackSourceResolution(
                trackId = trackId,
                originalPath = originalPath,
                resolvedUriOrPath = newPath,
                sourceType = ResolvedSourceType.REMOVABLE_STORAGE_PATH,
                isPlayable = true,
                volumeUuid = reinserted.first.uuid,
                isRemovable = true,
                diagnostics = diag.copy(
                    hasVolumeOrPathChanged = true,
                    recommendedResolution = newPath,
                    detectedFailureMode = "Relocated on new mount point: $newPath"
                )
            )
        }

        // STEP 5: Removable storage handling (Permission required vs Volume unmounted)
        if (isRemovable) {
            val isMounted = volumeInfo?.isMounted ?: isRemovableStorageVolumeMounted(context, volumeUuid ?: "")
            if (!isMounted) {
                Log.w(TAG, "STEP 5: Storage volume '$volumeUuid' containing track '${track.title}' is disconnected or unmounted.")
                return@withContext TrackSourceResolution(
                    trackId = trackId,
                    originalPath = originalPath,
                    resolvedUriOrPath = null,
                    sourceType = ResolvedSourceType.REMOVABLE_STORAGE_PATH,
                    isPlayable = false,
                    volumeUuid = volumeUuid,
                    isRemovable = true,
                    requiresFolderAccess = false,
                    diagnostics = diag.copy(
                        isVolumeMounted = false,
                        detectedFailureMode = "Storage volume $volumeUuid is unmounted or removed."
                    )
                )
            } else {
                val folderRoot = if (volumeUuid != null) "/storage/$volumeUuid" else directFile.parent ?: ""
                Log.w(TAG, "STEP 5: Removable storage track requires user folder permission for '$folderRoot'.")
                return@withContext TrackSourceResolution(
                    trackId = trackId,
                    originalPath = originalPath,
                    resolvedUriOrPath = null,
                    sourceType = ResolvedSourceType.REMOVABLE_STORAGE_PATH,
                    isPlayable = false,
                    volumeUuid = volumeUuid,
                    isRemovable = true,
                    requiresFolderAccess = true,
                    targetRemovableFolder = folderRoot,
                    diagnostics = diag.copy(
                        isScopedStorageBlockingRawAccess = true,
                        detectedFailureMode = "Scoped storage restricts raw access to removable storage ($volumeUuid). Single folder grant required.",
                        recommendedResolution = "Request SAF folder access for $folderRoot"
                    )
                )
            }
        }

        // STEP 6: Missing file
        Log.w(TAG, "Track '$originalPath' could not be resolved by any media source tier.")
        TrackSourceResolution(
            trackId = trackId,
            originalPath = originalPath,
            resolvedUriOrPath = null,
            sourceType = ResolvedSourceType.UNKNOWN,
            isPlayable = false,
            volumeUuid = volumeUuid,
            isRemovable = false,
            requiresFolderAccess = false,
            diagnostics = diag.copy(
                detectedFailureMode = "File missing from storage and not found in MediaStore or SAF."
            )
        )
    }

    /**
     * Executes the comprehensive 18-point diagnostic probe for a track's source.
     */
    fun runDiagnosticProbe(context: Context, track: Track): StorageDiagnosticReport {
        val path = track.filePath
        val cleanPath = path.removePrefix("file://")
        val directFile = File(cleanPath)
        val isContentUri = path.startsWith("content://")

        val fileExists = if (!isContentUri) directFile.exists() else false
        val fileIsFile = if (!isContentUri) directFile.isFile else false
        val fileCanRead = if (!isContentUri) directFile.canRead() else false
        val fileLength = if (!isContentUri && fileExists) directFile.length() else {
            if (isContentUri) getUriLength(context, Uri.parse(path)) else 0L
        }

        // Check FileInputStream
        var fisOpened = false
        var fisStatus = "NOT_TESTED"
        var originalExClass: String? = null
        var originalExMessage: String? = null

        if (!isContentUri) {
            try {
                if (directFile.exists()) {
                    FileInputStream(directFile).use { fis ->
                        val buf = ByteArray(1)
                        val r = fis.read(buf)
                        fisOpened = (r >= 0 || directFile.length() == 0L)
                        fisStatus = if (fisOpened) "OK" else "FAILED (Empty stream)"
                    }
                } else {
                    fisStatus = "FAILED (File does not exist: ENOENT)"
                    originalExClass = FileNotFoundException::class.java.name
                    originalExMessage = "File does not exist: $cleanPath"
                }
            } catch (fnfe: FileNotFoundException) {
                fisOpened = false
                fisStatus = "FAILED (${fnfe.javaClass.simpleName}: ${fnfe.message})"
                originalExClass = fnfe.javaClass.name
                originalExMessage = fnfe.message
            } catch (se: SecurityException) {
                fisOpened = false
                fisStatus = "FAILED (SecurityException: ${se.message})"
                originalExClass = se.javaClass.name
                originalExMessage = se.message
            } catch (ioe: IOException) {
                fisOpened = false
                fisStatus = "FAILED (IOException: ${ioe.message})"
                originalExClass = ioe.javaClass.name
                originalExMessage = ioe.message
            } catch (t: Throwable) {
                fisOpened = false
                fisStatus = "FAILED (${t.javaClass.simpleName}: ${t.message})"
                originalExClass = t.javaClass.name
                originalExMessage = t.message
            }
        } else {
            fisStatus = "N/A (Content URI)"
        }

        // Check ParcelFileDescriptor
        var pfdOpened = false
        var pfdStatus = "NOT_TESTED"
        try {
            if (isContentUri) {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { pfd ->
                    pfdOpened = pfd.fileDescriptor.valid()
                    pfdStatus = if (pfdOpened) "OK" else "FAILED (Invalid FD)"
                } ?: run {
                    pfdStatus = "FAILED (Null PFD returned)"
                }
            } else if (fileExists && fileCanRead) {
                ParcelFileDescriptor.open(directFile, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                    pfdOpened = pfd.fileDescriptor.valid()
                    pfdStatus = if (pfdOpened) "OK" else "FAILED (Invalid FD)"
                } ?: run {
                    pfdStatus = "FAILED (Null PFD)"
                }
            } else {
                pfdStatus = "FAILED (File cannot be opened)"
            }
        } catch (e: Throwable) {
            pfdOpened = false
            pfdStatus = "FAILED (${e.javaClass.simpleName}: ${e.message})"
            if (originalExClass == null) {
                originalExClass = e.javaClass.name
                originalExMessage = e.message
            }
        }

        // Storage Volume inspection
        val volumeInfo = getStorageVolumeForPath(context, path)
        val volumeUuid = volumeInfo?.uuid ?: extractVolumeUuid(path)
        val isRemovable = volumeInfo?.isRemovable ?: StorageAvailabilityHelper.isExternalStoragePath(path)
        val isVolumeMounted = volumeInfo?.isMounted ?: isRemovableStorageVolumeMounted(context, volumeUuid ?: "")
        val volumeSummary = volumeInfo?.let {
            "${it.description} [UUID=${it.uuid ?: "primary"}, State=${it.state}, Removable=${it.isRemovable}, ReadOnly=${it.isReadOnly}]"
        } ?: if (isRemovable) "Removable Volume (UUID=$volumeUuid, Mounted=$isVolumeMounted)" else "Internal Emulated Storage (/storage/emulated/0)"

        // MediaStore awareness
        val mediaStoreUri = findMediaStoreUriForTrack(context, track)
        val isMediaStoreAware = mediaStoreUri != null

        // Permissions
        val hasReadMediaAudio = hasAudioReadPermission(context)
        val hasSafTreeGrant = SafStorageManager.getPersistedWriteFolderUris(context).any { treeUri ->
            volumeUuid != null && treeUri.toString().contains(volumeUuid, ignoreCase = true)
        }

        // Scoped Storage restriction detection
        val isScopedStorageBlocking = isRemovable && fileExists && !fisOpened && (
            originalExMessage?.contains("Permission denied", ignoreCase = true) == true ||
            originalExMessage?.contains("EACCES", ignoreCase = true) == true ||
            originalExClass?.contains("SecurityException", ignoreCase = true) == true
        )

        // Corruption & Path check
        val isGenuinelyCorrupt = fileLength in 1..127L
        val isStalePath = !fileExists && !isMediaStoreAware && !hasSafTreeGrant
        val hasVolumeChanged = !isVolumeMounted && findReinsertedVolumeForTrack(context, track) != null

        val failureMode = when {
            !isVolumeMounted -> "External storage volume ($volumeUuid) is disconnected or unmounted."
            isScopedStorageBlocking -> "Android Scoped Storage is blocking raw filesystem access to removable storage ($volumeUuid)."
            isGenuinelyCorrupt -> "Audio file container is damaged or truncated (<128 bytes)."
            !fileExists && !isContentUri -> "Physical audio file is missing at path ($cleanPath)."
            fisOpened || isContentUriPlayable(context, Uri.parse(path)) -> "None (Source is accessible)"
            else -> "Storage read failure: ${originalExMessage ?: "Unknown reason"}"
        }

        val recommended = when {
            mediaStoreUri != null -> mediaStoreUri.toString()
            hasSafTreeGrant -> findSafDocumentUriForTrack(context, track)?.toString()
            isRemovable && isScopedStorageBlocking -> "Request SAF folder access for /storage/$volumeUuid"
            else -> null
        }

        return StorageDiagnosticReport(
            storedSource = path,
            fileExists = fileExists,
            fileIsFile = fileIsFile,
            fileCanRead = fileCanRead,
            fileLength = fileLength,
            fileInputStreamOpened = fisOpened,
            fileInputStreamStatus = fisStatus,
            parcelFileDescriptorOpened = pfdOpened,
            parcelFileDescriptorStatus = pfdStatus,
            isVolumeMounted = isVolumeMounted,
            isRemovableStorage = isRemovable,
            storageVolumeSummary = volumeSummary,
            mediaStoreAware = isMediaStoreAware,
            mediaStoreUri = mediaStoreUri?.toString(),
            hasReadMediaAudioPermission = hasReadMediaAudio,
            hasSafTreeGrant = hasSafTreeGrant,
            isScopedStorageBlockingRawAccess = isScopedStorageBlocking,
            isGenuinelyCorrupt = isGenuinelyCorrupt,
            isStaleDatabasePath = isStalePath,
            hasVolumeOrPathChanged = hasVolumeChanged,
            originalExceptionClass = originalExClass,
            originalExceptionMessage = originalExMessage,
            detectedFailureMode = failureMode,
            recommendedResolution = recommended
        )
    }

    /**
     * Checks whether a physical file can genuinely be opened and read at the byte level.
     */
    fun isGenuinelyRawReadable(file: File): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) return false
        val fisReadable = try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(1)
                val r = fis.read(buf)
                r >= 0 || file.length() == 0L
            }
        } catch (_: Throwable) {
            false
        }
        if (!fisReadable) return false

        // Secondary check: verify ParcelFileDescriptor can be opened (fails under scoped storage on secondary external volumes)
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                pfd.fileDescriptor.valid()
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Searches MediaStore for an audio track using title, artist, duration, display name, and disk path.
     */
    fun findMediaStoreUriForTrack(context: Context, track: Track): Uri? {
        mediaStoreUriFinderForTesting?.let { return it(context, track) }
        val path = track.filePath.removePrefix("file://")
        val fileName = File(path).name.ifBlank {
            if (track.title.isNotBlank() && track.title != "<unknown>") {
                "${track.title}.${track.format.lowercase(Locale.ROOT).ifBlank { "wav" }}"
            } else ""
        }
        val volumeUuid = extractVolumeUuid(path)
        val contentResolver = context.contentResolver

        // Build list of volume collection URIs to search across all mounted collections
        val collections = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val volumeNames = MediaStore.getExternalVolumeNames(context)
                for (volName in volumeNames) {
                    try {
                        collections.add(MediaStore.Audio.Media.getContentUri(volName))
                    } catch (_: Exception) {}
                }
                collections.add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            } catch (_: Exception) {
                collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            }
        } else {
            collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }
        val projection = projectionList.toTypedArray()

        val relPath = CanonicalStorageHelper.toStorageRelativePath(path).ifBlank { track.storageRelativePath }
        val parentDir = if (relPath.contains('/')) relPath.substringBeforeLast('/') + "/" else ""

        for (collectionUri in collections.distinct()) {
            // Strategy A: Exact DATA column match
            if (path.isNotBlank()) {
                try {
                    contentResolver.query(
                        collectionUri,
                        projection,
                        "${MediaStore.Audio.Media.DATA} = ?",
                        arrayOf(path),
                        null
                    )?.use { cursor ->
                        val uri = verifyAndExtractPlayableUri(context, cursor, collectionUri)
                        if (uri != null) return uri
                    }
                } catch (_: Throwable) {}
            }

            // Strategy B: Match by DISPLAY_NAME
            if (fileName.isNotBlank()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && parentDir.isNotBlank()) {
                    try {
                        contentResolver.query(
                            collectionUri,
                            projection,
                            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                            arrayOf(fileName, "%$parentDir%"),
                            null
                        )?.use { cursor ->
                            val uri = pickBestCursorMatch(context, cursor, collectionUri, track)
                            if (uri != null) return uri
                        }
                    } catch (_: Throwable) {}
                }

                try {
                    contentResolver.query(
                        collectionUri,
                        projection,
                        "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                        arrayOf(fileName),
                        null
                    )?.use { cursor ->
                        val uri = pickBestCursorMatch(context, cursor, collectionUri, track)
                        if (uri != null) return uri
                    }
                } catch (_: Throwable) {}
            }

            // Strategy C: Match by TITLE and ARTIST
            if (track.title.isNotBlank() && track.title != "<unknown>") {
                try {
                    val hasArtist = track.artist.isNotBlank() && track.artist != "Unknown Artist"
                    val sel = if (hasArtist) {
                        "${MediaStore.Audio.Media.TITLE} = ? AND ${MediaStore.Audio.Media.ARTIST} = ?"
                    } else {
                        "${MediaStore.Audio.Media.TITLE} = ?"
                    }
                    val args = if (hasArtist) arrayOf(track.title, track.artist) else arrayOf(track.title)

                    contentResolver.query(collectionUri, projection, sel, args, null)?.use { cursor ->
                        val uri = pickBestCursorMatch(context, cursor, collectionUri, track)
                        if (uri != null) return uri
                    }
                } catch (_: Throwable) {}
            }

            // Strategy D: DATA column suffix LIKE %/fileName
            if (fileName.isNotBlank()) {
                try {
                    contentResolver.query(
                        collectionUri,
                        projection,
                        "${MediaStore.Audio.Media.DATA} LIKE ?",
                        arrayOf("%/$fileName"),
                        null
                    )?.use { cursor ->
                        val uri = pickBestCursorMatch(context, cursor, collectionUri, track)
                        if (uri != null) return uri
                    }
                } catch (_: Throwable) {}
            }
        }

        return null
    }

    /**
     * Resolves a raw filesystem path into a MediaStore content URI if indexed.
     */
    fun findMediaStoreUriForPath(context: Context, filePath: String): String? {
        val clean = filePath.removePrefix("file://")
        val dummyTrack = Track(id = "", title = File(clean).nameWithoutExtension, artist = "", filePath = clean)
        return findMediaStoreUriForTrack(context, dummyTrack)?.toString()
    }

    /**
     * Searches persisted SAF directory trees for a matching DocumentFile.
     */
    fun findSafDocumentUriForTrack(context: Context, track: Track): Uri? {
        safDocumentUriFinderForTesting?.let { return it(context, track) }
        val path = track.filePath.removePrefix("file://")
        val relPath = CanonicalStorageHelper.toStorageRelativePath(path).ifBlank { track.storageRelativePath }
        val fileName = File(path).name.ifBlank {
            "${track.title}.${track.format.lowercase(Locale.ROOT).ifBlank { "wav" }}"
        }

        // Fast lookup via SafStorageManager
        val fastDoc = SafStorageManager.findDocumentForPathOrName(context, path, relPath)
        if (fastDoc != null && fastDoc.exists() && fastDoc.canRead() && isContentUriPlayable(context, fastDoc.uri)) {
            return fastDoc.uri
        }

        val persistedTrees = SafStorageManager.getPersistedWriteFolderUris(context)
        for (treeUri in persistedTrees) {
            val rootDoc = try { DocumentFile.fromTreeUri(context, treeUri) } catch (_: Throwable) { null }
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.canRead()) continue

            val rootName = rootDoc.name.orEmpty()
            val adjustedRelPath = if (rootName.isNotBlank() && relPath.startsWith(rootName, ignoreCase = true)) {
                relPath.removePrefix(rootName).trimStart('/')
            } else if (rootName.isNotBlank() && path.contains(rootName, ignoreCase = true)) {
                path.substringAfter(rootName).trimStart('/')
            } else {
                relPath
            }

            // 1. Try matching by adjusted relative path
            if (adjustedRelPath.isNotBlank()) {
                val doc = SafStorageManager.findDocumentByRelativePath(rootDoc, adjustedRelPath)
                if (doc != null && doc.exists() && doc.canRead() && isContentUriPlayable(context, doc.uri)) {
                    return doc.uri
                }
            }

            // 2. Try direct child file match in rootDoc
            if (fileName.isNotBlank()) {
                val directDoc = rootDoc.findFile(fileName)
                if (directDoc != null && directDoc.exists() && directDoc.canRead() && isContentUriPlayable(context, directDoc.uri)) {
                    return directDoc.uri
                }
            }

            // 3. Try matching by filename within shallow tree
            val docByName = SafStorageManager.findDocumentByName(rootDoc, fileName, maxDepth = 4)
            if (docByName != null && docByName.exists() && docByName.canRead() && isContentUriPlayable(context, docByName.uri)) {
                return docByName.uri
            }
        }
        return null
    }

    @androidx.annotation.VisibleForTesting
    internal val mountedVolumesOverrideForTesting = mutableMapOf<String, Boolean>()

    @androidx.annotation.VisibleForTesting
    var mediaStoreUriFinderForTesting: ((context: Context, track: Track) -> Uri?)? = null

    @androidx.annotation.VisibleForTesting
    var safDocumentUriFinderForTesting: ((context: Context, track: Track) -> Uri?)? = null

    @androidx.annotation.VisibleForTesting
    var contentUriPlayableCheckerForTesting: ((context: Context, uri: Uri) -> Boolean)? = null

    fun setVolumeMountedForTesting(uuid: String, isMounted: Boolean) {
        mountedVolumesOverrideForTesting[uuid.uppercase(Locale.ROOT)] = isMounted
    }

    fun clearVolumeOverridesForTesting() {
        mountedVolumesOverrideForTesting.clear()
        mediaStoreUriFinderForTesting = null
        safDocumentUriFinderForTesting = null
        contentUriPlayableCheckerForTesting = null
    }

    /**
     * Inspects the Android StorageManager for information about the volume holding [path].
     */
    fun getStorageVolumeForPath(context: Context, path: String): StorageVolumeInfo? {
        val clean = path.removePrefix("file://")
        val uuid = extractVolumeUuid(clean)

        if (uuid != null && mountedVolumesOverrideForTesting.containsKey(uuid.uppercase(Locale.ROOT))) {
            val isMounted = mountedVolumesOverrideForTesting[uuid.uppercase(Locale.ROOT)] == true
            return StorageVolumeInfo(
                uuid = uuid,
                description = "External Storage ($uuid)",
                isPrimary = false,
                isRemovable = true,
                state = if (isMounted) Environment.MEDIA_MOUNTED else Environment.MEDIA_UNMOUNTED,
                mountDirectory = File("/storage/$uuid"),
                isMounted = isMounted,
                isReadOnly = false
            )
        }

        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null) {
                for (vol in sm.storageVolumes) {
                    val volUuid = vol.uuid
                    if (uuid != null && volUuid != null && volUuid.equals(uuid, ignoreCase = true)) {
                        val state = vol.state
                        val isMounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
                        val isReadOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY
                        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else File("/storage/$volUuid")
                        return StorageVolumeInfo(
                            uuid = volUuid,
                            description = vol.getDescription(context) ?: "Storage Volume $volUuid",
                            isPrimary = vol.isPrimary,
                            isRemovable = vol.isRemovable,
                            state = state,
                            mountDirectory = dir,
                            isMounted = isMounted,
                            isReadOnly = isReadOnly
                        )
                    }
                }
            }
        } catch (_: Throwable) {}

        if (uuid != null) {
            val dir = File("/storage/$uuid")
            val exists = dir.exists()
            return StorageVolumeInfo(
                uuid = uuid,
                description = "External Storage ($uuid)",
                isPrimary = false,
                isRemovable = true,
                state = if (exists) Environment.MEDIA_MOUNTED else Environment.MEDIA_UNMOUNTED,
                mountDirectory = dir,
                isMounted = exists,
                isReadOnly = false
            )
        }

        return null
    }

    /**
     * Determines whether a removable storage volume is currently mounted.
     */
    fun isRemovableStorageVolumeMounted(context: Context, volumeUuid: String): Boolean {
        if (volumeUuid.isBlank()) return true
        if (mountedVolumesOverrideForTesting.containsKey(volumeUuid.uppercase(Locale.ROOT))) {
            return mountedVolumesOverrideForTesting[volumeUuid.uppercase(Locale.ROOT)] == true
        }
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null) {
                val match = sm.storageVolumes.firstOrNull { it.uuid?.equals(volumeUuid, ignoreCase = true) == true }
                if (match != null) {
                    val state = match.state
                    return state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
                }
            }
        } catch (_: Throwable) {}

        return File("/storage/$volumeUuid").exists()
    }

    /**
     * Handles card reinsertion under a different UUID or volume swap.
     */
    fun findReinsertedVolumeForTrack(context: Context, track: Track): Pair<StorageVolumeInfo, File>? {
        val oldPath = track.filePath.removePrefix("file://")
        val oldUuid = extractVolumeUuid(oldPath) ?: return null
        val relSubPath = oldPath.substringAfter("/storage/$oldUuid/").trimStart('/')
        if (relSubPath.isBlank()) return null

        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
            for (vol in sm.storageVolumes) {
                val currentUuid = vol.uuid ?: continue
                if (currentUuid.equals(oldUuid, ignoreCase = true)) continue
                val state = vol.state
                if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    val mountDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else File("/storage/$currentUuid")
                    if (mountDir != null) {
                        val candidateFile = File(mountDir, relSubPath)
                        if (candidateFile.exists()) {
                            val info = StorageVolumeInfo(
                                uuid = currentUuid,
                                description = vol.getDescription(context) ?: "Storage Volume $currentUuid",
                                isPrimary = vol.isPrimary,
                                isRemovable = vol.isRemovable,
                                state = state,
                                mountDirectory = mountDir,
                                isMounted = true,
                                isReadOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY
                            )
                            return Pair(info, candidateFile)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    /**
     * Called when the user grants SAF folder access for a removable storage volume.
     * Persists permission, recursively reconciles all affected tracks, updates source records,
     * and enables immediate playback retry.
     */
    suspend fun onRemovableStorageFolderGranted(
        context: Context,
        treeUri: Uri,
        trackDao: TrackDao
    ): FolderReconciliationSummary = withContext(Dispatchers.IO) {
        SafStorageManager.takePersistablePermissions(context, treeUri)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext FolderReconciliationSummary(0, 0, emptyList())

        val docId = extractDocumentIdFromUri(treeUri.toString()) ?: ""
        val volumeUuid = if (docId.contains(':')) docId.substringBefore(':') else null
        val folderSubPath = if (docId.contains(':')) docId.substringAfter(':') else ""

        val allTracks = try { trackDao.getAllTracksSync() } catch (_: Throwable) { emptyList() }
        val affectedTracks = allTracks.filter { entity ->
            val p = entity.filePath
            (volumeUuid != null && p.contains(volumeUuid, ignoreCase = true)) ||
            (volumeUuid != null && p.startsWith("/storage/$volumeUuid")) ||
            (folderSubPath.isNotBlank() && p.contains(folderSubPath, ignoreCase = true)) ||
            (rootDoc.name != null && p.contains(rootDoc.name!!, ignoreCase = true)) ||
            entity.playabilityStatus == PlayabilityStatus.PERMISSION_DENIED.name ||
            entity.playabilityStatus == PlayabilityStatus.PERMISSION_REQUIRED.name ||
            entity.playabilityStatus == PlayabilityStatus.READ_ERROR.name ||
            entity.playabilityStatus == PlayabilityStatus.VOLUME_UNAVAILABLE.name ||
            entity.playbackErrorCode == "ERR_SCOPED_STORAGE_RESTRICTION" ||
            entity.playbackErrorCode == "ERR_RAW_PATH_PERMISSION_BLOCKED" ||
            entity.playbackErrorCode == "ERR_IO_READ" ||
            entity.playbackErrorCode == "ERR_STORAGE_UNMOUNTED"
        }

        var reconciledCount = 0
        var failedCount = 0
        val updatedTracks = mutableListOf<TrackEntity>()

        for (entity in affectedTracks) {
            val fileName = File(entity.filePath.removePrefix("file://")).name
            val relPath = CanonicalStorageHelper.toStorageRelativePath(entity.filePath).ifBlank { entity.storageRelativePath }
            val rootName = rootDoc.name.orEmpty()
            val subRelPath = if (rootName.isNotBlank() && relPath.startsWith(rootName, ignoreCase = true)) {
                relPath.removePrefix(rootName).trimStart('/')
            } else if (rootName.isNotBlank() && entity.filePath.contains(rootName, ignoreCase = true)) {
                entity.filePath.substringAfter(rootName).trimStart('/')
            } else {
                relPath
            }

            val doc = (if (subRelPath.isNotBlank()) SafStorageManager.findDocumentByRelativePath(rootDoc, subRelPath) else null)
                ?: (if (fileName.isNotBlank()) rootDoc.findFile(fileName) else null)
                ?: SafStorageManager.findDocumentByName(rootDoc, fileName, maxDepth = 4)

            if (doc != null && doc.exists() && isContentUriPlayable(context, doc.uri)) {
                val docUriStr = doc.uri.toString()
                val updated = entity.copy(
                    filePath = docUriStr,
                    resolvedUri = docUriStr,
                    playabilityStatus = PlayabilityStatus.PLAYABLE.name,
                    playbackErrorCode = null,
                    playbackErrorMessage = null
                )
                trackDao.updateTrack(updated)
                updatedTracks.add(updated)
                reconciledCount++
            } else {
                failedCount++
            }
        }

        // Update SourceFolder in database
        val sourceFolder = SourceFolderEntity(
            id = "saf_removable_${volumeUuid ?: System.currentTimeMillis()}",
            label = rootDoc.name ?: "Removable Storage",
            path = treeUri.toString(),
            uriString = treeUri.toString(),
            typeName = StorageSourceType.SD_CARD.name,
            isOnline = true,
            trackCount = reconciledCount,
            freeSpaceGb = 32.0,
            totalSpaceGb = 64.0,
            lastScanned = System.currentTimeMillis()
        )
        try {
            AppDatabase.getDatabase(context).sourceFolderDao().insertSourceFolder(sourceFolder)
        } catch (_: Throwable) {}

        Log.i(TAG, "Reconciled $reconciledCount tracks on removable storage volume '$volumeUuid'")
        FolderReconciliationSummary(reconciledCount, failedCount, updatedTracks)
    }

    // ── Helper Utilities ────────────────────────────────────────────────────

    fun extractVolumeUuid(pathOrUri: String): String? {
        if (pathOrUri.isBlank()) return null
        val match = Regex("""/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?""").find(pathOrUri)
        if (match != null) {
            return match.groupValues[1]
        }
        val docId = extractDocumentIdFromUri(pathOrUri)
        if (docId != null && docId.contains(':')) {
            val vol = docId.substringBefore(':')
            if (vol.matches(Regex("""[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"""))) {
                return vol
            }
        }
        return null
    }

    private fun extractDocumentIdFromUri(uriString: String): String? {
        val docMatch = Regex("""/document/([^/?#]+)""").find(uriString)
        if (docMatch != null) {
            return try { java.net.URLDecoder.decode(docMatch.groupValues[1], "UTF-8") } catch (_: Exception) { docMatch.groupValues[1] }
        }
        val treeMatch = Regex("""/tree/([^/?#]+)""").find(uriString)
        if (treeMatch != null) {
            return try { java.net.URLDecoder.decode(treeMatch.groupValues[1], "UTF-8") } catch (_: Exception) { treeMatch.groupValues[1] }
        }
        return null
    }

    private fun isContentUriPlayable(context: Context, uri: Uri): Boolean {
        contentUriPlayableCheckerForTesting?.let { return it(context, uri) }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.fileDescriptor.valid()
            } ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.fileDescriptor.valid()
            } ?: context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(1)
                stream.read(buf) >= 0
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun getUriLength(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.coerceAtLeast(0L)
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun verifyAndExtractPlayableUri(context: Context, cursor: Cursor, collectionUri: Uri): Uri? {
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
        while (cursor.moveToNext()) {
            val id = if (idCol != -1) cursor.getLong(idCol) else -1L
            if (id > 0L) {
                val uri = ContentUris.withAppendedId(collectionUri, id)
                if (isContentUriPlayable(context, uri)) {
                    return uri
                }
            }
        }
        return null
    }

    private fun pickBestCursorMatch(context: Context, cursor: Cursor, collectionUri: Uri, track: Track): Uri? {
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
        val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = if (idCol != -1) cursor.getLong(idCol) else -1L
            val durationMs = if (durCol != -1) cursor.getLong(durCol) else 0L

            if (track.durationSeconds > 0 && durationMs > 0L) {
                val durationSec = (durationMs / 1000L).toInt()
                if (kotlin.math.abs(durationSec - track.durationSeconds) > 5) {
                    continue
                }
            }

            if (id > 0L) {
                val uri = ContentUris.withAppendedId(collectionUri, id)
                if (isContentUriPlayable(context, uri)) {
                    return uri
                }
            }
        }
        return null
    }

    private fun hasAudioReadPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_AUDIO") == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, "android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend fun persistHealedPathIfRequested(track: Track, newPath: String, persist: Boolean, trackDao: TrackDao?) {
        if (!persist || trackDao == null || track.id.isBlank()) return
        try {
            val entity = trackDao.getTrackById(track.id)
            if (entity != null) {
                trackDao.updateTrack(entity.copy(filePath = newPath, resolvedUri = newPath))
            } else {
                trackDao.updateFilePath(track.id, newPath)
            }
            Log.d(TAG, "Persisted healed path to database for track '${track.title}': '$newPath'")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist healed path to database: ${e.message}")
        }
    }

    /**
     * Groups library tracks with storage/permission issues by their removable storage volume UUID.
     * Allows UI to present single-grant folder authorization options for bulk track recovery.
     */
    fun getAffectedVolumesNeedingPermission(context: Context, tracks: List<Track>): List<VolumePermissionGroup> {
        val affected = tracks.filter { track ->
            val p = track.filePath
            val uuid = extractVolumeUuid(p)
            val isRemovable = uuid != null || StorageAvailabilityHelper.isExternalStoragePath(p)
            isRemovable && (
                track.hasPlaybackIssue ||
                track.playability == PlayabilityStatus.PERMISSION_DENIED ||
                track.playability == PlayabilityStatus.PERMISSION_REQUIRED ||
                track.playability == PlayabilityStatus.READ_ERROR ||
                track.playability == PlayabilityStatus.VOLUME_UNAVAILABLE ||
                track.playbackErrorCode == "ERR_SCOPED_STORAGE_RESTRICTION" ||
                track.playbackErrorCode == "ERR_RAW_PATH_PERMISSION_BLOCKED" ||
                track.playbackErrorCode == "ERR_IO_READ" ||
                !StorageAvailabilityHelper.isTrackPathAvailable(context, p)
            )
        }

        return affected.groupBy { extractVolumeUuid(it.filePath) ?: "removable" }
            .map { (uuid, group) ->
                VolumePermissionGroup(
                    volumeUuid = uuid,
                    folderPath = "/storage/$uuid",
                    displayName = "Removable Volume $uuid",
                    affectedTracks = group
                )
            }
    }
}
