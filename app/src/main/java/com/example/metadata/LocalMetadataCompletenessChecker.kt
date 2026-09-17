package com.example.metadata

import android.content.Context
import android.util.Log
import com.example.metadata.artwork.CanonicalArtworkDetector
import com.example.model.Track
import java.io.File
import java.util.Locale

/**
 * Result of metadata completeness evaluation for an audio track.
 */
data class MetadataCompletenessResult(
    val isComplete: Boolean,
    val missingFields: List<String>
)

/**
 * Single authoritative completeness checker for SoundSync audio tracks.
 *
 * Ensures that audio files whose metadata was already successfully written or embedded
 * are treated as complete and never re-analysed.
 *
 * REQUIRED fields (must be present and non-empty):
 * - Title (non-blank, not placeholder)
 * - Artist (non-blank, not placeholder)
 * - Album (non-blank, not placeholder)
 * - Genre (non-blank, not placeholder)
 * - Year / Release Date (valid 4-digit year or parseable release date)
 * - Track Number (> 0)
 * - Disc Number (>= 1, default 1)
 * - BPM (valid non-zero value: 30..300, not 120.0 default with 0 confidence)
 * - Musical Key (valid key, not empty, "—", "-", "Unknown", etc.)
 * - Cover Art (embedded in audio file, or valid cached/source artwork)
 *
 * OPTIONAL fields (must NOT cause a track to be re-analysed if missing):
 * - Album Artist, ISRC, Barcode / UPC, Record Label, Rating, Comments
 */
object LocalMetadataCompletenessChecker {
    private const val TAG = "MetadataCompleteness"

    fun isPlaceholderOrBlank(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val trimmed = value.trim().lowercase(Locale.ROOT)
        return when (trimmed) {
            "", "unknown", "unknown artist", "unknown album", "unknown title", "unknown genre",
            "n/a", "na", "none", "undefined", "null", "blank", "<unknown>", "untitled",
            "various artists", "various", "generic album", "album", "artist" -> true
            else -> false
        }
    }

    fun isPlaceholderTitle(title: String?): Boolean {
        if (isPlaceholderOrBlank(title)) return true
        val trimmed = title!!.trim().lowercase(Locale.ROOT)
        if (trimmed.matches(Regex("^(audio\\s*)?track[\\s_\\-]*\\d+$"))) return true
        if (trimmed.matches(Regex("^untitled[\\s_\\-]*(\\d+)?$"))) return true
        return false
    }

    fun isPlaceholderArtist(artist: String?): Boolean {
        return isPlaceholderOrBlank(artist)
    }

    fun isPlaceholderAlbum(album: String?): Boolean {
        if (isPlaceholderOrBlank(album)) return true
        val trimmed = album!!.trim().lowercase(Locale.ROOT)
        return trimmed == "album" || trimmed == "generic album" || trimmed == "unknown album"
    }

    fun isPlaceholderGenre(genre: String?): Boolean {
        return isPlaceholderOrBlank(genre)
    }

    fun isValidYear(releaseYear: Int?, releaseDate: String?): Boolean {
        if (releaseYear != null && releaseYear in 1900..2100) return true
        if (!releaseDate.isNullOrBlank() && !isPlaceholderOrBlank(releaseDate)) {
            val yearPart = releaseDate.trim().take(4).toIntOrNull()
            if (yearPart != null && yearPart in 1900..2100) return true
        }
        return false
    }

    fun isValidTrackAndDiscNumber(trackNumber: Int?, discNumber: Int?): Boolean {
        val validTrack = (trackNumber != null && trackNumber > 0)
        val validDisc = (discNumber == null || discNumber >= 1)
        return validTrack && validDisc
    }

    fun isValidBpm(bpm: Double?, confidence: Double = 1.0): Boolean {
        if (bpm == null || bpm <= 0.0) return false
        if (bpm !in 30.0..300.0) return false
        // BPM = 120.0 default when confidence is 0
        if (bpm == 120.0 && confidence <= 0.0) return false
        return true
    }

