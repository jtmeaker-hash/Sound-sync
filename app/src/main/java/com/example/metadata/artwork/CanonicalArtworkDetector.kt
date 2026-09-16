package com.example.metadata.artwork

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.SoundSyncApplication
import com.example.metadata.ArtworkCache
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.model.MetadataWriteState
import com.example.model.Track
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The single canonical artwork detection authority for SoundSync.
 *
 * Every feature that needs to know whether a track has artwork MUST use this detector:
 * - Local Library filters (No Cover Art, Has Cover Art)
 * - Library Insights (Missing Artwork count)
 * - Library Health Dashboard
 * - Library Doctor Auditor
 * - Smart Crates (HAS_ARTWORK rule)
 * - Command Palette (missing artwork queries)
 * - Album & Artist detail views
 * - Track rows and metadata viewers
 *
 * It enforces that fallback drawables, dummy placeholders, stale database paths,
 * broken content URIs, and missing files evaluate to NO_ARTWORK.
 */
object CanonicalArtworkDetector {
    private const val TAG = "CanonicalArtDetector"

    // In-memory cache for fast lookup during 60fps scrolling & large library queries
    private val statusCache = ConcurrentHashMap<String, ArtworkStatus>()

    // Manual status override cache (e.g. when user manually attaches or removes artwork)
    private val statusOverrideCache = ConcurrentHashMap<String, ArtworkStatus>()

    /**
     * Detects the canonical artwork presence status for a track.
     */
    fun detectArtworkStatus(context: Context? = null, track: Track): ArtworkStatus {
        // Fast path 0: Manual user override
        statusOverrideCache[track.id]?.let { return it }

        // Fast path 1: In-memory evaluation cache keyed by track ID + artwork attributes
        val cacheKey = buildCacheKey(track)
        statusCache[cacheKey]?.let { return it }

        val resolvedStatus = evaluateArtworkStatus(context, track)
        statusCache[cacheKey] = resolvedStatus
        return resolvedStatus
    }

    /**
     * Convenience boolean check returning true if the track has real, usable artwork.
     */
    fun hasArtwork(context: Context? = null, track: Track): Boolean {
        return detectArtworkStatus(context, track) == ArtworkStatus.HAS_ARTWORK
    }

    /**
     * Convenience boolean check returning true if the track is missing artwork.
     */
    fun isMissingArtwork(context: Context? = null, track: Track): Boolean {
        return detectArtworkStatus(context, track) == ArtworkStatus.NO_ARTWORK
    }

    /**
     * Set explicit artwork status for a track (e.g. user manually selected artwork or removed artwork).
     */
    fun setTrackArtworkStatus(trackId: String, status: ArtworkStatus) {
        statusOverrideCache[trackId] = status
        invalidate(trackId)
    }

    /**
     * Invalidate cached detection status for a track when its metadata/files change.
     */
    fun invalidate(trackId: String) {
        val keysToRemove = statusCache.keys.filter { it.startsWith("$trackId:") || it == trackId }
        for (k in keysToRemove) {
            statusCache.remove(k)
        }
    }

    /**
     * Remove explicit status override for a track, reverting to natural evaluation.
     */
    fun removeOverride(trackId: String) {
        statusOverrideCache.remove(trackId)
        invalidate(trackId)
    }

    /**
     * Clear all cached evaluations.
     */
    fun clearCache() {
        statusCache.clear()
        statusOverrideCache.clear()
    }

    /**
     * Batch-evaluate a list of tracks efficiently.
     */
    fun batchDetect(context: Context? = null, tracks: List<Track>): Map<String, ArtworkStatus> {
        val results = HashMap<String, ArtworkStatus>(tracks.size)
        for (track in tracks) {
            results[track.id] = detectArtworkStatus(context, track)
        }
        return results
    }

