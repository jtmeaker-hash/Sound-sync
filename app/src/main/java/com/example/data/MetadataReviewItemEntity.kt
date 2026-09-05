package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "metadata_review_inbox",
    indices = [
        Index("trackId"),
        Index("status"),
        Index("confidenceScore"),
        Index("timestamp")
    ]
)
data class MetadataReviewItemEntity(
    @PrimaryKey
    val id: String,
    val trackId: String,
    val filePath: String,
    val originalArtist: String,
    val originalTitle: String,
    val originalAlbum: String,
    val proposedArtist: String,
    val proposedTitle: String,
    val proposedAlbum: String,
    val proposedGenre: String? = null,
    val proposedYear: Int? = null,
    val proposedTrackNumber: Int? = null,
    val proposedArtworkUrl: String? = null,
    val provider: String,
    val confidenceScore: Double,
    val evidenceSummary: String,
    val status: String = "PENDING", // PENDING, ACCEPTED, REJECTED, IGNORED
    val timestamp: Long = System.currentTimeMillis()
)
