package com.example.metadata

import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.repair.StringNormalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ScoredAppleCandidate(
    val candidate: AppleTrackResult,
    val totalScore: Double,
    val titleScore: Double,
    val artistScore: Double,
    val durationDeltaSeconds: Int,
    val isVersionMatched: Boolean,
    val scoreBreakdown: String
)

/**
 * Evaluates and scores Apple Search API candidate songs against local file characteristics.
 *
 * Enforces rigorous duration gating, version matching (Phase 10), and cross-attribute scoring.
 * Prevents casual misassignment (e.g. matching an Extended Mix with a Radio Edit).
 */
object MetadataConfidenceScorer {

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
        val artistPoints: Double
        if (!localArtist.isNullOrBlank() && TrackIdentityParser.isArtistValid(localArtist)) {
            val artistSim = StringNormalizer.calculateArtistSimilarity(localArtist, candidate.artistName)
            artistPoints = artistSim * 30.0
            details.append("Artist: ${"%.1f".format(artistPoints)}/30 | ")
        } else {
            // Missing artist scenario: award provisional points if artist is clearly present in title or query
            artistPoints = 20.0
            details.append("Artist: 20.0 (provisional) | ")
        }

        // 3. Duration Similarity (+25 bonus down to -45 penalty)
        var durationDelta = 0
        var durationModifier = 0.0
        if (localDurationSeconds > 0 && candidate.durationSeconds > 0) {
            durationDelta = abs(localDurationSeconds - candidate.durationSeconds)
            durationModifier = when {
                durationDelta <= 3 -> +25.0
                durationDelta <= 7 -> +18.0
                durationDelta <= 15 -> +10.0
                durationDelta <= 25 -> 0.0
                durationDelta <= 45 -> -20.0
                durationDelta <= 90 -> -35.0
                else -> -50.0
            }
            details.append("Duration: delta=${durationDelta}s (${if (durationModifier >= 0) "+" else ""}${"%.1f".format(durationModifier)}) | ")
        }

        // 4. Version Matching (Phase 10)
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
                versionModifier = -30.0
                details.append("Version: MISMATCH [$localVersion vs $candidateVersion] (-30) | ")
            } else {
                versionModifier = -20.0
                details.append("Version: UNVERSIONED CANDIDATE for [$localVersion] (-20) | ")
            }
        } else {
            // Local is unversioned: check if candidate is a drastically different special version
            if (candidateVersion != null && (candidateVersion.contains("Remix", true) || candidateVersion.contains("Extended", true) || candidateVersion.contains("VIP", true))) {
                if (durationDelta > 15) {
                    versionModifier = -15.0
                    details.append("Version: UNREQUESTED SPECIAL VERSION [$candidateVersion] (-15) | ")
                }
            } else {
                isVersionMatched = true
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

        val rawTotal = titlePoints + artistPoints + durationModifier + versionModifier + albumModifier
        val clampedTotal = rawTotal.coerceIn(0.0, 100.0)

        return ScoredAppleCandidate(
            candidate = candidate,
            totalScore = clampedTotal,
            titleScore = titlePoints,
            artistScore = artistPoints,
            durationDeltaSeconds = durationDelta,
            isVersionMatched = isVersionMatched,
            scoreBreakdown = details.toString().trimEnd(' ', '|')
        )
    }

    fun isHighConfidence(score: Double): Boolean {
        return score >= COMMIT_CONFIDENCE_THRESHOLD
    }
}
