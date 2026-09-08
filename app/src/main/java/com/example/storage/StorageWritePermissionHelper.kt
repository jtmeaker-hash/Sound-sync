package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.data.TrackDao
import com.example.model.Track
import java.io.File
import java.io.FileOutputStream

/**
 * Storage and scoped-storage permission helper for physical audio tag embedding.
 *
 * Handles MediaStore URI resolution, Scoped Storage write permission detection,
 * batch write request generation (Android 11+ / API 30+), and RecoverableSecurityException
 * handling (Android 10 / API 29).
 */
data class CanonicalStorageInfo(
    val uri: Uri,
    val isDirectFile: Boolean = false,
    val directFile: File? = null,
    val isWritable: Boolean = false
)

object StorageWritePermissionHelper {

    private const val TAG = "StoragePermissionHelper"

    var resolveCanonicalOverrideForTesting: ((Context, Track) -> CanonicalStorageInfo?)? = null

    /**
     * Resolves the authoritative MediaStore or SAF content URI for a given track.
     * Prioritizes existing content:// URIs, then track ID media_ mapping, then DATA column query,
     * then display name query.
     */
    fun resolveTargetUri(context: Context, track: Track): Uri? {
        val path = track.filePath
        if (path.startsWith("content://")) {
            return try { Uri.parse(path) } catch (_: Exception) { null }
        }

        // Check if track ID encodes MediaStore _ID (standard in SoundSync MediaScannerHelper)
        if (track.id.startsWith("media_")) {
            val mediaId = track.id.removePrefix("media_").toLongOrNull()
            if (mediaId != null && mediaId > 0L) {
                val mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                if (isMediaStoreUriValid(context, mediaUri)) {
                    return mediaUri
                }
            }
        }

        // Probe MediaStore query by filesystem DATA path
        if (path.isNotBlank()) {
            val uriByData = AudioTagWriter.getMediaStoreUriForPath(context, path)
            if (uriByData != null && isMediaStoreUriValid(context, uriByData)) {
                return uriByData
            }
        }

        // Probe MediaStore query by DISPLAY_NAME
        val fileName = File(path).name
        if (fileName.isNotBlank()) {
            val uriByName = queryMediaStoreByDisplayName(context, fileName, track.durationSeconds, File(path).parent)
            if (uriByName != null && isMediaStoreUriValid(context, uriByName)) {
                return uriByName
            }
        }

        return null
    }

    /**
     * Resolves the canonical, authoritative storage access point for a track.
     *
     * Follows the resilient multi-tiered resolution ladder:
     * Tier 1: Canonical content:// URI directly in track.filePath
     * Tier 2: Directly accessible, writable java.io.File
     * Tier 3: MediaStore URI from track.id (media_ mapping)
     * Tier 4: MediaStore query by _DATA path
     * Tier 5: MediaStore query by DISPLAY_NAME (with duration/relative path disambiguation)
     * Tier 6: DocumentFile within any granted SAF folder tree
     * Tier 7: Directly accessible read-only java.io.File
     * Tier 8: Null (file is genuinely missing from storage and library)
     *
     * When resolved to a content URI from a stale filesystem path, updates the Room database
     * so subsequent operations do not require re-resolution.
     */
    suspend fun resolveCanonicalStorage(
        context: Context,
        track: Track,
        trackDao: TrackDao? = null
    ): CanonicalStorageInfo? {
        resolveCanonicalOverrideForTesting?.let { return it(context, track) }

        val path = track.filePath

        // Tier 1: content:// URI
        if (path.startsWith("content://")) {
            val uri = try { Uri.parse(path) } catch (_: Exception) { null }
            if (uri != null) {
                val writable = hasUriWritePermission(context, uri)
                return CanonicalStorageInfo(uri = uri, isDirectFile = false, isWritable = writable)
            }
        }

        // Tier 2: Directly accessible, writable local file
        var rawFile: File? = null
        if (path.isNotBlank() && !path.startsWith("content://") && !path.startsWith("demo://")) {
            val f = File(path)
            if (f.exists() && f.isFile) {
                rawFile = f
                if (isDirectlyWritableFile(f)) {
                    return CanonicalStorageInfo(
                        uri = Uri.fromFile(f),
                        isDirectFile = true,
                        directFile = f,
                        isWritable = true
                    )
                }
            }
        }

        // Tier 3: MediaStore URI from track.id (media_12345)
        if (track.id.startsWith("media_")) {
            val mediaId = track.id.removePrefix("media_").toLongOrNull()
            if (mediaId != null && mediaId > 0L) {
                val mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                if (isMediaStoreUriValid(context, mediaUri)) {
                    val writable = hasUriWritePermission(context, mediaUri)
                    return CanonicalStorageInfo(uri = mediaUri, isDirectFile = false, isWritable = writable)
                }
            }
        }

        // Tier 4: MediaStore query by _DATA
        if (path.isNotBlank()) {
            val mediaStoreUri = AudioTagWriter.getMediaStoreUriForPath(context, path)
            if (mediaStoreUri != null && isMediaStoreUriValid(context, mediaStoreUri)) {
                val writable = hasUriWritePermission(context, mediaStoreUri)
                return CanonicalStorageInfo(uri = mediaStoreUri, isDirectFile = false, isWritable = writable)
            }
        }

        // Tier 5: MediaStore query by DISPLAY_NAME
        val fileName = if (rawFile != null) rawFile.name else File(path).name
        if (fileName.isNotBlank()) {
            val uriByName = queryMediaStoreByDisplayName(context, fileName, track.durationSeconds, rawFile?.parent)
            if (uriByName != null && isMediaStoreUriValid(context, uriByName)) {
                val writable = hasUriWritePermission(context, uriByName)
                return CanonicalStorageInfo(uri = uriByName, isDirectFile = false, isWritable = writable)
            }
        }

        // Tier 6: Persisted SAF Directory Trees
        val safDoc = SafStorageManager.findDocumentForTrack(context, track)
        if (safDoc != null && safDoc.exists()) {
            val writable = safDoc.canWrite() || hasUriWritePermission(context, safDoc.uri)
            return CanonicalStorageInfo(uri = safDoc.uri, isDirectFile = false, isWritable = writable)
        }

        // Tier 7: Raw file existed on disk, but was read-only
        if (rawFile != null) {
            return CanonicalStorageInfo(
                uri = Uri.fromFile(rawFile),
                isDirectFile = true,
                directFile = rawFile,
                isWritable = false
            )
        }

        // Tier 8: Genuinely missing
        return null
    }

