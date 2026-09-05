package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId LIMIT 1")
    fun getLyricsForTrackFlow(trackId: String): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId LIMIT 1")
    suspend fun getLyricsForTrack(trackId: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId LIMIT 1")
    fun getLyricsForTrackSync(trackId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateLyrics(lyrics: LyricsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateLyricsSync(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE trackId = :trackId")
    suspend fun deleteLyricsForTrack(trackId: String)

    @Query("SELECT COUNT(*) FROM lyrics WHERE isSynced = 1")
    suspend fun getSyncedLyricsCount(): Int

    @Query("SELECT COUNT(*) FROM lyrics WHERE isUserEdited = 1")
    suspend fun getUserEditedLyricsCount(): Int

    @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun getTotalLyricsCount(): Int

    @Query("DELETE FROM lyrics")
    suspend fun clearAllLyrics()
}
