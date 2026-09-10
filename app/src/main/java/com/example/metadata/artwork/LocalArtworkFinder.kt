package com.example.metadata.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.metadata.ArtworkCache
import com.example.model.Track
import com.example.storage.CanonicalStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stage 1: Inspects the local audio file for embedded picture tags (ID3 APIC, MP4/M4A covr,
 * FLAC picture, Vorbis comment) and common folder artwork files (cover.jpg, folder.jpg, etc.).
 */
class LocalArtworkFinder(
    private val context: Context,
    private val artworkCache: ArtworkCache = ArtworkCache(context)
) {
    companion object {
        private const val TAG = "LocalArtworkFinder"

        private val FOLDER_ARTWORK_PATTERNS = listOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "album.jpg", "album.jpeg", "album.png",
            "front.jpg", "front.jpeg", "front.png",
            "art.jpg", "art.jpeg", "art.png"
        )
    }

    /**
     * Attempts to find valid local artwork for the track.
     */
    suspend fun findLocalArtwork(track: Track): LocalArtworkResult? = withContext(Dispatchers.IO) {
        if (track.filePath.isBlank()) return@withContext null

        // 1. First check if we already have it in the ArtworkCache for this track
        val cachedTrackFile = artworkCache.getCachedArtworkFileForTrack(track.id)
        if (cachedTrackFile != null && cachedTrackFile.exists() && cachedTrackFile.length() > 0) {
            val (w, h) = getImageDimensions(cachedTrackFile)
            return@withContext LocalArtworkResult(
                file = cachedTrackFile,
                source = "Artwork Cache",
                isEmbedded = false,
                width = w,
                height = h
            )
        }

        // Also check if artist + album artwork exists in cache (deduplicated album cover)
        if (track.artist.isNotBlank() && !track.album.isNullOrBlank()) {
            val cachedAlbumFile = artworkCache.getCachedArtworkFile(track.artist, track.album)
            if (cachedAlbumFile != null && cachedAlbumFile.exists() && cachedAlbumFile.length() > 0) {
                val (w, h) = getImageDimensions(cachedAlbumFile)
                return@withContext LocalArtworkResult(
                    file = cachedAlbumFile,
                    source = "Artwork Cache (Album)",
                    isEmbedded = false,
                    width = w,
                    height = h
                )
            }
        }

        // 2. Check for embedded artwork in the audio file container
        val embeddedResult = extractEmbeddedArtwork(track)
        if (embeddedResult != null) {
            return@withContext embeddedResult
        }

        // 3. Check for folder-level artwork files (cover.jpg, folder.jpg, etc.)
        val folderResult = findFolderArtwork(track)
        if (folderResult != null) {
            return@withContext folderResult
        }

        null
    }

    /**
     * Extracts embedded picture data using MediaMetadataRetriever and caches it.
     */
    private fun extractEmbeddedArtwork(track: Track): LocalArtworkResult? {
        val retriever = MediaMetadataRetriever()
        try {
            if (track.filePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(track.filePath))
            } else {
                val file = File(track.filePath)
                if (!file.exists() || !file.canRead()) return null
                retriever.setDataSource(track.filePath)
            }

            val pictureBytes = retriever.embeddedPicture ?: return null
            if (pictureBytes.isEmpty()) return null

            val (w, h, mime) = getImageBoundsAndMime(pictureBytes)
            if (w <= 0 || h <= 0) return null

            // Save to persistent ArtworkCache for fast access
            val savedFile = artworkCache.saveArtworkForTrack(
                trackId = track.id,
                bytes = pictureBytes,
                mimeType = mime,
                sourceProvider = "Embedded Tag"
            )

            // Also save album deduplicated file if artist and album are known
            if (track.artist.isNotBlank() && !track.album.isNullOrBlank()) {
                val albumKey = generateAlbumKey(track.artist, track.album)
                artworkCache.saveArtworkBytes(
                    key = albumKey,
                    bytes = pictureBytes,
                    mimeType = mime,
                    sourceProvider = "Embedded Tag",
                    artist = track.artist,
                    album = track.album
                )
            }

            Log.d(TAG, "Extracted embedded artwork for \"${track.title}\" (${w}x${h})")
            return LocalArtworkResult(
                file = savedFile,
                source = "Embedded Tag",
                isEmbedded = true,
                width = w,
                height = h
            )
        } catch (e: Exception) {
            Log.d(TAG, "Non-fatal error reading embedded artwork for ${track.filePath}: ${e.message}")
            return null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Inspects the track's parent directory for common artwork filenames.
     */
    private fun findFolderArtwork(track: Track): LocalArtworkResult? {
        val resolvedPath = CanonicalStorageHelper.toCanonicalPath(track.filePath).takeIf { it.isNotBlank() } ?: track.filePath
        val audioFile = File(resolvedPath)
        val parentDir = audioFile.parentFile ?: return null
        if (!parentDir.exists() || !parentDir.isDirectory) return null

        try {
            val siblingFiles = parentDir.listFiles() ?: return null

            for (pattern in FOLDER_ARTWORK_PATTERNS) {
                val matched = siblingFiles.firstOrNull { it.name.equals(pattern, ignoreCase = true) }
                if (matched != null && matched.exists() && matched.length() > 100) {
                    val (w, h) = getImageDimensions(matched)
                    if (w > 0 && h > 0) {
                        Log.d(TAG, "Found folder artwork: ${matched.absolutePath} (${w}x${h}) for track ${track.title}")

                        // Cache a reference or copy into artwork cache
                        val savedFile = try {
                            val bytes = matched.readBytes()
                            val mime = if (matched.name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                            artworkCache.saveArtworkForTrack(
                                trackId = track.id,
                                bytes = bytes,
                                mimeType = mime,
                                sourceProvider = "Local Folder (${matched.name})"
                            )
                        } catch (_: Exception) {
                            matched
                        }

                        return LocalArtworkResult(
                            file = savedFile,
                            source = "Local Folder (${matched.name})",
                            isEmbedded = false,
                            width = w,
                            height = h
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error checking folder artwork in ${parentDir.absolutePath}: ${e.message}")
        }
        return null
    }

    private fun getImageDimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return Pair(options.outWidth, options.outHeight)
    }

    private fun getImageBoundsAndMime(bytes: ByteArray): Triple<Int, Int, String> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val mime = options.outMimeType ?: "image/jpeg"
        return Triple(options.outWidth, options.outHeight, mime)
    }

    private fun generateAlbumKey(artist: String, album: String?): String {
        val raw = "${artist.trim().lowercase()}:${album?.trim()?.lowercase().orEmpty()}"
        val digest = java.security.MessageDigest.getInstance("MD5").digest(raw.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
