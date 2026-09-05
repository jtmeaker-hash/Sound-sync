package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SongFindDao {

    @Query("SELECT * FROM song_finds ORDER BY createdAt DESC")
    fun getAllSongFinds(): Flow<List<SongFindEntity>>

    @Query("SELECT * FROM song_finds WHERE isCompleted = 0 ORDER BY createdAt DESC")
    fun getPendingSongFinds(): Flow<List<SongFindEntity>>

    @Query("SELECT * FROM song_finds WHERE id = :id LIMIT 1")
    suspend fun getSongFindById(id: String): SongFindEntity?

    @Query("SELECT * FROM song_finds WHERE url = :url LIMIT 1")
    suspend fun getSongFindByUrl(url: String): SongFindEntity?

    @Query("SELECT COUNT(*) FROM song_finds WHERE url = :url")
    suspend fun countByUrl(url: String): Int

    @Query("SELECT * FROM song_finds ORDER BY createdAt DESC")
    suspend fun getAllSongFindsSync(): List<SongFindEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongFind(songFind: SongFindEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongFinds(songFinds: List<SongFindEntity>)

    @Update
    suspend fun updateSongFind(songFind: SongFindEntity)

    @Query("DELETE FROM song_finds WHERE id = :id")
    suspend fun deleteSongFindById(id: String)

    @Query("UPDATE song_finds SET isCompleted = :completed WHERE id = :id")
    suspend fun updateCompletedState(id: String, completed: Boolean)

    @Query("DELETE FROM song_finds WHERE isCompleted = 1")
    suspend fun clearCompletedFinds()
}
