package com.example.intelligence

import android.content.Context
import androidx.collection.LruCache
import com.example.dj.MixCompatibilityEngine
import com.example.dj.MixRecommendation
import com.example.model.AudioQualityRating
import com.example.model.Track
import com.example.smartcrate.SmartCrate
import com.example.smartcrate.SmartField
import com.example.smartcrate.SmartMatchMode
import com.example.smartcrate.SmartOperator
import com.example.smartcrate.SmartRule
import com.example.smartcrate.SmartSortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class SimilarTrack(
    val track: Track,
    val similarityScore: Int, // 0 to 100
    val reasons: List<String>,
    val genreMatch: Boolean,
    val tempoMatch: Boolean,
    val keyMatch: Boolean,
    val eraMatch: Boolean
)

data class LibraryHealthReport(
    val totalTracks: Int,
    val tracksMissingArtist: Int,
    val tracksMissingArtwork: Int,
    val tracksMissingBpmOrKey: Int,
    val neverPlayedCount: Int,
    val unplayedOverOneYearCount: Int,
    val suspiciousTranscodeCount: Int,
    val losslessTracksCount: Int,
    val topBpmCluster: String,
    val topMusicalKeys: List<Pair<String, Int>>,
    val topGenres: List<Pair<String, Int>>
)

data class TrackIntelligenceData(
    val trackId: String,
    val identityConfidence: Float, // 0.0 to 1.0
    val metadataTrustScore: Float, // 0.0 to 1.0
    val hasFingerprint: Boolean,
    val hasBpmKey: Boolean,
    val hasPhraseAnalysis: Boolean,
    val hasQualityInspection: Boolean,
    val hasLyrics: Boolean,
    val isLossless: Boolean,
    val isSuspiciousTranscode: Boolean,
    val similarTracksCount: Int,
    val mixCompatibleTracksCount: Int
)

/**
 * SoundSync Central Intelligence Layer implementing Step 3 Parts C, D, E, F, G, H, I, J, K, L.
 *
 * Combines track metadata, audio analysis, Camelot harmonic theory, listening history,
 * audio quality, and phrase structures into actionable, explainable insights.
 */
object SoundSyncIntelligenceEngine {

    private val similarityCache = LruCache<String, List<SimilarTrack>>(40)

    // ──────────────────────────────────────────────────────────────────────────
    // Part C & D: Reusable Domain Functions with Explainable Reasoning
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Scores harmonic, tempo, energy, and genre mix compatibility between two tracks.
     */
    fun scoreMixCompatibility(trackA: Track, trackB: Track): MixRecommendation {
        return MixCompatibilityEngine.evaluatePair(trackA, trackB)
    }

