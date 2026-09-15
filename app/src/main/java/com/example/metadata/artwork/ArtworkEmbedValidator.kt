package com.example.metadata.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class ValidatedArtwork(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int
)

/**
 * Validates, prepares, and standardizes album artwork for physical audio tag embedding.
 *
 * Rules:
 * - Prefer JPEG or PNG.
 * - Detect MIME type accurately from binary magic signatures.
 * - Never embed broken, zero-byte, or invalid image data.
 * - Convert non-standard image formats (WebP, BMP, GIF) into standard JPEG/PNG before embedding.
 * - Maintain original byte data if already valid JPEG/PNG <= 1200x1200 to prevent lossy recompression.
 * - Scale down oversized images exceeding 1200x1200 to ensure broad player compatibility (Kid3, CDJ, Pioneer).
 */
object ArtworkEmbedValidator {

    private const val TAG = "ArtworkEmbedValidator"
    const val DEFAULT_MAX_DIMENSION = 1200

    fun validateAndPrepare(
        rawBytes: ByteArray?,
        fallbackMime: String = "image/jpeg",
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): ValidatedArtwork? {
        if (rawBytes == null || rawBytes.size < 64) {
            Log.w(TAG, "Artwork rejected: empty or insufficient bytes (${rawBytes?.size ?: 0})")
            return null
        }

        // Check format magic signatures
        val isPng = isPngSignature(rawBytes)
        val isJpeg = isJpegSignature(rawBytes)

        // Read dimensions without full allocation
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)

        val origWidth = boundsOpts.outWidth
        val origHeight = boundsOpts.outHeight

        if (origWidth <= 0 || origHeight <= 0) {
            Log.w(TAG, "Artwork rejected: invalid or corrupt image payload (bounds: ${origWidth}x${origHeight})")
            return null
        }

        val detectedMime = when {
            isPng -> "image/png"
            isJpeg -> "image/jpeg"
            !boundsOpts.outMimeType.isNullOrBlank() -> boundsOpts.outMimeType
            else -> fallbackMime.ifBlank { "image/jpeg" }
        }

        // If already valid JPEG or PNG and within maxDimension bounds, preserve original bytes verbatim
        val isStandardFormat = (isPng || isJpeg || detectedMime == "image/jpeg" || detectedMime == "image/png")
        if (isStandardFormat && origWidth <= maxDimension && origHeight <= maxDimension) {
            val finalMime = if (isPng) "image/png" else "image/jpeg"
            return ValidatedArtwork(
                bytes = rawBytes,
                mimeType = finalMime,
                width = origWidth,
                height = origHeight
            )
        }

        // Otherwise, decode and convert / scale down to standard compliant image
        return try {
            val sampleSize = calculateInSampleSize(origWidth, origHeight, maxDimension, maxDimension)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return null

            val currentW = decodedBitmap.width
            val currentH = decodedBitmap.height

            val scaledBitmap = if (currentW > maxDimension || currentH > maxDimension) {
                val scale = minOf(maxDimension.toFloat() / currentW, maxDimension.toFloat() / currentH)
                val targetW = (currentW * scale).roundToInt().coerceAtLeast(1)
                val targetH = (currentH * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true).also {
                    if (it != decodedBitmap) decodedBitmap.recycle()
                }
            } else {
                decodedBitmap
            }

            val hasAlpha = isPng && scaledBitmap.hasAlpha()
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = 90
            val targetMime = if (hasAlpha) "image/png" else "image/jpeg"

            val outStream = ByteArrayOutputStream()
            scaledBitmap.compress(format, quality, outStream)
            val outputBytes = outStream.toByteArray()

            val finalW = scaledBitmap.width
            val finalH = scaledBitmap.height
            scaledBitmap.recycle()

            if (outputBytes.isNotEmpty()) {
                Log.d(TAG, "Artwork normalized: ${origWidth}x${origHeight} -> ${finalW}x${finalH}, mime=$targetMime, size=${outputBytes.size} bytes")
                ValidatedArtwork(
                    bytes = outputBytes,
                    mimeType = targetMime,
                    width = finalW,
                    height = finalH
                )
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed standardizing artwork for embedding: ${e.message}", e)
            null
        }
    }

    private fun isPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() && // P
                bytes[2] == 0x4E.toByte() && // N
                bytes[3] == 0x47.toByte() && // G
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()
    }

    private fun isJpegSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
