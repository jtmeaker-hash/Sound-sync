package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Normalized individual artist entity in the SoundSync Artist index.
 */
@Entity(
    tableName = "artists",
    indices = [
        Index("name"),
        Index("normalizedName")
    ]
)
data class ArtistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val normalizedName: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val totalDurationSeconds: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Many-to-many relationship linking a track to an individual artist entity.
 */
@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "artistId"],
    indices = [
        Index("trackId"),
        Index("artistId"),
        Index("artistName")
    ]
)
data class TrackArtistEntity(
    val trackId: String,
    val artistId: String,
    val artistName: String,
    val role: String = "PRIMARY"
)
