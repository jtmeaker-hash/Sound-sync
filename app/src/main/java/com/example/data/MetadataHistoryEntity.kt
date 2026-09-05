package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "metadata_history",
    indices = [
        Index("trackId"),
        Index("timestamp"),
        Index("filePath")
    ]
)
data class MetadataHistoryEntity(
    @PrimaryKey
    val id: String,
    val trackId: String,
    val filePath: String,
    val fieldChanged: String,
    val previousValue: String?,
    val newValue: String?,
    val source: String, // "MANUAL", "APPLE_SEARCH", "THEAUDIODB", "INBOX_APPROVAL", "BULK_EDIT", "UNDO"
    val timestamp: Long = System.currentTimeMillis(),
    val isAutomatic: Boolean = false
)