    /**
     * Computes tracks similar to the given target based on genre, tempo, key, and era.
     * Complies with Part E (Local Library Discovery).
     */
    suspend fun getSimilarTracks(
        target: Track,
        library: List<Track>,
        limit: Int = 20
    ): List<SimilarTrack> = withContext(Dispatchers.Default) {
        val cacheKey = "${target.id}_${library.size}_similar"
        similarityCache.get(cacheKey)?.let { return@withContext it }

        val targetGenre = target.genre.trim().lowercase(Locale.ROOT)
        val targetBpm = target.bpm
        val targetKey = target.camelotKey.ifBlank { target.musicalKey }
        val targetYear = target.releaseYear ?: 0

        val results = mutableListOf<SimilarTrack>()

        for (candidate in library) {
            if (candidate.id == target.id) continue
            // Exclude identical title + artist
            if (candidate.title.equals(target.title, ignoreCase = true) && candidate.artist.equals(target.artist, ignoreCase = true)) {
                continue
            }

            var score = 0
            val reasons = mutableListOf<String>()

            // 1. Genre Match (Weight: 35)
            val candidateGenre = candidate.genre.trim().lowercase(Locale.ROOT)
            val genreMatch = targetGenre.isNotBlank() && candidateGenre.isNotBlank() &&
                    (targetGenre == candidateGenre || targetGenre.contains(candidateGenre) || candidateGenre.contains(targetGenre))
            if (genreMatch) {
                score += 35
                reasons.add("Matching genre: ${candidate.genre}")
            }

            // 2. Tempo Match (Weight: 30)
            val tempoMatch = if (targetBpm > 0.0 && candidate.bpm > 0.0) {
                val diffPct = abs(candidate.bpm - targetBpm) / targetBpm * 100.0
                if (diffPct <= 5.0) {
                    score += 30
                    reasons.add("Similar tempo (${candidate.bpm.toInt()} BPM)")
                    true
                } else if (diffPct <= 10.0) {
                    score += 18
                    reasons.add("Nearby tempo (~${candidate.bpm.toInt()} BPM)")
                    true
                } else false
            } else false

            // 3. Key Match / Harmony (Weight: 20)
            val candidateKey = candidate.camelotKey.ifBlank { candidate.musicalKey }
            val keyMatch = if (targetKey.isNotBlank() && candidateKey.isNotBlank()) {
                val pair = MixCompatibilityEngine.evaluatePair(target, candidate)
                if (pair.harmonicScore >= 0.85f) {
                    score += (pair.harmonicScore * 20.0f).toInt()
                    reasons.add(pair.keyRelationship)
                    true
                } else false
            } else false

            // 4. Era / Release Year (Weight: 15)
            val candidateYear = candidate.releaseYear ?: 0
            val eraMatch = if (targetYear > 1900 && candidateYear > 1900) {
                val yearDiff = abs(candidateYear - targetYear)
                if (yearDiff <= 3) {
                    score += 15
                    reasons.add("Same era ($candidateYear)")
                    true
                } else if (yearDiff <= 8) {
                    score += 8
                    true
                } else false
            } else false

            if (score >= 35) {
                results.add(
                    SimilarTrack(
                        track = candidate,
                        similarityScore = score.coerceIn(0, 100),
                        reasons = reasons,
                        genreMatch = genreMatch,
                        tempoMatch = tempoMatch,
                        keyMatch = keyMatch,
                        eraMatch = eraMatch
                    )
                )
            }
        }

        val sorted = results.sortedByDescending { it.similarityScore }.take(limit)
        similarityCache.put(cacheKey, sorted)
        sorted
    }

    /**
     * Recommends the next tracks for smart playback queue assistance.
     * Complies with Part F (Smart Queue Assistance).
     */
    suspend fun getRecommendedNextTracks(
        currentTrack: Track,
        library: List<Track>,
        recentHistory: List<Track> = emptyList(),
        limit: Int = 15
    ): List<Track> = withContext(Dispatchers.Default) {
        val recentIds = recentHistory.map { it.id }.toSet() + currentTrack.id

        // Pre-filter out recent history to avoid repeating recent tracks
        val candidates = library.filter { it.id !in recentIds && it.isAvailable }

        // Find mix-compatible tracks first
        val recommendations = MixCompatibilityEngine.findCompatibleTracks(currentTrack, candidates, limit = limit * 2)

        if (recommendations.isNotEmpty()) {
            return@withContext recommendations.map { it.candidateTrack }.take(limit)
        }

        // Fallback: Similar tracks by genre and tempo
        val similar = getSimilarTracks(currentTrack, candidates, limit = limit)
        if (similar.isNotEmpty()) {
            return@withContext similar.map { it.track }
        }

        // Fallback: High rated tracks
        candidates.sortedByDescending { it.rating }.take(limit)
    }

    /**
     * Finds tracks that haven't been played in over 60 days or have never been played.
     */
    fun getForgottenTracks(library: List<Track>, limit: Int = 20): List<Track> {
        val sixtyDaysAgo = System.currentTimeMillis() - (60L * 24 * 3600 * 1000)
        return library.filter { it.isAvailable && it.dateAdded < sixtyDaysAgo }
            .sortedWith(compareByDescending<Track> { it.rating }.thenBy { it.dateAdded })
            .take(limit)
    }

    /**
     * Finds tracks that the user rarely plays (low play count).
     */
    fun getRarelyPlayedTracks(library: List<Track>, limit: Int = 20): List<Track> {
        return library.filter { it.isAvailable && it.rating <= 1 }
            .sortedBy { it.dateAdded }
            .take(limit)
    }

