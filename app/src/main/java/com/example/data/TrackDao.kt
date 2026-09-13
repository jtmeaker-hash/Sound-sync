package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    suspend fun getAllTracksSync(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    suspend fun getAllTracksList(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC LIMIT 1")
    suspend fun getFirstTrackSync(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE crateId = :crateId ORDER BY bpm ASC")
    fun getTracksByCrate(crateId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getTrackFlowById(id: String): Flow<TrackEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Update
    suspend fun updateTracks(tracks: List<TrackEntity>)

    @Delete
    suspend fun deleteTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteTracksByIds(ids: List<String>)

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<String>): List<TrackEntity>

    @Query("DELETE FROM tracks")
    suspend fun deleteAllTracks()

    @Query("SELECT * FROM crates")
    fun getAllCrates(): Flow<List<CrateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrate(crate: CrateEntity)

    @Delete
    suspend fun deleteCrate(crate: CrateEntity)

    @Query("SELECT contentFingerprint FROM tracks WHERE contentFingerprint != ''")
    suspend fun getAllFingerprints(): List<String>

    @Query("SELECT filePath FROM tracks WHERE filePath != ''")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT * FROM tracks WHERE contentFingerprint = :fingerprint LIMIT 1")
    suspend fun getTrackByFingerprint(fingerprint: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE filePath = :filePath LIMIT 1")
    suspend fun getTrackByFilePath(filePath: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackIfNotExist(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIfNotExist(tracks: List<TrackEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int

    @Query("SELECT * FROM tracks WHERE analysisState IN ('NOT_ANALYSED', 'QUEUED', 'PARTIAL') OR bpm <= 0.0 OR camelotKey = '' ORDER BY CASE WHEN analysisState = 'QUEUED' THEN 0 WHEN analysisState = 'NOT_ANALYSED' THEN 1 ELSE 2 END, dateAdded DESC LIMIT :limit")
    suspend fun getTracksNeedingAnalysis(limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE analysisState IN ('NOT_ANALYSED', 'QUEUED')")
    fun observePendingAnalysisCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE analysisState IN ('NOT_ANALYSED', 'QUEUED', 'PARTIAL') OR bpm <= 0.0 OR camelotKey = ''")
    suspend fun getPendingAnalysisCount(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE analysisState = 'COMPLETE'")
    fun observeCompletedAnalysisCount(): Flow<Int>

    @Query("UPDATE tracks SET analysisState = :state, lastAnalysedAt = :lastAnalysedAt, analysisFailureReason = :reason, analysisRetryCount = :retryCount WHERE id = :id")
    suspend fun updateTrackAnalysisStatus(id: String, state: String, lastAnalysedAt: Long?, reason: String?, retryCount: Int)

    @Query("UPDATE tracks SET analysisState = 'QUEUED' WHERE analysisState != 'COMPLETE'")
    suspend fun queueUnfinishedTracks()

    @Query("UPDATE tracks SET analysisState = 'QUEUED' WHERE id IN (:ids)")
    suspend fun queueTracksByIds(ids: List<String>)

    @Query("UPDATE tracks SET analysisState = 'QUEUED'")
    suspend fun markAllForReanalysis()

    @Query("UPDATE tracks SET analysisState = 'QUEUED' WHERE bpm <= 0.0 OR camelotKey = '' OR artworkUrl IS NULL OR artworkUrl = ''")
    suspend fun markMissingForAnalysis()

    @Query("UPDATE tracks SET metadataWriteState = :state WHERE id = :id")
    suspend fun updateMetadataWriteState(id: String, state: String)

    @Query("SELECT * FROM tracks WHERE metadataWriteState != 'FILE_WRITE_SUCCESS' AND filePath != '' AND filePath NOT LIKE 'demo://%'")
    suspend fun getTracksNeedingFileWrite(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE metadataWriteState != 'FILE_WRITE_SUCCESS' AND filePath != '' AND filePath NOT LIKE 'demo://%'")
    suspend fun getCountNeedingFileWrite(): Int

    @Query("SELECT * FROM tracks WHERE metadataWriteState IN ('FILE_WRITE_FAILED', 'READ_ONLY_FILE', 'PERMISSION_REQUIRED', 'DATABASE_ONLY') AND filePath != '' AND filePath NOT LIKE 'demo://%'")
    suspend fun getTracksWithWriteIssues(): List<TrackEntity>

    @Query("UPDATE tracks SET filePath = :newPath WHERE id = :id")
    suspend fun updateFilePath(id: String, newPath: String)

    @Query("SELECT * FROM tracks WHERE playabilityStatus NOT IN ('PLAYABLE', 'REPAIRED', 'UNKNOWN')")
    fun observeTracksWithPlaybackIssues(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE playabilityStatus NOT IN ('PLAYABLE', 'REPAIRED', 'UNKNOWN')")
    suspend fun getTracksWithPlaybackIssues(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE playabilityStatus NOT IN ('PLAYABLE', 'REPAIRED', 'UNKNOWN')")
    fun observePlaybackIssuesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE playabilityStatus NOT IN ('PLAYABLE', 'REPAIRED', 'UNKNOWN')")
    suspend fun getPlaybackIssuesCount(): Int

    @Query("SELECT * FROM tracks WHERE playabilityStatus = 'UNKNOWN' OR lastPlaybackValidation IS NULL ORDER BY dateAdded DESC")
    suspend fun getTracksNeedingPlayabilityValidation(): List<TrackEntity>

    @Query("UPDATE tracks SET playabilityStatus = :status, playbackErrorCode = :errorCode, playbackErrorMessage = :errorMessage, lastPlaybackValidation = :timestamp, resolvedUri = :resolvedUri, validationFileSize = :fileSize, validationModifiedTimestamp = :fileModified WHERE id = :id")
    suspend fun updatePlayabilityStatus(
        id: String,
        status: String,
        errorCode: String? = null,
        errorMessage: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        resolvedUri: String? = null,
        fileSize: Long = 0L,
        fileModified: Long = 0L
    )

    @Query("UPDATE tracks SET playabilityStatus = :status, lastRepairAttempt = :timestamp, resolvedUri = :resolvedUri, filePath = CASE WHEN :newFilePath IS NOT NULL AND :newFilePath != '' THEN :newFilePath ELSE filePath END WHERE id = :id")
    suspend fun updateRepairedTrack(
        id: String,
        status: String,
        timestamp: Long,
        resolvedUri: String?,
        newFilePath: String?
    )

    @Query("UPDATE tracks SET playabilityStatus = 'UNKNOWN', lastPlaybackValidation = NULL")
    suspend fun resetAllPlayabilityStatus()

    @Query("SELECT * FROM tracks WHERE physicalMediaKey = :key LIMIT 1")
    suspend fun getTrackByPhysicalMediaKey(key: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE physicalMediaKey = :key")
    suspend fun getTracksByPhysicalMediaKey(key: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE mediaStoreId = :mediaId LIMIT 1")
    suspend fun getTrackByMediaStoreId(mediaId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE mediaStoreId = :mediaId")
    suspend fun getTracksByMediaStoreId(mediaId: Long): List<TrackEntity>

    @Transaction
    suspend fun upsertPhysicalTrack(track: TrackEntity): Long {
        val existing = if (track.physicalMediaKey.isNotBlank()) {
            getTrackByPhysicalMediaKey(track.physicalMediaKey)
        } else null
            ?: (if (track.mediaStoreId != null) getTrackByMediaStoreId(track.mediaStoreId) else null)
            ?: (if (track.filePath.isNotBlank()) getTrackByFilePath(track.filePath) else null)
            ?: getTrackById(track.id)

        if (existing != null) {
            val merged = existing.copy(
                physicalMediaKey = if (existing.physicalMediaKey.isNotBlank()) existing.physicalMediaKey else track.physicalMediaKey,
                mediaStoreId = existing.mediaStoreId ?: track.mediaStoreId,
                mediaStoreVolume = existing.mediaStoreVolume ?: track.mediaStoreVolume,
                durationSeconds = if (existing.durationSeconds > 1) existing.durationSeconds else (if (track.durationSeconds > 1) track.durationSeconds else existing.durationSeconds),
                filePath = if (track.filePath.isNotBlank()) track.filePath else existing.filePath,
                storageRelativePath = if (track.storageRelativePath.isNotBlank()) track.storageRelativePath else existing.storageRelativePath,
                title = if (existing.userConfirmedMetadata || (existing.title.isNotBlank() && existing.title != "<unknown>" && !existing.title.startsWith("Track "))) existing.title else track.title,
                artist = if (existing.userConfirmedMetadata || (existing.artist.isNotBlank() && existing.artist != "<unknown>" && existing.artist != "Unknown Artist")) existing.artist else track.artist,
                album = if (existing.userConfirmedMetadata || (existing.album.isNotBlank() && existing.album != "<unknown>" && existing.album != "Single")) existing.album else track.album,
                bpm = if (existing.bpm > 0.0) existing.bpm else track.bpm,
                bpmConfidence = if (existing.bpm > 0.0) existing.bpmConfidence else track.bpmConfidence,
                bpmAnalysisVersion = existing.bpmAnalysisVersion ?: track.bpmAnalysisVersion,
                bpmLastAnalyzed = existing.bpmLastAnalyzed ?: track.bpmLastAnalyzed,
                musicalKey = if (existing.musicalKey.isNotBlank()) existing.musicalKey else track.musicalKey,
                camelotKey = if (existing.camelotKey.isNotBlank()) existing.camelotKey else track.camelotKey,
                keyConfidence = if (existing.keyConfidence > 0.0) existing.keyConfidence else track.keyConfidence,
                keyAnalysisVersion = existing.keyAnalysisVersion ?: track.keyAnalysisVersion,
                keyLastAnalyzed = existing.keyLastAnalyzed ?: track.keyLastAnalyzed,
                analysisState = if (existing.analysisState == "COMPLETE") existing.analysisState else track.analysisState,
                lastAnalysedAt = existing.lastAnalysedAt ?: track.lastAnalysedAt,
                artworkCachePath = existing.artworkCachePath ?: track.artworkCachePath,
                artworkUrl = existing.artworkUrl ?: track.artworkUrl,
                artworkSource = existing.artworkSource ?: track.artworkSource,
                metadataScanState = if (existing.userConfirmedMetadata || existing.metadataScanState == "COMPLETE") existing.metadataScanState else track.metadataScanState,
                userConfirmedMetadata = existing.userConfirmedMetadata || track.userConfirmedMetadata,
                contentFingerprint = if (existing.contentFingerprint.isNotBlank()) existing.contentFingerprint else track.contentFingerprint,
                playabilityStatus = if (existing.playabilityStatus == "PLAYABLE") existing.playabilityStatus else track.playabilityStatus,
                resolvedUri = track.resolvedUri ?: existing.resolvedUri
            )
            updateTrack(merged)
            return 0L
        } else {
            insertTrack(track)
            return 1L
        }
    }

    @Transaction
    suspend fun upsertPhysicalTracks(tracks: List<TrackEntity>) {
        for (track in tracks) {
            upsertPhysicalTrack(track)
        }
    }
}
