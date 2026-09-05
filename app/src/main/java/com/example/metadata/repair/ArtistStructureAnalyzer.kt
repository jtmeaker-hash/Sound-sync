package com.example.metadata.repair

import java.io.File
import java.util.Locale

/**
 * Result of analyzing the track title and filename structure.
 */
data class CandidateHypothesis(
    val title: String,
    val candidateArtist: String?,
    val alternativeArtist: String? = null,
    val alternativeTitle: String? = null,
    val remixer: String? = null,
    val isAmbiguous: Boolean = false,
    val confidence: Double = 0.0,
    val searchQueries: List<String> = emptyList()
)

/**
 * Analyzes track filenames and embedded titles to identify candidate artists and titles
 * when embedded tags are missing, unknown, or corrupted.
 */
object ArtistStructureAnalyzer {

    private val GENERIC_ARTIST_NAMES = setOf(
        "unknown",
        "unknown artist",
        "<unknown>",
        "various artists",
        "various",
        "va",
        "untitled",
        "soundtrack",
        "artist"
    )

    private val GENERIC_TITLE_NAMES = setOf(
        "unknown",
        "unknown title",
        "<unknown>",
        "untitled",
        "track",
        "audio"
    )

    /**
     * Checks whether an artist metadata string is considered missing, invalid, or unknown.
     */
    fun isArtistMissingOrInvalid(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val clean = artist.trim().lowercase(Locale.ROOT)
        if (GENERIC_ARTIST_NAMES.contains(clean)) return true
        if (clean.matches(Regex("^(track|audio|media|sound)?\\s*\\d+$"))) return true
        return false
    }

    /**
     * Checks whether a title metadata string is missing or invalid.
     */
    fun isTitleMissingOrInvalid(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val clean = title.trim().lowercase(Locale.ROOT)
        if (GENERIC_TITLE_NAMES.contains(clean)) return true
        if (clean.matches(Regex("^(track|audio|media|sound)?\\s*\\d+$"))) return true
        if (clean.startsWith("saf_") || clean.startsWith("media_")) return true
        return false
    }

    /**
     * Extracts filename base without extension and leading track numbers.
     */
    fun cleanFilename(filename: String): String {
        val nameOnly = File(filename).name
        return StringNormalizer.stripVersionAndExtension(nameOnly)
            .replace(Regex("^\\[?[0-9]{1,3}\\]?[.\\-\\s_]+"), "")
            .trim()
    }

