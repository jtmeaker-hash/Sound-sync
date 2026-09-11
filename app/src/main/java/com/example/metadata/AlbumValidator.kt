package com.example.metadata

import com.example.metadata.parser.TrackIdentityParser
import java.io.File

/**
 * Utility object to validate album metadata values.
 *
 * Rules:
 *   1. Must be non‑blank.
 *   2. Must not be a generic placeholder like "Single" or "Unknown Album".
 *   3. Must not match any generic folder name identified by TrackIdentityParser.
 *   4. Must not be equal (case‑insensitive) to any directory component of the track's file path.
 */
object AlbumValidator {
    /**
     * Returns true if the supplied [album] is considered a valid album name for the track at [filePath].
     */
    fun isValidAlbum(album: String?, filePath: String): Boolean {
        if (album.isNullOrBlank()) return false
        val trimmed = album.trim()
        if (trimmed.equals("Single", ignoreCase = true) || trimmed.equals("Unknown Album", ignoreCase = true)) {
            return false
        }
        // Reject generic folder names identified by the existing parser logic.
        if (TrackIdentityParser.isGenericAlbumName(trimmed)) return false
        // Reject if album equals any directory name in the path.
        val parent = File(filePath).parent ?: return true
        val pathSegments = parent.split('/')
            .map { it.trim().lowercase() }
        if (trimmed.lowercase() in pathSegments) return false
        return true
    }
}
