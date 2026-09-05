package com.example.metadata.theaudiodb

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class DownloadedArtwork(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sourceUrl: String
)

/**
 * Dedicated cover artwork provider utilizing TheAudioDB v1 API.
 *
 * Reference: https://www.theaudiodb.com/free_music_api
 * Uses the free public development key "123".
 */
open class TheAudioDbArtworkProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val apiKey: String = "123"
) : ArtworkProvider {

    companion object {
        private const val TAG = "TheAudioDbArtworkProvider"
        private const val BASE_URL = "https://www.theaudiodb.com/api/v1/json"
        // Free tier rate limit: 30 requests per minute -> min spacing 2000ms
        private const val MIN_INTERVAL_MS = 2000L
        private val rateLimitMutex = Mutex()
        private var lastRequestTime = 0L

        suspend fun enforceRateLimit() = rateLimitMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestTime
            val waitTime = MIN_INTERVAL_MS - elapsed
            if (waitTime > 0) {
                delay(waitTime)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

    private val artworkCache = ConcurrentHashMap<String, List<ArtworkCandidate>>()

    override suspend fun findArtwork(
        artist: String,
        album: String?,
        track: String?
    ): List<ArtworkCandidate> = withContext(Dispatchers.IO) {
        val cleanArtist = artist.trim()
        if (cleanArtist.isBlank()) return@withContext emptyList()

        val cleanAlbum = album?.trim()?.takeIf { it.isNotBlank() && !it.equals("Single", ignoreCase = true) }
        val cacheKey = "$cleanArtist:${cleanAlbum.orEmpty()}:${track.orEmpty()}".lowercase()
        artworkCache[cacheKey]?.let { return@withContext it }

        Log.d(TAG, "search started")
        Log.d(TAG, "query artist: $cleanArtist")
        Log.d(TAG, "query album: ${cleanAlbum ?: "(none)"}")

        val candidates = mutableListOf<ArtworkCandidate>()

        // 1. Primary: Search Album if album name is present
        if (cleanAlbum != null) {
            val albumResults = searchAlbumDirect(cleanArtist, cleanAlbum)
            candidates.addAll(albumResults)
        }

        // 2. Fallback: Search all artist albums if primary album search returned no cover
        if (candidates.isEmpty()) {
            val allAlbums = searchArtistAlbums(cleanArtist)
            if (cleanAlbum != null) {
                // Find matching album
                val matched = allAlbums.filter {
                    it.album?.contains(cleanAlbum, ignoreCase = true) == true ||
                    cleanAlbum.contains(it.album.orEmpty(), ignoreCase = true)
                }
                candidates.addAll(matched)
            }
            if (candidates.isEmpty()) {
                candidates.addAll(allAlbums)
            }
        }

        // 3. Fallback: Search track if available
        if (candidates.isEmpty() && !track.isNullOrBlank()) {
            val trackResults = searchTrackDirect(cleanArtist, track.trim())
            candidates.addAll(trackResults)
        }

        Log.d(TAG, "candidate count: ${candidates.size}")
        if (candidates.isNotEmpty()) {
            val selected = candidates.first()
            Log.d(TAG, "selected artwork URL: ${selected.artworkUrl}")
        }

        artworkCache[cacheKey] = candidates
        candidates
    }

    private suspend fun searchAlbumDirect(artist: String, album: String): List<ArtworkCandidate> {
        val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.name())
        val encodedAlbum = URLEncoder.encode(album, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/$apiKey/searchalbum.php?s=$encodedArtist&a=$encodedAlbum"

        val json = executeGet(url) ?: return emptyList()
        val parsed = TheAudioDbAlbumResponse.fromJson(json)
        return extractCandidatesFromAlbums(parsed.albums, artist, album)
    }

    private suspend fun searchArtistAlbums(artist: String): List<ArtworkCandidate> {
        val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/$apiKey/searchalbum.php?s=$encodedArtist"

        val json = executeGet(url) ?: return emptyList()
        val parsed = TheAudioDbAlbumResponse.fromJson(json)
        return extractCandidatesFromAlbums(parsed.albums, artist, null)
    }

    private suspend fun searchTrackDirect(artist: String, track: String): List<ArtworkCandidate> {
        val encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.name())
        val encodedTrack = URLEncoder.encode(track, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/$apiKey/searchtrack.php?s=$encodedArtist&t=$encodedTrack"

        val json = executeGet(url) ?: return emptyList()
        val parsed = TheAudioDbTrackResponse.fromJson(json)
        val results = mutableListOf<ArtworkCandidate>()
        for (item in parsed.tracks) {
            val thumb = item.strTrackThumb?.takeIf(String::isNotBlank) ?: continue
            results.add(
                ArtworkCandidate(
                    artworkUrl = thumb,
                    provider = "TheAudioDB",
                    artist = item.strArtist.ifBlank { artist },
                    album = null,
                    track = item.strTrack,
                    isHighQuality = false,
                    description = "TheAudioDB Track Thumb"
                )
            )
        }
        return results
    }

    private fun extractCandidatesFromAlbums(
        albums: List<TheAudioDbAlbumItem>,
        defaultArtist: String,
        targetAlbum: String?
    ): List<ArtworkCandidate> {
        val candidates = mutableListOf<ArtworkCandidate>()
        for (item in albums) {
            // Prioritize HQ album thumb
            val hqThumb = item.strAlbumThumbHQ?.takeIf(String::isNotBlank)
            if (hqThumb != null) {
                candidates.add(
                    ArtworkCandidate(
                        artworkUrl = hqThumb,
                        provider = "TheAudioDB",
                        artist = item.strArtist.ifBlank { defaultArtist },
                        album = item.strAlbum.ifBlank { targetAlbum },
                        track = null,
                        isHighQuality = true,
                        description = "TheAudioDB HQ Album Thumb"
                    )
                )
            }
            // Standard album thumb
            val stdThumb = item.strAlbumThumb?.takeIf(String::isNotBlank)
            if (stdThumb != null && stdThumb != hqThumb) {
                candidates.add(
                    ArtworkCandidate(
                        artworkUrl = stdThumb,
                        provider = "TheAudioDB",
                        artist = item.strArtist.ifBlank { defaultArtist },
                        album = item.strAlbum.ifBlank { targetAlbum },
                        track = null,
                        isHighQuality = false,
                        description = "TheAudioDB Standard Album Thumb"
                    )
                )
            }
        }
        return candidates
    }

    /**
     * Downloads and decodes an image, verifying valid dimensions and square aspect ratio.
     */
    open suspend fun downloadArtwork(artworkUrl: String): DownloadedArtwork? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(artworkUrl)
            .header("User-Agent", "SoundSync/1.0.0 (Linux; Android)")
            .header("Accept", "image/jpeg,image/png,image/*")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "HTTP status for artwork download: $code")
                if (!response.isSuccessful) {
                    Log.w(TAG, "download result: FAILED (HTTP $code)")
                    return@withContext null
                }

                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "download result: FAILED (empty body)")
                    return@withContext null
                }

                val mime = response.header("Content-Type") ?: "image/jpeg"

                // Decode dimensions without full allocation first
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val width = options.outWidth
                val height = options.outHeight

                Log.d(TAG, "image dimensions: ${width}x${height}")

                // Validation (Phase 14):
                // Reject invalid or excessively tiny images (< 150px)
                if (width < 150 || height < 150) {
                    Log.w(TAG, "download result: REJECTED (dimensions too small: ${width}x${height})")
                    return@withContext null
                }

                // Verify aspect ratio (reject extreme banners or non-cover aspect ratios)
                val ratio = width.toFloat() / height.toFloat()
                if (ratio < 0.70f || ratio > 1.35f) {
                    Log.w(TAG, "download result: REJECTED (aspect ratio $ratio not suitable for album cover)")
                    return@withContext null
                }

                Log.d(TAG, "download result: SUCCESS")
                return@withContext DownloadedArtwork(
                    bytes = bytes,
                    mimeType = mime,
                    width = width,
                    height = height,
                    sourceUrl = artworkUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "download result: FAILED with exception: ${e.message}", e)
            null
        }
    }

    private suspend fun executeGet(url: String): String? {
        enforceRateLimit()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SoundSync/1.0.0 (Linux; Android)")
            .header("Accept", "application/json")
            .get()
            .build()

        var attempts = 0
        while (attempts < 2) {
            attempts++
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d(TAG, "HTTP status: $code for $url")
                    if (code == 429) {
                        delay(2500)
                        return@use
                    }
                    if (!response.isSuccessful) return null
                    return response.body?.string()
                }
            } catch (e: IOException) {
                Log.w(TAG, "TheAudioDB network error: ${e.message}")
            }
        }
        return null
    }
}
