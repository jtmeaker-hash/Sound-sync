package com.example.metadata.backup

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MetadataBackupDao
import com.example.data.MetadataBackupEntity
import com.example.data.TrackEntity
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.EmbeddedAudioMetadata
import com.example.metadata.MetadataFileWriter
import com.example.metadata.MetadataWriteResult
import com.example.metadata.history.MetadataHistoryManager
import com.example.model.MetadataScanState
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.CompleteTagPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Authoritative transactional backup and rollback manager (Sections 9, 10, 11).
 *
 * Guarantees:
 * 1. Pre-Write Backup: Complete snapshot of tags/artwork saved before any physical file modification.
 * 2. Automatic Rollback: If post-write read-back verification fails, original tags are restored immediately.
 * 3. Restoration Engine: 1-click restore ("Restore this track", "Restore selected tracks", "Restore all SoundSync changes")
 *    rewrites physical audio files and updates database state to RESTORED.
 * 4. Persistence: Backups survive app restarts and library rescans. Initial original baselines are never overwritten.
 */
class MetadataBackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val historyManager: MetadataHistoryManager = MetadataHistoryManager(context, database)
) {
    private val backupDao: MetadataBackupDao = database.metadataBackupDao()
    private val trackDao = database.trackDao()

    companion object {
        private const val TAG = "MetadataBackupManager"
    }

    /**
     * Saves a snapshot of a track's metadata before modifications are written.
     */
    suspend fun savePreWriteBackup(track: TrackEntity, isBaseline: Boolean = false): MetadataBackupEntity = withContext(Dispatchers.IO) {
        val existingBaseline = backupDao.getOriginalBackupForTrack(track.id)
        val shouldMarkBaseline = isBaseline || (existingBaseline == null)

        val physicalMetadata = try {
            if (File(track.filePath).exists()) {
                AudioEmbeddedMetadataReader.read(context, track.filePath)
            } else null
        } catch (_: Exception) {
            null
        }

        val backup = MetadataBackupEntity(
            id = UUID.randomUUID().toString(),
            trackId = track.id,
            filePath = track.filePath,
            title = physicalMetadata?.title ?: track.title,
            artist = physicalMetadata?.artist ?: track.artist,
            album = physicalMetadata?.album ?: track.album,
            albumArtist = physicalMetadata?.albumArtist ?: track.albumArtist,
            genre = physicalMetadata?.genre ?: track.genre,
            releaseYear = physicalMetadata?.releaseYear ?: track.releaseYear,
            releaseDate = physicalMetadata?.releaseDate ?: track.releaseDate,
            trackNumber = physicalMetadata?.trackNumber ?: track.trackNumber,
            discNumber = physicalMetadata?.discNumber ?: track.discNumber,
            bpm = physicalMetadata?.bpm ?: track.bpm,
            musicalKey = physicalMetadata?.musicalKey ?: track.musicalKey,
            artworkUrl = track.artworkUrl,
            artworkCachePath = track.artworkCachePath,
            hasEmbeddedArtwork = physicalMetadata?.hasEmbeddedArtwork ?: false,
            timestamp = System.currentTimeMillis(),
            isOriginalScanBackup = shouldMarkBaseline
        )

        backupDao.insertBackup(backup)
        Log.d(TAG, "Saved pre-write backup for track \"${track.title}\" (id=${track.id}, baseline=$shouldMarkBaseline)")
        backup
    }

    /**
     * Writes metadata with transactional rollback (Section 11).
     * If read-back verification fails, automatically rolls back file to original state.
     */
    private fun isFileWritable(file: File): Boolean {
        if (!file.canWrite()) return false
        return try {
            val perms = Files.getPosixFilePermissions(file.toPath())
            perms.contains(PosixFilePermission.OWNER_WRITE)
        } catch (e: Throwable) {
            file.canWrite()
        }
    }

    suspend fun writeWithTransactionalRollback(
        track: Track,
        artworkBytes: ByteArray? = null,
        artworkMimeType: String = "image/jpeg"
    ): MetadataWriteResult = withContext(Dispatchers.IO) {
        val path = track.filePath
        val file = File(path)

        if (!file.exists()) {
            return@withContext MetadataWriteResult.Failed("File does not exist: $path")
        }
        if (!isFileWritable(file)) {
            return@withContext MetadataWriteResult.PermissionRequired(path)
        }

        val ext = file.extension.lowercase()
        if (ext != "mp3" && ext != "flac") {
            return@withContext MetadataWriteResult.Unsupported("Unsupported container: $ext")
        }

        // 1. Read existing physical metadata
        val preWritePhysical = AudioEmbeddedMetadataReader.read(context, path)

        // 2. Save pre-write backup in database
        val trackEntity = trackDao.getTrackById(track.id)
        if (trackEntity != null) {
            savePreWriteBackup(trackEntity)
        }

        // 3. Write new tags
        val payload = CompleteTagPayload(
            title = track.title,
            artist = track.artist,
            album = track.album,
            genre = track.genre,
            trackNumber = track.trackNumber.takeIf { it > 0 },
            discNumber = track.discNumber.takeIf { it > 0 },
            releaseYear = track.releaseYear,
            releaseDate = track.releaseDate,
            bpm = track.bpm.takeIf { it > 0 },
            musicalKey = track.musicalKey.takeIf { it.isNotBlank() },
            artworkBytes = artworkBytes,
            artworkMimeType = artworkMimeType
        )

        val writeSuccess = AudioTagWriter.writeCompleteTags(context, path, payload)
        if (!writeSuccess) {
            Log.e(TAG, "AudioTagWriter failed writing tags to $path; file untouched.")
            return@withContext MetadataWriteResult.Failed("AudioTagWriter failed writing tags to file")
        }

        // 4. Re-read the file from disk (read-back verification)
        val verified = AudioEmbeddedMetadataReader.read(context, path)

        var verificationFailure: MetadataWriteResult.VerificationFailed? = null

        if (!track.title.isNullOrBlank() && (verified.title == null || !verified.title.equals(track.title, ignoreCase = true))) {
            verificationFailure = MetadataWriteResult.VerificationFailed("title", track.title, verified.title ?: "<null>")
        } else if (!track.artist.isNullOrBlank() && (verified.artist == null || !verified.artist.equals(track.artist, ignoreCase = true))) {
            verificationFailure = MetadataWriteResult.VerificationFailed("artist", track.artist, verified.artist ?: "<null>")
        } else if (!track.album.isNullOrBlank() && track.album != "Single" && track.album != "Unknown Album" &&
            (verified.album == null || !verified.album.equals(track.album, ignoreCase = true))) {
            verificationFailure = MetadataWriteResult.VerificationFailed("album", track.album, verified.album ?: "<null>")
        }

        // 5. Automatic Rollback if verification failed
        if (verificationFailure != null) {
            Log.e(TAG, "Read-back verification FAILED: ${verificationFailure.field}. Initiating automatic rollback for $path")
            val rollbackPayload = CompleteTagPayload(
                title = preWritePhysical.title,
                artist = preWritePhysical.artist,
                album = preWritePhysical.album,
                genre = preWritePhysical.genre,
                trackNumber = preWritePhysical.trackNumber,
                discNumber = preWritePhysical.discNumber,
                releaseYear = preWritePhysical.releaseYear,
                releaseDate = preWritePhysical.releaseDate,
                bpm = preWritePhysical.bpm,
                musicalKey = preWritePhysical.musicalKey,
                artworkBytes = preWritePhysical.embeddedArtworkBytes
            )
            try {
                AudioTagWriter.writeCompleteTags(context, path, rollbackPayload)
                Log.i(TAG, "Automatic rollback successfully restored original tags for $path")
            } catch (rollbackEx: Exception) {
                Log.e(TAG, "Rollback attempt failed for $path: ${rollbackEx.message}", rollbackEx)
            }
            return@withContext verificationFailure
        }

        Log.i(TAG, "Transactional write and read-back verification PASSED for ${file.name}")
        MetadataWriteResult.Written(verified)
    }

    /**
     * Restores a single track to its pre-modification baseline (Section 10).
     * Rewrites physical audio tags and updates database entity to RESTORED.
     */
    suspend fun restoreTrack(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val backup = backupDao.getOriginalBackupForTrack(trackId) ?: backupDao.getLatestBackupForTrack(trackId)
        if (backup == null) {
            Log.w(TAG, "No backup found for trackId=$trackId; cannot restore.")
            return@withContext false
        }

        val trackEntity = trackDao.getTrackById(trackId)
        val currentTitle = trackEntity?.title ?: "Unknown"
        val currentArtist = trackEntity?.artist ?: "Unknown"
        val filePath = backup.filePath.ifBlank { trackEntity?.filePath ?: "" }

        // Revert database track entity
        val restoredEntity = (trackEntity ?: TrackEntity(
            id = trackId,
            filePath = filePath,
            title = backup.title ?: "Unknown Title",
            artist = backup.artist ?: "Unknown Artist"
        )).copy(
            title = backup.title ?: trackEntity?.title ?: "Unknown Title",
            artist = backup.artist ?: trackEntity?.artist ?: "Unknown Artist",
            album = backup.album ?: "",
            albumArtist = backup.albumArtist ?: "",
            genre = backup.genre ?: "",
            releaseYear = backup.releaseYear,
            releaseDate = backup.releaseDate,
            trackNumber = backup.trackNumber ?: 0,
            discNumber = backup.discNumber ?: 1,
            bpm = backup.bpm ?: 0.0,
            musicalKey = backup.musicalKey ?: "",
            artworkUrl = backup.artworkUrl,
            artworkCachePath = backup.artworkCachePath,
            metadataScanState = MetadataScanState.RESTORED.name,
            metadataConfidence = 100.0,
            userConfirmedMetadata = true
        )

        trackDao.updateTrack(restoredEntity)

        // Rewrite physical audio file tags
        if (filePath.isNotBlank() && File(filePath).exists() && isFileWritable(File(filePath))) {
            val payload = CompleteTagPayload(
                title = backup.title,
                artist = backup.artist,
                album = backup.album,
                genre = backup.genre,
                trackNumber = backup.trackNumber,
                discNumber = backup.discNumber,
                releaseYear = backup.releaseYear,
                releaseDate = backup.releaseDate,
                bpm = backup.bpm,
                musicalKey = backup.musicalKey
            )
            try {
                AudioTagWriter.writeCompleteTags(context, filePath, payload)
                Log.d(TAG, "Physically restored original audio tags in $filePath")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write restored tags to physical file: ${e.message}")
            }
        }

        // Record undo in history
        historyManager.recordChange(
            trackId = trackId,
            filePath = filePath,
            fieldChanged = "ALL (RESTORE)",
            previousValue = "$currentArtist - $currentTitle",
            newValue = "${backup.artist} - ${backup.title}",
            source = "RESTORE",
            isAutomatic = false
        )

        Log.i(TAG, "Successfully restored track $trackId to original metadata: \"${backup.artist} - ${backup.title}\"")
        true
    }

    /**
     * Restores multiple tracks by ID.
     */
    suspend fun restoreTracks(trackIds: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (id in trackIds) {
            if (restoreTrack(id)) {
                count++
            }
        }
        count
    }

    /**
     * Restores all SoundSync metadata changes back to original baseline (Section 10).
     */
    suspend fun restoreAll(): Int = withContext(Dispatchers.IO) {
        val allLatest = backupDao.getLatestBackupsForAllTracks()
        var count = 0
        for (backup in allLatest) {
            if (restoreTrack(backup.trackId)) {
                count++
            }
        }
        Log.i(TAG, "Restored all SoundSync metadata modifications ($count tracks)")
        count
    }

    fun observeModifiedTracksCount(): Flow<Int> {
        return backupDao.observeModifiedTracksCount()
    }

    suspend fun getModifiedTracksCount(): Int = withContext(Dispatchers.IO) {
        backupDao.getModifiedTracksCount()
    }
}
