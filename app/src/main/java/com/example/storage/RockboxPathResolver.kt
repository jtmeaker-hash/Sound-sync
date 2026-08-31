package com.example.storage

import android.net.Uri
import android.util.Log
import com.example.model.Track
import java.io.File

/**
 * Rockbox-compatible relative path resolution and normalization engine.
 * Ensures all exported .m3u8 playlists contain storage-relative, clean POSIX paths
 * compatible with Rockbox firmware, Hiby, iPod classic (Rockbox), and portable DAPs.
 */
object RockboxPathResolver {

    private const val TAG = "RockboxPathResolver"

    /**
     * Calculates the relative path from the playlist file's directory to the target track's relative path.
     *
     * Example:
     * - playlistRelativePath: "Playlists/MyMix.m3u8"
     * - trackRelativePath: "Music/Daft Punk/Discovery/01 - One More Time.flac"
     * - Output: "../Music/Daft Punk/Discovery/01 - One More Time.flac"
     */
    fun calculateRelativePath(
        playlistRelativePath: String,
        trackRelativePath: String
    ): String {
        val normalizedPlaylist = normalizePath(playlistRelativePath)
        val normalizedTrack = normalizePath(trackRelativePath)

        // If either is blank, return whatever track path is available
        if (normalizedPlaylist.isBlank() || normalizedTrack.isBlank()) {
            return normalizedTrack
        }

        // Get the directory containing the playlist
        val playlistDir = if (normalizedPlaylist.contains("/")) {
            normalizedPlaylist.substringBeforeLast("/")
        } else {
            ""
        }

        if (playlistDir.isBlank()) {
            // Playlist is in the root directory, relative path is just track's relative path
            return normalizedTrack
        }

        val playlistSegments = playlistDir.split("/").filter { it.isNotBlank() }
        val trackSegments = normalizedTrack.split("/").filter { it.isNotBlank() }

        // Find common prefix segments
        var commonPrefixLength = 0
        val maxCommon = minOf(playlistSegments.size, trackSegments.size - 1) // do not include filename in common prefix
        while (commonPrefixLength < maxCommon &&
            playlistSegments[commonPrefixLength].equals(trackSegments[commonPrefixLength], ignoreCase = false)
        ) {
            commonPrefixLength++
        }

        val upCount = playlistSegments.size - commonPrefixLength
        val relativeParts = mutableListOf<String>()

        for (i in 0 until upCount) {
            relativeParts.add("..")
        }

        for (i in commonPrefixLength until trackSegments.size) {
            relativeParts.add(trackSegments[i])
        }

        return relativeParts.joinToString("/")
    }

    /**
     * Resolves a relative path entry found in an M3U file back to its normalized storage-relative path.
     *
     * Example:
     * - playlistDir: "Playlists"
     * - entry: "../Music/Queen/Bohemian Rhapsody.flac"
     * - Output: "Music/Queen/Bohemian Rhapsody.flac"
     */
    fun resolveTrackPathFromPlaylistEntry(
        playlistDir: String,
        entry: String
    ): String {
        val trimmed = entry.trim().replace('\\', '/')
        if (trimmed.startsWith("/")) {
            // Absolute path in M3U file (e.g., /Music/...)
            return trimmed.trimStart('/')
        }

        val cleanEntry = normalizePath(trimmed)
        val normalizedPlaylistDir = normalizePath(playlistDir)
        val combined = if (normalizedPlaylistDir.isNotBlank()) {
            "$normalizedPlaylistDir/$cleanEntry"
        } else {
            cleanEntry
        }

        return normalizePathComponents(combined)
    }

    /**
     * Computes the storage-relative path for any track given its filePath and optional directory.
     * Strips scheme prefixes, Android content URIs, /storage/emulated/0/, /storage/XXXX-XXXX/, etc.
     */
    fun computeStorageRelativePath(filePath: String, directoryPath: String = ""): String {
        if (filePath.isBlank()) return ""

        // Handle SAF URIs with document ID (using URLDecoder for JVM test safety)
        if (filePath.startsWith("content://")) {
            val decoded = try {
                java.net.URLDecoder.decode(filePath, "UTF-8")
            } catch (e: Exception) {
                filePath
            }
            if (decoded.contains("document/")) {
                val docId = decoded.substringAfter("document/")
                if (docId.contains(":")) {
                    val rel = docId.substringAfter(":")
                    return normalizePath(rel)
                }
            } else if (decoded.contains("tree/")) {
                val docId = decoded.substringAfter("tree/")
                if (docId.contains(":")) {
                    val rel = docId.substringAfter(":")
                    return normalizePath(rel)
                }
            }
        }

        val cleanPath = filePath.removePrefix("file://")
        val normalized = normalizePath(cleanPath)

        val relative = when {
            normalized.contains("storage/emulated/0/") -> {
                normalized.substringAfter("storage/emulated/0/").trimStart('/')
            }
            normalized.contains("storage/") -> {
                // Secondary SD card e.g., storage/1234-5678/Music/...
                val afterStorage = normalized.substringAfter("storage/")
                if (afterStorage.contains("/")) {
                    afterStorage.substringAfter("/").trimStart('/')
                } else {
                    afterStorage
                }
            }
            normalized.contains("sdcard/") -> {
                normalized.substringAfter("sdcard/").trimStart('/')
            }
            normalized.startsWith("/") -> {
                normalized.trimStart('/')
            }
            else -> normalized
        }

        return relative
    }

    /**
     * Normalizes backslashes to forward slashes, trims whitespace, removes leading ./
     */
    fun normalizePath(path: String): String {
        return path
            .replace('\\', '/')
            .trim()
            .removePrefix("./")
            .trimStart('/')
    }

    /**
     * Collapses '..' and '.' path components.
     */
    fun normalizePathComponents(path: String): String {
        val raw = normalizePath(path)
        val segments = raw.split("/").filter { it.isNotBlank() && it != "." }
        val stack = mutableListOf<String>()

        for (segment in segments) {
            if (segment == "..") {
                if (stack.isNotEmpty() && stack.last() != "..") {
                    stack.removeAt(stack.size - 1)
                } else {
                    // Cannot go above root, or keeping as relative
                }
            } else {
                stack.add(segment)
            }
        }

        return stack.joinToString("/")
    }

    /**
     * Detects if a collection of tracks spans multiple distinct storage volumes.
     * Rockbox playlists cannot cross physical storage volumes if copied to a single microSD card.
     */
    fun detectCrossStorageMismatch(tracks: List<Track>): Boolean {
        if (tracks.size <= 1) return false
        val sourceIds = tracks.map { it.sourceId }.filter { it.isNotBlank() }.toSet()
        if (sourceIds.size > 1) return true

        // Also check root prefixes for different SD card IDs
        val volumes = tracks.mapNotNull { track ->
            val p = track.filePath.removePrefix("file://")
            when {
                p.contains("/storage/emulated/0") -> "primary"
                p.contains("/storage/") -> {
                    val vol = p.substringAfter("/storage/").substringBefore("/")
                    vol.ifBlank { "primary" }
                }
                else -> null
            }
        }.toSet()

        return volumes.size > 1
    }
}
