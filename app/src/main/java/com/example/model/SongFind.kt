package com.example.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PendingSongFind(
    val url: String = "",
    val initialTitle: String = "",
    val detectedPlatform: String = "Web Find",
    val initialNotes: String = "",
    val isAlreadySaved: Boolean = false,
    val existingId: String? = null
)

/**
 * Represents a saved song find / music discovery inbox item.
 * Captured from Android share sheets (Instagram, TikTok, YouTube, Spotify, Browser, etc.)
 * or created manually by the user.
 */
data class SongFind(
    val id: String,
    val url: String,
    val title: String,
    val sourceAppName: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false
) {
    val displayTitle: String
        get() = if (title.isNotBlank()) title else url.substringAfter("://").take(45)

    val cleanDomain: String
        get() = try {
            val withoutScheme = url.substringAfter("://")
            val domain = withoutScheme.substringBefore("/").substringBefore("?")
            domain.removePrefix("www.").removePrefix("open.").removePrefix("m.")
        } catch (e: Exception) {
            "web"
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }

    val relativeTimeSpan: String
        get() {
            val diffMs = System.currentTimeMillis() - createdAt
            val diffSec = diffMs / 1000
            val diffMin = diffSec / 60
            val diffHours = diffMin / 60
            val diffDays = diffHours / 24
            return when {
                diffMin < 1 -> "Just now"
                diffMin < 60 -> "${diffMin}m ago"
                diffHours < 24 -> "${diffHours}h ago"
                diffDays < 7 -> "${diffDays}d ago"
                else -> {
                    val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
                    sdf.format(Date(createdAt))
                }
            }
        }

    companion object {
        fun detectPlatform(url: String, sharedText: String = ""): String {
            val combined = (url + " " + sharedText).lowercase(Locale.ROOT)
            return when {
                combined.contains("instagram.com") || combined.contains("instagr.am") -> "Instagram"
                combined.contains("tiktok.com") -> "TikTok"
                combined.contains("youtube.com") || combined.contains("youtu.be") -> "YouTube"
                combined.contains("spotify.com") -> "Spotify"
                combined.contains("soundcloud.com") -> "SoundCloud"
                combined.contains("bandcamp.com") -> "Bandcamp"
                combined.contains("apple.com/music") || combined.contains("music.apple.com") -> "Apple Music"
                combined.contains("shazam.com") -> "Shazam"
                combined.contains("twitter.com") || combined.contains("x.com") -> "X / Twitter"
                combined.contains("reddit.com") -> "Reddit"
                else -> "Web Find"
            }
        }
    }
}