    /**
     * Analyzes the embedded title and filename to extract artist candidates and generate search queries.
     */
    fun analyze(
        embeddedTitle: String,
        embeddedArtist: String,
        filePathOrName: String,
        album: String? = null,
        durationSeconds: Int = 0
    ): CandidateHypothesis {
        val filenameBase = cleanFilename(filePathOrName)
        val remixer = StringNormalizer.extractRemixer(embeddedTitle)
            ?: StringNormalizer.extractRemixer(filePathOrName)

        val artistIsInvalid = isArtistMissingOrInvalid(embeddedArtist)

        // Case 1: Embedded title contains separator "Artist - Title"
        if (artistIsInvalid && embeddedTitle.contains(" - ")) {
            val parts = embeddedTitle.split(" - ", limit = 2)
            val p0 = parts[0].trim()
            val p1 = parts[1].trim()
            if (p0.isNotBlank() && p1.isNotBlank()) {
                val queries = generateQueries(p1, p0, p0, p1, album)
                return CandidateHypothesis(
                    title = StringNormalizer.stripVersionAndExtension(p1),
                    candidateArtist = p0,
                    alternativeArtist = p1,
                    alternativeTitle = p0,
                    remixer = remixer,
                    isAmbiguous = isOrderAmbiguous(p0, p1),
                    confidence = 70.0,
                    searchQueries = queries
                )
            }
        }

        // Case 2: Filename contains separator "Artist - Title" or "Artist _ Title"
        val delimiter = when {
            filenameBase.contains(" - ") -> " - "
            filenameBase.contains(" – ") -> " – "
            filenameBase.contains(" — ") -> " — "
            filenameBase.contains(" _ ") -> " _ "
            else -> null
        }

        if (delimiter != null) {
            val parts = filenameBase.split(delimiter, limit = 2)
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (left.isNotBlank() && right.isNotBlank()) {
                val candidateArtist = if (artistIsInvalid) left else embeddedArtist.trim()
                val candidateTitle = if (!isTitleMissingOrInvalid(embeddedTitle)) {
                    StringNormalizer.stripVersionAndExtension(embeddedTitle)
                } else {
                    StringNormalizer.stripVersionAndExtension(right)
                }

                val queries = generateQueries(candidateTitle, candidateArtist, right, left, album)
                return CandidateHypothesis(
                    title = candidateTitle,
                    candidateArtist = candidateArtist,
                    alternativeArtist = right,
                    alternativeTitle = left,
                    remixer = remixer,
                    isAmbiguous = isOrderAmbiguous(left, right),
                    confidence = if (artistIsInvalid) 70.0 else 90.0,
                    searchQueries = queries
                )
            }
        }

        // Case 3: Embedded title is "Title by Artist"
        val byMatch = Regex("(?i)^(.+?)\\s+by\\s+(.+)$").find(embeddedTitle)
        if (artistIsInvalid && byMatch != null) {
            val titlePart = byMatch.groupValues[1].trim()
            val artistPart = byMatch.groupValues[2].trim()
            val queries = generateQueries(titlePart, artistPart, artistPart, titlePart, album)
            return CandidateHypothesis(
                title = StringNormalizer.stripVersionAndExtension(titlePart),
                candidateArtist = artistPart,
                remixer = remixer,
                isAmbiguous = false,
                confidence = 75.0,
                searchQueries = queries
            )
        }

        // Case 4: No separator detected; use title or filename as title
        val fallbackTitle = if (!isTitleMissingOrInvalid(embeddedTitle)) {
            StringNormalizer.stripVersionAndExtension(embeddedTitle)
        } else {
            filenameBase
        }

        val knownArtist = if (!artistIsInvalid) embeddedArtist.trim() else null
        val queries = generateQueries(fallbackTitle, knownArtist, null, null, album)

        return CandidateHypothesis(
            title = fallbackTitle,
            candidateArtist = knownArtist,
            remixer = remixer,
            isAmbiguous = knownArtist == null,
            confidence = if (knownArtist != null) 90.0 else 0.0,
            searchQueries = queries
        )
    }

    private fun isOrderAmbiguous(p0: String, p1: String): Boolean {
        // If one looks clearly like a track number or common song word vs artist name
        val p0Lower = p0.lowercase(Locale.ROOT)
        val p1Lower = p1.lowercase(Locale.ROOT)
        if (p0Lower.contains("feat.") || p0Lower.contains("ft.") || p0Lower.contains("&")) return false
        if (p1Lower.contains("feat.") || p1Lower.contains("ft.")) return true
        return true
    }

    private fun generateQueries(
        title: String,
        candidateArtist: String?,
        altArtist: String?,
        altTitle: String?,
        album: String?
    ): List<String> {
        val queries = LinkedHashSet<String>()
        val cleanT = StringNormalizer.stripVersionAndExtension(title).trim()

        if (cleanT.isNotBlank()) {
            // "Track Title" artist
            queries.add("\"$cleanT\" artist")
            // "Track Title" song
            queries.add("\"$cleanT\" song")
            // "Track Title" lyrics artist
            queries.add("\"$cleanT\" lyrics artist")
            // "Track Title" music
            queries.add("\"$cleanT\" music")

            if (!candidateArtist.isNullOrBlank() && !isArtistMissingOrInvalid(candidateArtist)) {
                val cleanA = candidateArtist.trim()
                // "Candidate Artist" "Track Title"
                queries.add("\"$cleanA\" \"$cleanT\"")
            }

            if (!album.isNullOrBlank() && !album.equals("Single", ignoreCase = true)) {
                queries.add("\"$cleanT\" \"${album.trim()}\"")
            }

            // If alternative interpretation exists (ambiguous ordering)
            if (!altArtist.isNullOrBlank() && !altTitle.isNullOrBlank() && altArtist != candidateArtist) {
                queries.add("\"${altArtist.trim()}\" song")
                queries.add("\"${altTitle.trim()}\" song")
                queries.add("\"${altArtist.trim()}\" \"${altTitle.trim()}\"")
            }
        }
        return queries.toList()
    }
}
