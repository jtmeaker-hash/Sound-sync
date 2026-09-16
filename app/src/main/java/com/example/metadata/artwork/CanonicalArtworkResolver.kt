package com.example.metadata.artwork

import android.content.Context
import android.graphics.Bitmap
import com.example.model.Album
import com.example.model.Track
import kotlinx.coroutines.flow.SharedFlow

/**
 * Authoritative Canonical Artwork Resolver Contract.
 * Ensures a single, unified pipeline for artwork resolution across the Album tab,
 * Now Playing, Mini-Player, and Background Services.
 */
interface CanonicalArtworkResolver {
    /**
     * Synchronously checks if track artwork is already resident in memory cache.
     * Prevents UI flicker on initial render.
     */
    fun getCachedArtwork(track: Track, sizePx: Int = 512): Bitmap?

    /**
     * Synchronously checks if album artwork is already resident in memory cache.
     */
    fun getCachedArtworkForAlbum(album: Album, sizePx: Int = 320): Bitmap?

    /**
     * Resolves artwork for a track according to canonical priority:
     * 1. User-selected / Custom artwork
     * 2. SoundSync DB reference / persistent disk cache
     * 3. MediaStore album art / content URI
     * 4. Embedded audio file tags (ID3v2 APIC, Vorbis PICTURE, MP4 covr)
     * 5. Folder cover art (cover.jpg, folder.jpg)
     * 6. Deterministic vinyl fallback
     */
    suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int = 512): Bitmap

    /**
     * Resolves representative artwork for an album:
     * - Uses explicit album-level artwork if set
     * - Otherwise deterministically selects valid canonical artwork from member tracks
     * - Falls back to directory cover art or album-specific vinyl graphic
     */
    suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int = 320): Bitmap

    /**
     * Invalidates memory cache and emits an invalidation event for a specific track.
     */
    fun invalidateTrack(trackId: String, artist: String? = null, album: String? = null)

    /**
     * Determines whether the track has usable cover artwork across all supported sources:
     * - Embedded artwork inside the audio file
     * - Artwork previously downloaded by the metadata/artwork scanner
     * - Manually selected artwork
     * - Cached artwork on disk
     * - Artwork paths/URIs stored in SoundSync's database
     * - MediaStore album art
     * - Folder artwork (cover.jpg, folder.jpg)
     *
     * Returns false if SoundSync's normal artwork resolver would otherwise display generic placeholder art.
     */
    fun hasUsableCoverArtwork(context: Context, track: Track): Boolean

    /**
     * Invalidates memory cache and emits an invalidation event for an album.
     */
    fun invalidateAlbum(artist: String, album: String)

    /**
     * Clears all in-memory artwork caches.
     */
    fun clearMemoryCache()

    /**
     * Hot stream of invalidated track IDs and album keys for reactive UI recomposition.
     */
    val artworkInvalidationFlow: SharedFlow<String>
}
