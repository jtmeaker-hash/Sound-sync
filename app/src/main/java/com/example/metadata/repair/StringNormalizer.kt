package com.example.metadata.repair

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Normalises music artist and title strings for cross-source comparison and consensus matching.
 *
 * Handles differences in:
 * - capitalisation
 * - punctuation
 * - Unicode characters and accents (e.g. "RÜFÜS DU SOL" vs "rufus du sol")
 * - apostrophes
 * - "&" vs "and"
 * - feat., ft., featuring
 * - remix labels, radio edit labels, extended mix labels
 * - collaborations (e.g. "Calvin Harris & Dua Lipa", "Fred again.. & Skrillex")
 */
object StringNormalizer {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val VERSION_TAGS_REGEX = Regex(
        "(?i)\\s*[\\[(](original mix|radio edit|extended mix|club mix|remix|vip|dub|instrumental|live|acoustic|remaster|clean|dirty|master|official).*?[\\])]"
    )
    private val FEATURING_TAGS_REGEX = Regex(
        "(?i)\\s*[\\[(](feat\\.?|ft\\.?|featuring)\\s+.*?[\\])]"
    )
    private val FEATURING_INLINE_REGEX = Regex(
        "(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*"
    )
    private val AUDIO_EXT_REGEX = Regex(
        "(?i)\\.(mp3|flac|wav|m4a|aac|ogg|opus|aif|aiff|wma)$"
    )
    private val TRACK_NUMBER_PREFIX_REGEX = Regex(
        "^(\\[?[0-9]{1,3}\\]?[.\\-\\s]+|\\([0-9]{1,3}\\)[.\\-\\s]+)"
    )

    /**
     * Decomposes Unicode, removes diacritics, normalises apostrophes/quotes, and converts to lowercase.
     */
    fun foldUnicodeAndCase(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val stripped = DIACRITICS_REGEX.replace(decomposed, "")
        return stripped
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('`', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace('—', '-')
            .lowercase(Locale.ROOT)
            .trim()
    }

    /**
     * Standardises representation of collaborations (&, and, +, x, vs).
     */
    fun standardizeCollaborations(input: String): String {
        return input
            .replace(Regex("(?i)\\s+and\\s+"), " & ")
            .replace(Regex("(?i)\\s*\\+\\s*"), " & ")
            .replace(Regex("(?i)\\s+x\\s+"), " & ")
            .replace(Regex("(?i)\\s+vs\\.?\\s+"), " & ")
    }

    /**
     * Strips version / remix suffixes and file extensions from a title.
     */
    fun stripVersionAndExtension(title: String): String {
        var result = AUDIO_EXT_REGEX.replace(title, "")
        result = TRACK_NUMBER_PREFIX_REGEX.replace(result, "")
        result = VERSION_TAGS_REGEX.replace(result, "")
        result = FEATURING_TAGS_REGEX.replace(result, "")
        return result.trim()
    }

    /**
     * Extracts remixer name if present in title (e.g. "Song Name (John Summit Remix)" -> "John Summit").
     */
    fun extractRemixer(title: String): String? {
        val match = Regex("(?i)[\\[(](.+?)\\s+(remix|mix|dub|vip)[\\])]").find(title)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf {
            !it.equals("original", ignoreCase = true) &&
            !it.equals("extended", ignoreCase = true) &&
            !it.equals("club", ignoreCase = true) &&
            !it.equals("radio", ignoreCase = true)
        }
    }

    /**
     * Normalises an artist string for matching.
     * Returns a stripped, lower-case, diacritic-free string.
     */
    fun normalizeArtist(artist: String): String {
        val folded = foldUnicodeAndCase(standardizeCollaborations(artist))
        // Remove special punctuation, keeping alphanumeric and &
        return folded
            .replace(Regex("[^a-z0-9& ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Normalises a title string for matching.
     */
    fun normalizeTitle(title: String): String {
        val stripped = stripVersionAndExtension(title)
        val folded = foldUnicodeAndCase(stripped)
        return folded
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Splits multi-artist collaboration into individual artist tokens.
     */
    fun extractArtistTokens(artist: String): List<String> {
        val std = standardizeCollaborations(artist)
        return std.split('&', ',')
            .map { normalizeArtist(it) }
            .filter { it.isNotBlank() }
    }

    /**
     * Determines whether two artist strings represent the same artist or group,
     * accounting for accents, case, & vs and, and collaborations.
     */
    fun areArtistsEquivalent(a: String, b: String): Boolean {
        val normA = normalizeArtist(a)
        val normB = normalizeArtist(b)
        if (normA == normB) return true
        if (normA.isBlank() || normB.isBlank()) return false

        // Check if one contains the other
        if (normA.contains(normB) || normB.contains(normA)) {
            val minLen = min(normA.length, normB.length)
            val maxLen = max(normA.length, normB.length)
            if (minLen.toDouble() / maxLen > 0.70) return true
        }

        // Collaboration token set overlap (e.g. "Calvin Harris & Dua Lipa" == "Dua Lipa & Calvin Harris")
        val tokensA = extractArtistTokens(a).toSet()
        val tokensB = extractArtistTokens(b).toSet()
        if (tokensA.isNotEmpty() && tokensA == tokensB) return true

        return calculateArtistSimilarity(normA, normB) >= 0.85
    }

    /**
     * Computes similarity between two artist strings (0.0 to 1.0) using Jaccard and Levenshtein metrics.
     */
    fun calculateArtistSimilarity(a: String, b: String): Double {
        val normA = normalizeArtist(a)
        val normB = normalizeArtist(b)
        if (normA == normB) return 1.0
        if (normA.isBlank() || normB.isBlank()) return 0.0

        val wordsA = normA.split(' ').filter { it.isNotBlank() }.toSet()
        val wordsB = normB.split(' ').filter { it.isNotBlank() }.toSet()

        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        val jaccard = if (union > 0) intersection.toDouble() / union else 0.0

        val lev = levenshteinSimilarity(normA, normB)
        return max(jaccard, lev)
    }

    /**
     * Computes similarity between two title strings (0.0 to 1.0).
     */
    fun calculateTitleSimilarity(a: String, b: String): Double {
        val normA = normalizeTitle(a)
        val normB = normalizeTitle(b)
        if (normA == normB) return 1.0
        if (normA.isBlank() || normB.isBlank()) return 0.0

        val wordsA = normA.split(' ').filter { it.isNotBlank() }.toSet()
        val wordsB = normB.split(' ').filter { it.isNotBlank() }.toSet()

        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        val jaccard = if (union > 0) intersection.toDouble() / union else 0.0

        val lev = levenshteinSimilarity(normA, normB)
        return max(jaccard, lev)
    }

    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return (maxLen - dist).toDouble() / maxLen
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
