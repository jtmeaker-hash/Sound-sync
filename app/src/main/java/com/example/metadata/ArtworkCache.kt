package com.example.metadata

import android.content.Context
import android.util.Log
import com.example.metadata.theaudiodb.DownloadedArtwork
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CachedArtworkInfo(
    val file: File,
    val sourceProvider: String,
    val sourceUrl: String,
    val downloadTimestamp: Long,
    val dimensions: String,
    val artist: String,
    val album: String?
)

/**
 * Manages persistent local disk caching of verified album cover artwork.
 * Reference: Phase 15 requirements.
 */
class ArtworkCache(private val context: Context) {

    private val cacheDir = File(context.filesDir, "artwork_cache").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private const val TAG = "ArtworkCache"
    }

    private fun generateCacheKey(artist: String, album: String?): String {
        val raw = "${artist.trim().lowercase()}:${album?.trim()?.lowercase().orEmpty()}"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getCachedArtworkFile(artist: String, album: String?): File? {
        val key = generateCacheKey(artist, album)
        val imageFile = File(cacheDir, "$key.jpg")
        return if (imageFile.exists() && imageFile.length() > 0) imageFile else null
    }

    fun hasCachedArtwork(artist: String, album: String?): Boolean {
        return getCachedArtworkFile(artist, album) != null
    }

    fun saveArtwork(
        artist: String,
        album: String?,
        artwork: DownloadedArtwork
    ): File {
        val key = generateCacheKey(artist, album)
        val imageFile = File(cacheDir, "$key.jpg")
        val metaFile = File(cacheDir, "$key.json")

        // Atomic write via temp file
        val tempFile = File(cacheDir, "$key.tmp")
        tempFile.writeBytes(artwork.bytes)
        if (imageFile.exists()) {
            imageFile.delete()
        }
        tempFile.renameTo(imageFile)

        // Save metadata record
        val meta = JSONObject().apply {
            put("sourceProvider", "TheAudioDB")
            put("sourceUrl", artwork.sourceUrl)
            put("downloadTimestamp", System.currentTimeMillis())
            put("dimensions", "${artwork.width}x${artwork.height}")
            put("artist", artist)
            put("album", album.orEmpty())
        }
        metaFile.writeText(meta.toString(2), StandardCharsets.UTF_8)

        Log.d(TAG, "Cached artwork to ${imageFile.absolutePath} (${artwork.width}x${artwork.height})")
        return imageFile
    }

    fun getCachedArtworkInfo(artist: String, album: String?): CachedArtworkInfo? {
        val file = getCachedArtworkFile(artist, album) ?: return null
        val key = generateCacheKey(artist, album)
        val metaFile = File(cacheDir, "$key.json")

        if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText(StandardCharsets.UTF_8))
                return CachedArtworkInfo(
                    file = file,
                    sourceProvider = json.optString("sourceProvider", "TheAudioDB"),
                    sourceUrl = json.optString("sourceUrl", ""),
                    downloadTimestamp = json.optLong("downloadTimestamp", file.lastModified()),
                    dimensions = json.optString("dimensions", "Unknown"),
                    artist = json.optString("artist", artist),
                    album = json.optString("album", album)
                )
            } catch (_: Exception) {}
        }

        return CachedArtworkInfo(
            file = file,
            sourceProvider = "TheAudioDB",
            sourceUrl = "",
            downloadTimestamp = file.lastModified(),
            dimensions = "Unknown",
            artist = artist,
            album = album
        )
    }
}
