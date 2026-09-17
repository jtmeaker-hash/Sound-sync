package com.example.metadata.artwork

import android.content.Context
import android.net.Uri
import com.example.model.Track
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared canonical artwork status for SoundSync.
 */
enum class ArtworkStatus {
    HAS_ARTWORK,
    NO_ARTWORK;

    val hasArtwork: Boolean
        get() = this == HAS_ARTWORK
}

/**
 * Authoritative Canonical Artwork Status Resolver for SoundSync.
 *
 * Every feature that needs to know whether a track has real artwork queries this resolver:
 * - Local Library filters (SongsScreen)
 * - Library Insights (LibraryInsightsDialog / SoundSyncIntelligenceEngine)
 * - Library Health Screen
 * - Listening Statistics (Metadata Hygiene)
 * - Smart Crates (SmartCrateEngine)
 * - Command Palette (CommandPaletteEngine)
 * - Library Doctor (LibraryDoctorAuditor)
 *
 * Validation rules:
 * - A track is only classified as [ArtworkStatus.HAS_ARTWORK] when SoundSync can verify that
 *   a real, usable artwork source exists.
 * - Stale database paths and missing local artwork files return [ArtworkStatus.NO_ARTWORK].
 * - SoundSync placeholder images, fallback drawables, empty/blank URIs, and bare MediaStore
 *   auto-albumart URIs (`content://media/external/audio/albumart/...`) return [ArtworkStatus.NO_ARTWORK].
 * - Thread-safe in-memory caching ensures that querying across 2000+ tracks is instantaneous.
 */
object ArtworkStatusResolver {

    private val statusCache = ConcurrentHashMap<String, ArtworkStatus>()

    fun computeCacheKey(track: Track): String {
        return "${track.id}_${track.artworkCachePath}_${track.artworkUrl}_${track.artworkSource}_${track.metadataWriteState}_${track.fileModifiedTimestamp}"
    }

    /**
     * Resolves the canonical [ArtworkStatus] for the given [track].
     */
    fun getStatus(track: Track, context: Context? = null): ArtworkStatus {
        val key = computeCacheKey(track)
        statusCache[key]?.let { return it }

        val status = resolveStatusUncached(track, context)
        statusCache[key] = status
        return status
    }

    /**
     * Convenience method returning true if [getStatus] is [ArtworkStatus.HAS_ARTWORK].
     */
    fun hasArtwork(track: Track, context: Context? = null): Boolean {
        return getStatus(track, context) == ArtworkStatus.HAS_ARTWORK
    }

    /**
     * Invalidates any cached artwork status for a track.
     */
    fun invalidate(trackId: String) {
        val prefix = "${trackId}_"
        val keysToRemove = statusCache.keys().toList().filter { it.startsWith(prefix) }
        for (k in keysToRemove) {
            statusCache.remove(k)
        }
    }

    /**
     * Clears all cached artwork status entries.
     */
    fun invalidateAll() {
        statusCache.clear()
    }

