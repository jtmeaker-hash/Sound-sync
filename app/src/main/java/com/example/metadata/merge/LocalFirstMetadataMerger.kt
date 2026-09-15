package com.example.metadata.merge

import android.util.Log
import com.example.metadata.AlbumValidator
import com.example.metadata.MetadataConfidenceScorer
import com.example.metadata.parser.TrackIdentityParser
import com.example.model.MetadataScanState
import com.example.model.Track

/**
 * Result of a deterministic local-first metadata merge.
 */
data class LocalFirstMergeResult(
    val mergedTrack: Track,
    val conflicts: List<MetadataFieldConflict>,
    val appliedProvenances: Map<String, MetadataSourceProvenance>,
    val wasRepaired: Boolean = false,
    val summary: String = ""
)

/**
 * Local-first metadata merge engine (Upgrade 26).
 *
 * Enforces core local-first principles:
 * 1. Existing local metadata from files or DSP analysis is authoritative evidence, not disposable scratchpad data.
 * 2. Online enrichment fills missing and placeholder metadata, but NEVER silently replaces valid local tags.
 * 3. Conflicts between valid local tags and online suggestions are recorded as candidates for manual review.
 * 4. User edits are strictly immutable against automated scanners.
 * 5. Local DSP audio analysis (BPM, Musical Key) is protected against web catalog values.
 * 6. Local artwork is preserved unless missing or explicitly overridden.
 * 7. Null/blank online fields never erase local values.
 */
object LocalFirstMetadataMerger {

    private const val TAG = "LocalFirstMerger"

    /**
     * Checks if an artist tag represents a generic placeholder rather than a verified identity.
     */
    fun isPlaceholderArtist(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val trimmed = artist.trim()
        val lower = trimmed.lowercase()
        return lower == "unknown artist" ||
                lower == "<unknown>" ||
                lower == "unknown" ||
                lower == "artist" ||
                lower == "various artists" ||
                !TrackIdentityParser.isArtistValid(trimmed)
    }

