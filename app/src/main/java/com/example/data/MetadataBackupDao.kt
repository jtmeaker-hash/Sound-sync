package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataBackupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: MetadataBackupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackups(backups: List<MetadataBackupEntity>)

    @Query("SELECT * FROM metadata_backups WHERE trackId = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBackupForTrack(trackId: String): MetadataBackupEntity?

    @Query("SELECT * FROM metadata_backups WHERE trackId = :trackId AND isOriginalScanBackup = 1 LIMIT 1")
    suspend fun getOriginalBackupForTrack(trackId: String): MetadataBackupEntity?

    @Query("SELECT * FROM metadata_backups WHERE trackId = :trackId ORDER BY timestamp DESC")
    suspend fun getAllBackupsForTrack(trackId: String): List<MetadataBackupEntity>

    @Query("SELECT * FROM metadata_backups ORDER BY timestamp DESC")
    suspend fun getAllBackups(): List<MetadataBackupEntity>

    @Query("SELECT * FROM metadata_backups WHERE id IN (SELECT id FROM metadata_backups GROUP BY trackId HAVING timestamp = MAX(timestamp))")
    suspend fun getLatestBackupsForAllTracks(): List<MetadataBackupEntity>

    @Query("SELECT COUNT(DISTINCT trackId) FROM metadata_backups")
    fun observeModifiedTracksCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT trackId) FROM metadata_backups")
    suspend fun getModifiedTracksCount(): Int

    @Query("DELETE FROM metadata_backups WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)

    @Query("DELETE FROM metadata_backups")
    suspend fun clearAll()
}