    /**
     * Checks whether an artwork reference string is empty, invalid, a placeholder, or a fallback.
     */
    fun isPlaceholderOrUnusable(ref: String?): Boolean {
        if (ref.isNullOrBlank()) return true
        val trimmed = ref.trim()

        if (trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("none", ignoreCase = true) ||
            trimmed.equals("undefined", ignoreCase = true) ||
            trimmed.equals("empty", ignoreCase = true) ||
            trimmed.equals("blank", ignoreCase = true)
        ) {
            return true
        }

        // Android internal resource / drawable URLs
        if (trimmed.startsWith("android.resource://", ignoreCase = true)) {
            return true
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        val placeholderTokens = listOf(
            "placeholder",
            "default_album",
            "default_cover",
            "default_artwork",
            "generic_album",
            "generic_art",
            "music_note",
            "musicnote",
            "ic_music",
            "ic_album",
            "no_art",
            "no_artwork",
            "no_cover",
            "nocover",
            "fallback",
            "dummy",
            "unknown_album",
            "unknown_art",
            "empty_art",
            "broken_art"
        )
        if (placeholderTokens.any { lower.contains(it) }) {
            return true
        }

        return false
    }

    private fun buildCacheKey(track: Track): String {
        return "${track.id}:${track.artworkCachePath.orEmpty()}:${track.artworkUrl.orEmpty()}:" +
                "${track.artworkSource.orEmpty()}:${track.metadataWriteState}:${track.fileModifiedTimestamp}:${track.filePath}"
    }

    private fun evaluateArtworkStatus(context: Context?, track: Track): ArtworkStatus {
        val effectiveContext = context ?: SoundSyncApplication.instance

        // 1. Check artworkCachePath
        val cachePath = track.artworkCachePath
        if (!isPlaceholderOrUnusable(cachePath)) {
            val localPath = if (cachePath!!.startsWith("file://", ignoreCase = true)) {
                try {
                    Uri.parse(cachePath).path ?: cachePath.removePrefix("file://")
                } catch (_: Throwable) {
                    cachePath.removePrefix("file://")
                }
            } else {
                cachePath
            }
            val cacheFile = File(localPath)
            if (cacheFile.exists() && cacheFile.isFile && cacheFile.length() > 0L) {
                return ArtworkStatus.HAS_ARTWORK
            }
        }

        // 2. Check artworkUrl
        val artUrl = track.artworkUrl
        if (!isPlaceholderOrUnusable(artUrl)) {
            val urlStr = artUrl!!.trim()
            when {
                urlStr.startsWith("http://", ignoreCase = true) || urlStr.startsWith("https://", ignoreCase = true) -> {
                    val isValidHttp = try {
                        val parsed = Uri.parse(urlStr)
                        val host = parsed.host
                        !host.isNullOrBlank() && !host.contains("example.com") && !parsed.path.orEmpty().contains("placeholder")
                    } catch (_: Throwable) {
                        false
                    }
                    if (isValidHttp) {
                        return ArtworkStatus.HAS_ARTWORK
                    }
                }
                urlStr.startsWith("file://", ignoreCase = true) || urlStr.startsWith("/") -> {
                    val path = if (urlStr.startsWith("file://", ignoreCase = true)) {
                        try {
                            Uri.parse(urlStr).path ?: urlStr.removePrefix("file://")
                        } catch (_: Throwable) {
                            urlStr.removePrefix("file://")
                        }
                    } else {
                        urlStr
                    }
                    val localFile = File(path)
                    if (localFile.exists() && localFile.isFile && localFile.length() > 0L) {
                        return ArtworkStatus.HAS_ARTWORK
                    }
                }
                urlStr.startsWith("content://", ignoreCase = true) -> {
                    if (effectiveContext != null) {
                        val isUsableContent = try {
                            val uri = Uri.parse(urlStr)
                            effectiveContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                                afd.length > 0L || afd.length == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
                            } ?: effectiveContext.contentResolver.openInputStream(uri)?.use { stream ->
                                stream.available() > 0 || stream.read() != -1
                            } ?: false
                        } catch (_: Throwable) {
                            false
                        }
                        if (isUsableContent) {
                            return ArtworkStatus.HAS_ARTWORK
                        }
                    }
                }
            }
        }

        // 3. Check SoundSync internal ArtworkCache
        if (effectiveContext != null) {
            try {
                val artworkCache = ArtworkCache(effectiveContext)
                val trackCacheFile = artworkCache.getCachedArtworkFileForTrack(track.id)
                if (trackCacheFile != null && trackCacheFile.exists() && trackCacheFile.isFile && trackCacheFile.length() > 0L) {
                    return ArtworkStatus.HAS_ARTWORK
                }

                // Check cached album artwork if artist and album are valid and not generic
                if (track.artist.isNotBlank() &&
                    !track.artist.equals("Unknown Artist", ignoreCase = true) &&
                    !track.album.isNullOrBlank() &&
                    !track.album.equals("Single", ignoreCase = true) &&
                    !track.album.equals("Unknown Album", ignoreCase = true)
                ) {
                    val albumCacheFile = artworkCache.getCachedArtworkFile(track.artist, track.album)
                    if (albumCacheFile != null && albumCacheFile.exists() && albumCacheFile.isFile && albumCacheFile.length() > 0L) {
                        return ArtworkStatus.HAS_ARTWORK
                    }
                }
            } catch (_: Throwable) {}
        }

        // 4. Check explicit metadata flags if the underlying audio file exists
        if (track.artworkSource in listOf("Embedded Tag", "Embedded", "Local Folder", "Apple iTunes", "TheAudioDB") ||
            track.metadataWriteState == MetadataWriteState.ARTWORK_SAVED.name ||
            track.metadataWriteState == MetadataWriteState.ARTWORK_EMBEDDED.name
        ) {
            if (track.filePath.isNotBlank()) {
                val audioFile = File(track.filePath)
                if (audioFile.exists() && audioFile.isFile && audioFile.length() > 0L) {
                    return ArtworkStatus.HAS_ARTWORK
                }
            }
        }

        // 5. Check MediaStore album art
        if (track.mediaStoreId != null && effectiveContext != null) {
            try {
                val albumArtUri = Uri.parse("content://media/external/audio/media/${track.mediaStoreId}/albumart")
                val isMediaStoreArtPresent = effectiveContext.contentResolver.openFileDescriptor(albumArtUri, "r")?.use { pfd ->
                    pfd.statSize > 0L || pfd.statSize == -1L
                } ?: false
                if (isMediaStoreArtPresent) {
                    return ArtworkStatus.HAS_ARTWORK
                }
            } catch (_: Throwable) {}
        }

        // 6. Check embedded tags inside the audio file
        if (track.filePath.isNotBlank() && !track.filePath.startsWith("content://", ignoreCase = true)) {
            val audioFile = File(track.filePath)
            if (audioFile.exists() && audioFile.isFile && audioFile.length() > 1024L) {
                try {
                    val embedded = AudioEmbeddedMetadataReader.read(effectiveContext, track.filePath, includeArtworkBytes = false)
                    if (embedded.hasEmbeddedArtwork) {
                        return ArtworkStatus.HAS_ARTWORK
                    }
                } catch (_: Throwable) {}
            }
        }

        // 7. Check folder artwork (restricted to genuine album directories, not shared root directories)
        if (track.filePath.isNotBlank() &&
            !track.album.isNullOrBlank() &&
            !track.album.equals("Single", ignoreCase = true) &&
            !track.album.equals("Unknown Album", ignoreCase = true)
        ) {
            try {
                val parentFile = File(track.filePath).parentFile
                if (parentFile != null && parentFile.exists() && parentFile.isDirectory) {
                    val parentPath = parentFile.canonicalPath.lowercase(Locale.ROOT)
                    val isSharedRoot = parentPath in listOf(
                        "/",
                        "/storage",
                        "/storage/emulated",
                        "/storage/emulated/0",
                        "/storage/emulated/0/music",
                        "/storage/emulated/0/download",
                        "/storage/emulated/0/downloads",
                        "/sdcard",
                        "/sdcard/music",
                        "/sdcard/download",
                        "/sdcard/downloads"
                    ) || parentPath.endsWith("/music") || parentPath.endsWith("/download") || parentPath.endsWith("/downloads")

                    if (!isSharedRoot) {
                        val validImageNames = setOf(
                            "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
                            "folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
                            "album.jpg", "album.jpeg", "album.png", "album.webp",
                            "front.jpg", "front.jpeg", "front.png", "front.webp"
                        )
                        val folderArtFile = parentFile.listFiles()?.firstOrNull { f ->
                            f.isFile && f.length() > 100L && validImageNames.contains(f.name.lowercase(Locale.ROOT))
                        }
                        if (folderArtFile != null) {
                            return ArtworkStatus.HAS_ARTWORK
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return ArtworkStatus.NO_ARTWORK
    }
}
