package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceFolderDao {
    @Query("SELECT * FROM source_folders ORDER BY lastScanned DESC")
    fun getAllSources(): Flow<List<SourceFolderEntity>>

    @Query("SELECT * FROM source_folders ORDER BY lastScanned DESC")
    suspend fun getAllSourceFoldersSync(): List<SourceFolderEntity>

    @Query("SELECT * FROM source_folders ORDER BY lastScanned DESC")
    suspend fun getAllSourcesSync(): List<SourceFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: SourceFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSourceFolder(source: SourceFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<SourceFolderEntity>)

    @Update
    suspend fun updateSource(source: SourceFolderEntity)

    @Delete
    suspend fun deleteSource(source: SourceFolderEntity)

    @Query("DELETE FROM source_folders WHERE id = :id")
    suspend fun deleteSourceById(id: String)
}
