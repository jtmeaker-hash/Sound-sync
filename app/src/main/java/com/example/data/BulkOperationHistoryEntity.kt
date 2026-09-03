package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bulk_operation_history")
data class BulkOperationHistoryEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val operationType: String,
    val summary: String,
    val affectedTracksCount: Int,
    val undoPayloadJson: String
)