    /**
     * Checks if a title tag represents an uninformative placeholder rather than a real song name.
     */
    fun isPlaceholderTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val trimmed = title.trim()
        val lower = trimmed.lowercase()
        if (lower == "unknown title" || lower == "<unknown>" || lower == "unknown" || lower == "title") return true
        val placeholderRegex = Regex("^(?i)(track|audio|recording|rec|title)\\s*([0-9_-]+)?$")
        if (placeholderRegex.matches(trimmed)) return true
        return !TrackIdentityParser.isTitleValid(trimmed)
    }

    /**
     * Checks if an album tag is generic, blank, or an invalid path segment.
     */
    fun isPlaceholderAlbum(album: String?, filePath: String = ""): Boolean {
        if (album.isNullOrBlank()) return true
        val trimmed = album.trim()
        val lower = trimmed.lowercase()
        if (lower == "single" || lower == "unknown album" || lower == "<unknown>" || lower == "unknown") return true
        if (TrackIdentityParser.isGenericAlbumName(trimmed)) return true
        if (filePath.isNotBlank() && !AlbumValidator.isValidAlbum(trimmed, filePath)) return true
        return false
    }

    /**
     * Infers the source provenance for a specific field on a track.
     */
    fun inferFieldProvenance(track: Track, fieldName: String): MetadataSourceProvenance {
        // First check explicit stored provenance if present
        val storedMap = TrackFieldProvenance.parse(track.fieldProvenanceJson)
        val stored = storedMap[fieldName.lowercase()]
        if (stored != null && stored != MetadataSourceProvenance.EMPTY && stored != MetadataSourceProvenance.PLACEHOLDER) {
            return stored
        }

        // User confirmed track metadata implies user edit
        if (track.userConfirmedMetadata) {
            return MetadataSourceProvenance.USER_EDIT
        }

        return when (fieldName.lowercase()) {
            "bpm" -> {
                when {
                    track.isManualBpm -> MetadataSourceProvenance.USER_EDIT
                    track.isLocallyAnalyzed || (track.bpmLastAnalyzed != null && track.bpmLastAnalyzed > 0) -> MetadataSourceProvenance.LOCAL_DSP
                    track.bpm > 0.0 -> MetadataSourceProvenance.LOCAL_TAG
                    else -> MetadataSourceProvenance.EMPTY
                }
            }
            "key", "musicalkey", "camelotkey" -> {
                when {
                    track.isManualKey -> MetadataSourceProvenance.USER_EDIT
                    track.isLocallyAnalyzed || (track.keyLastAnalyzed != null && track.keyLastAnalyzed > 0) -> MetadataSourceProvenance.LOCAL_DSP
                    track.musicalKey.isNotBlank() && track.musicalKey != "—" && track.musicalKey != "-" -> MetadataSourceProvenance.LOCAL_TAG
                    else -> MetadataSourceProvenance.EMPTY
                }
            }
            "artist" -> {
                when {
                    track.artist.isBlank() -> MetadataSourceProvenance.EMPTY
                    isPlaceholderArtist(track.artist) -> MetadataSourceProvenance.PLACEHOLDER
                    track.metadataScanState == MetadataScanState.RESTORED.name -> MetadataSourceProvenance.RESTORED_BACKUP
                    track.isAppleIdentified && track.metadataSource?.contains("Apple") == true -> MetadataSourceProvenance.ONLINE_PROVIDER
                    else -> MetadataSourceProvenance.LOCAL_TAG
                }
            }
            "title" -> {
                when {
                    track.title.isBlank() -> MetadataSourceProvenance.EMPTY
                    isPlaceholderTitle(track.title) -> MetadataSourceProvenance.PLACEHOLDER
                    track.metadataScanState == MetadataScanState.RESTORED.name -> MetadataSourceProvenance.RESTORED_BACKUP
                    track.isAppleIdentified && track.metadataSource?.contains("Apple") == true -> MetadataSourceProvenance.ONLINE_PROVIDER
                    else -> MetadataSourceProvenance.LOCAL_TAG
                }
            }
            "album" -> {
                when {
                    track.album.isBlank() -> MetadataSourceProvenance.EMPTY
                    isPlaceholderAlbum(track.album, track.filePath) -> MetadataSourceProvenance.PLACEHOLDER
                    track.metadataScanState == MetadataScanState.RESTORED.name -> MetadataSourceProvenance.RESTORED_BACKUP
                    track.isAppleIdentified && track.metadataSource?.contains("Apple") == true -> MetadataSourceProvenance.ONLINE_PROVIDER
                    else -> MetadataSourceProvenance.LOCAL_TAG
                }
            }
            "artwork" -> {
                when {
                    track.artworkCachePath?.isNotBlank() == true && track.artworkSource?.contains("Apple") == true -> MetadataSourceProvenance.ONLINE_PROVIDER
                    track.artworkCachePath?.isNotBlank() == true || track.artworkUrl?.isNotBlank() == true -> MetadataSourceProvenance.LOCAL_TAG
                    else -> MetadataSourceProvenance.EMPTY
                }
            }
            "genre" -> {
                when {
                    track.genre.isBlank() || track.genre == "DJ Library" || track.genre == "Club" -> MetadataSourceProvenance.PLACEHOLDER
                    else -> MetadataSourceProvenance.LOCAL_TAG
                }
            }
            "year", "releaseyear" -> {
                when {
                    track.releaseYear != null && track.releaseYear > 0 -> MetadataSourceProvenance.LOCAL_TAG
                    else -> MetadataSourceProvenance.EMPTY
                }
            }
            else -> MetadataSourceProvenance.LOCAL_TAG
        }
    }

    /**
     * Performs a deterministic local-first merge between a local track and an incoming candidate.
     */
    fun merge(
        localTrack: Track,
        candidate: CandidateMetadata?,
        candidateScore: Double,
        matchState: MetadataScanState,
        replaceExistingArtistSetting: Boolean = false,
        replaceExistingTitleSetting: Boolean = false,
        replaceExistingArtworkSetting: Boolean = false
    ): LocalFirstMergeResult {
        // If candidate is absent, rejected, or below minimum confidence, preserve local track untouched
        if (candidate == null || matchState == MetadataScanState.NO_MATCH || matchState == MetadataScanState.REJECTED ||
            candidateScore < MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD) {
            val provs = localTrack.getAllFieldProvenances().toMutableMap()
            return LocalFirstMergeResult(
                mergedTrack = localTrack.copy(fieldProvenanceJson = TrackFieldProvenance.toJson(provs)),
                conflicts = emptyList(),
                appliedProvenances = provs,
                wasRepaired = false,
                summary = "Local metadata preserved; no acceptable online candidate"
            )
        }

        val conflicts = mutableListOf<MetadataFieldConflict>()
        val appliedProvenances = localTrack.getAllFieldProvenances().toMutableMap()
        var wasRepaired = false

        // User edit immunity: User confirmed metadata is strictly protected
        val isUserConfirmed = localTrack.userConfirmedMetadata ||
                localTrack.metadataScanState == MetadataScanState.USER_CONFIRMED.name ||
                localTrack.metadataScanState == MetadataScanState.APPROVED.name ||
                localTrack.metadataScanState == MetadataScanState.APPLIED.name

        // --- ARTIST MERGE ---
        val localArtist = localTrack.artist
        val candidateArtist = candidate.artist?.trim()
        val localArtistProv = inferFieldProvenance(localTrack, "artist")
        var finalArtist = localArtist

        if (!candidateArtist.isNullOrBlank()) {
            if (isUserConfirmed || localArtistProv == MetadataSourceProvenance.USER_EDIT) {
                finalArtist = localArtist
                if (!candidateArtist.equals(localArtist, ignoreCase = true)) {
                    conflicts.add(
                        MetadataFieldConflict(
                            fieldName = "artist",
                            localValue = localArtist,
                            localProvenance = MetadataSourceProvenance.USER_EDIT,
                            proposedValue = candidateArtist,
                            proposedProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
                            proposedSource = candidate.provider,
                            confidence = candidateScore
                        )
                    )
                }
            } else if (replaceExistingArtistSetting) {
                finalArtist = candidateArtist
                appliedProvenances["artist"] = MetadataSourceProvenance.ONLINE_PROVIDER
                wasRepaired = true
            } else if (localArtistProv == MetadataSourceProvenance.EMPTY || localArtistProv == MetadataSourceProvenance.PLACEHOLDER) {
                // Missing or placeholder artist -> safely fill from online candidate
                finalArtist = candidateArtist
                appliedProvenances["artist"] = MetadataSourceProvenance.ONLINE_PROVIDER
                wasRepaired = true
            } else {
                // Valid local artist tag exists! Protect local-first!
                if (!candidateArtist.equals(localArtist, ignoreCase = true)) {
                    finalArtist = localArtist
                    appliedProvenances["artist"] = MetadataSourceProvenance.LOCAL_TAG
                    conflicts.add(
                        MetadataFieldConflict(
                            fieldName = "artist",
                            localValue = localArtist,
                            localProvenance = localArtistProv,
                            proposedValue = candidateArtist,
                            proposedProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
                            proposedSource = candidate.provider,
                            confidence = candidateScore
                        )
                    )
                } else {
                    finalArtist = localArtist
                    appliedProvenances["artist"] = MetadataSourceProvenance.LOCAL_TAG
                }
            }
        }

        // --- TITLE MERGE ---
        val localTitle = localTrack.title
        val candidateTitle = candidate.title?.trim()
        val localTitleProv = inferFieldProvenance(localTrack, "title")
        var finalTitle = localTitle

        if (!candidateTitle.isNullOrBlank()) {
            if (isUserConfirmed || localTitleProv == MetadataSourceProvenance.USER_EDIT) {
                finalTitle = localTitle
                if (!candidateTitle.equals(localTitle, ignoreCase = true)) {
                    conflicts.add(
                        MetadataFieldConflict(
                            fieldName = "title",
                            localValue = localTitle,
                            localProvenance = MetadataSourceProvenance.USER_EDIT,
                            proposedValue = candidateTitle,
                            proposedProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
                            proposedSource = candidate.provider,
                            confidence = candidateScore
                        )
                    )
                }
            } else if (replaceExistingTitleSetting) {
                finalTitle = candidateTitle
                appliedProvenances["title"] = MetadataSourceProvenance.ONLINE_PROVIDER
                wasRepaired = true
            } else if (localTitleProv == MetadataSourceProvenance.EMPTY || localTitleProv == MetadataSourceProvenance.PLACEHOLDER) {
                // Missing or placeholder title -> safely fill from online candidate
                finalTitle = candidateTitle
                appliedProvenances["title"] = MetadataSourceProvenance.ONLINE_PROVIDER
                wasRepaired = true
            } else {
                // Valid local title tag exists! Protect local-first!
                if (!candidateTitle.equals(localTitle, ignoreCase = true)) {
                    finalTitle = localTitle
                    appliedProvenances["title"] = MetadataSourceProvenance.LOCAL_TAG
                    conflicts.add(
                        MetadataFieldConflict(
                            fieldName = "title",
                            localValue = localTitle,
                            localProvenance = localTitleProv,
                            proposedValue = candidateTitle,
                            proposedProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
                            proposedSource = candidate.provider,
                            confidence = candidateScore
                        )
                    )
                } else {
                    finalTitle = localTitle
                    appliedProvenances["title"] = MetadataSourceProvenance.LOCAL_TAG
                }
            }
        }

        // --- ALBUM MERGE ---
        val localAlbum = localTrack.album
        val candidateAlbum = candidate.album?.trim()
        val localAlbumProv = inferFieldProvenance(localTrack, "album")
        var finalAlbum = localAlbum

        if (!candidateAlbum.isNullOrBlank() && AlbumValidator.isValidAlbum(candidateAlbum, localTrack.filePath)) {
            if (isUserConfirmed || localAlbumProv == MetadataSourceProvenance.USER_EDIT) {
                finalAlbum = localAlbum
            } else if (localAlbumProv == MetadataSourceProvenance.EMPTY || localAlbumProv == MetadataSourceProvenance.PLACEHOLDER) {
                finalAlbum = candidateAlbum
                appliedProvenances["album"] = MetadataSourceProvenance.ONLINE_PROVIDER
                wasRepaired = true
            } else {
                // Valid local album tag exists
                if (!candidateAlbum.equals(localAlbum, ignoreCase = true)) {
                    finalAlbum = localAlbum
                    conflicts.add(
                        MetadataFieldConflict(
                            fieldName = "album",
                            localValue = localAlbum,
                            localProvenance = localAlbumProv,
                            proposedValue = candidateAlbum,
                            proposedProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
                            proposedSource = candidate.provider,
                            confidence = candidateScore
                        )
                    )
                }
            }
        }

        // --- ARTWORK MERGE ---
        val hasLocalArtwork = (!localTrack.artworkUrl.isNullOrBlank() && !localTrack.artworkUrl.startsWith("http")) ||
                (!localTrack.artworkCachePath.isNullOrBlank())
        var finalArtworkUrl = localTrack.artworkUrl
        var finalArtworkCachePath = localTrack.artworkCachePath
        var finalArtworkSource = localTrack.artworkSource

        val hasCandidateArtwork = !candidate.artworkUrl.isNullOrBlank() || !candidate.artworkCachePath.isNullOrBlank()
        if (hasCandidateArtwork) {
            if (isUserConfirmed) {
                // User choice preserved
            } else if (replaceExistingArtworkSetting || !hasLocalArtwork) {
                // No local artwork or explicit user replacement toggle enabled
                finalArtworkUrl = candidate.artworkUrl ?: localTrack.artworkUrl
                finalArtworkCachePath = candidate.artworkCachePath ?: localTrack.artworkCachePath
                finalArtworkSource = candidate.artworkSource ?: candidate.provider
                appliedProvenances["artwork"] = MetadataSourceProvenance.ONLINE_PROVIDER
            } else {
                // Local artwork is present and valid -> KEEP LOCAL!
                conflicts.add(
                    MetadataFieldConflict(
                        fieldName = "artwork",
                        localValue = localTrack.artworkCachePath ?: localTrack.artworkUrl ?: "Local Artwork",
                        localProvenance = MetadataSourceProvenance.LOCAL_TAG,
                        proposedValue = candidate.artworkCachePath ?: candidate.artworkUrl ?: "Online Artwork",
                        proposedProvenance = MetadataSourceProvenance.ONLINE_PROVIDER,
                        proposedSource = candidate.provider,
                        confidence = candidateScore
                    )
                )
            }
        }

        // --- BPM & MUSICAL KEY: LOCAL DSP STRICTLY PRESERVED ---
        // Never overwrite audio-derived DSP or manual BPM/Key with an online catalog value
        val finalBpm = localTrack.bpm
        val finalMusicalKey = localTrack.musicalKey
        val finalCamelotKey = localTrack.camelotKey

        // --- RELEASE YEAR & DATE MERGE ---
        val finalYear = when {
            localTrack.releaseYear != null && localTrack.releaseYear > 0 -> localTrack.releaseYear
            candidate.releaseYear != null && candidate.releaseYear > 0 -> {
                appliedProvenances["year"] = MetadataSourceProvenance.ONLINE_PROVIDER
                candidate.releaseYear
            }
            else -> localTrack.releaseYear
        }

        val finalReleaseDate = when {
            !localTrack.releaseDate.isNullOrBlank() -> localTrack.releaseDate
            !candidate.releaseDate.isNullOrBlank() -> candidate.releaseDate
            finalYear != null -> finalYear.toString()
            else -> localTrack.releaseDate
        }

        // --- GENRE MERGE ---
        val localGenreProv = inferFieldProvenance(localTrack, "genre")
        val finalGenre = when {
            isUserConfirmed -> localTrack.genre
            localGenreProv != MetadataSourceProvenance.PLACEHOLDER && localTrack.genre.isNotBlank() -> localTrack.genre
            !candidate.genre.isNullOrBlank() -> {
                appliedProvenances["genre"] = MetadataSourceProvenance.ONLINE_PROVIDER
                candidate.genre
            }
            else -> localTrack.genre
        }

        val finalTrackNumber = if (localTrack.trackNumber > 0) localTrack.trackNumber else (candidate.trackNumber ?: localTrack.trackNumber)
        val finalDiscNumber = if (localTrack.discNumber > 0) localTrack.discNumber else (candidate.discNumber ?: localTrack.discNumber)

        val mergedTrack = localTrack.copy(
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            genre = finalGenre,
            releaseYear = finalYear,
            releaseDate = finalReleaseDate,
            trackNumber = finalTrackNumber,
            discNumber = finalDiscNumber,
            bpm = finalBpm,
            musicalKey = finalMusicalKey,
            camelotKey = finalCamelotKey,
            artworkUrl = finalArtworkUrl,
            artworkCachePath = finalArtworkCachePath,
            artworkSource = finalArtworkSource,
            appleTrackId = candidate.appleTrackId ?: localTrack.appleTrackId,
            appleCollectionId = candidate.appleCollectionId ?: localTrack.appleCollectionId,
            appleArtistId = candidate.appleArtistId ?: localTrack.appleArtistId,
            metadataSource = if (appliedProvenances.values.any { it == MetadataSourceProvenance.ONLINE_PROVIDER }) candidate.provider else localTrack.metadataSource,
            metadataConfidence = if (appliedProvenances.values.any { it == MetadataSourceProvenance.ONLINE_PROVIDER }) candidateScore else localTrack.metadataConfidence,
            metadataScanState = if (conflicts.isNotEmpty()) MetadataScanState.REVIEW_REQUIRED.name else matchState.name,
            fieldProvenanceJson = TrackFieldProvenance.toJson(appliedProvenances)
        )

        return LocalFirstMergeResult(
            mergedTrack = mergedTrack,
            conflicts = conflicts,
            appliedProvenances = appliedProvenances,
            wasRepaired = wasRepaired,
            summary = if (conflicts.isNotEmpty()) "${conflicts.size} conflict(s) detected; local tags preserved" else "Local-first merge complete"
        )
    }
}
