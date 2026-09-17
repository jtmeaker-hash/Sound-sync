package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.example.metadata.ArtworkCache
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.artwork.CanonicalArtworkResolver
import com.example.metadata.artwork.LocalArtworkFinder
import com.example.model.Album
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Authoritative Canonical Artwork Resolver & Memory Cache.
 * Implements [CanonicalArtworkResolver] with strict priority:
 *
 * 1. User-selected / Custom artwork (userConfirmedMetadata or explicit manual source)
 * 2. SoundSync DB reference / persistent disk cache (artworkCachePath, ArtworkCache)
 * 3. MediaStore album art / content URI
 * 4. Embedded audio tags (ID3v2 APIC, Vorbis PICTURE, MP4 covr) via MediaMetadataRetriever and AudioEmbeddedMetadataReader
 * 5. Folder cover art (LocalArtworkFinder for cover.jpg / folder.jpg in track directory)
 * 6. Deterministic vinyl fallback (isolated in fallbackCache so real art is never shadowed)
 */
object AlbumArtHelper : CanonicalArtworkResolver {

    private const val TAG = "AlbumArtHelper"
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(16 * 1024, 64 * 1024)

    // Primary cache for verified real artwork bitmaps
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    // Secondary cache for deterministic vinyl placeholders (never collides with real art)
    private val fallbackCache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val _artworkInvalidationFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow()

    fun computeCacheKey(track: Track, sizePx: Int): String {
        val artRef = track.artworkCachePath ?: track.artworkUrl ?: ""
        val mod = track.fileModifiedTimestamp
        return "${track.id}_${artRef.hashCode()}_${mod}_${track.filePath.hashCode()}_$sizePx"
    }

    fun computeAlbumCacheKey(album: Album, sizePx: Int): String {
        val artRef = album.artworkUri ?: ""
        return "album_${album.id}_${artRef.hashCode()}_$sizePx"
    }

    /**
     * Synchronously checks if artwork for [track] at [sizePx] is already resident in memory cache.
     * Allows Composable cards to render immediately without blank/flicker frames.
     */
    override fun getCachedArtwork(track: Track, sizePx: Int): Bitmap? {
        val cacheKey = computeCacheKey(track, sizePx)
        return memoryCache.get(cacheKey) ?: fallbackCache.get("fallback_${track.id}_$sizePx")
    }

    /**
     * Synchronously checks if album artwork is already resident in memory cache.
     */
    override fun getCachedArtworkForAlbum(album: Album, sizePx: Int): Bitmap? {
        val cacheKey = computeAlbumCacheKey(album, sizePx)
        return memoryCache.get(cacheKey) ?: fallbackCache.get("fallback_album_${album.id}_$sizePx")
    }

