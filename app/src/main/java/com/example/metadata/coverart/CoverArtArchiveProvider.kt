package com.example.metadata.coverart

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DownloadedCoverArt(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sourceUrl: String
)

/**
 * Official Cover Art Archive artwork provider (Sections 9 & 11).
 *
 * Implements official Cover Art Archive specification:
 * - Direct queries using MusicBrainz release MBID and release-group MBID.
 * - Prioritizes front-1200 thumbnail, falling back to front-500, then front.
 * - Handles HTTP 307 redirects to the underlying archive.org CDN.
 * - Strictly validates downloaded images before returning.
 */
open class CoverArtArchiveProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "CoverArtArchiveProvider"
        private const val BASE_URL = "https://coverartarchive.org"
        private const val USER_AGENT = "SoundSync/1.0.0 ( contact@soundsync.app )"
        private const val MIN_VALID_IMAGE_BYTES = 1024
    }

    /**
     * Downloads verified front cover artwork for a MusicBrainz release or release-group.
     *
     * Fallback strategy:
     * 1. /release/{mbid}/front-1200
     * 2. /release/{mbid}/front-500
     * 3. /release/{mbid}/front
     * 4. /release-group/{mbid}/front-1200
     * 5. /release-group/{mbid}/front-500
     * 6. /release-group/{mbid}/front
     */
    open suspend fun fetchFrontCover(
        releaseMbid: String?,
        releaseGroupMbid: String?
    ): DownloadedCoverArt? = withContext(Dispatchers.IO) {
        val candidateUrls = mutableListOf<String>()

        if (!releaseMbid.isNullOrBlank()) {
            candidateUrls.add("$BASE_URL/release/$releaseMbid/front-1200")
            candidateUrls.add("$BASE_URL/release/$releaseMbid/front-500")
            candidateUrls.add("$BASE_URL/release/$releaseMbid/front")
        }

        if (!releaseGroupMbid.isNullOrBlank()) {
            candidateUrls.add("$BASE_URL/release-group/$releaseGroupMbid/front-1200")
            candidateUrls.add("$BASE_URL/release-group/$releaseGroupMbid/front-500")
            candidateUrls.add("$BASE_URL/release-group/$releaseGroupMbid/front")
        }

        if (candidateUrls.isEmpty()) {
            Log.w(TAG, "Cannot fetch cover art: neither release MBID nor release-group MBID provided.")
            return@withContext null
        }

        for (url in candidateUrls) {
            Log.d(TAG, "Requesting Cover Art Archive front cover: $url")
            val art = downloadAndValidateImage(url)
            if (art != null) {
                Log.d(TAG, "Cover Art Archive HTTP success: ${art.width}x${art.height} (${art.bytes.size} bytes, ${art.mimeType}) from ${art.sourceUrl}")
                return@withContext art
            }
        }

        Log.w(TAG, "No valid front cover artwork found in Cover Art Archive for release=$releaseMbid group=$releaseGroupMbid")
        null
    }

    /**
     * Downloads directly from a known image URL (e.g. for testing).
     */
    suspend fun downloadArtwork(url: String): DownloadedCoverArt? = withContext(Dispatchers.IO) {
        downloadAndValidateImage(url)
    }

    private fun downloadAndValidateImage(url: String): DownloadedCoverArt? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "Cover Art Archive HTTP status: $code for $url")

                if (!response.isSuccessful) {
                    if (code == 404) {
                        Log.d(TAG, "Artwork not found (404) at $url; checking next candidate.")
                    } else {
                        Log.w(TAG, "Cover Art Archive returned error HTTP $code for $url")
                    }
                    return null
                }

                val body = response.body ?: run {
                    Log.w(TAG, "Cover Art Archive returned empty response body for $url")
                    return null
                }

                val contentType = body.contentType()?.toString()?.lowercase() ?: "image/jpeg"
                if (!contentType.startsWith("image/")) {
                    Log.w(TAG, "Cover Art Archive invalid Content-Type \"$contentType\" (not an image) for $url")
                    return null
                }

                val bytes = body.bytes()
                if (bytes.size < MIN_VALID_IMAGE_BYTES) {
                    Log.w(TAG, "Cover Art Archive response too small (${bytes.size} bytes) for $url")
                    return null
                }

                // Verify image decodes successfully
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    Log.e(TAG, "Cover Art Archive image failed decoding verification for $url")
                    return null
                }

                val resolvedMime = when {
                    options.outMimeType != null -> options.outMimeType
                    contentType.contains("png") -> "image/png"
                    contentType.contains("webp") -> "image/webp"
                    else -> "image/jpeg"
                }

                return DownloadedCoverArt(
                    bytes = bytes,
                    mimeType = resolvedMime,
                    width = options.outWidth,
                    height = options.outHeight,
                    sourceUrl = url
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network exception querying Cover Art Archive ($url): ${e.message}")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error processing Cover Art Archive response ($url): ${e.message}", e)
            return null
        }
    }
}
