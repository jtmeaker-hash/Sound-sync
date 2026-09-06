package com.example.metadata

import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.repair.StringNormalizer
import com.example.model.MetadataScanState
import kotlin.math.abs

data class ScoredAppleCandidate(
    val candidate: AppleTrackResult,
    val totalScore: Double,
    val titleScore: Double,
    val artistScore: Double,
    val durationDeltaSeconds: Int,
    val isVersionMatched: Boolean,
    val scoreBreakdown: String,
    val matchStatus: MetadataScanState = MetadataScanState.REVIEW_REQUIRED
)

data class CandidateEvaluationResult(
    val bestCandidate: ScoredAppleCandidate?,
    val matchStatus: MetadataScanState,
    val allCandidates: List<ScoredAppleCandidate>,
    val isMultipleMatches: Boolean,
    val summary: String
)

/**
 * Evaluates and scores external metadata candidate tracks against local file characteristics.
 *
 * Enforces strict safety rules (Sections 3, 4, 13, 14, 16):
 * - External metadata may only be marked VERIFIED when confidence is effectively 100% (>= 95.0%).
 * - Strict version matching: Never treat Original Mix, Extended Mix, Radio Edit, VIP, Remix, Live as identical.
 * - Strict duration gating: Substantial duration differences (e.g. 6:42 vs 3:18) heavily penalize and reject.
 * - Multi-candidate conflict detection: When multiple valid releases or mixes are found, mark CONFLICTING_RESULTS.
 */
object MetadataConfidenceScorer {

    const val VERIFIED_CONFIDENCE_THRESHOLD = 95.0
    const val COMMIT_CONFIDENCE_THRESHOLD = 85.0
    const val MINIMUM_ACCEPTABLE_THRESHOLD = 60.0

    fun scoreCandidate(
        localTitle: String,
        localArtist: String?,
        localAlbum: String?,
        localDurationSeconds: Int,
        candidate: AppleTrackResult
    ): ScoredAppleCandidate {
        val details = StringBuilder()

        // 1. Title Similarity (0 to 40)
        val cleanCandidateTitle = StringNormalizer.stripVersionAndExtension(candidate.trackName)
        val cleanLocalTitle = StringNormalizer.stripVersionAndExtension(localTitle)
        val titleSim = StringNormalizer.calculateTitleSimilarity(cleanLocalTitle, cleanCandidateTitle)
        val titlePoints = titleSim * 40.0
        details.append("Title: ${"%.1f".format(titlePoints)}/40 (sim=${"%.2f".format(titleSim)}) | ")

        // 2. Artist Similarity (0 to 30)
        var artistSim = 0.0
        val artistPoints: Double
        var artistModifier = 0.0
        if (!localArtist.isNullOrBlank() && TrackIdentityParser.isArtistValid(localArtist)) {
            artistSim = StringNormalizer.calculateArtistSimilarity(localArtist, candidate.artistName)
            if (StringNormalizer.areArtistsEquivalent(localArtist, candidate.artistName)) {
                artistSim = 1.0
            }
            artistPoints = artistSim * 30.0
            // Heavy penalty if artist is clearly different
            if (artistSim < 0.35) {
                artistModifier = -40.0
                details.append("Artist: MISMATCH (${"%.2f".format(artistSim)}) (-40) | ")
            } else {
                details.append("Artist: ${"%.1f".format(artistPoints)}/30 | ")
            }
        } else {
            // Missing artist scenario: award provisional points if artist is clearly present in title or query
            artistSim = 0.70
            artistPoints = 20.0
            details.append("Artist: 20.0 (provisional) | ")
        }

        // 3. Duration Similarity (+25 bonus down to -65 penalty)
        var durationDelta = 0
        var durationModifier = 0.0
        if (localDurationSeconds > 0 && candidate.durationSeconds > 0) {
            durationDelta = abs(localDurationSeconds - candidate.durationSeconds)
            durationModifier = when {
                durationDelta <= 3 -> +25.0
                durationDelta <= 7 -> +20.0
                durationDelta <= 12 -> +15.0
                durationDelta <= 25 -> 0.0
                durationDelta <= 45 -> -25.0
                durationDelta <= 90 -> -45.0
                else -> -65.0 // Substantial difference (e.g. 6:42 vs 3:18)
            }
            details.append("Duration: delta=${durationDelta}s (${if (durationModifier >= 0) "+" else ""}${"%.1f".format(durationModifier)}) | ")
        }

        // 4. Version Matching (Section 3)
        val localVersion = TrackIdentityParser.extractVersion(localTitle)
        val candidateVersion = TrackIdentityParser.extractVersion(candidate.trackName)
        var versionModifier = 0.0
        var isVersionMatched = false

        if (localVersion != null) {
            if (candidateVersion != null && candidateVersion.equals(localVersion, ignoreCase = true)) {
                versionModifier = +15.0
                isVersionMatched = true
                details.append("Version: MATCH [$localVersion] (+15) | ")
            } else if (candidateVersion != null) {
                versionModifier = -45.0
                details.append("Version: MISMATCH [$localVersion vs $candidateVersion] (-45) | ")
            } else {
                versionModifier = -35.0
                details.append("Version: UNVERSIONED CANDIDATE for [$localVersion] (-35) | ")
            }
        } else {
            // Local is unversioned
            if (candidateVersion != null) {
                versionModifier = -35.0
                details.append("Version: CANDIDATE HAS EXTRA VERSION [$candidateVersion] (-35) | ")
            } else {
                isVersionMatched = true
                details.append("Version: BOTH UNVERSIONED (+0) | ")
            }
        }

        // 5. Album / Collection Matching (+10 bonus)
        var albumModifier = 0.0
        if (!localAlbum.isNullOrBlank() && !candidate.collectionName.isNullOrBlank()) {
            if (StringNormalizer.calculateTitleSimilarity(localAlbum, candidate.collectionName) >= 0.75) {
                albumModifier = +10.0
                details.append("Album: MATCH (+10) | ")
            }
        }

        val rawTotal = titlePoints + artistPoints + durationModifier + versionModifier + albumModifier + artistModifier
        val clampedTotal = rawTotal.coerceIn(0.0, 100.0)

        // Classify candidate status
        val status = when {
            clampedTotal >= VERIFIED_CONFIDENCE_THRESHOLD && isVersionMatched && durationDelta <= 12 && titleSim >= 0.90 && artistSim >= 0.85 -> {
                MetadataScanState.VERIFIED
            }
            clampedTotal >= MINIMUM_ACCEPTABLE_THRESHOLD -> {
                MetadataScanState.REVIEW_REQUIRED
            }
            else -> {
                MetadataScanState.REJECTED
            }
        }

        return ScoredAppleCandidate(
            candidate = candidate,
            totalScore = clampedTotal,
            titleScore = titlePoints,
            artistScore = artistPoints,
            durationDeltaSeconds = durationDelta,
            isVersionMatched = isVersionMatched,
            scoreBreakdown = details.toString().trimEnd(' ', '|'),
            matchStatus = status
        )
    }