    override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap = withContext(Dispatchers.IO) {
        val cacheKey = computeCacheKey(track, sizePx)
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // PRIORITY 1: User-Selected / Custom Artwork
        if (track.userConfirmedMetadata || track.artworkSource in listOf("Manual Selection", "User Selected", "Custom")) {
            val userPath = track.artworkCachePath ?: track.artworkUrl?.takeIf { it.startsWith("/") || it.startsWith("file://") }
            if (!userPath.isNullOrBlank()) {
                val actualPath = if (userPath.startsWith("file://")) Uri.parse(userPath).path else userPath
                if (actualPath != null) {
                    val file = File(actualPath)
                    if (file.exists() && file.canRead()) {
                        decodeFileToBitmap(file, sizePx)?.let { decoded ->
                            memoryCache.put(cacheKey, decoded)
                            return@withContext decoded
                        }
                    }
                }
            }
        }

        // PRIORITY 2: SoundSync DB Reference / Persistent Disk Cache
        val cachePath = track.artworkCachePath ?: track.artworkUrl?.takeIf { it.startsWith("/") || it.startsWith("file://") }
        if (!cachePath.isNullOrBlank()) {
            val actualPath = if (cachePath.startsWith("file://")) Uri.parse(cachePath).path else cachePath
            if (actualPath != null) {
                val cachedFile = File(actualPath)
                if (cachedFile.exists() && cachedFile.canRead()) {
                    decodeFileToBitmap(cachedFile, sizePx)?.let { decoded ->
                        memoryCache.put(cacheKey, decoded)
                        return@withContext decoded
                    }
                }
            }
        }

        try {
            val artworkCache = ArtworkCache(context)
            val cachedFile = artworkCache.getCachedArtworkFileForTrack(track.id)
                ?: if (track.artist.isNotBlank() && track.album.isNotBlank() && track.album != "Single") {
                    artworkCache.getCachedArtworkFile(track.artist, track.album)
                } else null

            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                decodeFileToBitmap(cachedFile, sizePx)?.let { decoded ->
                    memoryCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Exception) {}

        // PRIORITY 3: MediaStore album art / content URI
        val artUrl = track.artworkUrl
        if (!artUrl.isNullOrBlank() && artUrl.startsWith("content://")) {
            try {
                context.contentResolver.openInputStream(Uri.parse(artUrl))?.use { stream ->
                    decodeStreamToBitmap(stream, sizePx)?.let { decoded ->
                        memoryCache.put(cacheKey, decoded)
                        return@withContext decoded
                    }
                }
            } catch (_: Exception) {}
        }
        if (track.mediaStoreId != null) {
            try {
                val albumArtUri = Uri.parse("content://media/external/audio/media/${track.mediaStoreId}/albumart")
                context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                    decodeStreamToBitmap(stream, sizePx)?.let { decoded ->
                        memoryCache.put(cacheKey, decoded)
                        return@withContext decoded
                    }
                }
            } catch (_: Exception) {}
        }

        // PRIORITY 4: Embedded Artwork in the Audio File (MP3, FLAC, M4A/AAC)
        val embeddedBitmap = extractEmbeddedPicture(context, track.filePath, sizePx)
            ?: (if (!track.resolvedUri.isNullOrBlank() && track.resolvedUri != track.filePath) extractEmbeddedPicture(context, track.resolvedUri, sizePx) else null)
        if (embeddedBitmap != null) {
            memoryCache.put(cacheKey, embeddedBitmap)
            return@withContext embeddedBitmap
        }

        // Fallback embedded audio metadata reader for specialized tags/formats
        try {
            val embeddedMeta = AudioEmbeddedMetadataReader.read(context, track.filePath, includeArtworkBytes = true)
            if (embeddedMeta.hasEmbeddedArtwork && embeddedMeta.embeddedArtworkBytes != null) {
                decodeByteArrayToBitmap(embeddedMeta.embeddedArtworkBytes, sizePx)?.let { decoded ->
                    memoryCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Exception) {}

        // PRIORITY 5: Folder / Directory Cover Art (cover.jpg, folder.jpg)
        try {
            val localFinder = LocalArtworkFinder(context)
            val localResult = localFinder.findLocalArtwork(track)
            if (localResult != null && localResult.file.exists() && localResult.file.length() > 0) {
                decodeFileToBitmap(localResult.file, sizePx)?.let { decoded ->
                    memoryCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Exception) {}

        // PRIORITY 6: Deterministic Fallback Vinyl Placeholder
        val fallbackKey = "fallback_${track.id}_$sizePx"
        fallbackCache.get(fallbackKey)?.let { return@withContext it }
        val generated = generateFallbackArtwork(track, sizePx)
        fallbackCache.put(fallbackKey, generated)
        generated
    }

    override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap = withContext(Dispatchers.IO) {
        val cacheKey = computeAlbumCacheKey(album, sizePx)
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // 1. Check explicit album.artworkUri if present
        val artUri = album.artworkUri
        if (!artUri.isNullOrBlank()) {
            if (artUri.startsWith("/") || artUri.startsWith("file://")) {
                val path = if (artUri.startsWith("file://")) Uri.parse(artUri).path else artUri
                if (path != null) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        decodeFileToBitmap(file, sizePx)?.let { decoded ->
                            memoryCache.put(cacheKey, decoded)
                            return@withContext decoded
                        }
                    }
                }
            } else if (artUri.startsWith("content://")) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(artUri))?.use { stream ->
                        decodeStreamToBitmap(stream, sizePx)?.let { decoded ->
                            memoryCache.put(cacheKey, decoded)
                            return@withContext decoded
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Check ArtworkCache by artist + album
        try {
            val artworkCache = ArtworkCache(context)
            val cachedFile = artworkCache.getCachedArtworkFile(album.artist, album.title)
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                decodeFileToBitmap(cachedFile, sizePx)?.let { decoded ->
                    memoryCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Exception) {}

        // 3. Deterministic check across member tracks
        val sortedTracks = album.tracks.sortedWith(
            compareBy<Track> { it.discNumber }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )

        // 3a. First check tracks with explicit disk cache path
        for (track in sortedTracks) {
            val trackCache = track.artworkCachePath ?: track.artworkUrl?.takeIf { it.startsWith("/") || it.startsWith("file://") }
            if (!trackCache.isNullOrBlank()) {
                val path = if (trackCache.startsWith("file://")) Uri.parse(trackCache).path else trackCache
                if (path != null && File(path).exists() && File(path).canRead()) {
                    decodeFileToBitmap(File(path), sizePx)?.let { decoded ->
                        memoryCache.put(cacheKey, decoded)
                        return@withContext decoded
                    }
                }
            }
        }

        // 3b. Next check embedded artwork in member tracks
        for (track in sortedTracks) {
            val embedded = extractEmbeddedPicture(context, track.filePath, sizePx)
            if (embedded != null) {
                memoryCache.put(cacheKey, embedded)
                return@withContext embedded
            }
        }

        // 4. Check folder artwork in directory of first track
        val firstDir = sortedTracks.firstOrNull()?.directoryPath ?: sortedTracks.firstOrNull()?.filePath?.let {
            if (it.contains("/")) it.substringBeforeLast("/") else null
        }
        if (!firstDir.isNullOrBlank() && !firstDir.startsWith("content://")) {
            val dir = File(firstDir)
            if (dir.exists() && dir.isDirectory) {
                val candidates = listOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "cover.png", "folder.png")
                for (name in candidates) {
                    val coverFile = File(dir, name)
                    if (coverFile.exists() && coverFile.canRead() && coverFile.length() > 0) {
                        decodeFileToBitmap(coverFile, sizePx)?.let { decoded ->
                            memoryCache.put(cacheKey, decoded)
                            return@withContext decoded
                        }
                    }
                }
            }
        }

        // 5. Fallback vinyl artwork for this album
        val fallbackKey = "fallback_album_${album.id}_$sizePx"
        fallbackCache.get(fallbackKey)?.let { return@withContext it }
        val generated = generateFallbackArtwork(title = album.title, artist = album.artist, seedId = album.id, size = sizePx)
        fallbackCache.put(fallbackKey, generated)
        generated
    }

    override fun invalidateTrack(trackId: String, artist: String?, album: String?) {
        val snapshot = memoryCache.snapshot()
        for (key in snapshot.keys) {
            if (key.startsWith("${trackId}_") || key.contains("_${trackId}_")) {
                memoryCache.remove(key)
            }
        }
        fallbackCache.remove("fallback_${trackId}_512")
        fallbackCache.remove("fallback_${trackId}_320")

        if (!artist.isNullOrBlank() && !album.isNullOrBlank()) {
            invalidateAlbum(artist, album)
        }
        com.example.metadata.artwork.ArtworkStatusResolver.invalidate(trackId)
        _artworkInvalidationFlow.tryEmit(trackId)
        Log.d(TAG, "Invalidated artwork cache for track '$trackId'")
    }

    override fun invalidateAlbum(artist: String, album: String) {
        val snapshot = memoryCache.snapshot()
        val artistHash = artist.trim().lowercase().hashCode().toString()
        val albumHash = album.trim().lowercase().hashCode().toString()
        for (key in snapshot.keys) {
            if (key.startsWith("album_") && (key.contains(artistHash) || key.contains(albumHash))) {
                memoryCache.remove(key)
            }
        }
        val target = "${artist.trim().lowercase()}:::${album.trim().lowercase()}"
        _artworkInvalidationFlow.tryEmit(target)
        Log.d(TAG, "Invalidated artwork cache for album '$target'")
    }

    override fun clearMemoryCache() {
        memoryCache.evictAll()
        fallbackCache.evictAll()
        com.example.metadata.artwork.ArtworkStatusResolver.invalidateAll()
    }

    fun decodeStreamToBitmap(inputStream: InputStream, targetSize: Int): Bitmap? {
        return try {
            val bytes = inputStream.readBytes()
            decodeByteArrayToBitmap(bytes, targetSize)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode stream: ${e.message}")
            null
        }
    }

    fun decodeByteArrayToBitmap(bytes: ByteArray, targetSize: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var sampleSize = 1
            if (options.outHeight > targetSize || options.outWidth > targetSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / sampleSize) >= targetSize && (halfWidth / sampleSize) >= targetSize) {
                    sampleSize *= 2
                }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode byte array: ${e.message}")
            null
        }
    }

    fun decodeFileToBitmap(file: File, targetSize: Int): Bitmap? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            var sampleSize = 1
            if (options.outHeight > targetSize || options.outWidth > targetSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / sampleSize) >= targetSize && (halfWidth / sampleSize) >= targetSize) {
                    sampleSize *= 2
                }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode cached artwork file ${file.absolutePath}: ${e.message}")
            null
        }
    }

    private fun extractEmbeddedPicture(context: Context, uriOrPath: String, targetSize: Int): Bitmap? {
        if (uriOrPath.isBlank() || uriOrPath.startsWith("demo://")) return null

        val retriever = MediaMetadataRetriever()
        return try {
            if (uriOrPath.startsWith("content://")) {
                try {
                    context.contentResolver.openFileDescriptor(Uri.parse(uriOrPath), "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    } ?: return null
                } catch (_: Exception) {
                    retriever.setDataSource(context, Uri.parse(uriOrPath))
                }
            } else if (uriOrPath.startsWith("file://")) {
                val path = Uri.parse(uriOrPath).path
                if (path != null && File(path).canRead()) {
                    retriever.setDataSource(path)
                } else {
                    context.contentResolver.openFileDescriptor(Uri.parse(uriOrPath), "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    } ?: return null
                }
            } else {
                val f = File(uriOrPath)
                if (!f.exists() || !f.canRead()) return null
                retriever.setDataSource(uriOrPath)
            }

            val picture = retriever.embeddedPicture ?: return null
            decodeByteArrayToBitmap(picture, targetSize)
        } catch (e: Exception) {
            Log.v(TAG, "No embedded artwork for $uriOrPath: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    fun generateFallbackArtwork(track: Track, size: Int): Bitmap {
        return generateFallbackArtwork(track.title, track.artist, track.id, size)
    }

    fun generateFallbackArtwork(title: String, artist: String, seedId: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Generate consistent colors from track/album title + artist + id
        val hash = (title + artist + seedId).hashCode()
        val hue = (hash and 0xFFFF) % 360f
        val darkBg = Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.18f))
        val accentColor = Color.HSVToColor(floatArrayOf((hue + 45f) % 360f, 0.85f, 0.90f))
        val vinylColor = Color.rgb(24, 24, 28)

        // Background
        val bgPaint = Paint().apply {
            color = darkBg
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Vinyl record disc circle
        val discPaint = Paint().apply {
            color = vinylColor
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        val center = size / 2f
        val discRadius = size * 0.42f
        canvas.drawCircle(center, center, discRadius, discPaint)

        // Vinyl grooves
        val groovePaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(center, center, discRadius * 0.85f, groovePaint)
        canvas.drawCircle(center, center, discRadius * 0.70f, groovePaint)
        canvas.drawCircle(center, center, discRadius * 0.55f, groovePaint)

        // Center label
        val labelPaint = Paint().apply {
            color = accentColor
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, discRadius * 0.35f, labelPaint)

        // Center spindle hole
        val centerHolePaint = Paint().apply {
            color = darkBg
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, discRadius * 0.10f, centerHolePaint)

        // Initial letter
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = size * 0.12f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val letter = title.trim().take(1).uppercase().ifBlank { "♪" }
        val textY = center + (textPaint.textSize / 3f)
        canvas.drawText(letter, center, textY, textPaint)

        return bitmap
    }
}
