package com.example.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Authoritative Physical Media Identity Utility for SoundSync.
 *
 * Guarantees a permanent, stable identity for every physical audio track,
 * regardless of whether it is accessed via MediaStore content:// URIs,
 * SAF document URIs (content://com.android.externalstorage.documents/...),
 * file:// URIs, or raw Linux filesystem paths (/storage/emulated/0/... or /storage/UUID/...).
 *
 * Identity Hierarchy:
 *  1. MediaStore volume name + MediaStore ID: "ms:<volume>:<id>"
 *  2. Canonical filesystem path: "path:<canonical_path>"
 *  3. Fallback normalized URI: "uri:<clean_uri>"
 *
 * Never uses title, artist, album, or duration as identity.
 */
object PhysicalMediaIdentifier {

    private const val TAG = "PhysicalMediaIdentifier"

    /**
     * Computes the permanent physical media key for any audio reference.
     */
    fun computePhysicalMediaKey(
        context: Context?,
        filePathOrUri: String,
        trackId: String? = null,
        mediaId: Long? = null,
        volume: String? = null
    ): String {
        val clean = filePathOrUri.trim()
        if (clean.isBlank() || clean.startsWith("demo://") || clean.startsWith("http")) {
            return if (clean.isNotBlank()) "demo:$clean" else ""
        }

        // 1. If explicit MediaStore ID is provided
        if (mediaId != null && mediaId > 0L) {
            val vol = (volume ?: extractVolumeFromUri(clean) ?: "external").lowercase(Locale.ROOT)
            return "ms:$vol:$mediaId"
        }

        // 2. If trackId has media_ prefix (e.g. "media_1000014321")
        if (trackId != null && trackId.startsWith("media_")) {
            val parsedId = trackId.removePrefix("media_").toLongOrNull()
            if (parsedId != null && parsedId > 0L) {
                val vol = (volume ?: extractVolumeFromUri(clean) ?: "external").lowercase(Locale.ROOT)
                return "ms:$vol:$parsedId"
            }
        }

        // 3. If filePathOrUri is a direct MediaStore content URI
        if (clean.startsWith("content://media/")) {
            val parsedPair = extractMediaIdAndVolume(clean)
            if (parsedPair != null) {
                val (vol, id) = parsedPair
                return "ms:${vol.lowercase(Locale.ROOT)}:$id"
            }
        }

        // 4. If Context is available, check if the underlying path maps to a known MediaStore entry
        if (context != null) {
            val canonicalPath = CanonicalStorageHelper.toCanonicalPath(context, clean)
            if (canonicalPath.isNotBlank() && !canonicalPath.startsWith("content://")) {
                val msUri = TrackSourceResolver.findMediaStoreUriForPath(context, canonicalPath)
                if (msUri != null) {
                    val parsedPair = extractMediaIdAndVolume(msUri.toString())
                    if (parsedPair != null) {
                        val (vol, id) = parsedPair
                        return "ms:${vol.lowercase(Locale.ROOT)}:$id"
                    }
                }
                return "path:${canonicalPath.lowercase(Locale.ROOT)}"
            }
        }

        // 5. Canonical path fallback
        val canonical = CanonicalStorageHelper.toCanonicalPath(context, clean)
        if (canonical.isNotBlank() && !canonical.startsWith("content://")) {
            return "path:${canonical.lowercase(Locale.ROOT)}"
        }

        // 6. Direct SAF or content URI normalization
        return "uri:${clean.lowercase(Locale.ROOT)}"
    }

    /**
     * Extracts volume name and media ID from a MediaStore content URI string.
     * Examples:
     *  - content://media/external/audio/media/1000014321 -> ("external", 1000014321)
     *  - content://media/aa44-8296/audio/media/1000038010 -> ("aa44-8296", 1000038010)
     *  - content://media/external_primary/audio/media/555 -> ("external_primary", 555)
     */
    fun extractMediaIdAndVolume(uriString: String): Pair<String, Long>? {
        if (!uriString.startsWith("content://media/")) return null
        return try {
            val uri = Uri.parse(uriString)
            val id = uri.lastPathSegment?.toLongOrNull() ?: return null
            val segments = uri.pathSegments
            val vol = if (segments.isNotEmpty()) {
                val candidate = segments[0]
                if (candidate == "audio") "external" else candidate
            } else "external"
            Pair(vol, id)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts volume name from a MediaStore URI string.
     */
    fun extractVolumeFromUri(uriString: String): String? {
        if (!uriString.startsWith("content://media/")) return null
        return try {
            val uri = Uri.parse(uriString)
            val segments = uri.pathSegments
            if (segments.isNotEmpty()) {
                val candidate = segments[0]
                if (candidate == "audio") "external" else candidate
            } else "external"
        } catch (_: Exception) {
            null
        }
    }
}
