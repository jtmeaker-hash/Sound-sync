package com.example.metadata.musicbrainz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class MusicBrainzMatch(
    val releaseMbid: String?,
    val releaseGroupMbid: String?,
    val releaseTitle: String?,
    val artistName: String?,
    val score: Double
)

/**
 * Identifier bridge to resolve MusicBrainz release and release-group MBIDs
 * strictly from confirmed iTunes textual metadata (Sections 9 & 10).
 *
 * NOTE: MusicBrainz is used EXCLUSIVELY for identifier resolution.
 * Textual metadata authority remains Apple iTunes Search API.
 */
open class MusicBrainzResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "MusicBrainzResolver"
        private const val BASE_URL = "https://musicbrainz.org/ws/2"
        private const val USER_AGENT = "SoundSync/1.0.0 ( contact@soundsync.app )"

        // MusicBrainz rate limit policy: max 1 request per second
        private const val MIN_REQUEST_INTERVAL_MS = 1500L
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

    // In-memory cache mapping normalized (artist + album/title) -> MusicBrainzMatch
    private val mbidCache = ConcurrentHashMap<String, MusicBrainzMatch>()

    /**
     * Resolves the strongest matching MusicBrainz release MBID or release-group MBID
     * using canonical iTunes track attributes.
     */
    open suspend fun resolveMbid(
        artistName: String,
        trackName: String,
        collectionName: String? = null,
        durationMs: Long = 0L
    ): MusicBrainzMatch? = withContext(Dispatchers.IO) {
        val cleanArtist = sanitizeQueryValue(artistName)
        val cleanTrack = sanitizeQueryValue(trackName)
        val cleanCollection = collectionName?.let { sanitizeQueryValue(it) }?.takeIf {
            it.isNotBlank() && !it.equals("Single", ignoreCase = true) && !it.equals("Unknown Album", ignoreCase = true)
        }

        val cacheKey = "${cleanArtist.lowercase()}:${(cleanCollection ?: cleanTrack).lowercase()}"
        mbidCache[cacheKey]?.let {
            Log.d(TAG, "Using cached MusicBrainz MBID for \"$cacheKey\": release=${it.releaseMbid}, releaseGroup=${it.releaseGroupMbid}")
            return@withContext it
        }

        Log.d(TAG, "Resolving MusicBrainz MBID for artist=\"$artistName\", title=\"$trackName\", collection=\"$collectionName\"")

        // Helper to extract the primary artist if collaborations/features are listed in the artist string
        // Uses word boundaries so words like "Daft" or "Band" aren't incorrectly split
        val primaryArtist = cleanArtist.split(Regex("(?i)\\s*(?:,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\band\\b)\\s*")).firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(cleanArtist, ignoreCase = true) }

        // 1. If collection/album is available, query releases first
        if (!cleanCollection.isNullOrBlank()) {
            Log.d(TAG, "MusicBrainz searching releases for artist=\"$cleanArtist\", collection=\"$cleanCollection\"")
            var releaseMatch = searchByRelease(cleanArtist, cleanCollection, durationMs)
            if (releaseMatch == null && primaryArtist != null) {
                Log.d(TAG, "Retrying MusicBrainz release search with primary artist: \"$primaryArtist\"")
                releaseMatch = searchByRelease(primaryArtist, cleanCollection, durationMs)
            }
            if (releaseMatch != null) {
                Log.d(TAG, "MusicBrainz release matched: releaseMBID=${releaseMatch.releaseMbid}, releaseGroupMBID=${releaseMatch.releaseGroupMbid} (score=${releaseMatch.score})")
                mbidCache[cacheKey] = releaseMatch
                return@withContext releaseMatch
            }
        }

        // 2. Query recordings by track title and artist
        Log.d(TAG, "MusicBrainz searching recordings for artist=\"$cleanArtist\", track=\"$cleanTrack\"")
        var recordingMatch = searchByRecording(cleanArtist, cleanTrack, durationMs)
        if (recordingMatch == null && primaryArtist != null) {
            Log.d(TAG, "Retrying MusicBrainz recording search with primary artist: \"$primaryArtist\"")
            recordingMatch = searchByRecording(primaryArtist, cleanTrack, durationMs)
        }
        if (recordingMatch != null) {
            Log.d(TAG, "MusicBrainz recording matched: releaseMBID=${recordingMatch.releaseMbid}, releaseGroupMBID=${recordingMatch.releaseGroupMbid} (score=${recordingMatch.score})")
            mbidCache[cacheKey] = recordingMatch
            return@withContext recordingMatch
        }

        Log.w(TAG, "No MusicBrainz MBID found for \"$artistName - $trackName\"")
        null
    }

    private suspend fun searchByRelease(
        cleanArtist: String,
        cleanCollection: String,
        durationMs: Long
    ): MusicBrainzMatch? {
        val luceneQuery = "release:\"$cleanCollection\" AND artist:\"$cleanArtist\""
        val encodedQuery = URLEncoder.encode(luceneQuery, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/release?query=$encodedQuery&fmt=json&limit=5"

        val jsonStr = executeRequest(url) ?: return null
        return parseReleaseResponse(jsonStr, cleanArtist, cleanCollection)
    }

    private suspend fun searchByRecording(
        cleanArtist: String,
        cleanTrack: String,
        durationMs: Long
    ): MusicBrainzMatch? {
        val luceneQuery = "recording:\"$cleanTrack\" AND artist:\"$cleanArtist\""
        val encodedQuery = URLEncoder.encode(luceneQuery, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/recording?query=$encodedQuery&fmt=json&limit=5"

        val jsonStr = executeRequest(url) ?: return null
        return parseRecordingResponse(jsonStr, cleanArtist, cleanTrack, durationMs)
    }

    private suspend fun executeRequest(url: String, retryCount: Int = 0): String? {
        enforceRateLimit()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "MusicBrainz HTTP response: $code for $url")
                if ((code == 503 || code == 429) && retryCount < 2) {
                    Log.w(TAG, "MusicBrainz rate limited (HTTP $code). Retrying in 2000ms...")
                    delay(2000L)
                    return executeRequest(url, retryCount + 1)
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "MusicBrainz API returned HTTP $code")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MusicBrainz request failed: ${e.message}")
            null
        }
    }

    private fun parseReleaseResponse(
        jsonStr: String,
        targetArtist: String,
        targetCollection: String
    ): MusicBrainzMatch? {
        return try {
            val root = JSONObject(jsonStr)
            val releases = root.optJSONArray("releases") ?: return null
            if (releases.length() == 0) return null

            var bestMatch: MusicBrainzMatch? = null
            var highestScore = -1.0

            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                val id = rel.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = rel.optString("title")
                val releaseGroup = rel.optJSONObject("release-group")
                val releaseGroupId = releaseGroup?.optString("id")?.takeIf { it.isNotBlank() }
                val score = rel.optDouble("score", 50.0)

                if (score > highestScore) {
                    highestScore = score
                    bestMatch = MusicBrainzMatch(
                        releaseMbid = id,
                        releaseGroupMbid = releaseGroupId,
                        releaseTitle = title,
                        artistName = targetArtist,
                        score = score
                    )
                }
            }
            bestMatch
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing MusicBrainz release response: ${e.message}")
            null
        }
    }

    private fun parseRecordingResponse(
        jsonStr: String,
        targetArtist: String,
        targetTrack: String,
        targetDurationMs: Long
    ): MusicBrainzMatch? {
        return try {
            val root = JSONObject(jsonStr)
            val recordings = root.optJSONArray("recordings") ?: return null
            if (recordings.length() == 0) return null

            var bestRecMatch: MusicBrainzMatch? = null
            var bestDelta = Long.MAX_VALUE

            for (i in 0 until recordings.length()) {
                val rec = recordings.optJSONObject(i) ?: continue
                val releases = rec.optJSONArray("releases") ?: continue
                val recLength = rec.optLong("length", 0L)
                val delta = if (targetDurationMs > 0 && recLength > 0) abs(targetDurationMs - recLength) else 0L

                if (releases.length() > 0) {
                    val rel = releases.optJSONObject(0) ?: continue
                    val releaseId = rel.optString("id").takeIf { it.isNotBlank() }
                    val relGroup = rel.optJSONObject("release-group")
                    val relGroupId = relGroup?.optString("id")?.takeIf { it.isNotBlank() }
                    val title = rel.optString("title")

                    if (releaseId != null || relGroupId != null) {
                        if (bestRecMatch == null || delta < bestDelta) {
                            bestDelta = delta
                            bestRecMatch = MusicBrainzMatch(
                                releaseMbid = releaseId,
                                releaseGroupMbid = relGroupId,
                                releaseTitle = title,
                                artistName = targetArtist,
                                score = 80.0
                            )
                        }
                    }
                }
            }
            bestRecMatch
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing MusicBrainz recording response: ${e.message}")
            null
        }
    }

    private fun sanitizeQueryValue(value: String): String {
        return value.replace("\"", "")
            .replace("\\", "")
            .replace("/", " ")
            .trim()
    }
}
