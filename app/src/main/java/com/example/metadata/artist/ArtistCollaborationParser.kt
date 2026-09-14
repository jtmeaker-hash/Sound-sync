package com.example.metadata.artist

import java.util.Locale

/**
 * Intelligent parser that splits collaboration strings into individual artist entities
 * while safely preserving genuine band and group names containing symbols (&, commas, x, etc.).
 */
object ArtistCollaborationParser {

    /**
     * Well-known genuine band / group names that should not be split on '&' or commas.
     */
    private val PROTECTED_ARTIST_NAMES = setOf(
        "above & beyond",
        "earth, wind & fire",
        "simon & garfunkel",
        "crosby, stills, nash & young",
        "crosby, stills & nash",
        "emerson, lake & palmer",
        "hall & oates",
        "daryl hall & john oates",
        "brooks & dunn",
        "huey lewis & the news",
        "tom petty & the heartbreakers",
        "bob marley & the wailers",
        "kc & the sunshine band",
        "kool & the gang",
        "sly & the family stone",
        "joan jett & the blackhearts",
        "florence + the machine",
        "marina and the diamonds",
        "the xx",
        "jamie xx",
        "sunn o)))",
        "ac/dc",
        "blood, sweat & tears",
        "katrina and the waves",
        "toots & the maytals",
        "me first and the gimme gimmes",
        "echo & the bunnymen",
        "captain & tennille",
        "peaches & herb",
        "sam & dave",
        "chas & dave",
        "twenty one pilots",
        "of monsters and men",
        "iron & wine",
        "bloodhound gang",
        "m&o",
        "cast, crew"
    )

    // Collaboration separator regex:
    // 1. Featuring phrases: "feat.", "feat", "ft.", "ft", "featuring"
    private val FEAT_REGEX = Regex("(?i)\\s+(?:feat\\.|feat|ft\\.|ft|featuring)\\s+")

    // 2. Delimiters: semicolons, commas, " & ", " x ", " X ", " vs ", " vs. "
    private val DELIMITER_REGEX = Regex("(?i)\\s*(?:;|\\s&\\s|\\s[xX]\\s|\\svs\\.?\\s|,\\s*)\\s*")

    /**
     * Splits a raw artist metadata string into individual artist names.
     *
     * Example:
     * - "240 KM/H & Adrian Mills" -> ["240 KM/H", "Adrian Mills"]
     * - "Artist A feat. Artist B" -> ["Artist A", "Artist B"]
     * - "Artist A, Artist B & Artist C" -> ["Artist A", "Artist B", "Artist C"]
     * - "Above & Beyond" -> ["Above & Beyond"]
     */
    fun parseCollaborators(rawArtist: String?): List<String> = splitArtists(rawArtist)

    fun splitArtists(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank()) {
            return listOf("Unknown Artist")
        }
        val trimmed = rawArtist.trim()
        if (trimmed == "<unknown>" || trimmed.equals("unknown artist", ignoreCase = true) || trimmed.equals("various artists", ignoreCase = true)) {
            return listOf(trimmed.ifBlank { "Unknown Artist" })
        }

        // Check if the entire string is a known protected artist
        if (isProtectedArtist(trimmed)) {
            return listOf(trimmed)
        }

        // Split on featuring phrases first
        val featParts = trimmed.split(FEAT_REGEX)
        val extractedTokens = mutableListOf<String>()

        for (featChunk in featParts) {
            val chunkTrimmed = featChunk.trim()
            if (chunkTrimmed.isBlank()) continue

            if (isProtectedArtist(chunkTrimmed)) {
                extractedTokens.add(chunkTrimmed)
                continue
            }

            // Split on delimiters (;, &, x, vs, comma)
            val subTokens = chunkTrimmed.split(DELIMITER_REGEX)
            for (sub in subTokens) {
                val cleaned = sub.trim().trim('\"', '\'', '(', ')', '[', ']')
                if (cleaned.isNotBlank()) {
                    extractedTokens.add(cleaned)
                }
            }
        }

        // Case-insensitive deduplication while preserving display capitalization
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (token in extractedTokens) {
            val lower = token.lowercase(Locale.ROOT)
            if (seen.add(lower)) {
                result.add(token)
            }
        }

        return if (result.isEmpty()) listOf(trimmed) else result
    }

    fun isProtectedArtist(name: String): Boolean {
        return PROTECTED_ARTIST_NAMES.contains(name.trim().lowercase(Locale.ROOT))
    }
}
