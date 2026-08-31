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
    private val memoryCache = mutableMapOf<String, Bitmap>()

    suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int = 512): Bitmap = withContext(Dispatchers.IO) {
        val cacheKey = "${track.id}_${track.filePath.hashCode()}"
        synchronized(memoryCache) {
            memoryCache[cacheKey]?.let { return@withContext it }
        }

        // Try extracting embedded picture via MediaMetadataRetriever
        val embeddedBitmap = extractEmbeddedPicture(context, track.filePath, sizePx)
        if (embeddedBitmap != null) {
            synchronized(memoryCache) {
                if (memoryCache.size > 50) memoryCache.clear()
                memoryCache[cacheKey] = embeddedBitmap
            }
            return@withContext embeddedBitmap
        }

        // Fallback: Generate a crisp, vibrant DJ vinyl record artwork Bitmap
        val generated = generateFallbackArtwork(track, sizePx)
        synchronized(memoryCache) {
            if (memoryCache.size > 50) memoryCache.clear()
            memoryCache[cacheKey] = generated
        }
        generated
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
