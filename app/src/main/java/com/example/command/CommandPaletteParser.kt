package com.example.command

import com.example.metadata.CamelotKey
import java.util.Locale

/**
 * Robust query parser for the SoundSync Command & Search Palette.
 * Converts raw query strings into structured filters, plain text search terms,
 * and command intent tokens.
 */
object CommandPaletteParser {

    private val KEYWORD_PREFIXES = setOf("artist", "bpm", "key", "folder", "missing")

    /**
     * Parses an arbitrary user query string into a structured [ParsedPaletteQuery].
     */
    fun parse(rawInput: String): ParsedPaletteQuery {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return ParsedPaletteQuery(
                rawQuery = "",
                textTerms = emptyList(),
                filters = emptyList()
            )
        }

        val filters = mutableListOf<PaletteFilter>()
        var working = trimmed

        // 1. Check for standalone phrase filters: recently added, recently played, unplayed
        val recentlyAddedRegex = Regex("(?i)\\b(recently\\s*[-_]?\\s*added)\\b")
        if (recentlyAddedRegex.containsMatchIn(working)) {
            filters.add(PaletteFilter.RecentlyAdded)
            working = working.replace(recentlyAddedRegex, " ")
        }

        val recentlyPlayedRegex = Regex("(?i)\\b(recently\\s*[-_]?\\s*played)\\b")
        if (recentlyPlayedRegex.containsMatchIn(working)) {
            filters.add(PaletteFilter.RecentlyPlayed)
            working = working.replace(recentlyPlayedRegex, " ")
        }

        val unplayedRegex = Regex("(?i)\\b(unplayed)\\b")
        if (unplayedRegex.containsMatchIn(working)) {
            filters.add(PaletteFilter.Unplayed)
            working = working.replace(unplayedRegex, " ")
        }

        // 2. Missing filters: missing artwork | missing bpm | missing key | missing metadata
        val missingRegex = Regex("(?i)\\bmissing(?::|\\s+)(artwork|bpm|key|metadata)\\b")
        missingRegex.findAll(working).forEach { match ->
            val typeStr = match.groupValues[1].lowercase(Locale.ROOT)
            val type = when (typeStr) {
                "artwork" -> MissingFieldType.ARTWORK
                "bpm" -> MissingFieldType.BPM
                "key" -> MissingFieldType.KEY
                "metadata" -> MissingFieldType.METADATA
                else -> null
            }
            if (type != null) {
                filters.add(PaletteFilter.Missing(type))
            }
        }
        working = working.replace(missingRegex, " ")

        // 3. Colon-style structured filters: key:value or key:"quoted value"
        // e.g. artist:"Fred again..", bpm:128, bpm:120-130, key:8A, folder:"Downloads"
        val colonRegex = Regex("(?i)\\b(artist|bpm|key|folder):(?:\"([^\"]+)\"|(\\S+))")
        colonRegex.findAll(working).forEach { match ->
            val field = match.groupValues[1].lowercase(Locale.ROOT)
            val value = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
            val filter = buildFilter(field, value)
            if (filter != null) {
                filters.add(filter)
            }
        }
        working = working.replace(colonRegex, " ")

        // 4. Tokenize remaining words while preserving quoted strings
        val tokens = extractTokens(working)
        val textTerms = mutableListOf<String>()
        var i = 0

        while (i < tokens.size) {
            val token = tokens[i]
            val lowerToken = token.lowercase(Locale.ROOT)

            if (KEYWORD_PREFIXES.contains(lowerToken) && i + 1 < tokens.size) {
                // Collect values for this keyword until the next keyword or end
                val valueTokens = mutableListOf<String>()
                i++ // advance past keyword

                // For bpm or key, typically takes 1 token (e.g. 128 or 124-130 or 8A)
                if (lowerToken == "bpm" || lowerToken == "key") {
                    val firstVal = tokens[i]
                    // Handle case like bpm 124 - 130 (space separated range)
                    if (lowerToken == "bpm" && i + 2 < tokens.size && tokens[i + 1] == "-") {
                        valueTokens.add("$firstVal-${tokens[i + 2]}")
                        i += 3
                    } else {
                        valueTokens.add(firstVal)
                        i++
                    }
                } else {
                    // For artist or folder: collect tokens until next keyword
                    while (i < tokens.size && !KEYWORD_PREFIXES.contains(tokens[i].lowercase(Locale.ROOT))) {
                        valueTokens.add(tokens[i])
                        i++
                    }
                }

                val value = valueTokens.joinToString(" ").trim('"', '\'')
                val filter = buildFilter(lowerToken, value)
                if (filter != null) {
                    filters.add(filter)
                } else {
                    textTerms.addAll(valueTokens)
                }
            } else {
                textTerms.add(token.trim('"', '\''))
                i++
            }
        }

        val cleanedTextTerms = textTerms
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return ParsedPaletteQuery(
            rawQuery = trimmed,
            textTerms = cleanedTextTerms,
            filters = filters,
            explicitCommandQuery = if (filters.isEmpty()) trimmed else null
        )
    }

    private fun buildFilter(field: String, value: String): PaletteFilter? {
        val cleanVal = value.trim('"', '\'').trim()
        if (cleanVal.isEmpty()) return null

        return when (field.lowercase(Locale.ROOT)) {
            "artist" -> PaletteFilter.Artist(cleanVal)
            "bpm" -> {
                // Check if range: e.g. 124-130
                if (cleanVal.contains("-")) {
                    val parts = cleanVal.split("-").map { it.trim() }
                    val min = parts.getOrNull(0)?.toDoubleOrNull()
                    val max = parts.getOrNull(1)?.toDoubleOrNull()
                    if (min != null && max != null) {
                        PaletteFilter.BpmRange(minBpm = min.coerceAtMost(max), maxBpm = min.coerceAtLeast(max))
                    } else min?.let { PaletteFilter.BpmExact(it) }
                } else {
                    cleanVal.toDoubleOrNull()?.let { PaletteFilter.BpmExact(it) }
                }
            }
            "key" -> {
                val upperVal = cleanVal.uppercase(Locale.ROOT)
                // If it looks like Camelot e.g. 8A, 11B
                val camelotMatch = Regex("^(1[0-2]|[1-9])[AB]$").matches(upperVal)
                if (camelotMatch) {
                    PaletteFilter.Key(rawKey = cleanVal, camelotKey = upperVal, musicalKey = null)
                } else {
                    // Try parsing as musical key e.g. Am, C#m, F#min, etc.
                    val camelotDerived = CamelotKey.fromMusicalKey(cleanVal)
                    PaletteFilter.Key(rawKey = cleanVal, camelotKey = camelotDerived, musicalKey = cleanVal)
                }
            }
            "folder" -> PaletteFilter.Folder(cleanVal)
            else -> null
        }
    }

    /**
     * Splits string into tokens respecting double and single quotes.
     */
    private fun extractTokens(text: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
        regex.findAll(text).forEach { match ->
            val token = match.groupValues[1].ifEmpty {
                match.groupValues[2].ifEmpty {
                    match.groupValues[3]
                }
            }
            if (token.isNotEmpty()) {
                result.add(token)
            }
        }
        return result
    }
}
