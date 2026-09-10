package com.example.metadata.artwork

import java.io.File

/**
 * High-precision lookup states for tracking artwork resolution progress and provenance.
 */
enum class ArtworkLookupState {
    NOT_SCANNED,
    LOCAL_FOUND,
    PRIMARY_PROVIDER_FOUND,
    FALLBACK_FOUND,
    MANUAL_SELECTED,
    SEARCHING,
    LOW_CONFIDENCE,
    NOT_FOUND,
    TEMPORARY_FAILURE,
    RATE_LIMITED;

    val isSuccess: Boolean
        get() = this == LOCAL_FOUND || this == PRIMARY_PROVIDER_FOUND || this == FALLBACK_FOUND || this == MANUAL_SELECTED
}

/**
 * Discovered candidate artwork item suitable for ranking, auto-application, or manual selection.
 */
data class ArtworkCandidateItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val artworkUrl: String,
    val thumbnailUrl: String = artworkUrl,
    val provider: String, // "Apple iTunes", "TheAudioDB", "Local Embedded", "Local Folder", "Apple Album"
    val artist: String,
    val title: String? = null,
    val album: String? = null,
    val releaseYear: Int? = null,
    val durationSeconds: Int = 0,
    val confidence: Double = 0.0,
    val scoreBreakdown: String = "",
    val isrc: String? = null,
    val isHighResolution: Boolean = true
)

/**
 * Result of local audio file or folder artwork inspection.
 */
data class LocalArtworkResult(
    val file: File,
    val source: String, // "Embedded Tag" or "Local Folder (cover.jpg)"
    val isEmbedded: Boolean,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Final result produced by MultiStageArtworkResolver.
 */
data class ResolvedArtworkResult(
    val state: ArtworkLookupState,
    val artworkCachePath: String? = null,
    val artworkUrl: String? = null,
    val artworkSource: String? = null,
    val confidence: Double = 0.0,
    val candidates: List<ArtworkCandidateItem> = emptyList(),
    val message: String = "",
    val wasRepaired: Boolean = false
)