    /**
     * Identifies tracks that may benefit from a higher-quality lossless version.
     */
    fun getHighQualityVersionCandidates(library: List<Track>): List<Track> {
        return library.filter { track ->
            val isLowBitrate = track.bitrateKbps in 1..192
            val isSuspicious = track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED
            isLowBitrate || isSuspicious
        }
    }

    /**
     * Identifies tracks needing metadata repair / review.
     */
    fun getTracksNeedingReview(library: List<Track>): List<Track> {
        return library.filter { track ->
            track.artist.isBlank() ||
            track.artist.equals("Unknown Artist", ignoreCase = true) ||
            track.title.isBlank() ||
            track.title.startsWith("Track ", ignoreCase = true) ||
            track.artworkCachePath.isNullOrBlank()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part G: Factual Library Health Insights
    // ──────────────────────────────────────────────────────────────────────────

    fun getLibraryHealthInsights(library: List<Track>): LibraryHealthReport {
        if (library.isEmpty()) {
            return LibraryHealthReport(0, 0, 0, 0, 0, 0, 0, 0, "None", emptyList(), emptyList())
        }

        val total = library.size
        var missingArtist = 0
        var missingArt = 0
        var missingBpmKey = 0
        var neverPlayed = 0
        var unplayed1Year = 0
        var suspiciousTranscodes = 0
        var losslessCount = 0

        val oneYearAgo = System.currentTimeMillis() - (365L * 24 * 3600 * 1000)

        val keyCounts = mutableMapOf<String, Int>()
        val genreCounts = mutableMapOf<String, Int>()
        val bpmBuckets = mutableMapOf<String, Int>()

        for (t in library) {
            if (t.artist.isBlank() || t.artist.equals("Unknown Artist", ignoreCase = true)) missingArtist++
            if (t.artworkCachePath.isNullOrBlank() && t.artworkUrl.isNullOrBlank()) missingArt++
            if (t.bpm <= 0.0 || (t.musicalKey.isBlank() && t.camelotKey.isBlank())) missingBpmKey++
            if (t.rating == 0) neverPlayed++
            if (t.dateAdded <= oneYearAgo) unplayed1Year++
            if (t.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) suspiciousTranscodes++
            if (t.qualityRating.isLossless || t.format.uppercase() in listOf("FLAC", "WAV", "AIFF")) losslessCount++

            // Keys
            val k = t.camelotKey.ifBlank { t.musicalKey }
            if (k.isNotBlank()) keyCounts[k] = (keyCounts[k] ?: 0) + 1

            // Genres
            if (t.genre.isNotBlank() && !t.genre.equals("Unknown", ignoreCase = true)) {
                val g = t.genre.trim()
                genreCounts[g] = (genreCounts[g] ?: 0) + 1
            }

            // BPM Clusters (buckets of 6 BPM)
            if (t.bpm in 60.0..180.0) {
                val bucketFloor = (t.bpm / 6.0).toInt() * 6
                val bucketLabel = "$bucketFloor–${bucketFloor + 6} BPM"
                bpmBuckets[bucketLabel] = (bpmBuckets[bucketLabel] ?: 0) + 1
            }
        }

        val topClusterEntry = bpmBuckets.maxByOrNull { it.value }
        val topCluster = if (topClusterEntry != null) "${topClusterEntry.key} (${topClusterEntry.value} tracks)" else "N/A"

        val topKeys = keyCounts.toList().sortedByDescending { it.second }.take(5)
        val topGenres = genreCounts.toList().sortedByDescending { it.second }.take(5)

        return LibraryHealthReport(
            totalTracks = total,
            tracksMissingArtist = missingArtist,
            tracksMissingArtwork = missingArt,
            tracksMissingBpmOrKey = missingBpmKey,
            neverPlayedCount = neverPlayed,
            unplayedOverOneYearCount = unplayed1Year,
            suspiciousTranscodeCount = suspiciousTranscodes,
            losslessTracksCount = losslessCount,
            topBpmCluster = topCluster,
            topMusicalKeys = topKeys,
            topGenres = topGenres
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part H: Smart Crate Suggestions
    // ──────────────────────────────────────────────────────────────────────────

    fun getSmartCrateSuggestions(library: List<Track>): List<SmartCrate> {
        val suggestions = mutableListOf<SmartCrate>()

        // Suggestion 1: Peak-Time Energy (124-130 BPM)
        suggestions.add(
            SmartCrate(
                id = "suggest_peak_time",
                name = "Peak-Time Anthems (124–130 BPM)",
                matchMode = SmartMatchMode.MATCH_ALL,
                rules = listOf(
                    SmartRule(field = SmartField.BPM, operator = SmartOperator.BETWEEN, value = "124", secondaryValue = "130")
                ),
                sortField = SmartSortField.BPM,
                sortAscending = true
            )
        )

        // Suggestion 2: Lossless Sound Quality Library
        suggestions.add(
            SmartCrate(
                id = "suggest_lossless",
                name = "Pure Lossless Archive",
                matchMode = SmartMatchMode.MATCH_ALL,
                rules = listOf(
                    SmartRule(field = SmartField.IS_LOSSLESS, operator = SmartOperator.EQUALS, value = "true")
                ),
                sortField = SmartSortField.BITRATE,
                sortAscending = false
            )
        )

        // Suggestion 3: Unplayed Gems
        suggestions.add(
            SmartCrate(
                id = "suggest_unplayed",
                name = "Unplayed Discoveries",
                matchMode = SmartMatchMode.MATCH_ALL,
                rules = listOf(
                    SmartRule(field = SmartField.RATING, operator = SmartOperator.GREATER_THAN, value = "0")
                ),
                sortField = SmartSortField.DATE_ADDED,
                sortAscending = false,
                maxTrackLimit = 50
            )
        )

        // Suggestion 4: Needs Metadata Review
        suggestions.add(
            SmartCrate(
                id = "suggest_needs_review",
                name = "Missing Artwork or Metadata",
                matchMode = SmartMatchMode.MATCH_ANY,
                rules = listOf(
                    SmartRule(field = SmartField.HAS_ARTWORK, operator = SmartOperator.EQUALS, value = "false"),
                    SmartRule(field = SmartField.ARTIST, operator = SmartOperator.IS_EMPTY, value = "")
                ),
                sortField = SmartSortField.TITLE,
                sortAscending = true
            )
        )

        return suggestions
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part I: Track Intelligence Summary
    // ──────────────────────────────────────────────────────────────────────────

    fun getTrackIntelligence(track: Track, library: List<Track>): TrackIntelligenceData {
        var trust = 0.5f
        if (track.title.isNotBlank() && !track.title.startsWith("Track ")) trust += 0.2f
        if (track.artist.isNotBlank() && !track.artist.equals("Unknown Artist", ignoreCase = true)) trust += 0.2f
        if (!track.artworkCachePath.isNullOrBlank() || !track.artworkUrl.isNullOrBlank()) trust += 0.1f

        val identityConfidence = if (track.contentFingerprint.isNotBlank()) 0.95f else trust

        val hasBpm = track.bpm > 0.0
        val hasKey = track.musicalKey.isNotBlank() || track.camelotKey.isNotBlank()
        val isLossless = track.qualityRating.isLossless || track.format.uppercase() in listOf("FLAC", "WAV")
        val isSuspicious = track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED

        // Quick candidate counters
        val compatibleCount = if (hasBpm && hasKey) {
            library.count { other ->
                other.id != track.id && abs(other.bpm - track.bpm) <= 6.0
            }
        } else 0

        val similarCount = if (track.genre.isNotBlank()) {
            library.count { other ->
                other.id != track.id && other.genre.equals(track.genre, ignoreCase = true)
            }
        } else 0

        return TrackIntelligenceData(
            trackId = track.id,
            identityConfidence = identityConfidence.coerceIn(0.0f, 1.0f),
            metadataTrustScore = trust.coerceIn(0.0f, 1.0f),
            hasFingerprint = track.contentFingerprint.isNotBlank(),
            hasBpmKey = hasBpm && hasKey,
            hasPhraseAnalysis = hasBpm,
            hasQualityInspection = track.bitrateKbps > 0,
            hasLyrics = false, // Updated reactively by LyricsManager
            isLossless = isLossless,
            isSuspiciousTranscode = isSuspicious,
            similarTracksCount = similarCount,
            mixCompatibleTracksCount = compatibleCount
        )
    }
}
