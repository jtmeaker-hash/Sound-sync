package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackBrainDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: TrackBrainStatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(statuses: List<TrackBrainStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(statuses: List<TrackBrainStatusEntity>)

    @Query("SELECT * FROM track_brain_status WHERE trackId = :trackId")
    suspend fun getStatusForTrack(trackId: String): TrackBrainStatusEntity?

    @Query("SELECT * FROM track_brain_status WHERE trackId = :trackId")
    fun observeStatusForTrack(trackId: String): Flow<TrackBrainStatusEntity?>

    @Query("SELECT * FROM track_brain_status WHERE trackId IN (:trackIds)")
    suspend fun getStatusesForTracks(trackIds: List<String>): List<TrackBrainStatusEntity>

    @Query("SELECT * FROM track_brain_status")
    fun observeAllStatuses(): Flow<List<TrackBrainStatusEntity>>

    @Query("SELECT * FROM track_brain_status")
    suspend fun getAllStatuses(): List<TrackBrainStatusEntity>

    @Query("SELECT COUNT(*) FROM track_brain_status")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM track_brain_status WHERE overallStatus = :status")
    suspend fun getCountByStatus(status: String): Int

    @Query("SELECT * FROM track_brain_status WHERE overallStatus IN ('PENDING', 'QUEUED', 'PARTIALLY_COMPLETE') ORDER BY lastAttemptTime ASC LIMIT :limit")
    suspend fun getTracksNeedingAnalysis(limit: Int = 50): List<TrackBrainStatusEntity>

    @Query("SELECT * FROM track_brain_status WHERE overallStatus = 'FAILED' LIMIT :limit")
    suspend fun getFailedTracks(limit: Int = 100): List<TrackBrainStatusEntity>

    @Query("SELECT * FROM track_brain_status WHERE overallStatus = :status LIMIT :limit")
    suspend fun getTracksByStatus(status: String, limit: Int = 100): List<TrackBrainStatusEntity>

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', retryCount = 0, errorCode = null, errorMessage = null WHERE overallStatus = 'FAILED'")
    suspend fun resetFailedTracks(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', retryCount = 0")
    suspend fun markAllPending(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', bpmStatus = 'NOT_STARTED', keyStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markBpmKeyForReanalysis(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', waveformStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markWaveformForReanalysis(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', artworkStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markArtworkForReanalysis(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', metadataStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markMetadataForReanalysis(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', qualityStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markQualityForReanalysis(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', replayGainStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markReplayGainForReanalysis(): Int

    @Query("UPDATE track_brain_status SET overallStatus = 'PENDING', lyricsStatus = 'NOT_STARTED' WHERE overallStatus != 'ANALYSING'")
    suspend fun markLyricsForReanalysis(): Int

    @Query("DELETE FROM track_brain_status WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)

    @Query("DELETE FROM track_brain_status WHERE trackId NOT IN (SELECT id FROM tracks)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT trackId FROM track_brain_status")
    suspend fun getAllKnownTrackIds(): List<String>
}
