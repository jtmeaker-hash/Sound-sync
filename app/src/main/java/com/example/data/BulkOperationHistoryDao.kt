package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BulkOperationHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: BulkOperationHistoryEntity)

    @Query("SELECT * FROM bulk_operation_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestOperation(): BulkOperationHistoryEntity?

    @Query("SELECT * FROM bulk_operation_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOperations(limit: Int): List<BulkOperationHistoryEntity>

    @Query("SELECT * FROM bulk_operation_history ORDER BY timestamp DESC")
    fun observeRecentOperations(): Flow<List<BulkOperationHistoryEntity>>

    @Query("DELETE FROM bulk_operation_history WHERE id = :id")
    suspend fun deleteOperation(id: String)

    @Query("DELETE FROM bulk_operation_history")
    suspend fun clearHistory()
}
