package com.example.network.drive

import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import java.util.Locale

enum class DriveSyncStatus(val label: String) {
    CLOUD_ONLY("Cloud only"),
    DOWNLOADING("Downloading"),
    SYNCED("Synced"),
    UPDATED_REMOTELY("Remote file updated"),
    ERROR("Error")
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
    val parentFolderId: String = "root",
    // Audio metadata
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val bitrateKbps: Int = 320,
    val bpm: Double = 0.0,
    val musicalKey: String = ""
) {
    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            name.contains(".") -> name.substringBeforeLast(".")
            else -> name
        }

    val displayArtist: String
        get() = if (artist.isNotBlank()) artist else "Unknown Artist"

    val displayAlbum: String
        get() = if (album.isNotBlank()) album else "Google Drive"

    val extension: String
        get() = if (name.contains(".")) name.substringAfterLast(".").uppercase(Locale.ROOT) else "MP3"

    val formattedSize: String
        get() {
            if (sizeBytes <= 0L) return "0 MB"
            val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", sizeBytes.toDouble() / 1024.0)
        }

    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return "--:--"
            val m = durationSeconds / 60
            val s = durationSeconds % 60
            return String.format(Locale.US, "%d:%02d", m, s)
        }

    fun toAppTrack(streamUrlOrLocalPath: String): Track {
        val ext = extension
        val isLossless = ext == "FLAC" || ext == "WAV" || ext == "AIFF"
        val quality = when {
            isLossless -> AudioQualityRating.TRUE_LOSSLESS
            bitrateKbps >= 320 -> AudioQualityRating.TRUE_320
            bitrateKbps >= 256 -> AudioQualityRating.TRUE_256
            else -> AudioQualityRating.LOW_128
        }
        val isLocal = syncStatus == DriveSyncStatus.SYNCED && !localFilePath.isNullOrBlank()

        return Track(
            id = "gdrive_$id",
            title = displayTitle,
            artist = displayArtist,
            album = displayAlbum,
            genre = "Cloud DJ Library",
            subGenre = "Google Drive",
            bpm = bpm,
            musicalKey = musicalKey,
            durationSeconds = if (durationSeconds > 0) durationSeconds else 240,
            bitrateKbps = if (bitrateKbps > 0) bitrateKbps else 320,
            format = ext,
            fileSizeMb = (sizeBytes.toDouble() / (1024.0 * 1024.0)).coerceAtLeast(0.1),
            filePath = if (isLocal) (localFilePath ?: streamUrlOrLocalPath) else streamUrlOrLocalPath,
            directoryPath = if (isLocal) (localFilePath?.substringBeforeLast('/') ?: "/GoogleDrive") else "/GoogleDrive",
            isOfflineReady = isLocal,
            syncState = if (isLocal) SyncState.SYNCED else SyncState.CLOUD_ONLY,
            platforms = if (isLocal) listOf(MusicPlatform.GOOGLE_DRIVE, MusicPlatform.LOCAL) else listOf(MusicPlatform.GOOGLE_DRIVE),
            energyRating = 7,
            hotCues = listOf(0, 32, 64, 128),
            isAiTagged = bpm > 0 || musicalKey.isNotBlank(),
            qualityRating = quality,
            crateId = "crate_gdrive",
            sourceId = "gdrive"
        )
    }
}

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
    val refreshToken: String? = null,
    val tokenExpiryEpochMs: Long = 0L,
    val errorMessage: String? = null
)

data class DriveFolderListing(
    val folderId: String,
    val folderName: String,
    val items: List<DriveFileItem> = emptyList(),
    val nextPageToken: String? = null,
    val isLoadingMore: Boolean = false
)
