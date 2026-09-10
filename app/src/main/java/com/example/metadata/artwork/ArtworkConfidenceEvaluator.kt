package com.example.metadata.artwork

import com.example.metadata.repair.StringNormalizer
import com.example.model.Track
import java.util.Locale
import kotlin.math.abs

/**
 * Evaluates candidate artwork against a track with fine-grained scoring breakdown.
 */
object ArtworkConfidenceEvaluator {

    data class EvaluationResult(
        val totalScore: Double,
        val breakdown: String,
        val isAutoAcceptable: Boolean,
        val isConditionalAcceptable: Boolean,
        val hasSeverePenalty: Boolean
    )

    /**
     * Evaluates a candidate match against the source track.
     */
    fun evaluateCandidate(
        track: Track,
        candidateArtist: String,
        candidateTitle: String?,
        candidateAlbum: String?,
        candidateDurationSeconds: Int = 0,
        candidateYear: Int? = null,
        candidateIsrc: String? = null
    ): EvaluationResult {
        var score = 0.0
        val reasons = mutableListOf<String>()
        var hasSeverePenalty = false

        val trackArtistNorm = StringNormalizer.normalizeArtist(track.artist)
        val candArtistNorm = StringNormalizer.normalizeArtist(candidateArtist)

        val trackTitleNorm = StringNormalizer.stripVersionAndExtension(track.title).lowercase()
        val candTitleNorm = candidateTitle?.let { StringNormalizer.stripVersionAndExtension(it).lowercase() }.orEmpty()

        val trackAlbumNorm = track.album?.lowercase()?.trim().orEmpty()
        val candAlbumNorm = candidateAlbum?.lowercase()?.trim().orEmpty()

        // 1. ISRC Match (+50)
        if (!track.isrc.isNullOrBlank() && !candidateIsrc.isNullOrBlank()) {
            val cleanTrackIsrc = track.isrc.replace("-", "").uppercase()
            val cleanCandIsrc = candidateIsrc.replace("-", "").uppercase()
            if (cleanTrackIsrc == cleanCandIsrc) {
                score += 50.0
                reasons.add("Exact ISRC match (+50)")
            }
        }

        // 2. Artist Matching (+40 exact, +20 fuzzy, -40 mismatch)
        if (trackArtistNorm.isNotBlank() && candArtistNorm.isNotBlank()) {
            when {
                trackArtistNorm == candArtistNorm -> {
                    score += 40.0
                    reasons.add("Exact artist match (+40)")
                }
                trackArtistNorm.contains(candArtistNorm) || candArtistNorm.contains(trackArtistNorm) -> {
                    score += 20.0
                    reasons.add("Fuzzy artist match (+20)")
                }
                else -> {
                    score -= 40.0
                    hasSeverePenalty = true
                    reasons.add("Artist mismatch (-40)")
                }
            }
        }

        // 3. Title Matching (+40 exact, +30 version match)
        if (trackTitleNorm.isNotBlank() && candTitleNorm.isNotBlank()) {
            if (trackTitleNorm == candTitleNorm) {
                score += 40.0
                reasons.add("Exact title match (+40)")
            } else if (trackTitleNorm.contains(candTitleNorm) || candTitleNorm.contains(trackTitleNorm)) {
                score += 25.0
                reasons.add("Fuzzy title match (+25)")
            }

            // Version tags check (Extended Mix, Remix, Radio Edit, Club Mix, etc.)
            val trackLower = track.title.lowercase()
            val candLower = (candidateTitle ?: "").lowercase()
            val versionKeywords = listOf(
                "extended", "remix", "radio edit", "club mix", "original mix",
                "vip", "dub", "acoustic", "instrumental", "live", "remaster"
            )
            for (kw in versionKeywords) {
                if (trackLower.contains(kw) && candLower.contains(kw)) {
                    score += 30.0
                    reasons.add("Version match ($kw) (+30)")
                    break
                }
            }
        }

        // 4. Album Matching (+25 exact, +15 fuzzy, -20 contradict)
        if (trackAlbumNorm.isNotBlank() && candAlbumNorm.isNotBlank()) {
            if (trackAlbumNorm == candAlbumNorm) {
                score += 25.0
                reasons.add("Exact album match (+25)")
            } else if (trackAlbumNorm.contains(candAlbumNorm) || candAlbumNorm.contains(trackAlbumNorm)) {
                score += 15.0
                reasons.add("Fuzzy album match (+15)")
            } else if (trackAlbumNorm != "single" && candAlbumNorm != "single") {
                score -= 20.0
                reasons.add("Different album (-20)")
            }
        }

        // 5. Duration Match (+10 for +-5s, +5 for +-15s, -20 for >30s)
        if (track.durationSeconds > 0 && candidateDurationSeconds > 0) {
            val diff = abs(track.durationSeconds - candidateDurationSeconds)
            when {
                diff <= 5 -> {
                    score += 10.0
                    reasons.add("Duration match (+-5s) (+10)")
                }
                diff <= 15 -> {
                    score += 5.0
                    reasons.add("Duration close (+-15s) (+5)")
                }
                diff > 30 -> {
                    score -= 20.0
                    reasons.add("Duration mismatch (${diff}s) (-20)")
                }
            }
        }

        // 6. Release Year Match (+10)
        val trackYear = track.releaseYear
        if (trackYear != null && trackYear > 0 && candidateYear != null && candidateYear > 0) {
            if (abs(trackYear - candidateYear) <= 1) {
                score += 10.0
                reasons.add("Release year match (+10)")
            }
        }

        // 7. Penalties for Tribute, Karaoke, Cover
        val textToCheck = "${candidateTitle.orEmpty()} ${candidateArtist} ${candidateAlbum.orEmpty()}".lowercase()
        val badKeywords = listOf("tribute", "karaoke", "originally performed by", "instrumental cover", "synth cover", "ringtone")
        for (kw in badKeywords) {
            if (textToCheck.contains(kw)) {
                score -= 30.0
                hasSeverePenalty = true
                reasons.add("Tribute/Cover detected ($kw) (-30)")
                break
            }
        }

        val clampedScore = score.coerceIn(0.0, 100.0)
        val isAuto = clampedScore >= 90.0 && !hasSeverePenalty
        val isConditional = clampedScore in 70.0..89.9 && !hasSeverePenalty

        return EvaluationResult(
            totalScore = clampedScore,
            breakdown = reasons.joinToString(", "),
            isAutoAcceptable = isAuto,
            isConditionalAcceptable = isConditional,
            hasSeverePenalty = hasSeverePenalty
        )
    }
}
