package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataReviewInboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: MetadataReviewItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MetadataReviewItemEntity>)

    @Query("SELECT * FROM metadata_review_inbox WHERE status = 'PENDING' ORDER BY timestamp DESC")
    suspend fun getPendingItems(): List<MetadataReviewItemEntity>

    @Query("SELECT * FROM metadata_review_inbox WHERE status = 'PENDING' ORDER BY timestamp DESC")
    fun observePendingItems(): Flow<List<MetadataReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM metadata_review_inbox WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM metadata_review_inbox WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM metadata_review_inbox WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): MetadataReviewItemEntity?

    @Query("SELECT * FROM metadata_review_inbox WHERE trackId = :trackId AND status = 'PENDING' LIMIT 1")
    suspend fun getPendingItemForTrack(trackId: String): MetadataReviewItemEntity?

    @Query("UPDATE metadata_review_inbox SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE metadata_review_inbox SET status = :status WHERE id IN (:ids)")
    suspend fun updateStatusBulk(ids: List<String>, status: String)

    @Query("DELETE FROM metadata_review_inbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM metadata_review_inbox WHERE status != 'PENDING'")
    suspend fun pruneResolved()
}
