package com.example.storage

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import com.example.data.WatchedFolderEntity
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class WatchedFolderManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private val folderDao = database.watchedFolderDao()
    private val trackDao = database.trackDao()

    companion object {
        private const val TAG = "WatchedFolderManager"
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "aiff")
    }

    fun observeWatchedFolders(): Flow<List<WatchedFolderEntity>> {
        return folderDao.observeAllFolders()
    }

    suspend fun addFolder(
        folderPath: String,
        displayName: String,
        includeSubfolders: Boolean = true,
        autoScanNewFiles: Boolean = true,
        autoAnalyzeMetadata: Boolean = true,
        autoFingerprint: Boolean = true,
        autoAnalyzeBpmKey: Boolean = true,
        autoFetchArtwork: Boolean = true,
        ignoredExtensions: String = "tmp,bak"
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val entity = WatchedFolderEntity(
            id = id,
            folderPathOrUri = folderPath,
            displayName = displayName,
            includeSubfolders = includeSubfolders,
            autoScanNewFiles = autoScanNewFiles,
            autoAnalyzeMetadata = autoAnalyzeMetadata,
            autoFingerprint = autoFingerprint,
            autoAnalyzeBpmKey = autoAnalyzeBpmKey,
            autoFetchArtwork = autoFetchArtwork,
            ignoredExtensions = ignoredExtensions,
            lastScannedTimestamp = 0L,
            isEnabled = true
        )
        folderDao.insertFolder(entity)
        id
    }

    suspend fun removeFolder(folderId: String) = withContext(Dispatchers.IO) {
        folderDao.deleteFolder(folderId)
    }

    suspend fun updateFolder(folder: WatchedFolderEntity) = withContext(Dispatchers.IO) {
        folderDao.updateFolder(folder)
    }

    /**
     * Incrementally scans a single watched folder without rescanning the whole library.
     */
    suspend fun scanWatchedFolder(folderId: String): Int = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext 0
        if (!folder.isEnabled) return@withContext 0

        val rootDir = File(folder.folderPathOrUri)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            Log.w(TAG, "Watched folder does not exist: ${folder.folderPathOrUri}")
            return@withContext 0
        }

        val ignoredSet = folder.ignoredExtensions.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val foundFiles = mutableListOf<File>()
        collectFiles(rootDir, folder.includeSubfolders, ignoredSet, foundFiles)

        val existingTracks = trackDao.getAllTracksSync()
        val existingPathMap = existingTracks.associateBy { it.filePath }
        val existingFingerprintMap = existingTracks.filter { it.contentFingerprint.isNotBlank() }
            .associateBy { it.contentFingerprint }

        var addedCount = 0

        for (file in foundFiles) {
            val existing = existingPathMap[file.absolutePath]
            if (existing != null) {
                continue // Already indexed at this exact path
            }

            // Generate quick fingerprint to check if this is a moved or renamed file
            val fingerprint = if (folder.autoFingerprint) {
                AudioFingerprintUtil.generateFingerprint(context, file.absolutePath, file.length(), 0)
            } else ""

            val movedMatch: TrackEntity? = if (fingerprint.isNotBlank()) existingFingerprintMap[fingerprint] else null

            if (movedMatch != null) {
                // Moved file detected! Update filePath while preserving track ID and data!
                Log.i(TAG, "Detected moved file: ${movedMatch.filePath} -> ${file.absolutePath}")
                trackDao.updateTrack(
                    movedMatch.copy(
                        filePath = file.absolutePath,
                        fileModifiedTimestamp = file.lastModified()
                    )
                )
            } else if (folder.autoScanNewFiles) {
                // New file found! Extract metadata and insert
                val meta = AudioEmbeddedMetadataReader.read(context, file.absolutePath)
                val titleStr = meta.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                val artistStr = meta.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                val albumStr = meta.album?.takeIf { it.isNotBlank() } ?: "Single"
                val genreStr = meta.genre?.takeIf { it.isNotBlank() } ?: "General"

                val newTrack = TrackEntity(
                    id = UUID.randomUUID().toString(),
                    title = titleStr,
                    artist = artistStr,
                    album = albumStr,
                    genre = genreStr,
                    durationSeconds = meta.durationSeconds,
                    bitrateKbps = meta.bitrateKbps,
                    format = file.extension.uppercase(),
                    fileSizeMb = file.length() / (1024.0 * 1024.0),
                    filePath = file.absolutePath,
                    contentFingerprint = fingerprint,
                    fingerprintAlgorithm = if (fingerprint.isNotBlank()) "SOUNDSYNC_SHA256" else null,
                    fingerprintTimestamp = if (fingerprint.isNotBlank()) System.currentTimeMillis() else null,
                    dateAdded = System.currentTimeMillis(),
                    fileModifiedTimestamp = file.lastModified()
                )
                trackDao.insertTrack(newTrack)
                addedCount++
            }
        }

        folderDao.updateLastScanned(folder.id, System.currentTimeMillis())
        Log.i(TAG, "Completed scan for watched folder '${folder.displayName}': added $addedCount new tracks.")
        addedCount
    }

    private fun collectFiles(
        dir: File,
        recursive: Boolean,
        ignoredExtensions: Set<String>,
        accumulator: MutableList<File>
    ) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.isDirectory && recursive) {
                collectFiles(entry, recursive, ignoredExtensions, accumulator)
            } else if (entry.isFile) {
                val ext = entry.extension.lowercase()
                if (ext in SUPPORTED_EXTENSIONS && ext !in ignoredExtensions) {
                    accumulator.add(entry)
                }
            }
        }
    }
}
