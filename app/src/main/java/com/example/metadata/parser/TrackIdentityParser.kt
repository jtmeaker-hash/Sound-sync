package com.example.metadata.parser

import java.util.Locale

data class ParsedTrackIdentity(
    val artist: String?,
    val title: String,
    val album: String?,
    val version: String?,
    val isArtistMissing: Boolean,
    val collaborations: List<String>,
    val searchTerms: List<String>
)

/**
 * Robust track identity and tag parser.
 *
 * Extracts clean artist, title, and version signatures from filenames and broken ID3/embedded tags,
 * stripping website prefixes, download hashes, and bitrate tags while strictly preserving
 * version descriptors (Original Mix, Extended Mix, Radio Edit, Club Mix, Remix, VIP, etc.).
 */
object TrackIdentityParser {

    private val AUDIO_EXTENSIONS = listOf(
        ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".aif", ".aiff", ".wma"
    )

    // Meaningful version patterns to preserve
    val VERSION_TAGS = listOf(
        "Extended Mix", "Original Mix", "Radio Edit", "Club Mix",
        "Remix", "VIP", "Bootleg", "Edit", "Dub", "Instrumental",
        "Acoustic", "Live", "Remaster", "Sped Up", "Slowed", "Clean", "Explicit"
    )

    // Garbage patterns to strip from filenames / titles
    private val GARBAGE_REGEXES = listOf(
        Regex("(?i)\\[(y2mate\\.com|yt1s\\.com|ssyoutube\\.com|flvto|snaptube|tubemate|mp3skull)[^\\]]*\\]"),
        Regex("(?i)\\((official (music )?video|official audio|lyric video|audio|video|visualizer)\\)"),
        Regex("(?i)\\[(official (music )?video|official audio|lyric video|audio|video|visualizer)\\]"),
        Regex("(?i)\\[(320kbps|320k|256k|192k|128k|flac 24bit|flac|hq|hd|cd rip)\\]"),
        Regex("(?i)\\((320kbps|320k|256k|192k|128k|flac 24bit|flac|hq|hd|cd rip)\\)"),
        Regex("(?i)^(\\w+\\.(com|net|org|io|ru|cc|me)\\s*[-_–—:]+\\s*)"),
        Regex("(?i)\\[(www\\.[^\\]]+)\\]")
    )

    // Leading track number patterns (e.g. "01 - ", "01. ", "[01] ")
    private val LEADING_TRACK_NUMBER_REGEX = Regex("^(\\[?\\d{1,3}\\]?[.\\-\\s]+|\\(\\d{1,3}\\)[.\\-\\s]+)")

    fun parse(
        existingTitle: String?,
        existingArtist: String?,
        album: String? = null,
        filename: String = "",
        durationSeconds: Int = 0
    ): ParsedTrackIdentity {
        var rawName = filename.substringAfterLast('/').substringAfterLast('\\')
        for (ext in AUDIO_EXTENSIONS) {
            if (rawName.endsWith(ext, ignoreCase = true)) {
                rawName = rawName.substring(0, rawName.length - ext.length)
                break
            }
        }

        // Clean website and encoding garbage from filename
        var cleanFilename = cleanGarbage(rawName).trim()
        cleanFilename = LEADING_TRACK_NUMBER_REGEX.replace(cleanFilename, "").trim()

        // Normalise dashes
        cleanFilename = cleanFilename.replace('–', '-').replace('—', '-')

        var extractedArtist: String? = null
        var extractedTitle: String? = null

        // 1. Inspect filename structure
        if (cleanFilename.contains(" - ")) {
            val parts = cleanFilename.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                extractedArtist = parts[0].trim()
                extractedTitle = parts[1].trim()
            }
        } else if (cleanFilename.contains(" by ", ignoreCase = true)) {
            val parts = cleanFilename.split(Regex("(?i)\\s+by\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                extractedTitle = parts[0].trim()
                extractedArtist = parts[1].trim()
            }
        }

        // 2. Validate against existing tags
        val validExistingArtist = existingArtist?.trim()?.takeIf { isArtistValid(it) }
        val validExistingTitle = existingTitle?.trim()?.takeIf { isTitleValid(it) }

        val finalArtist = validExistingArtist ?: extractedArtist
        val isArtistMissing = finalArtist.isNullOrBlank() || !isArtistValid(finalArtist)

        val rawTitle = validExistingTitle ?: extractedTitle ?: cleanFilename.ifBlank { "Unknown Title" }
        val cleanTitle = cleanGarbage(rawTitle)

        // 3. Extract version signature
        val version = extractVersion(cleanTitle) ?: extractVersion(cleanFilename)

        // 4. Extract collaboration tokens
        val collaborations = if (!finalArtist.isNullOrBlank()) {
            extractCollaborations(finalArtist)
        } else {
            emptyList()
        }

        // 5. Generate search query candidates
        val searchTerms = mutableListOf<String>()

        if (!isArtistMissing && finalArtist != null) {
            // "Artist Title"
            searchTerms.add("$finalArtist $cleanTitle")
            // "Artist Title (Version)"
            if (version != null && !cleanTitle.contains(version, ignoreCase = true)) {
                searchTerms.add("$finalArtist $cleanTitle ($version)")
            }
        } else {
            // Missing artist: search by full title, and by stripped title
            searchTerms.add(cleanTitle)
            if (version != null) {
                val titleWithoutVersion = cleanTitle.replace(Regex("(?i)[\\[(].*?$version.*?[\\])]"), "").trim()
                if (titleWithoutVersion.isNotBlank() && titleWithoutVersion != cleanTitle) {
                    searchTerms.add(titleWithoutVersion)
                }
            }
        }

        return ParsedTrackIdentity(
            artist = finalArtist?.takeIf { isArtistValid(it) },
            title = cleanTitle,
            album = album?.trim()?.takeIf { it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true) },
            version = version,
            isArtistMissing = isArtistMissing,
            collaborations = collaborations,
            searchTerms = searchTerms.distinct()
        )
    }

    fun isArtistValid(artist: String): Boolean {
        val trimmed = artist.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower != "unknown" &&
            lower != "unknown artist" &&
            lower != "<unknown>" &&
            lower != "various artists" &&
            lower != "n/a" &&
            lower != "none" &&
            lower != "null"
    }

    fun isTitleValid(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower != "unknown" &&
            lower != "unknown title" &&
            lower != "<unknown>" &&
            lower != "track" &&
            lower != "audio"
    }

    fun extractVersion(text: String): String? {
        for (version in VERSION_TAGS) {
            val pattern = Regex("(?i)(^|[\\[( /_-])$version([\\]) /_-]|$)")
            if (pattern.containsMatchIn(text)) {
                return version
            }
        }
        // Custom remixer pattern: "[Name] Remix" or "(Name Remix)"
        val remixMatch = Regex("(?i)[\\[(]([^\\])]+?\\s+(remix|mix|dub|vip|bootleg|edit))[\\])]").find(text)
        return remixMatch?.groupValues?.getOrNull(1)?.trim()
    }

    fun cleanGarbage(text: String): String {
        var cleaned = text
        for (regex in GARBAGE_REGEXES) {
            cleaned = regex.replace(cleaned, " ")
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    fun extractCollaborations(artist: String): List<String> {
        val std = artist
            .replace(Regex("(?i)\\s+and\\s+"), " & ")
            .replace(Regex("(?i)\\s*\\+\\s*"), " & ")
            .replace(Regex("(?i)\\s+x\\s+"), " & ")
            .replace(Regex("(?i)\\s+vs\\.?\\s+"), " & ")
            .replace(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+"), " & ")
        return std.split('&', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
