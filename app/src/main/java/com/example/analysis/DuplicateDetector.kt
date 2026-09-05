package com.example.analysis

import com.example.metadata.parser.TrackIdentityParser
import com.example.model.AudioQualityRating
import com.example.model.DuplicateMatch
import com.example.model.Track
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DuplicateDetector {

    private data class NormalizedTrack(
        val track: Track,
        val normTitle: String,
        val normArtist: String,
        val combined: String
    )

    /**
     * Finds potential duplicates in the track list based on fuzzy title, artist, duration, and BPM.
     * Uses sliding duration-window indexing and pre-computed string normalizations for high performance on large libraries.
     */
    fun findDuplicates(tracks: List<Track>): List<DuplicateMatch> {
        if (tracks.size < 2) return emptyList()
        val matches = mutableListOf<DuplicateMatch>()

        val normalized = tracks.map { t ->
            val nTitle = normalizeTrackString(t.title)
            val nArtist = normalizeTrackString(t.artist)
            NormalizedTrack(
                track = t,
                normTitle = nTitle,
                normArtist = nArtist,
                combined = "$nArtist $nTitle"
            )
        }.sortedBy { it.track.durationSeconds }

        for (i in 0 until normalized.size) {
            val t1 = normalized[i]
            val dur1 = t1.track.durationSeconds

            for (j in i + 1 until normalized.size) {
                val t2 = normalized[j]
                val dur2 = t2.track.durationSeconds

                // Early exit: duration difference exceeds threshold for valid duplicate score
                if (dur1 > 0 && dur2 > 0 && (dur2 - dur1) > 30) {
                    break
                }

                // Fast path for identical content fingerprints
                val fp1 = t1.track.contentFingerprint
                val fp2 = t2.track.contentFingerprint
                if (fp1.isNotBlank() && fp2.isNotBlank() && fp1 == fp2) {
                    matches.add(
                        DuplicateMatch(
                            trackA = t1.track,
                            trackB = t2.track,
                            similarityScore = 100,
                            reason = "Exact audio fingerprint match (identical audio hash).",
                            recommendedAction = buildRecommendation(t1.track, t2.track)
                        )
                    )
                    continue
                }

                val score = calculateSimilarity(t1, t2)
                if (score >= 68) { // Confident threshold for fuzzy duplicates
                    val reason = buildReason(t1.track, t2.track, score)
                    val recommendation = buildRecommendation(t1.track, t2.track)
                    matches.add(
                        DuplicateMatch(
                            trackA = t1.track,
                            trackB = t2.track,
                            similarityScore = score,
                            reason = reason,
                            recommendedAction = recommendation
                        )
                    )
                }
            }
        }

        return matches.sortedByDescending { it.similarityScore }
    }

    private fun calculateSimilarity(t1: NormalizedTrack, t2: NormalizedTrack): Int {
        // Version / Remix check: Different versions/remixes must NOT be treated as duplicates
        val v1 = TrackIdentityParser.extractVersion(t1.track.title)
        val v2 = TrackIdentityParser.extractVersion(t2.track.title)
        if (v1 != null && v2 != null && !v1.equals(v2, ignoreCase = true)) {
            return 0
        }

        val titleSim = tokenSimilarity(t1.normTitle, t2.normTitle)
        val artistSim = tokenSimilarity(t1.normArtist, t2.normArtist)

        // Cross check: sometimes artist is in the title e.g. "Daft Punk - One More Time"
        val combinedSim = tokenSimilarity(t1.combined, t2.combined)
        val effectiveTitleSim = max(titleSim, combinedSim)

        // Duration check: tracks with similar length are much more likely duplicates
        val durationDiff = abs(t1.track.durationSeconds - t2.track.durationSeconds)
        val durationFactor = when {
            durationDiff <= 3 -> 1.0f
            durationDiff <= 10 -> 0.85f
            durationDiff <= 30 -> 0.60f
            else -> 0.30f
        }

        // BPM check
        val bpmDiff = abs(t1.track.bpm - t2.track.bpm)
        val bpmFactor = if (bpmDiff < 1.0) 1.0f else 0.85f

        val rawScore = (effectiveTitleSim * 0.6f + artistSim * 0.4f) * durationFactor * bpmFactor
        return (rawScore * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Normalizes string by stripping track numbers, brackets, extension info, and common suffixes
     */
    fun normalizeTrackString(input: String): String {
        return input.lowercase(Locale.ROOT)
            .replace(Regex("^\\d{1,3}[.\\-\\s_]+"), "") // leading track numbers like "01. " or "02 - "
            .replace(Regex("\\.(mp3|flac|wav|aac|aiff|m4a|ogg)$"), "")
            .replace(Regex("[\\[\\(][^\\]\\)]*[\\]\\)]"), " ") // strip bracketed/parenthesized content
            .replace(Regex("^[a-z0-9\\s]+[\\-–—:]\\s*"), " ") // strip "Artist - " prefix in title
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        if (s1 == s2) return 1.0f

        val tokens1 = s1.split(" ").filter { it.isNotBlank() }.toSet()
        val tokens2 = s2.split(" ").filter { it.isNotBlank() }.toSet()

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0f

        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        val minSize = min(tokens1.size, tokens2.size)

        val jaccard = intersection.toFloat() / union.toFloat()
        val containment = if (minSize > 0) intersection.toFloat() / minSize.toFloat() else 0.0f
        val lev = 1.0f - (levenshteinDistance(s1, s2).toFloat() / max(s1.length, s2.length).toFloat())

        return maxOf(jaccard, containment * 0.9f, lev)
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = Array(lhsLength + 1) { it }
        var newCost = Array(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    private fun buildReason(t1: Track, t2: Track, score: Int): String {
        val durationDiff = abs(t1.durationSeconds - t2.durationSeconds)
        return when {
            t1.title.equals(t2.title, ignoreCase = true) -> 
                "Exact title match with different file qualities (${t1.format} ${t1.bitrateKbps}k vs ${t2.format} ${t2.bitrateKbps}k)."
            durationDiff <= 2 -> 
                "Near-identical audio duration (${t1.durationSeconds}s) and harmonic metadata with $score% naming similarity."
            else -> 
                "Fuzzy match detected ($score% match) between '${t1.title}' and '${t2.title}'."
        }
    }

    private fun buildRecommendation(t1: Track, t2: Track): String {
        val q1 = qualityScore(t1)
        val q2 = qualityScore(t2)

        return when {
            q1 > q2 -> "Keep Track A (${t1.format} ${t1.bitrateKbps}kbps) - Higher fidelity. Delete or archive Track B to save cloud & local storage."
            q2 > q1 -> "Keep Track B (${t2.format} ${t2.bitrateKbps}kbps) - Higher fidelity. Delete or archive Track A to save cloud & local storage."
            t1.isAiTagged && !t2.isAiTagged -> "Keep Track A (Verified AI Tagged & Cued). Merge metadata."
            else -> "Merge metadata into Track A and remove redundant copy."
        }
    }

    private fun qualityScore(t: Track): Int {
        var score = t.bitrateKbps
        if (t.format.equals("FLAC", ignoreCase = true) || t.format.equals("WAV", ignoreCase = true)) score += 500
        if (t.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) score -= 400
        if (t.qualityRating == AudioQualityRating.STUDIO_LOSSLESS) score += 600
        return score
    }
}
