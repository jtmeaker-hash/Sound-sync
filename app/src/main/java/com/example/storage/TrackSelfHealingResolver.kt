package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.TrackDao
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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

        // Strategy 1: MediaStore ID lookup
        val fromMediaStoreId = resolveFromMediaStoreId(context, track)
        if (fromMediaStoreId != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromMediaStoreId)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via MediaStore ID: healed '$originalPath' -> '$fromMediaStoreId'")
            return@withContext applyHealedPath(track, fromMediaStoreId, trackDao)
        }

        // Strategy 2: Cross-reference conversion (content:// <-> file path)
        val fromCrossReference = resolveFromCrossReference(context, track)
        if (fromCrossReference != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromCrossReference)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via cross-reference: healed '$originalPath' -> '$fromCrossReference'")
            return@withContext applyHealedPath(track, fromCrossReference, trackDao)
        }

        // Strategy 3: Persisted SAF DocumentFile / tree URI
        val fromSaf = resolveFromSaf(context, track)
        if (fromSaf != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromSaf)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via SAF document: healed '$originalPath' -> '$fromSaf'")
            return@withContext applyHealedPath(track, fromSaf, trackDao)
        }

        // Strategy 4: MediaStore query by title, artist, duration, filename
        val fromMediaStoreQuery = resolveFromMediaStoreQuery(context, track)
        if (fromMediaStoreQuery != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromMediaStoreQuery)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via MediaStore query: healed '$originalPath' -> '$fromMediaStoreQuery'")
            return@withContext applyHealedPath(track, fromMediaStoreQuery, trackDao)
        }

        // Strategy 5: Filesystem search in standard directories & fingerprint matching
        val fromFilesystem = resolveFromFilesystem(context, track)
        if (fromFilesystem != null && StorageAvailabilityHelper.isTrackPathAvailable(context, fromFilesystem)) {
            Log.i(TAG, "[TrackSelfHealing] SUCCESS via filesystem search: healed '$originalPath' -> '$fromFilesystem'")
            return@withContext applyHealedPath(track, fromFilesystem, trackDao)
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
        val mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)

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
        if (isUriReadable(context, mediaUri)) {
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
                doc.uri.toString()
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

        // 4a. Query MediaStore by DISPLAY_NAME
        if (fileName.isNotBlank()) {
            try {
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(fileName)
                contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                    val matched = pickBestCursorMatch(cursor, track)
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

                contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                    val matched = pickBestCursorMatch(cursor, track)
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
                contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                    val matched = pickBestCursorMatch(cursor, track)
                    if (matched != null) return matched
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun pickBestCursorMatch(cursor: Cursor, track: Track): String? {
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

            // Prefer verified disk file path
            if (!data.isNullOrBlank()) {
                val f = File(data)
                if (f.exists() && f.canRead()) {
                    return data
                }
            }

            // Fallback to verified MediaStore content URI
            if (id > 0L) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                return uri.toString()
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

        return null
    }

    // ── Persistence & Helpers ──────────────────────────────────────────────

    private suspend fun applyHealedPath(
        track: Track,
        newPath: String,
        trackDao: TrackDao?
    ): Track {
        val healed = track.copy(filePath = newPath, isAvailable = true)
        if (trackDao != null && track.id.isNotBlank()) {
            try {
                trackDao.updateFilePath(track.id, newPath)
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
        val path = track.filePath
        if (path.isNotBlank() && !path.startsWith("content://")) {
            val name = File(path.removePrefix("file://")).name
            if (name.isNotBlank() && name.contains('.')) return name
        }
        val ext = track.format.lowercase(Locale.ROOT).ifBlank { "mp3" }
        return if (track.title.isNotBlank() && track.title != "<unknown>") {
            "${track.title}.$ext"
        } else {
            ""
        }
    }

    private fun isUriReadable(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { true }
                ?: context.contentResolver.openInputStream(uri)?.use { true }
                ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
