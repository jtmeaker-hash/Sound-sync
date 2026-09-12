package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AlbumArtHelper {

    private const val TAG = "AlbumArtHelper"
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceIn(16 * 1024, 64 * 1024)
    private val memoryCache = object : android.util.LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    /**
     * Synchronously checks if artwork for [track] at [sizePx] is already resident in memory cache.
     * Allows Composable cards to render immediately without any blank/flicker frames.
     */
    fun getCachedArtwork(track: Track, sizePx: Int = 512): Bitmap? {
        val cacheKey = "${track.id}_${track.filePath.hashCode()}_$sizePx"
        return memoryCache.get(cacheKey)
    }

    suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int = 512): Bitmap = withContext(Dispatchers.IO) {
        val cacheKey = "${track.id}_${track.filePath.hashCode()}_$sizePx"
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // 1. Try cached artwork file if present
        val cachePath = track.artworkCachePath ?: track.artworkUrl?.takeIf { it.startsWith("/") || it.startsWith("file://") }
        if (!cachePath.isNullOrBlank()) {
            val actualPath = if (cachePath.startsWith("file://")) Uri.parse(cachePath).path else cachePath
            if (actualPath != null) {
                val cachedFile = File(actualPath)
                if (cachedFile.exists() && cachedFile.canRead()) {
                    val decoded = decodeFileToBitmap(cachedFile, sizePx)
                    if (decoded != null) {
                        memoryCache.put(cacheKey, decoded)
                        return@withContext decoded
                    }
                }
            }
        }

        // 1b. Check ArtworkCache by track ID or artist + album
        try {
            val artworkCache = com.example.metadata.ArtworkCache(context)
            val cachedFile = artworkCache.getCachedArtworkFileForTrack(track.id)
                ?: if (track.artist.isNotBlank() && !track.album.isNullOrBlank()) {
                    artworkCache.getCachedArtworkFile(track.artist, track.album)
                } else null

            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                val decoded = decodeFileToBitmap(cachedFile, sizePx)
                if (decoded != null) {
                    memoryCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Exception) {}

        // 2. Try LocalArtworkFinder (checks embedded picture + folder artwork cover.jpg/folder.jpg)
        try {
            val localFinder = com.example.metadata.artwork.LocalArtworkFinder(context)
            val localResult = localFinder.findLocalArtwork(track)
            if (localResult != null && localResult.file.exists() && localResult.file.length() > 0) {
                val decoded = decodeFileToBitmap(localResult.file, sizePx)
                if (decoded != null) {
                    memoryCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            }
        } catch (_: Exception) {}

        // 2b. Direct embedded picture extraction as safety fallback
        val embeddedBitmap = extractEmbeddedPicture(context, track.filePath, sizePx)
        if (embeddedBitmap != null) {
            memoryCache.put(cacheKey, embeddedBitmap)
            return@withContext embeddedBitmap
        }

        // 3. Fallback: Generate a crisp, vibrant DJ vinyl record artwork Bitmap
        val generated = generateFallbackArtwork(track, sizePx)
        memoryCache.put(cacheKey, generated)
        generated
    }

    private fun decodeFileToBitmap(file: File, targetSize: Int): Bitmap? {
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
                retriever.setDataSource(context, Uri.parse(uriOrPath))
            } else if (uriOrPath.startsWith("file://")) {
                retriever.setDataSource(Uri.parse(uriOrPath).path)
            } else {
                val f = File(uriOrPath)
                if (!f.exists() || !f.canRead()) return null
                retriever.setDataSource(uriOrPath)
            }

            val picture = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(picture, 0, picture.size, options)

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
            BitmapFactory.decodeByteArray(picture, 0, picture.size, decodeOptions)
        } catch (e: Exception) {
            Log.v(TAG, "No embedded artwork for $uriOrPath: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun generateFallbackArtwork(track: Track, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Generate consistent colors from track ID / title
        val hash = (track.title + track.artist + track.id).hashCode()
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
        val letter = track.title.trim().take(1).uppercase()
        val textY = center + (textPaint.textSize / 3f)
        canvas.drawText(letter, center, textY, textPaint)

        return bitmap
    }
}
