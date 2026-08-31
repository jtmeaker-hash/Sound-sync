package com.example.network.drive

import com.example.model.SyncState

enum class DriveSyncStatus {
    CLOUD_ONLY,
    DOWNLOADING,
    SYNCED,
    UPDATED_REMOTELY,
    ERROR
}

data class DriveFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val modifiedTime: String = "",
    val md5Checksum: String = "",
    val thumbnailLink: String? = null,
    val isFolder: Boolean = false,
    val localFilePath: String? = null,
    val syncStatus: DriveSyncStatus = DriveSyncStatus.CLOUD_ONLY,
    val downloadProgressPercent: Int = 0,
    // Audio metadata
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0
)

data class DriveBreadcrumb(
    val folderId: String,
    val folderName: String
)

data class DriveAuthState(
    val isConnected: Boolean = false,
    val userEmail: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val accessToken: String? = null,
    val errorMessage: String? = null
)