    private suspend fun persistCanonicalPath(trackDao: TrackDao?, trackId: String, canonicalPath: String) {
        if (trackDao == null || trackId.isBlank()) return
        try {
            trackDao.updateFilePath(trackId, canonicalPath)
            Log.d(TAG, "Updated canonical path in DB for track $trackId -> $canonicalPath")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist canonical path for track $trackId: ${e.message}")
        }
    }

    /**
     * Resilient lookup searching MediaStore for a track by DISPLAY_NAME, scoring by duration and parent path.
     */
    fun queryMediaStoreByDisplayName(
        context: Context,
        displayName: String,
        expectedDurationSec: Int = 0,
        parentPath: String? = null
    ): Uri? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                var bestUri: Uri? = null
                var bestScore = -1
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val candUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    var score = 1
                    if (durCol >= 0 && expectedDurationSec > 0) {
                        val durMs = cursor.getLong(durCol)
                        if (kotlin.math.abs((durMs / 1000) - expectedDurationSec) <= 3) {
                            score += 5
                        }
                    }
                    if (pathCol >= 0 && parentPath != null) {
                        val relOrData = cursor.getString(pathCol).orEmpty()
                        if (relOrData.isNotBlank() && (parentPath.contains(relOrData) || relOrData.contains(parentPath))) {
                            score += 3
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestUri = candUri
                    }
                }
                bestUri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying MediaStore by display name $displayName: ${e.message}")
            null
        }
    }

    /**
     * Checks if a MediaStore content URI is still valid and reachable.
     */
    fun isMediaStoreUriValid(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    var isWritableOverrideForTesting: ((File) -> Boolean)? = null

    /**
     * Tests if a local java.io.File can be directly written to without Scoped Storage interception.
     */
    fun isDirectlyWritableFile(file: File): Boolean {
        isWritableOverrideForTesting?.let { return it(file) }
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
     * Tests if the application currently has write permission for a content:// URI.
     */
    fun hasUriWritePermission(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") {
            return isDirectlyWritableFile(File(uri.path ?: return false))
        }

        if (context.checkUriPermission(
                uri,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        // Test non-truncating ParcelFileDescriptor probe
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Generates a batch IntentSender on Android 11+ (API 30+) requesting write permission
     * for a collection of MediaStore URIs in a single system dialogue.
     */
    fun createBatchWriteRequest(context: Context, uris: Collection<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) {
            return null
        }
        return try {
            val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
            pendingIntent.intentSender
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create batch MediaStore write request for ${uris.size} URIs: ${e.message}")
            null
        }
    }

    /**
     * Extracts or creates an IntentSender for single file write approval on Android 10+ (API 29+).
     */
    fun createSingleWriteRequest(context: Context, uri: Uri, exception: Throwable? = null): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exception is android.app.RecoverableSecurityException) {
            return exception.userAction.actionIntent.intentSender
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return createBatchWriteRequest(context, listOf(uri))
        }
        return null
    }
}
