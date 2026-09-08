package com.example.storage

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Stage 6 & 7: Hard File Deletion Guard and Forensic Logger.
 *
 * Centralized gatekeeper for all physical file mutations and deletions.
 * Guarantees that:
 * 1. Background scans, metadata processing, duplicate detection, and automated workers
 *    CAN NEVER physically delete user audio files.
 * 2. Real audio file deletions REQUIRE explicit user confirmation (EXPLICIT_USER_ACTION).
 * 3. Temporary file deletions are strictly validated to prevent accidental deletion of actual audio files.
 * 4. All physical mutation and deletion operations are recorded in an audit log for diagnostics.
 */

enum class DeletionReason(val description: String) {
    EXPLICIT_USER_ACTION("Explicit user action with UI confirmation"),
    TEMPORARY_STAGING_CLEANUP("Safe cleanup of ephemeral staging or temporary file"),
    SYSTEM_TEST_FIXTURE("Automated test fixture cleanup"),
    UNAUTHORIZED_OR_AUTOMATED("Unauthorized or automated caller (STRICTLY PROHIBITED)")
}

sealed interface FileDeletionResult {
    data class Success(val path: String, val freedBytes: Long, val message: String) : FileDeletionResult
    data class Blocked(val path: String, val reason: String, val caller: String) : FileDeletionResult
    data class Failed(val path: String, val error: String) : FileDeletionResult
}

data class ForensicLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val operation: String,
    val path: String,
    val caller: String,
    val reason: DeletionReason,
    val outcome: String,
    val details: String = ""
)

object ForensicFileLogger {
    private const val TAG = "SoundSyncForensic"
    private const val MAX_LOG_ENTRIES = 200
    private val logEntries = ConcurrentLinkedDeque<ForensicLogEntry>()

    fun logEvent(
        operation: String,
        path: String,
        caller: String,
        reason: DeletionReason,
        outcome: String,
        details: String = ""
    ) {
        val entry = ForensicLogEntry(
            operation = operation,
            path = path,
            caller = caller,
            reason = reason,
            outcome = outcome,
            details = details
        )
        logEntries.addFirst(entry)
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.pollLast()
        }

        val msg = "[$operation] path='$path', caller='$caller', reason='${reason.name}', outcome='$outcome' $details"
        if (outcome.startsWith("BLOCKED") || outcome.startsWith("FAILED")) {
            Log.e(TAG, msg)
        } else {
            Log.i(TAG, msg)
        }
    }

    fun getRecentLogs(): List<ForensicLogEntry> = logEntries.toList()

    fun clear() {
        logEntries.clear()
    }
}

object FileDeletionGuard {

    private const val TAG = "FileDeletionGuard"

