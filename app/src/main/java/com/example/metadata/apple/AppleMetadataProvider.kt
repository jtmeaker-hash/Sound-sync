package com.example.metadata.apple

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

/**
 * Primary track identification provider utilizing Apple's official iTunes Search API.
 *
 * Reference: https://performance-partners.apple.com/search-api
 *
 * IMPORTANT: Per requirements, Apple artwork URLs (artworkUrl60, artworkUrl100) are NEVER
 * used as permanent local cover art, embedded into audio files, or cached as library artwork.
 * Apple is used solely for track identification and textual/catalog metadata.
 */
open class AppleMetadataProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val defaultCountry: String = "AU"
) {

    companion object {
        private const val TAG = "AppleMetadataProvider"
        private const val BASE_URL = "https://itunes.apple.com"
        // Apple rate limit: ~20 calls per minute. Min spacing: 2800 ms per request.
        private const val MIN_REQUEST_INTERVAL_MS = 2800L
        private val rateLimitMutex = Mutex()
        private var lastRequestTimestamp = 0L

        suspend fun enforceRateLimit() = rateLimitMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestTimestamp
            val waitTime = MIN_REQUEST_INTERVAL_MS - elapsed
            if (waitTime > 0) {
                delay(waitTime)
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }
    }

    // In-memory query cache to avoid repeating identical searches
    private val searchCache = ConcurrentHashMap<String, List<AppleTrackResult>>()

    /**
     * Searches Apple iTunes Search API for song tracks matching the query term.
     *
     * URL parameters:
     * - term: query
     * - country: storefront (default "AU")
     * - media: "music"
     * - entity: "song"
     * - limit: max results
     */
    open suspend fun searchTracks(
        query: String,
        country: String = defaultCountry,
        limit: Int = 15
    ): List<AppleTrackResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext emptyList()
        }

        val cacheKey = "$country:$limit:${trimmedQuery.lowercase()}"
        searchCache[cacheKey]?.let { return@withContext it }

        val encodedTerm = URLEncoder.encode(trimmedQuery, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/search?term=$encodedTerm&country=$country&media=music&entity=song&limit=$limit"

        Log.d(TAG, "request started")
        Log.d(TAG, "query: $trimmedQuery (country: $country, limit: $limit)")
        Log.d("AppleMetadata", "Searching: $trimmedQuery")

        var attempts = 0
        var backoffMs = 1500L

        while (attempts < 3) {
            attempts++
            enforceRateLimit()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SoundSync/1.0.0 (Linux; Android)")
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val statusCode = response.code
                    Log.d(TAG, "HTTP response status: $statusCode")
                    Log.d("AppleMetadata", "HTTP status: $statusCode")

                    if (statusCode == 429) {
                        Log.w(TAG, "HTTP 429 Rate limited by Apple Search API, backing off ${backoffMs}ms...")
                        delay(backoffMs)
                        backoffMs *= 2
                        return@use
                    }

                    if (!response.isSuccessful) {
                        Log.e(TAG, "Apple Search API error: HTTP $statusCode")
                        return@withContext emptyList()
                    }

                    val bodyString = response.body?.string()
                    Log.d(TAG, "response received")

                    if (bodyString.isNullOrBlank()) {
                        Log.w(TAG, "parsing status: empty body")
                        return@withContext emptyList()
                    }

                    try {
                        val parsed = AppleSearchResponse.fromJson(bodyString)
                        Log.d(TAG, "result count: ${parsed.results.size}")
                        Log.d("AppleMetadata", "Results returned: ${parsed.results.size}")
                        Log.d(TAG, "parsing status: SUCCESS")

                        // Log first few candidates for diagnostic trace
                        parsed.results.take(3).forEachIndexed { idx, candidate ->
                            Log.d(TAG, "Candidate #$idx: artistName=${candidate.artistName}, trackName=${candidate.trackName}, collectionName=${candidate.collectionName}, trackTimeMillis=${candidate.trackTimeMillis}, releaseDate=${candidate.releaseDate}, primaryGenreName=${candidate.primaryGenreName}, trackNumber=${candidate.trackNumber}, trackCount=${candidate.trackCount}, discNumber=${candidate.discNumber}, discCount=${candidate.discCount}, trackExplicitness=${candidate.trackExplicitness}")
                            if (!candidate.artworkUrl100.isNullOrBlank()) {
                                Log.d("AppleMetadata", "Artwork found: ${candidate.artworkUrl100}")
                            }
                        }

                        searchCache[cacheKey] = parsed.results
                        return@withContext parsed.results
                    } catch (e: Exception) {
                        Log.e(TAG, "parsing status: FAILED - ${e.message}", e)
                        return@withContext emptyList()
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network exception querying Apple Search API (attempt $attempts): ${e.message}")
                if (attempts >= 3) {
                    return@withContext emptyList()
                }
                delay(backoffMs)
                backoffMs *= 2
            }
        }

        emptyList()
    }

    /**
     * Looks up an item directly by its Apple track ID.
     */
    suspend fun lookupTrack(
        trackId: Long,
        country: String = defaultCountry
    ): AppleTrackResult? = withContext(Dispatchers.IO) {
        enforceRateLimit()
        val url = "$BASE_URL/lookup?id=$trackId&country=$country"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SoundSync/1.0.0 (Linux; Android)")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = AppleSearchResponse.fromJson(body)
                return@withContext parsed.results.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed looking up track $trackId: ${e.message}")
            null
        }
    }
}
