package com.example.metadata.parser

import java.util.Locale

data class ParsedTrackIdentity(
    val artist: String?,
    val title: String,
    val rawTitle: String = title,
    val rawArtist: String? = artist,
    val cleanSearchTitle: String = title,
    val album: String? = null,
    val version: String? = null,
    val isArtistMissing: Boolean = artist.isNullOrBlank(),
    val isRecordingPlaceholder: Boolean = false,
    val collaborations: List<String> = emptyList(),
    val searchTerms: List<String> = emptyList(),
    val sourceOfTruth: String = "TAGS",
    val confidence: Float = 1.0f
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

    // Leading track number patterns (e.g. "01 - ", "01. ", "[01] ", "01 ")
    private val LEADING_TRACK_NUMBER_REGEX = Regex("^(\\[?\\d{1,3}\\]?[.\\-\\s]+|\\(\\d{1,3}\\)[.\\-\\s]+)")

    // Timestamp suffixes: e.g. _202605281217014, -202601072125024, 202603161348019, _20260528_121701
    private val TIMESTAMP_SUFFIX_REGEX = Regex("[_\\-\\s]+\\d{8,17}$")
    private val DATE_TIME_SUFFIX_REGEX = Regex("[_\\-\\s]+\\d{6,8}_\\d{4,6}$")

    // Standalone noise suffixes (e.g. " Tiktok", " clip", " Download")
    private val NOISE_SUFFIX_REGEX = Regex("(?i)[_\\-\\s]+(tiktok|tik\\s+tok|clip|audio|download|downloaded|official audio|official video|official music video|lyric video|lyrics|hq|hd|4k|copy|edited|trim|trimmed)\\s*$")

    // Enclosed noise tokens (e.g. [official video], (lyric video), [320kbps], (clip))
    private val ENCLOSED_NOISE_REGEX = Regex("(?i)[\\[(](official (music )?video|official audio|lyric video|audio|video|visualizer|tiktok|tik\\s+tok|clip|download|downloaded|hq|hd|4k|320kbps|320k|256k|192k|128k|flac 24bit|flac|cd rip|copy|edited|trim|trimmed)[\\])]")

    // Video downloader / website noise
    private val DOWNLOADER_REGEX = Regex("(?i)[\\[(](y2mate\\.com|yt1s\\.com|ssyoutube\\.com|flvto|snaptube|tubemate|mp3skull)[^\\])]*[\\])]")
    private val WEBSITE_PREFIX_REGEX = Regex("(?i)^(\\w+\\.(com|net|org|io|ru|cc|me)\\s*[-_–—:]+\\s*)")
    private val WWW_ENCLOSED_REGEX = Regex("(?i)\\[(www\\.[^\\]]+)\\]")

    // Recording placeholder detector (e.g. REC001, REC_20260101, Voice 001, Recording (12), Audio 01, Track 01)
    private val RECORDING_REGEX = Regex("(?i)^(REC|Voice|Recording)([_\\-\\s(]*\\d+.*|\\s*)$|^(Audio|Track)[_\\-\\s(]+\\d+.*$")

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
        var parsingConfidence = 1.0f

        val validExistingArtist = existingArtist?.trim()?.takeIf { isArtistValid(it) }
        val rawExistingTitle = existingTitle?.trim()?.takeIf { it.isNotBlank() }
        var normalizedTitle = rawExistingTitle?.replace('–', '-')?.replace('—', '-')
        if (normalizedTitle != null) {
            normalizedTitle = LEADING_TRACK_NUMBER_REGEX.replace(normalizedTitle, "").trim()
        }

        val isRecording = (validExistingArtist == null) && (
                isRecordingPlaceholder(rawName) ||
                isRecordingPlaceholder(existingTitle) ||
                isRecordingPlaceholder(cleanFilename)
        )

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
                    parsingConfidence = 0.95f
                }
            } else if (normalizedTitle != null && normalizedTitle.contains(" by ", ignoreCase = true)) {
                val parts = normalizedTitle.split(Regex("(?i)\\s+by\\s+"), limit = 2)
                if (parts.size == 2 && isTitleValid(parts[0].trim()) && isArtistValid(parts[1].trim())) {
                    parsedTitle = parts[0].trim()
                    parsedArtist = parts[1].trim()
                    sourceOfTruth = "TITLE_PARSE"
                    parsingConfidence = 0.95f
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
                        parsingConfidence = 0.90f
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
                        parsingConfidence = 0.90f
                    }
                } else if (cleanFilename.contains('_') && !cleanFilename.contains(" - ")) {
                    // Check for "Artist_Title" format (e.g. Coone_Savages)
                    val parts = cleanFilename.split('_')
                    if (parts.size == 2) {
                        val left = parts[0].trim()
                        val right = parts[1].trim()
                        if (isArtistValid(left) && left.length >= 2 && isTitleValid(right) &&
                            !left.matches(Regex("\\d+")) && !right.matches(Regex("\\d+"))
                        ) {
                            parsedArtist = left
                            parsedTitle = right
                            sourceOfTruth = "FILENAME_PARSE"
                            parsingConfidence = 0.85f
                        }
                    }
                }
            }

            // Fallback: check parent directory if valid artist name
            if (parsedArtist == null && filename.isNotBlank()) {
                val parentDir = filename.substringBeforeLast('/', "").substringAfterLast('/').trim()
                if (isArtistValid(parentDir) && !isGenericDirectoryName(parentDir)) {
                    parsedArtist = parentDir
                    sourceOfTruth = "FOLDER_PARSE"
                    parsingConfidence = 0.70f
                }
            }
        } else {
            // Existing artist is already valid!
            parsedArtist = validExistingArtist
            sourceOfTruth = "TAGS"
            parsingConfidence = 1.0f

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
        val candidateTitle = parsedTitle ?: (rawExistingTitle?.takeIf { isTitleValid(it) } ?: cleanFilename.ifBlank { "Unknown Title" })
        val finalTitle = cleanGarbage(candidateTitle).ifBlank { candidateTitle }
        val finalRawTitle = rawExistingTitle ?: cleanFilename.ifBlank { "Unknown Title" }

        val isArtistMissing = finalArtist.isNullOrBlank() || !isArtistValid(finalArtist)

        // Extract version signature strictly preserving mixes (Remix, Extended Mix, Radio Edit, VIP, etc.)
        val version = extractVersion(finalTitle) ?: extractVersion(finalRawTitle) ?: extractVersion(cleanFilename)

        // Generate cleanSearchTitle: stripped of trailing version tags for optimal search indexing
        val cleanSearchTitle = if (version != null) {
            val stripped = finalTitle.replace(Regex("(?i)[\\[(].*?$version.*?[\\])]"), "")
                .replace(Regex("(?i)[_\\-\\s]+$version\\s*$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (stripped.isNotBlank() && isTitleValid(stripped)) stripped else finalTitle
        } else {
            finalTitle
        }

        val collaborations = if (!finalArtist.isNullOrBlank()) {
            extractCollaborations(finalArtist)
        } else {
            emptyList()
        }

        // Generate candidate search terms (Section 5)
        val searchTerms = mutableListOf<String>()
        if (!isArtistMissing && finalArtist != null) {
            searchTerms.add("$finalArtist $cleanSearchTitle")
            if (finalTitle != cleanSearchTitle) {
                searchTerms.add("$finalArtist $finalTitle")
            }
            if (version != null && !cleanSearchTitle.contains(version, ignoreCase = true)) {
                searchTerms.add("$finalArtist $cleanSearchTitle ($version)")
            }
            if (collaborations.size > 1) {
                searchTerms.add("${collaborations[0]} $cleanSearchTitle")
            }
            searchTerms.add(cleanSearchTitle)
        } else {
            searchTerms.add(cleanSearchTitle)
            if (finalTitle != cleanSearchTitle) {
                searchTerms.add(finalTitle)
            }
            if (cleanFilename != cleanSearchTitle && cleanFilename.isNotBlank()) {
                searchTerms.add(cleanFilename)
            }
        }

        // Album filtering: reject generic album folder names like "Download", "Recordings"
        val cleanAlbum = album?.trim()?.takeIf { !isGenericAlbumName(it) }

        return ParsedTrackIdentity(
            artist = finalArtist?.takeIf { isArtistValid(it) },
            title = finalTitle,
            rawTitle = finalRawTitle,
            rawArtist = existingArtist,
            cleanSearchTitle = cleanSearchTitle,
            album = cleanAlbum,
            version = version,
            isArtistMissing = isArtistMissing,
            isRecordingPlaceholder = isRecording,
            collaborations = collaborations,
            searchTerms = searchTerms.distinct().filter { it.isNotBlank() },
            sourceOfTruth = sourceOfTruth,
            confidence = parsingConfidence
        )
    }

    fun isGenericDirectoryName(dir: String): Boolean {
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

    /**
     * Identifies folder names, generic markers, or filesystem paths mistakenly treated as album names.
     */
    fun isGenericAlbumName(album: String?): Boolean {
        if (album.isNullOrBlank()) return true
        val lower = album.trim().lowercase(Locale.ROOT)
        if (lower.contains('/') || lower.contains('\\')) return true
        return lower in setOf(
            "download", "downloads", "recording", "recordings", "music", "audio",
            "sound", "sounds", "tracks", "songs", "single", "singles", "trim",
            "trimmed", "mstudio", "internal storage", "storage", "sdcard", "0",
            "emulated", "files", "media", "album", "albums", "various",
            "various artists", "unknown", "unknown album", "<unknown>", "soundsync"
        )
    }

    fun isRecordingPlaceholder(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        return RECORDING_REGEX.matches(trimmed)
    }

    fun isArtistValid(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return false
        val trimmed = artist.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower != "unknown" &&
            lower != "unknown artist" &&
            lower != "<unknown>" &&
            lower != "various artists" &&
            lower != "n/a" &&
            lower != "none" &&
            lower != "null" &&
            !trimmed.matches(Regex("^\\d{1,4}$")) &&
            !RECORDING_REGEX.matches(trimmed)
    }

    fun isTitleValid(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val trimmed = title.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower != "unknown" &&
            lower != "unknown title" &&
            lower != "<unknown>" &&
            lower != "track" &&
            lower != "audio" &&
            lower != "recording" &&
            lower != "untitled" &&
            !lower.matches(Regex("^(track|audio|recording)\\s*\\d+$"))
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
        // 1. Strip downloaders, website prefixes, www markers
        cleaned = DOWNLOADER_REGEX.replace(cleaned, " ")
        cleaned = WEBSITE_PREFIX_REGEX.replace(cleaned, " ")
        cleaned = WWW_ENCLOSED_REGEX.replace(cleaned, " ")

        // 2. Strip enclosed noise tokens
        cleaned = ENCLOSED_NOISE_REGEX.replace(cleaned, " ")

        // 3. Strip date/time suffixes
        val noDate = DATE_TIME_SUFFIX_REGEX.replace(cleaned.trim(), "")
        if (noDate.isNotBlank()) cleaned = noDate

        // 4. Strip timestamp suffixes (e.g. _202605281217014)
        val noTimestamp = TIMESTAMP_SUFFIX_REGEX.replace(cleaned.trim(), "")
        if (noTimestamp.isNotBlank()) cleaned = noTimestamp

        // 5. Strip trailing noise tokens (e.g. " Tiktok", " clip")
        val noNoise = NOISE_SUFFIX_REGEX.replace(cleaned.trim(), "")
        if (noNoise.isNotBlank()) cleaned = noNoise

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