    // Recognized audio formats that must NEVER be treated as disposable temporary files
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma", "alac"
    )

    /**
     * Checks whether a file path points to an actual audio file based on extension.
     */
    fun isAudioFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    /**
     * Strictly verifies whether a file qualifies as a disposable temporary/staging file.
     */
    fun isValidTempFile(file: File): Boolean {
        val name = file.name.lowercase()
        val path = file.absolutePath.lowercase()

        // Real audio files are NEVER temporary files!
        if (isAudioFile(file)) {
            return false
        }

        // Must match known temp patterns or be inside temp/cache directories
        val isTmpExtension = name.endsWith(".tmp") || name.endsWith(".bak") || name.contains(".art.tmp")
        val isStagingPrefix = name.startsWith("ss_tag_") || name.startsWith(".")
        val isInCache = path.contains("/cache/") || path.contains("/soundsync_staging/")

        return isTmpExtension || (isInCache && isStagingPrefix)
    }

    /**
     * Safely deletes an ephemeral staging/temp file.
     * Guarantees that real audio files cannot be deleted through this method.
     */
    fun deleteTempFile(file: File?, caller: String = "Unknown"): Boolean {
        if (file == null || !file.exists()) return true

        if (!isValidTempFile(file)) {
            ForensicFileLogger.logEvent(
                operation = "TEMP_CLEANUP",
                path = file.absolutePath,
                caller = caller,
                reason = DeletionReason.TEMPORARY_STAGING_CLEANUP,
                outcome = "BLOCKED_NOT_A_TEMP_FILE",
                details = "Refused to delete file that does not match temporary file safety criteria."
            )
            Log.e(TAG, "CRITICAL: Refused to delete non-temp file via deleteTempFile: ${file.absolutePath}")
            return false
        }

        val size = file.length()
        val deleted = try {
            file.delete()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed deleting temp file ${file.absolutePath}: ${e.message}")
            false
        }

        ForensicFileLogger.logEvent(
            operation = "TEMP_CLEANUP",
            path = file.absolutePath,
            caller = caller,
            reason = DeletionReason.TEMPORARY_STAGING_CLEANUP,
            outcome = if (deleted) "SUCCESS" else "FAILED",
            details = "freedBytes=$size"
        )
        return deleted
    }

    /**
     * Centrally guarded audio file deletion.
     * Requires explicit user intent. Rejects any automated or unapproved deletion attempts.
     */
    suspend fun deleteAudioFile(
        file: File,
        reason: DeletionReason,
        caller: String
    ): FileDeletionResult = withContext(Dispatchers.IO) {
        val path = file.absolutePath

        // Hard Block: Automated or unauthorized deletion attempts
        if (reason == DeletionReason.UNAUTHORIZED_OR_AUTOMATED) {
            ForensicFileLogger.logEvent(
                operation = "DELETE_AUDIO_FILE",
                path = path,
                caller = caller,
                reason = reason,
                outcome = "BLOCKED_UNAUTHORIZED",
                details = "Automated/background audio file deletion is strictly prohibited."
            )
            return@withContext FileDeletionResult.Blocked(
                path = path,
                reason = "Automated background file deletion is strictly forbidden in SoundSync.",
                caller = caller
            )
        }

        // Verify reason is permitted
        if (reason != DeletionReason.EXPLICIT_USER_ACTION && reason != DeletionReason.SYSTEM_TEST_FIXTURE) {
            ForensicFileLogger.logEvent(
                operation = "DELETE_AUDIO_FILE",
                path = path,
                caller = caller,
                reason = reason,
                outcome = "BLOCKED_INVALID_REASON",
                details = "Reason must be EXPLICIT_USER_ACTION or SYSTEM_TEST_FIXTURE."
            )
            return@withContext FileDeletionResult.Blocked(
                path = path,
                reason = "Physical deletion requires EXPLICIT_USER_ACTION.",
                caller = caller
            )
        }

        if (!file.exists()) {
            ForensicFileLogger.logEvent(
                operation = "DELETE_AUDIO_FILE",
                path = path,
                caller = caller,
                reason = reason,
                outcome = "SUCCESS_ALREADY_NONEXISTENT",
                details = "File did not exist on filesystem."
            )
            return@withContext FileDeletionResult.Success(path, 0L, "File does not exist.")
        }

        // Protect demo tracks from accidental deletion
        if (path.contains("/Music/Tech House/") || path.startsWith("demo://")) {
            ForensicFileLogger.logEvent(
                operation = "DELETE_AUDIO_FILE",
                path = path,
                caller = caller,
                reason = reason,
                outcome = "BLOCKED_SYSTEM_DEMO_TRACK",
                details = "Protected demo track cannot be deleted."
            )
            return@withContext FileDeletionResult.Blocked(
                path = path,
                reason = "Demo track is protected and cannot be deleted.",
                caller = caller
            )
        }

        return@withContext FileLockManager.withFileLock(path) {
            val size = file.length()
            val deleted = try {
                file.delete()
            } catch (e: Throwable) {
                ForensicFileLogger.logEvent(
                    operation = "DELETE_AUDIO_FILE",
                    path = path,
                    caller = caller,
                    reason = reason,
                    outcome = "FAILED",
                    details = "Exception: ${e.message}"
                )
                return@withFileLock FileDeletionResult.Failed(path, e.message ?: "File deletion failed")
            }

            if (deleted) {
                ForensicFileLogger.logEvent(
                    operation = "DELETE_AUDIO_FILE",
                    path = path,
                    caller = caller,
                    reason = reason,
                    outcome = "SUCCESS",
                    details = "Deleted $size bytes"
                )
                FileDeletionResult.Success(path, size, "Physical file deleted successfully.")
            } else {
                ForensicFileLogger.logEvent(
                    operation = "DELETE_AUDIO_FILE",
                    path = path,
                    caller = caller,
                    reason = reason,
                    outcome = "FAILED",
                    details = "file.delete() returned false (file locked or read-only mount)"
                )
                FileDeletionResult.Failed(path, "Failed to delete file on storage (permission or read-only filesystem).")
            }
        }
    }

    /**
     * Centrally deletes both the physical file and the track's database record
     * after explicit user confirmation.
     */
    suspend fun deleteAudioTrack(
        context: Context,
        database: AppDatabase,
        track: Track,
        reason: DeletionReason,
        caller: String
    ): FileDeletionResult = withContext(Dispatchers.IO) {
        val path = track.filePath

        if (path.startsWith("demo://")) {
            return@withContext FileDeletionResult.Blocked(path, "Demo tracks cannot be physically deleted.", caller)
        }

        val file = File(path)
        val fileResult = deleteAudioFile(file, reason, caller)

        if (fileResult is FileDeletionResult.Blocked) {
            return@withContext fileResult
        }

        // Only remove database records once physical file deletion was processed (or file already gone)
        try {
            database.trackDao().deleteTrackById(track.id)
            database.playlistDao().deleteTrackFromPlaylist("%", track.id)
            Log.i(TAG, "Removed track '${track.title}' (id=${track.id}) from database after physical file deletion.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing track record from database: ${e.message}", e)
        }

        fileResult
    }
}
