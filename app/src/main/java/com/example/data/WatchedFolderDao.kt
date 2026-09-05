package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: WatchedFolderEntity)

    @Update
    suspend fun updateFolder(folder: WatchedFolderEntity)

    @Query("SELECT * FROM watched_folders ORDER BY displayName ASC")
    suspend fun getAllFolders(): List<WatchedFolderEntity>

    @Query("SELECT * FROM watched_folders ORDER BY displayName ASC")
    fun observeAllFolders(): Flow<List<WatchedFolderEntity>>

    @Query("SELECT * FROM watched_folders WHERE isEnabled = 1")
    suspend fun getActiveFolders(): List<WatchedFolderEntity>

    @Query("SELECT * FROM watched_folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: String): WatchedFolderEntity?

    @Query("UPDATE watched_folders SET lastScannedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateLastScanned(id: String, timestamp: Long)

    @Query("DELETE FROM watched_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)
}
