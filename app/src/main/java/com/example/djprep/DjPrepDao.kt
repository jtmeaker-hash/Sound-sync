package com.example.djprep

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for DJ Prep Data (Upgrade 28).
 */
@Dao
interface DjPrepDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: DjPrepEntity)

    @Update
    suspend fun update(entity: DjPrepEntity)

    @Query("SELECT * FROM dj_prep_data WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): DjPrepEntity?

    @Query("SELECT * FROM dj_prep_data WHERE trackId = :trackId")
    fun observeByTrackId(trackId: String): Flow<DjPrepEntity?>

    @Query("SELECT * FROM dj_prep_data")
    suspend fun getAllPrepData(): List<DjPrepEntity>

    @Query("SELECT * FROM dj_prep_data WHERE prepStatus = :status")
    suspend fun getPrepDataByStatus(status: String): List<DjPrepEntity>

    @Query("UPDATE dj_prep_data SET prepStatus = :status, updatedAt = :updatedAt WHERE trackId = :trackId")
    suspend fun updatePrepStatus(trackId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE dj_prep_data SET prepStatus = :status, updatedAt = :updatedAt WHERE trackId IN (:trackIds)")
    suspend fun batchUpdatePrepStatus(trackIds: List<String>, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM dj_prep_data WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)
}
