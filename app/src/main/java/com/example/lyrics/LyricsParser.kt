package com.example.lyrics

import java.util.regex.Pattern

data class ParsedLrcResult(
    val lines: List<LyricLine>,
    val plainText: String,
    val isSynced: Boolean,
    val offsetMs: Long = 0L,
    val metadataTags: Map<String, String> = emptyMap()
)

object LyricsParser {

    private val TIME_TAG_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{2,3}))?]")
    private val META_TAG_PATTERN = Pattern.compile("\\[([a-zA-Z]+):([^\\]]*)]")

    /**
     * Parses .LRC or plain-text lyric content into synchronized LyricLine entries.
     */
    fun parse(content: String): ParsedLrcResult {
        if (content.isBlank()) {
            return ParsedLrcResult(emptyList(), "", false)
        }

        val lines = content.lines()
        val syncedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<String>()
        val metadataTags = mutableMapOf<String, String>()
        var globalOffsetMs = 0L

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 1. Check metadata tags: [ti:], [ar:], [al:], [offset:]
            val metaMatcher = META_TAG_PATTERN.matcher(line)
            if (metaMatcher.matches()) {
                val key = metaMatcher.group(1).lowercase()
                val value = metaMatcher.group(2).trim()
                metadataTags[key] = value
                if (key == "offset") {
                    globalOffsetMs = value.toLongOrNull() ?: 0L
                }
                continue
            }

            // 2. Check time tags (single or repeated like [00:12.00][00:24.00]Chorus)
            val timeMatcher = TIME_TAG_PATTERN.matcher(line)
            val timestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (timeMatcher.find()) {
                val min = timeMatcher.group(1).toLongOrNull() ?: 0L
                val sec = timeMatcher.group(2).toLongOrNull() ?: 0L
                val fracStr = timeMatcher.group(3) ?: "0"
                val ms = when (fracStr.length) {
                    1 -> (fracStr.toInt() * 100).toLong()
                    2 -> (fracStr.toInt() * 10).toLong()
                    else -> fracStr.take(3).toLong()
                }
                val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                timestamps.add(totalMs)
                lastMatchEnd = timeMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val text = line.substring(lastMatchEnd).trim()
                for (timeMs in timestamps) {
                    syncedLines.add(LyricLine(timeMs = timeMs, text = text))
                }
                plainLines.add(text)
            } else {
                // Untimed plain text line
                plainLines.add(line)
            }
        }

        // Sort synced lines chronologically
        syncedLines.sortBy { it.timeMs }

        val isSynced = syncedLines.isNotEmpty()
        val fullPlainText = plainLines.joinToString("\n")

        return ParsedLrcResult(
            lines = syncedLines,
            plainText = fullPlainText,
            isSynced = isSynced,
            offsetMs = globalOffsetMs,
            metadataTags = metadataTags
        )
    }

    /**
     * Serializes LyricLine list back to standard .LRC format with optional metadata tags.
     */
    fun toLrcString(
        lines: List<LyricLine>,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        offsetMs: Long = 0L
    ): String {
        return buildString {
            if (!title.isNullOrBlank()) appendLine("[ti:$title]")
            if (!artist.isNullOrBlank()) appendLine("[ar:$artist]")
            if (!album.isNullOrBlank()) appendLine("[al:$album]")
            if (offsetMs != 0L) appendLine("[offset:$offsetMs]")

            lines.sortedBy { it.timeMs }.forEach { line ->
                val totalSeconds = line.timeMs / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val hundredths = (line.timeMs % 1000) / 10
                appendLine(String.format("[%02d:%02d.%02d]%s", minutes, seconds, hundredths, line.text))
            }
        }
    }
}
