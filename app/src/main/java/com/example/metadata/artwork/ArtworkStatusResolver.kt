package com.example.metadata.artwork

import android.content.Context
import com.example.model.Track

/**
 * Canonical artwork status resolver for SoundSync.
 * Delegates directly to [CanonicalArtworkDetector] to provide a unified
 * artwork availability system across all SoundSync features.
 */
object ArtworkStatusResolver {
    /**
     * Resolves the canonical [ArtworkStatus] for the given [track].
     */
    fun getStatus(track: Track, context: Context? = null): ArtworkStatus =
        CanonicalArtworkDetector.detectArtworkStatus(context, track)

    /**
     * Convenience method returning true if [getStatus] is [ArtworkStatus.HAS_ARTWORK].
     */
    fun hasArtwork(track: Track, context: Context? = null): Boolean =
        CanonicalArtworkDetector.hasArtwork(context, track)

    /**
     * Invalidates any cached artwork status for a track.
     */
    fun invalidate(trackId: String) = CanonicalArtworkDetector.invalidate(trackId)

    /**
     * Clears all cached artwork status entries.
     */
    fun invalidateAll() = CanonicalArtworkDetector.clearCache()
}