    fun isValidMusicalKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val trimmed = key.trim()
        if (trimmed == "—" || trimmed == "-" ||
            trimmed.equals("unknown", ignoreCase = true) ||
            trimmed.equals("none", ignoreCase = true) ||
            trimmed.equals("n/a", ignoreCase = true) ||
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("undefined", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    fun hasValidArtwork(
        context: Context? = null,
        hasEmbeddedArtwork: Boolean,
        track: Track? = null,
        artworkSource: String? = null,
        artworkUrl: String? = null,
        artworkCachePath: String? = null
    ): Boolean {
        if (hasEmbeddedArtwork) return true
        if (track != null) {
            return CanonicalArtworkDetector.hasArtwork(context, track)
        }
        if (!artworkCachePath.isNullOrBlank()) {
            val path = artworkCachePath.removePrefix("file://")
            val f = File(path)
            if (f.exists() && f.isFile && f.length() > 0L) return true
        }
        if (!artworkUrl.isNullOrBlank() && !CanonicalArtworkDetector.isPlaceholderOrUnusable(artworkUrl)) {
            return true
        }
        if (!artworkSource.isNullOrBlank() && artworkSource !in listOf("NONE", "None", "null", "undefined")) {
            return true
        }
        return false
    }

    fun evaluate(
        title: String?,
        artist: String?,
        album: String?,
        genre: String?,
        releaseYear: Int?,
        releaseDate: String?,
        trackNumber: Int?,
        discNumber: Int?,
        bpm: Double?,
        bpmConfidence: Double = 1.0,
        musicalKey: String?,
        hasEmbeddedArtwork: Boolean,
        artworkSource: String? = null,
        artworkUrl: String? = null,
        artworkCachePath: String? = null,
        track: Track? = null,
        context: Context? = null
    ): MetadataCompletenessResult {
        val missing = mutableListOf<String>()

        if (isPlaceholderTitle(title)) missing.add("Title")
        if (isPlaceholderArtist(artist)) missing.add("Artist")
        if (isPlaceholderAlbum(album)) missing.add("Album")
        if (isPlaceholderGenre(genre)) missing.add("Genre")
        if (!isValidYear(releaseYear, releaseDate)) missing.add("Year")
        if (!isValidTrackAndDiscNumber(trackNumber, discNumber)) missing.add("TrackNumber")
        if (!isValidBpm(bpm, bpmConfidence)) missing.add("BPM")
        if (!isValidMusicalKey(musicalKey)) missing.add("MusicalKey")
        if (!hasValidArtwork(context, hasEmbeddedArtwork, track, artworkSource, artworkUrl, artworkCachePath)) missing.add("CoverArt")

        return MetadataCompletenessResult(isComplete = missing.isEmpty(), missingFields = missing)
    }

    fun evaluateTrack(context: Context? = null, track: Track): MetadataCompletenessResult {
        val hasEmbedded = track.artworkSource in listOf("Embedded Tag", "Embedded")
        val effectiveKey = track.camelotKey.takeIf { isValidMusicalKey(it) } ?: track.musicalKey
        return evaluate(
            title = track.title,
            artist = track.artist,
            album = track.album,
            genre = track.genre,
            releaseYear = track.releaseYear,
            releaseDate = track.releaseDate,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            bpm = track.bpm,
            bpmConfidence = track.bpmConfidence,
            musicalKey = effectiveKey,
            hasEmbeddedArtwork = hasEmbedded,
            artworkSource = track.artworkSource,
            artworkUrl = track.artworkUrl,
            artworkCachePath = track.artworkCachePath,
            track = track,
            context = context
        )
    }

    fun isTrackComplete(context: Context? = null, track: Track): Boolean {
        return evaluateTrack(context, track).isComplete
    }
}
