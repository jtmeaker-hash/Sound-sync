package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed interface FileOperationResult {
    data class Success(val newPath: String, val message: String) : FileOperationResult
    data class Failure(val error: String) : FileOperationResult
}

object SafeFileManager {

    private const val TAG = "SafeFileManager"

    /**
     * Safely renames a track's audio file on disk, atomically updating the database path
     * while completely preserving the track ID, metadata history, analysis, ratings, and playlist ties.
     */
    suspend fun renameTrackFile(
        context: Context,
        database: AppDatabase,
        track: TrackEntity,
        newFileName: String
    ): FileOperationResult = withContext(Dispatchers.IO) {
        val originalPath = track.filePath
        if (originalPath.isBlank()) return@withContext FileOperationResult.Failure("Track has empty file path.")
        if (originalPath.startsWith("content://")) {
            return@withContext FileOperationResult.Failure("DocumentProvider/SAF renaming requires DocumentFile contract.")
        }

        val originalFile = File(originalPath)
        if (!originalFile.exists()) {
            return@withContext FileOperationResult.Failure("Physical file does not exist at: $originalPath")
        }

        val parentDir = originalFile.parentFile ?: return@withContext FileOperationResult.Failure("Cannot resolve parent directory.")
        val extension = originalFile.extension
        val cleanName = if (newFileName.endsWith(".$extension", ignoreCase = true)) {
            newFileName
        } else {
            "$newFileName.$extension"
        }

        val targetFile = File(parentDir, cleanName)
        if (targetFile.exists() && targetFile.absolutePath != originalFile.absolutePath) {
            return@withContext FileOperationResult.Failure("A file named '$cleanName' already exists in this folder.")
        }

        // 1. Rename physical file
        val renamed = originalFile.renameTo(targetFile)
        if (!renamed) {
            return@withContext FileOperationResult.Failure("Failed to rename physical file on filesystem (permission or lock).")
        }

        // 2. Physical rename succeeded; now update database path
        try {
            val updatedTrack = track.copy(
                filePath = targetFile.absolutePath,
                fileModifiedTimestamp = targetFile.lastModified()
            )
            database.trackDao().updateTrack(updatedTrack)
            Log.i(TAG, "Renamed file from $originalPath to ${targetFile.absolutePath} for track ${track.id}")
            FileOperationResult.Success(targetFile.absolutePath, "File renamed successfully.")
        } catch (e: Exception) {
            // Rollback physical rename if DB update fails to ensure consistency
            targetFile.renameTo(originalFile)
            Log.e(TAG, "DB update failed during rename; rolled back physical file rename.", e)
            FileOperationResult.Failure("Database update failed: ${e.message}")
        }
    }

    /**
     * Safely moves a track file to a target directory, updating DB and preserving identity.
     */
    suspend fun moveTrackFile(
        context: Context,
        database: AppDatabase,
        track: TrackEntity,
        targetDirectory: File
    ): FileOperationResult = withContext(Dispatchers.IO) {
        val originalPath = track.filePath
        val sourceFile = File(originalPath)
        if (!sourceFile.exists()) {
            return@withContext FileOperationResult.Failure("Source file does not exist: $originalPath")
        }

        if (!targetDirectory.exists()) {
            val created = targetDirectory.mkdirs()
            if (!created && !targetDirectory.exists()) {
                return@withContext FileOperationResult.Failure("Failed to create target directory: ${targetDirectory.absolutePath}")
            }
        }

        val destinationFile = File(targetDirectory, sourceFile.name)
        if (destinationFile.exists()) {
            return@withContext FileOperationResult.Failure("File already exists in target destination: ${destinationFile.absolutePath}")
        }

        val moved = sourceFile.renameTo(destinationFile)
        if (!moved) {
            // Fallback to copy and delete if across different mount points
            val copied = copyFileAtomic(sourceFile, destinationFile)
            if (!copied) {
                return@withContext FileOperationResult.Failure("Failed to move file to ${destinationFile.absolutePath}")
            }
            sourceFile.delete()
        }

        try {
            val updated = track.copy(
                filePath = destinationFile.absolutePath,
                fileModifiedTimestamp = destinationFile.lastModified()
            )
            database.trackDao().updateTrack(updated)
            Log.i(TAG, "Moved track ${track.id} to ${destinationFile.absolutePath}")
            FileOperationResult.Success(destinationFile.absolutePath, "File moved successfully.")
        } catch (e: Exception) {
            // Rollback
            destinationFile.renameTo(sourceFile)
            FileOperationResult.Failure("Database update failed: ${e.message}")
        }
    }

    /**
     * Copies track file to a destination folder without altering the track's original DB record.
     */
    suspend fun copyTrackFile(
        track: TrackEntity,
        targetDirectory: File
    ): FileOperationResult = withContext(Dispatchers.IO) {
        val sourceFile = File(track.filePath)
        if (!sourceFile.exists()) {
            return@withContext FileOperationResult.Failure("Source file does not exist.")
        }

        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val destFile = File(targetDirectory, sourceFile.name)
        val copied = copyFileAtomic(sourceFile, destFile)
        if (copied) {
            FileOperationResult.Success(destFile.absolutePath, "File copied successfully.")
        } else {
            FileOperationResult.Failure("Failed to copy file.")
        }
    }

    /**
     * Deletes the physical file and removes or marks track missing.
     */
    suspend fun deleteTrackFile(
        database: AppDatabase,
        track: TrackEntity,
        removeFromDatabase: Boolean = true
    ): FileOperationResult = withContext(Dispatchers.IO) {
        val sourceFile = File(track.filePath)
        val deleted = if (sourceFile.exists()) sourceFile.delete() else true

        if (!deleted) {
            return@withContext FileOperationResult.Failure("Failed to delete physical file: ${track.filePath}")
        }

        if (removeFromDatabase) {
            database.trackDao().deleteTrackById(track.id)
            // Also clean playlist references
            database.playlistDao().deleteTrackFromPlaylist("%", track.id)
        }
        FileOperationResult.Success("", "Track and file deleted.")
    }

    private fun copyFileAtomic(src: File, dst: File): Boolean {
        return try {
            FileInputStream(src).use { inStream ->
                FileOutputStream(dst).use { outStream ->
                    val buf = ByteArray(65536)
                    var bytesRead: Int
                    while (inStream.read(buf).also { bytesRead = it } > 0) {
                        outStream.write(buf, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: ${e.message}", e)
            if (dst.exists()) dst.delete()
            false
        }
    }
}
