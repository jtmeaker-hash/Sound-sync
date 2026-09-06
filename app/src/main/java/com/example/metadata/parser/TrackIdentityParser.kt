package com.example.metadata.parser

import java.util.Locale

data class ParsedTrackIdentity(
    val artist: String?,
    val title: String,
    val rawTitle: String = title,
    val rawArtist: String? = artist,
    val album: String? = null,
    val version: String? = null,
    val isArtistMissing: Boolean = artist.isNullOrBlank(),
    val collaborations: List<String> = emptyList(),
    val searchTerms: List<String> = emptyList(),
    val sourceOfTruth: String = "TAGS"
)

/**
 * Robust track identity and tag parser (Sections 1 & 2).
 *
 * Treats existing music titles and filenames as the strongest source of truth.
 * Extracts probable artist and title from "Artist - Track" patterns in existing titles
 * or filenames when artist tag is missing, while strictly preserving meaningful title
 * information (Remix names, Extended Mix, Radio Edit, VIP, Dub, Instrumental, Live, Edit, Bootleg).
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

    // Garbage patterns to strip from filenames / titles (video downloaders, bitrate markers)
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
        cleanFilename = cleanFilename.replace('–', '-').replace('—', '-')

        var sourceOfTruth = "TAGS"
        var parsedArtist: String? = null
        var parsedTitle: String? = null

        val validExistingArtist = existingArtist?.trim()?.takeIf { isArtistValid(it) }
        val rawExistingTitle = existingTitle?.trim()?.takeIf { it.isNotBlank() }
        val normalizedTitle = rawExistingTitle?.replace('–', '-')?.replace('—', '-')

        // 1. If existing artist is missing or placeholder, extract from title or filename (Section 2)
        if (validExistingArtist == null) {
            // Check existing title for "Artist - Track"
            if (normalizedTitle != null && normalizedTitle.contains(" - ")) {
                val parts = normalizedTitle.split(" - ", limit = 2)
                val left = parts[0].trim()
                val right = parts[1].trim()
                if (isArtistValid(left) && left.length >= 2 && isTitleValid(right)) {
                    parsedArtist = left
                    parsedTitle = right
                    sourceOfTruth = "TITLE_PARSE"
                }
            } else if (normalizedTitle != null && normalizedTitle.contains(" by ", ignoreCase = true)) {
                val parts = normalizedTitle.split(Regex("(?i)\\s+by\\s+"), limit = 2)
                if (parts.size == 2 && isTitleValid(parts[0].trim()) && isArtistValid(parts[1].trim())) {
                    parsedTitle = parts[0].trim()
                    parsedArtist = parts[1].trim()
                    sourceOfTruth = "TITLE_PARSE"
                }
            }

            // If not found in title, check filename for "Artist - Track"
            if (parsedArtist == null) {
                if (cleanFilename.contains(" - ")) {
                    val parts = cleanFilename.split(" - ", limit = 2)
                    val left = parts[0].trim()
                    val right = parts[1].trim()
                    if (isArtistValid(left) && left.length >= 2 && isTitleValid(right)) {
                        parsedArtist = left
                        parsedTitle = if (rawExistingTitle != null && isTitleValid(rawExistingTitle) && !rawExistingTitle.contains(" - ")) {
                            rawExistingTitle
                        } else {
                            right
                        }
                        sourceOfTruth = "FILENAME_PARSE"
                    }
                } else if (cleanFilename.contains(" by ", ignoreCase = true)) {
                    val parts = cleanFilename.split(Regex("(?i)\\s+by\\s+"), limit = 2)
                    if (parts.size == 2 && isTitleValid(parts[0].trim()) && isArtistValid(parts[1].trim())) {
                        parsedArtist = parts[1].trim()
                        parsedTitle = if (rawExistingTitle != null && isTitleValid(rawExistingTitle) && !rawExistingTitle.contains(Regex("(?i)\\s+by\\s+"))) {
                            rawExistingTitle
                        } else {
                            parts[0].trim()
                        }
                        sourceOfTruth = "FILENAME_PARSE"
                    }
                }
            }

            // Fallback: check parent directory if valid artist name
            if (parsedArtist == null && filename.isNotBlank()) {
                val parentDir = filename.substringBeforeLast('/', "").substringAfterLast('/').trim()
                if (isArtistValid(parentDir) && !isGenericDirectoryName(parentDir)) {
                    parsedArtist = parentDir
                    sourceOfTruth = "FOLDER_PARSE"
                }
            }
        } else {
            // Existing artist is already valid!
            parsedArtist = validExistingArtist
            sourceOfTruth = "TAGS"

            // Check if existing title redundantly starts with "Artist - "
            if (normalizedTitle != null) {
                val prefix = "${validExistingArtist.lowercase(Locale.ROOT)} - "
                val lowerTitle = normalizedTitle.lowercase(Locale.ROOT)
                if (lowerTitle.startsWith(prefix)) {
                    val stripped = normalizedTitle.substring(prefix.length).trim()
                    if (isTitleValid(stripped)) {
                        parsedTitle = stripped
                    }
                }
            }
        }

        val finalArtist = parsedArtist ?: validExistingArtist
        val finalRawTitle = rawExistingTitle ?: cleanFilename.ifBlank { "Unknown Title" }
        val finalTitle = parsedTitle ?: (rawExistingTitle?.takeIf { isTitleValid(it) } ?: cleanFilename.ifBlank { "Unknown Title" })
        val cleanTitle = cleanGarbage(finalTitle)

        val isArtistMissing = finalArtist.isNullOrBlank() || !isArtistValid(finalArtist)

        // Extract version signature strictly preserving mixes (Remix, Extended Mix, Radio Edit, VIP, etc.)
        val version = extractVersion(cleanTitle) ?: extractVersion(finalRawTitle) ?: extractVersion(cleanFilename)

        val collaborations = if (!finalArtist.isNullOrBlank()) {
            extractCollaborations(finalArtist)
        } else {
            emptyList()
        }

        // Generate search terms
        val searchTerms = mutableListOf<String>()
        if (!isArtistMissing && finalArtist != null) {
            searchTerms.add("$finalArtist $cleanTitle")
            if (version != null && !cleanTitle.contains(version, ignoreCase = true)) {
                searchTerms.add("$finalArtist $cleanTitle ($version)")
            }
            if (collaborations.size > 1) {
                searchTerms.add("${collaborations[0]} $cleanTitle")
            }
        } else {
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
            rawTitle = finalRawTitle,
            rawArtist = existingArtist,
            album = album?.trim()?.takeIf { it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true) },
            version = version,
            isArtistMissing = isArtistMissing,
            collaborations = collaborations,
            searchTerms = searchTerms.distinct(),
            sourceOfTruth = sourceOfTruth
        )
    }

    private fun isGenericDirectoryName(dir: String): Boolean {
        val lower = dir.lowercase(Locale.ROOT)
        if (lower.contains("test") || lower.contains("cache") || lower.contains("temp") ||
            lower.contains("tmp") || lower.contains("build") || lower.contains("robolectric") ||
            lower.contains('_') || lower.matches(Regex(".*\\d{2,}.*"))
        ) {
            return true
        }
        return lower in listOf(
            "music", "download", "downloads", "audio", "sound", "sounds", "tracks",
            "songs", "internal storage", "storage", "sdcard", "0", "emulated", "files",
            "media", "album", "albums", "various", "unknown", "soundsync"
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
        // Custom remixer pattern: "[Name] Remix" or "(Name Remix)"
        val remixMatch = Regex("(?i)[\\[(]([^\\])]+?\\s+(remix|mix|dub|vip|bootleg|edit))[\\])]").find(text)
        if (remixMatch != null) {
            return remixMatch.groupValues[1].trim()
        }
        for (version in VERSION_TAGS) {
            val pattern = Regex("(?i)(^|[\\[( /_-])$version([\\]) /_-]|$)")
            if (pattern.containsMatchIn(text)) {
                return version
            }
        }
        return null
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
