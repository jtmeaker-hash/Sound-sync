package com.example.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

interface MusicBrainzTransport {
    suspend fun get(pathAndQuery: String): String
}

class MusicBrainzHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null
) : IOException("MusicBrainz HTTP $statusCode")

class OkHttpMusicBrainzTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val userAgent: String = "SoundSync/1.0.0 (https://github.com/jtmeaker-hash/Sound-sync)"
) : MusicBrainzTransport {
    override suspend fun get(pathAndQuery: String): String {
        val request = Request.Builder()
            .url("https://musicbrainz.org/ws/2/$pathAndQuery")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw MusicBrainzHttpException(
                    statusCode = response.code,
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                )
            }
            return response.body?.string() ?: error("Empty MusicBrainz response")
        }
    }
}

/** One process-wide scheduler. Every MusicBrainz request passes through this mutex. */
object MusicBrainzRequestScheduler {
    private const val MIN_INTERVAL_MS = 1_050L
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun <T> schedule(block: suspend () -> T): T = mutex.withLock {
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }
}

class MusicBrainzClient(
    private val transport: MusicBrainzTransport,
    private val cache: MusicBrainzCache = InMemoryMusicBrainzCache()
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<MusicBrainzRecording?>>()

    suspend fun lookupRecording(recordingId: String): MusicBrainzRecording? {
        if (recordingId.isBlank()) return null
        val cacheKey = "recording:$recordingId"
        cache.get(cacheKey)?.let { return it }
        val json = requestWithRetry("recording/${urlEncode(recordingId)}?fmt=json&inc=artist-credits+releases+release-groups+media+recordings+isrcs+tags+genres+ratings")
            ?: return null
        val recording = runCatching { parseRecording(json) }.getOrNull()
        if (recording != null) cache.put(cacheKey, recording)
        return recording
    }

    suspend fun lookupByIsrc(isrc: String): List<MusicBrainzRecording> {
        val cleanIsrc = isrc.trim()
        if (cleanIsrc.isBlank()) return emptyList()
        val cacheKey = "isrc:$cleanIsrc"
        val json = requestWithRetry("isrc/${urlEncode(cleanIsrc)}?fmt=json&inc=artist-credits+releases+release-groups+media+recordings+tags+genres")
        val candidates = parseRecordings(json.orEmpty())
        if (candidates.isNotEmpty()) return candidates
        // Fallback to Lucene search by ISRC if direct lookup returned empty
        val fallbackJson = requestWithRetry("recording/?fmt=json&limit=25&query=${urlEncode("isrc:\"${escape(cleanIsrc)}\"")}")
        return parseRecordings(fallbackJson.orEmpty())
    }

    suspend fun lookupRelease(releaseId: String): MusicBrainzRelease? {
        if (releaseId.isBlank()) return null
        val json = requestWithRetry("release/${urlEncode(releaseId)}?fmt=json&inc=artist-credits+labels+recordings+release-groups+media+genres+tags")
            ?: return null
        return runCatching { parseRelease(JSONObject(json)) }.getOrNull()
    }

    suspend fun searchRecordings(query: String, limit: Int = 25): List<MusicBrainzRecording> {
        if (query.isBlank()) return emptyList()
        val json = requestWithRetry("recording/?fmt=json&limit=$limit&query=${urlEncode(query)}")
        return parseRecordings(json.orEmpty())
    }

    suspend fun findRecording(identity: LocalTrackIdentity): MusicBrainzRecording? {
        val cacheKey = identity.recordingId?.let { "recording:$it" } ?: searchKey(identity)
        cache.get(cacheKey)?.let { return it }

        val existing = inFlight[cacheKey]
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<MusicBrainzRecording?>()
        val raced = inFlight.putIfAbsent(cacheKey, deferred)
        if (raced != null) return raced.await()

        try {
            var recording: MusicBrainzRecording? = null

            // Priority 1: Exact MBID lookup if available
            if (!identity.recordingId.isNullOrBlank()) {
                val candidate = lookupRecording(identity.recordingId)
                if (candidate != null && matchesIdentity(identity, candidate, requireStrongIdentity = false)) {
                    recording = candidate
                }
            }

            // Priority 2: ISRC lookup
            if (recording == null && !identity.isrc.isNullOrBlank()) {
                val candidates = lookupByIsrc(identity.isrc)
                recording = selectBestCandidate(identity, candidates)
            }

            // Priority 3: Title & Artist Lucene search
            if (recording == null) {
                val cleanTitle = cleanSearchTerm(identity.title)
                val cleanArtist = cleanSearchTerm(identity.artist)
                var searchCandidates = if (cleanTitle.isNotBlank()) {
                    val strictQuery = buildQuery(cleanTitle, cleanArtist)
                    searchRecordings(strictQuery, 25)
                } else emptyList()

                if (searchCandidates.isEmpty() && cleanTitle.isNotBlank()) {
                    // Fallback to broader unquoted search terms
                    val fallbackQuery = if (cleanArtist.isNotBlank()) {
                        "\"${escape(cleanTitle)}\" \"${escape(cleanArtist)}\""
                    } else {
                        "\"${escape(cleanTitle)}\""
                    }
                    searchCandidates = searchRecordings(fallbackQuery, 25)
                }

                recording = selectBestCandidate(identity, searchCandidates)
            }

            if (recording != null) cache.put(cacheKey, recording)
            deferred.complete(recording)
            return recording
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            deferred.complete(null)
            return null
        } finally {
            inFlight.remove(cacheKey, deferred)
        }
    }

    private suspend fun requestWithRetry(path: String): String? {
        var attempt = 0
        while (attempt < 4) {
            try {
                return MusicBrainzRequestScheduler.schedule { transport.get(path) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: MusicBrainzHttpException) {
                val retryable = error.statusCode == 429 || error.statusCode == 503
                if (!retryable) return null
                attempt++
                if (attempt >= 4) return null
                delay((error.retryAfterSeconds?.times(1_000L) ?: (500L shl (attempt - 1))).coerceAtMost(30_000L))
            } catch (_: IOException) {
                attempt++
                if (attempt >= 4) return null
                delay(500L shl (attempt - 1))
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun buildQuery(title: String, artist: String): String = buildString {
        append("recording:\"").append(escape(title)).append("\"")
        if (artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true) && !artist.equals("Various Artists", ignoreCase = true)) {
            append(" AND artist:\"").append(escape(artist)).append("\"")
        }
    }

    private fun selectBestCandidate(identity: LocalTrackIdentity, candidates: List<MusicBrainzRecording>): MusicBrainzRecording? {
        return candidates.map { candidate -> candidate to score(identity, candidate) }
            .filter { it.second >= MIN_MATCH_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun matchesIdentity(identity: LocalTrackIdentity, candidate: MusicBrainzRecording, requireStrongIdentity: Boolean): Boolean {
        if (identity.isrc != null && candidate.isrcs.any { it.equals(identity.isrc, true) }) return true
        val score = score(identity, candidate)
        return if (requireStrongIdentity) score >= 0.9 else score >= 0.62
    }

    private fun score(identity: LocalTrackIdentity, candidate: MusicBrainzRecording): Double {
        var score = 0.0
        if (identity.recordingId == candidate.id) score += 1.0
        if (identity.isrc != null && candidate.isrcs.any { it.equals(identity.isrc, true) }) score += 1.0
        score += textSimilarity(identity.title, candidate.title) * 0.35
        score += textSimilarity(identity.artist, candidate.artistCredits.joinToString(" ") { it.name }) * 0.25
        val duration = candidate.lengthMs?.let { it / 1000.0 }
        if (duration != null && identity.durationSeconds > 0) {
            val difference = abs(identity.durationSeconds - duration)
            score += when {
                difference <= 1 -> 0.30
                difference <= 3 -> 0.20
                difference <= 8 -> 0.08
                else -> -0.30
            }
        }
        val requestedVersion = versionTokens(identity.title)
        val candidateVersion = versionTokens("${candidate.title} ${candidate.disambiguation.orEmpty()}")
        if (requestedVersion == candidateVersion) score += 0.20
        else if (requestedVersion.isNotEmpty() || candidateVersion.isNotEmpty()) score -= 0.35
        return score
    }

    companion object {
        private const val MIN_MATCH_SCORE = 0.62
        private fun searchKey(identity: LocalTrackIdentity) = listOf(identity.artist, identity.title, identity.album, identity.durationSeconds).joinToString("|").lowercase(Locale.ROOT)
        private fun escape(value: String?) = value.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")
        private fun urlEncode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        private fun textSimilarity(a: String, b: String): Double {
            val left = normalize(a)
            val right = normalize(b)
            if (left.isBlank() || right.isBlank()) return 0.0
            if (left == right) return 1.0
            val leftTokens = left.split(' ').toSet()
            val rightTokens = right.split(' ').toSet()
            return leftTokens.intersect(rightTokens).size.toDouble() / max(leftTokens.size, rightTokens.size)
        }
        private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun versionTokens(value: String): Set<String> = Regex("(?i)original mix|radio edit|extended mix|club mix|remix|vip|dub|instrumental|live|acoustic|remaster")
            .findAll(value).map { it.value.lowercase(Locale.ROOT) }.toSet()

        fun cleanSearchTerm(value: String): String {
            return value
                .replace(Regex("\\.(mp3|flac|wav|m4a|aac|ogg|aif|aiff)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^\\[?[0-9]+\\]?[.\\-\\s]+"), "")
                .replace(Regex("\\[(320k|FLAC|HQ|Official|HD|HQ Rip)\\]", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        fun parseRecordings(json: String): List<MusicBrainzRecording> {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            val recordings = root.optJSONArray("recordings") ?: JSONArray()
            return (0 until recordings.length()).mapNotNull { index -> recordings.optJSONObject(index)?.let(::parseRecording) }
        }

        fun parseRecording(json: String): MusicBrainzRecording = parseRecording(JSONObject(json))

        fun parseRecording(obj: JSONObject): MusicBrainzRecording {
            val credits = obj.optJSONArray("artist-credit")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val credit = array.optJSONObject(i) ?: return@mapNotNull null
                    val artist = credit.optJSONObject("artist")
                    MusicBrainzArtistCredit(credit.optString("name", artist?.optString("name", "").orEmpty()), artist?.optString("id")?.takeIf { it.isNotBlank() })
                }
            }.orEmpty()
            val releases = obj.optJSONArray("releases")?.let { array ->
                (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parseRelease) }
            }.orEmpty()
            val isrcs = obj.optJSONArray("isrcs")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            
            val allTags = mutableListOf<String>()
            obj.optJSONArray("genres")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let { allTags.add(it) }
                }
            }
            obj.optJSONArray("tags")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let { allTags.add(it) }
                }
            }

            val rating = obj.optJSONObject("rating")?.optDouble("value")?.takeIf { !it.isNaN() && it > 0 }

            return MusicBrainzRecording(
                id = obj.optString("id"),
                title = obj.optString("title"),
                lengthMs = obj.optLong("length").takeIf { it > 0 },
                disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
                artistCredits = credits,
                releases = releases,
                isrcs = isrcs,
                tags = allTags.distinct(),
                rating = rating
            )
        }

        fun parseRelease(obj: JSONObject): MusicBrainzRelease {
            var trackNumber: Int? = null
            var discNumber: Int? = null
            var mediumPosition: Int? = null
            obj.optJSONArray("media")?.let { media ->
                for (mediumIndex in 0 until media.length()) {
                    val medium = media.optJSONObject(mediumIndex) ?: continue
                    val tracks = medium.optJSONArray("tracks") ?: continue
                    if (tracks.length() > 0) {
                        val track = tracks.optJSONObject(0)
                        trackNumber = track?.optString("position")?.toIntOrNull()
                        discNumber = medium.optString("position").toIntOrNull() ?: (mediumIndex + 1)
                        mediumPosition = medium.optString("position").toIntOrNull()
                        break
                    }
                }
            }
            return MusicBrainzRelease(
                id = obj.optString("id"),
                title = obj.optString("title"),
                date = obj.optString("date").takeIf { it.isNotBlank() },
                country = obj.optString("country").takeIf { it.isNotBlank() },
                status = obj.optString("status").takeIf { it.isNotBlank() },
                barcode = obj.optString("barcode").takeIf { it.isNotBlank() },
                releaseGroupId = obj.optJSONObject("release-group")?.optString("id")?.takeIf { it.isNotBlank() },
                label = obj.optJSONArray("label-info")?.optJSONObject(0)?.optJSONObject("label")?.optString("name")?.takeIf { it.isNotBlank() },
                trackNumber = trackNumber,
                discNumber = discNumber,
                mediumPosition = mediumPosition
            )
        }
    }
}

interface MusicBrainzCache {
    fun get(key: String): MusicBrainzRecording?
    fun put(key: String, value: MusicBrainzRecording)
}

class InMemoryMusicBrainzCache : MusicBrainzCache {
    private val cache = LinkedHashMap<String, MusicBrainzRecording>(300, 0.75f, true)

    override fun get(key: String): MusicBrainzRecording? = synchronized(cache) { cache[key] }

    override fun put(key: String, value: MusicBrainzRecording) {
        synchronized(cache) {
            cache[key] = value
            while (cache.size > 300) cache.remove(cache.entries.first().key)
        }
    }
}