    /**
     * Evaluates a collection of candidate tracks from search results,
     * detecting conflicts, ties, multiple releases, or verified matches.
     */
    fun evaluateCandidates(
        localTitle: String,
        localArtist: String?,
        localAlbum: String?,
        localDurationSeconds: Int,
        candidates: List<AppleTrackResult>
    ): CandidateEvaluationResult {
        if (candidates.isEmpty()) {
            return CandidateEvaluationResult(
                bestCandidate = null,
                matchStatus = MetadataScanState.NO_MATCH,
                allCandidates = emptyList(),
                isMultipleMatches = false,
                summary = "No search results returned"
            )
        }

        val scored = candidates.map { candidate ->
            scoreCandidate(
                localTitle = localTitle,
                localArtist = localArtist,
                localAlbum = localAlbum,
                localDurationSeconds = localDurationSeconds,
                candidate = candidate
            )
        }.sortedByDescending { it.totalScore }

        val best = scored.first()

        if (best.totalScore < 45.0) {
            return CandidateEvaluationResult(
                bestCandidate = best,
                matchStatus = MetadataScanState.NO_MATCH,
                allCandidates = scored,
                isMultipleMatches = false,
                summary = "No candidate reached minimum baseline score (top: ${"%.1f".format(best.totalScore)})"
            )
        }

        if (best.totalScore < MINIMUM_ACCEPTABLE_THRESHOLD) {
            return CandidateEvaluationResult(
                bestCandidate = best,
                matchStatus = MetadataScanState.REJECTED,
                allCandidates = scored,
                isMultipleMatches = false,
                summary = "Best candidate rejected due to low confidence (${"%.1f".format(best.totalScore)})"
            )
        }

        // Check for multiple viable candidates (conflict / multiple matches)
        if (scored.size >= 2) {
            val second = scored[1]
            if (second.totalScore >= MINIMUM_ACCEPTABLE_THRESHOLD && (best.totalScore - second.totalScore) <= 10.0) {
                return CandidateEvaluationResult(
                    bestCandidate = best,
                    matchStatus = MetadataScanState.CONFLICTING_RESULTS,
                    allCandidates = scored,
                    isMultipleMatches = true,
                    summary = "Multiple viable matches found (${"%.1f".format(best.totalScore)} vs ${"%.1f".format(second.totalScore)})"
                )
            }
        }

        return CandidateEvaluationResult(
            bestCandidate = best,
            matchStatus = best.matchStatus,
            allCandidates = scored,
            isMultipleMatches = false,
            summary = "Best match identified with ${"%.1f".format(best.totalScore)}% confidence (${best.matchStatus})"
        )
    }

    fun isHighConfidence(score: Double): Boolean {
        return score >= COMMIT_CONFIDENCE_THRESHOLD
    }

    fun isVerified(score: Double): Boolean {
        return score >= VERIFIED_CONFIDENCE_THRESHOLD
    }
}
