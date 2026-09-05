package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_folders")
data class WatchedFolderEntity(
    @PrimaryKey
    val id: String,
    val folderPathOrUri: String,
    val displayName: String,
    val includeSubfolders: Boolean = true,
    val autoScanNewFiles: Boolean = true,
    val autoAnalyzeMetadata: Boolean = true,
    val autoFingerprint: Boolean = true,
    val autoAnalyzeBpmKey: Boolean = true,
    val autoFetchArtwork: Boolean = true,
    val ignoredExtensions: String = "", // Comma-separated (e.g. "tmp,bak")
    val lastScannedTimestamp: Long = 0L,
    val isEnabled: Boolean = true
)