    private fun resolveStatusUncached(track: Track, context: Context?): ArtworkStatus {
        // 1. Check artworkCachePath (managed local cached file)
        val cachePath = track.artworkCachePath
        if (!cachePath.isNullOrBlank()) {
            if (!isPlaceholderString(cachePath)) {
                val file = File(cachePath)
                if (file.exists() && file.canRead() && file.length() > 0L) {
                    return ArtworkStatus.HAS_ARTWORK
                }
            }
            // If cachePath is set but the file does not exist, is unreadable, or is 0 bytes,
            // it is a stale database path or missing local file -> do NOT classify as HAS_ARTWORK.
        }

        // 2. Check artworkUrl
        val url = track.artworkUrl?.trim()
        if (!url.isNullOrBlank()) {
            if (isPlaceholderString(url)) {
                return ArtworkStatus.NO_ARTWORK
            }

            when {
                url.startsWith("file://") -> {
                    val path = try {
                        Uri.parse(url).path ?: url.removePrefix("file://")
                    } catch (_: Exception) {
                        url.removePrefix("file://")
                    }
                    val file = File(path)
                    if (file.exists() && file.canRead() && file.length() > 0L) {
                        return ArtworkStatus.HAS_ARTWORK
                    } else {
                        // Stale or missing local file URI
                        return ArtworkStatus.NO_ARTWORK
                    }
                }

                url.startsWith("/") -> {
                    val file = File(url)
                    if (file.exists() && file.canRead() && file.length() > 0L) {
                        return ArtworkStatus.HAS_ARTWORK
                    } else {
                        return ArtworkStatus.NO_ARTWORK
                    }
                }

                url.startsWith("http://") || url.startsWith("https://") -> {
                    // Valid remote/downloaded artwork URI associated with the track
                    return ArtworkStatus.HAS_ARTWORK
                }

                url.startsWith("content://") -> {
                    // Check if it's an auto-assigned MediaStore albumart URI
                    val isMediaStoreAutoAlbumArt = url.startsWith("content://media/external/audio/albumart") ||
                            url.startsWith("content://0@media/external/audio/albumart") ||
                            url.startsWith("content://media/external_primary/audio/albumart")

                    if (isMediaStoreAutoAlbumArt) {
                        // MediaStore assigns bare albumart URIs to every album even with no artwork.
                        // Verify with ContentResolver if context is available.
                        if (context != null) {
                            try {
                                context.contentResolver.openFileDescriptor(Uri.parse(url), "r")?.use { pfd ->
                                    if (pfd.statSize > 0L) return ArtworkStatus.HAS_ARTWORK
                                }
                            } catch (_: Exception) {}
                        }
                        // If unverified or cannot be opened, bare MediaStore albumart auto-URI is NO_ARTWORK
                        return ArtworkStatus.NO_ARTWORK
                    } else {
                        // Other custom content URI (e.g. SAF or custom provider)
                        if (context != null) {
                            try {
                                context.contentResolver.openFileDescriptor(Uri.parse(url), "r")?.use { pfd ->
                                    if (pfd.statSize > 0L) return ArtworkStatus.HAS_ARTWORK
                                }
                                return ArtworkStatus.NO_ARTWORK
                            } catch (_: Exception) {
                                return ArtworkStatus.NO_ARTWORK
                            }
                        } else {
                            return ArtworkStatus.HAS_ARTWORK
                        }
                    }
                }

                else -> {
                    // Unrecognized scheme or malformed URI
                    return ArtworkStatus.NO_ARTWORK
                }
            }
        }

        // 3. Embedded artwork indicator inside physical audio file
        if (track.isEmbeddedInFile || track.artworkSource in listOf("Embedded Tag", "Local Embedded", "Embedded")) {
            val audioFile = File(track.filePath)
            if (audioFile.exists() && audioFile.canRead() && audioFile.length() > 0L) {
                return ArtworkStatus.HAS_ARTWORK
            }
        }

        // 4. Persistent ArtworkCache lookup if context is available
        if (context != null) {
            try {
                val artworkCache = com.example.metadata.ArtworkCache(context)
                val cached = artworkCache.getCachedArtworkFileForTrack(track.id)
                    ?: if (track.artist.isNotBlank() && track.album.isNotBlank() && track.album != "Single") {
                        artworkCache.getCachedArtworkFile(track.artist, track.album)
                    } else null

                if (cached != null && cached.exists() && cached.length() > 0L) {
                    return ArtworkStatus.HAS_ARTWORK
                }
            } catch (_: Exception) {}
        }

        return ArtworkStatus.NO_ARTWORK
    }

    private fun isPlaceholderString(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.isEmpty() ||
                lower == "none" ||
                lower == "null" ||
                lower == "undefined" ||
                lower.contains("placeholder") ||
                lower.contains("default_album") ||
                lower.contains("default_cover") ||
                lower.contains("music_note") ||
                lower.contains("fallback")
    }
}
