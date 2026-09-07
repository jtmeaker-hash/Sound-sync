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
object StorageWritePermissionHelper {

    private const val TAG = "StoragePermissionHelper"

    /**
     * Resolves the authoritative MediaStore or SAF content URI for a given track.
     * Prioritizes existing content:// URIs, then track ID media_ mapping, then DATA column query.
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
                return mediaUri
            }
        }

        // Probe MediaStore query by filesystem DATA path
        if (path.isNotBlank()) {
            return AudioTagWriter.getMediaStoreUriForPath(context, path)
        }

        return null
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
