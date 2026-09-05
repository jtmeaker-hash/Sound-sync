package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: MetadataHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistories(entries: List<MetadataHistoryEntity>)

    @Query("SELECT * FROM metadata_history WHERE trackId = :trackId ORDER BY timestamp DESC")
    suspend fun getHistoryForTrack(trackId: String): List<MetadataHistoryEntity>

    @Query("SELECT * FROM metadata_history WHERE trackId = :trackId ORDER BY timestamp DESC")
    fun observeHistoryForTrack(trackId: String): Flow<List<MetadataHistoryEntity>>

    @Query("SELECT * FROM metadata_history WHERE trackId = :trackId AND fieldChanged = :field ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestChangeForField(trackId: String, field: String): MetadataHistoryEntity?

    @Query("SELECT * FROM metadata_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int = 100): List<MetadataHistoryEntity>

    @Query("SELECT * FROM metadata_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int = 100): Flow<List<MetadataHistoryEntity>>

    @Query("DELETE FROM metadata_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM metadata_history WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)

    /**
     * Retains the most recent entries and deletes older ones to prevent unbounded growth.
     */
    @Query("DELETE FROM metadata_history WHERE id NOT IN (SELECT id FROM metadata_history ORDER BY timestamp DESC LIMIT :maxEntries)")
    suspend fun pruneOldEntries(maxEntries: Int = 5000)
}
