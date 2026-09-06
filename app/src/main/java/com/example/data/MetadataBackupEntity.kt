package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "metadata_backups",
    indices = [
        Index("trackId"),
        Index("filePath"),
        Index("timestamp")
    ]
)
data class MetadataBackupEntity(
    @PrimaryKey
    val id: String,
    val trackId: String,
    val filePath: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String? = null,
    val genre: String? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val artworkUrl: String? = null,
    val artworkCachePath: String? = null,
    val hasEmbeddedArtwork: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isOriginalScanBackup: Boolean = false
)
