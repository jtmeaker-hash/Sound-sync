package com.example.backup

import com.example.data.TrackEntity
import com.example.metadata.repair.StringNormalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Confidence levels for matching tracks restored from backup with local audio files.
 */
enum class MatchConfidenceLevel {
    FINGERPRINT,          // Audio content acoustic fingerprint match
    RECORDING_ID,         // Apple Track ID match
    RELATIVE_PATH_EXACT,  // Matches relative path, duration, and file size
    FILE_PATH_EXACT,      // Matches absolute path, duration, and file size
    METADATA_HIGH,        // High similarity on title & artist within duration tolerance
    METADATA_MEDIUM,      // Moderate similarity on title & artist within duration tolerance
    NONE
}

data class TrackMatchResult(
    val backupTrack: TrackBackupItem,
    val matchedEntity: TrackEntity?,
    val confidenceLevel: MatchConfidenceLevel,
    val isFileModified: Boolean = false,
    val modificationReason: String? = null
)

/**
 * Robust matching engine that connects restored backup track data (analysis, cues, ratings, repaired metadata)
 * to tracks on the current device after an app reinstall or library re-scan.
 *
 * Also identifies modified or replaced audio files to prevent applying outdated beatgrids or cue points.
 */
object TrackMatcher {

    /**
     * Matches a single backup track against a list of candidate TrackEntity items.
     */
    fun matchTrack(
        backupTrack: TrackBackupItem,
        candidates: List<TrackEntity>
    ): TrackMatchResult {
        // 1. Content Fingerprint (exact acoustic identity)
        if (backupTrack.contentFingerprint.isNotBlank()) {
            val fpMatch = candidates.firstOrNull {
                it.contentFingerprint.isNotBlank() && it.contentFingerprint == backupTrack.contentFingerprint
            }
            if (fpMatch != null) {
                val (modified, reason) = checkFileModification(backupTrack, fpMatch)
                return TrackMatchResult(
                    backupTrack = backupTrack,
                    matchedEntity = fpMatch,
                    confidenceLevel = MatchConfidenceLevel.FINGERPRINT,
                    isFileModified = modified,
                    modificationReason = reason
                )
            }
        }

        // 2. Apple Track ID
        if (backupTrack.appleTrackId != null && backupTrack.appleTrackId > 0L) {
            val appleMatch = candidates.firstOrNull {
                it.appleTrackId != null && it.appleTrackId == backupTrack.appleTrackId
            }
            if (appleMatch != null) {
                val (modified, reason) = checkFileModification(backupTrack, appleMatch)
                return TrackMatchResult(
                    backupTrack = backupTrack,
                    matchedEntity = appleMatch,
                    confidenceLevel = MatchConfidenceLevel.RECORDING_ID,
                    isFileModified = modified,
                    modificationReason = reason
                )
            }
        }

        // 3. Storage Relative Path
        if (backupTrack.storageRelativePath.isNotBlank()) {
            val relMatch = candidates.firstOrNull {
                it.storageRelativePath.isNotBlank() &&
                    it.storageRelativePath.equals(backupTrack.storageRelativePath, ignoreCase = true)
            }
            if (relMatch != null) {
                val (modified, reason) = checkFileModification(backupTrack, relMatch)
                return TrackMatchResult(
                    backupTrack = backupTrack,
                    matchedEntity = relMatch,
                    confidenceLevel = MatchConfidenceLevel.RELATIVE_PATH_EXACT,
                    isFileModified = modified,
                    modificationReason = reason
                )
            }
        }

        // 4. Absolute File Path
        if (backupTrack.filePath.isNotBlank()) {
            val pathMatch = candidates.firstOrNull {
                it.filePath.equals(backupTrack.filePath, ignoreCase = true)
            }
            if (pathMatch != null) {
                val (modified, reason) = checkFileModification(backupTrack, pathMatch)
                return TrackMatchResult(
                    backupTrack = backupTrack,
                    matchedEntity = pathMatch,
                    confidenceLevel = MatchConfidenceLevel.FILE_PATH_EXACT,
                    isFileModified = modified,
                    modificationReason = reason
                )
            }
        }

        // 5. Scored Metadata Fallback
        var bestCandidate: TrackEntity? = null
        var bestScore = 0.0

        for (candidate in candidates) {
            // Duration gating: must be within 4 seconds if duration is known for both
            if (backupTrack.durationSeconds > 0 && candidate.durationSeconds > 0) {
                if (abs(backupTrack.durationSeconds - candidate.durationSeconds) > 4) {
                    continue
                }
            }

            val titleSim = StringNormalizer.calculateTitleSimilarity(backupTrack.title, candidate.title)
            val artistA = backupTrack.resolvedArtist ?: backupTrack.artist
            val artistB = candidate.resolvedArtist ?: candidate.artist
            val artistSim = StringNormalizer.calculateArtistSimilarity(artistA, artistB)

            val combinedScore = (titleSim * 0.55) + (artistSim * 0.45)
            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestCandidate = candidate
            }
        }

        if (bestCandidate != null) {
            if (bestScore >= 0.85) {
                val (modified, reason) = checkFileModification(backupTrack, bestCandidate)
                return TrackMatchResult(
                    backupTrack = backupTrack,
                    matchedEntity = bestCandidate,
                    confidenceLevel = MatchConfidenceLevel.METADATA_HIGH,
                    isFileModified = modified,
                    modificationReason = reason
                )
            } else if (bestScore >= 0.72) {
                val (modified, reason) = checkFileModification(backupTrack, bestCandidate)
                return TrackMatchResult(
                    backupTrack = backupTrack,
                    matchedEntity = bestCandidate,
                    confidenceLevel = MatchConfidenceLevel.METADATA_MEDIUM,
                    isFileModified = modified,
                    modificationReason = reason
                )
            }
        }

        return TrackMatchResult(
            backupTrack = backupTrack,
            matchedEntity = null,
            confidenceLevel = MatchConfidenceLevel.NONE
        )
    }

    /**
     * Efficiently matches all backup tracks against current library entities.
     * Prevents multiple backup tracks from binding to the same local entity.
     */
    fun matchTracks(
        backupTracks: List<TrackBackupItem>,
        currentEntities: List<TrackEntity>
    ): List<TrackMatchResult> {
        val remainingEntities = currentEntities.toMutableList()
        val results = mutableListOf<TrackMatchResult>()

        for (backupTrack in backupTracks) {
            val match = matchTrack(backupTrack, remainingEntities)
            results.add(match)
            if (match.matchedEntity != null) {
                // Avoid assigning the same track twice
                remainingEntities.removeAll { it.id == match.matchedEntity.id }
            }
        }

        return results
    }

    /**
     * Merges restored backup data into an existing TrackEntity in a non-destructive manner.
     * - Preserves local file identity and paths.
     * - Overlays verified/repaired metadata.
     * - If the file has NOT been modified, applies completed BPM, key, cues, and analysis state.
     * - If the file HAS been modified, leaves analysis state as NOT_ANALYSED for re-verification.
     */
    fun mergeTrack(
        backupTrack: TrackBackupItem,
        existingEntity: TrackEntity,
        isFileModified: Boolean
    ): TrackEntity {
        // Resolve artist preference
        val mergedOriginalArtist = existingEntity.originalArtist ?: backupTrack.originalArtist
        val mergedResolvedArtist = backupTrack.resolvedArtist ?: existingEntity.resolvedArtist
        val mergedMetadataSource = backupTrack.metadataSource ?: existingEntity.metadataSource
        val mergedMetadataConfidence = max(backupTrack.metadataConfidence, existingEntity.metadataConfidence)

        // Resolve title preference: prefer restored title if existing is generic, placeholder, or has timestamps
        val hasGarbageInExistingTitle = com.example.metadata.parser.TrackIdentityParser.cleanGarbage(existingEntity.title) != existingEntity.title
        val isBackupBetterTitle = backupTrack.title.isNotBlank() && backupTrack.title != existingEntity.title &&
            (backupTrack.metadataConfidence >= com.example.metadata.MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD ||
             backupTrack.metadataScanState == "COMPLETE" || backupTrack.metadataScanState == "APPROVED" || backupTrack.metadataScanState == "APPLIED" ||
             com.example.metadata.parser.TrackIdentityParser.cleanGarbage(existingEntity.title) == backupTrack.title)

        val mergedTitle = if (backupTrack.userConfirmedMetadata ||
            (!existingEntity.userConfirmedMetadata && (
                hasGarbageInExistingTitle ||
                com.example.metadata.parser.TrackIdentityParser.isRecordingPlaceholder(existingEntity.title) ||
                !com.example.metadata.parser.TrackIdentityParser.isTitleValid(existingEntity.title) ||
                isBackupBetterTitle
            ))
        ) {
            backupTrack.title
        } else {
            existingEntity.title
        }

        // Resolve artist preference: prefer restored artist if existing is unknown, missing, or lower confidence
        val mergedArtist = if (backupTrack.userConfirmedMetadata) {
            backupTrack.artist
        } else if (!mergedResolvedArtist.isNullOrBlank() && (existingEntity.artist.isBlank() || !com.example.metadata.parser.TrackIdentityParser.isArtistValid(existingEntity.artist))) {
            mergedResolvedArtist
        } else if (backupTrack.artist.isNotBlank() && !com.example.metadata.parser.TrackIdentityParser.isArtistValid(existingEntity.artist)) {
            backupTrack.artist
        } else if (!mergedResolvedArtist.isNullOrBlank() && backupTrack.metadataConfidence >= com.example.metadata.MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
            mergedResolvedArtist
        } else {
            existingEntity.artist
        }

        // Resolve album preference: reject generic folder names like "Download", "Recordings"
        val mergedAlbum = if (backupTrack.userConfirmedMetadata) {
            backupTrack.album
        } else if (com.example.metadata.parser.TrackIdentityParser.isGenericAlbumName(existingEntity.album) && !com.example.metadata.parser.TrackIdentityParser.isGenericAlbumName(backupTrack.album)) {
            backupTrack.album
        } else if (existingEntity.album.isBlank() || existingEntity.album == "Single") {
            if (!backupTrack.album.isNullOrBlank() && !com.example.metadata.parser.TrackIdentityParser.isGenericAlbumName(backupTrack.album)) backupTrack.album else existingEntity.album
        } else {
            existingEntity.album
        }

        // Resolve catalog attributes
        val mergedAlbumArtist = if (existingEntity.albumArtist.isBlank() && backupTrack.albumArtist.isNotBlank()) backupTrack.albumArtist else existingEntity.albumArtist
        val mergedGenre = if ((existingEntity.genre.isBlank() || existingEntity.genre == "DJ Library") && backupTrack.genre.isNotBlank()) backupTrack.genre else existingEntity.genre
        val mergedYear = existingEntity.releaseYear ?: backupTrack.releaseYear
        val mergedDate = existingEntity.releaseDate ?: backupTrack.releaseDate
        val mergedTrackNum = if (existingEntity.trackNumber == 0 && backupTrack.trackNumber > 0) backupTrack.trackNumber else existingEntity.trackNumber
        val mergedDiscNum = if (existingEntity.discNumber <= 1 && backupTrack.discNumber > 1) backupTrack.discNumber else existingEntity.discNumber
        val mergedLabel = existingEntity.recordLabel ?: backupTrack.recordLabel
        val mergedIsrc = existingEntity.isrc ?: backupTrack.isrc
        val mergedWriteState = if (existingEntity.metadataWriteState == "NOT_ANALYSED" && backupTrack.metadataWriteState.isNotBlank()) backupTrack.metadataWriteState else existingEntity.metadataWriteState

        val shouldTransferAnalysis = !isFileModified && (
            existingEntity.analysisState != "COMPLETE" ||
            backupTrack.isManualBpm ||
            backupTrack.isManualKey ||
            (existingEntity.bpm <= 0.0 && backupTrack.bpm > 0.0)
        )

        return existingEntity.copy(
            title = mergedTitle,
            artist = mergedArtist,
            originalArtist = mergedOriginalArtist,
            resolvedArtist = mergedResolvedArtist,
            metadataSource = mergedMetadataSource,
            metadataConfidence = mergedMetadataConfidence,
            album = mergedAlbum,
            albumArtist = mergedAlbumArtist,
            genre = mergedGenre,
            releaseYear = mergedYear,
            releaseDate = mergedDate,
            trackNumber = mergedTrackNum,
            discNumber = mergedDiscNum,
            recordLabel = mergedLabel,
            isrc = mergedIsrc,
            metadataWriteState = mergedWriteState,

            // Musical Analysis
            bpm = if (shouldTransferAnalysis && backupTrack.bpm > 0.0) backupTrack.bpm else existingEntity.bpm,
            bpmConfidence = if (shouldTransferAnalysis) max(backupTrack.bpmConfidence, existingEntity.bpmConfidence) else existingEntity.bpmConfidence,
            bpmAnalysisVersion = if (shouldTransferAnalysis) (backupTrack.bpmAnalysisVersion ?: existingEntity.bpmAnalysisVersion) else existingEntity.bpmAnalysisVersion,
            bpmLastAnalyzed = if (shouldTransferAnalysis) (backupTrack.bpmLastAnalyzed ?: existingEntity.bpmLastAnalyzed) else existingEntity.bpmLastAnalyzed,
            isManualBpm = existingEntity.isManualBpm || backupTrack.isManualBpm,

            musicalKey = if (shouldTransferAnalysis && backupTrack.musicalKey.isNotBlank()) backupTrack.musicalKey else existingEntity.musicalKey,
            camelotKey = if (shouldTransferAnalysis && backupTrack.camelotKey.isNotBlank()) backupTrack.camelotKey else existingEntity.camelotKey,
            keyConfidence = if (shouldTransferAnalysis) max(backupTrack.keyConfidence, existingEntity.keyConfidence) else existingEntity.keyConfidence,
            keyAnalysisVersion = if (shouldTransferAnalysis) (backupTrack.keyAnalysisVersion ?: existingEntity.keyAnalysisVersion) else existingEntity.keyAnalysisVersion,
            keyLastAnalyzed = if (shouldTransferAnalysis) (backupTrack.keyLastAnalyzed ?: existingEntity.keyLastAnalyzed) else existingEntity.keyLastAnalyzed,
            isManualKey = existingEntity.isManualKey || backupTrack.isManualKey,

            energyRating = if (shouldTransferAnalysis && backupTrack.energyRating > 0) backupTrack.energyRating else existingEntity.energyRating,
            hotCuesString = if (shouldTransferAnalysis && backupTrack.hotCuesString.isNotBlank()) backupTrack.hotCuesString else existingEntity.hotCuesString,
            qualityRating = if (existingEntity.qualityRating.isBlank()) backupTrack.qualityRating else existingEntity.qualityRating,

            analysisState = if (isFileModified) {
                "NOT_ANALYSED"
            } else if (shouldTransferAnalysis || backupTrack.analysisState == "COMPLETE" || backupTrack.bpm > 0.0 || backupTrack.musicalKey.isNotBlank()) {
                "COMPLETE"
            } else {
                existingEntity.analysisState
            },
            lastAnalysedAt = if (shouldTransferAnalysis) (backupTrack.lastAnalysedAt ?: existingEntity.lastAnalysedAt) else existingEntity.lastAnalysedAt,

            // Apple & TheAudioDB identifiers & artwork
            appleTrackId = existingEntity.appleTrackId ?: backupTrack.appleTrackId,
            appleCollectionId = existingEntity.appleCollectionId ?: backupTrack.appleCollectionId,
            appleArtistId = existingEntity.appleArtistId ?: backupTrack.appleArtistId,
            theAudioDbAlbumId = existingEntity.theAudioDbAlbumId ?: backupTrack.theAudioDbAlbumId,
            theAudioDbArtistId = existingEntity.theAudioDbArtistId ?: backupTrack.theAudioDbArtistId,
            artworkSource = existingEntity.artworkSource ?: backupTrack.artworkSource,
            artworkCachePath = existingEntity.artworkCachePath ?: backupTrack.artworkCachePath,
            metadataScanState = when {
                backupTrack.metadataScanState.isNotBlank() && backupTrack.metadataScanState != "NOT_SCANNED" -> backupTrack.metadataScanState
                existingEntity.metadataScanState in listOf("COMPLETE", "RESTORED", "APPROVED", "APPLIED", "USER_CONFIRMED") -> existingEntity.metadataScanState
                backupTrack.title.isNotBlank() || backupTrack.artist.isNotBlank() -> com.example.model.MetadataScanState.RESTORED.name
                else -> existingEntity.metadataScanState
            },
            metadataScanTimestamp = existingEntity.metadataScanTimestamp ?: backupTrack.metadataScanTimestamp,
            userConfirmedMetadata = true,

            // User metadata & ratings
            rating = if (existingEntity.rating != 0) existingEntity.rating else backupTrack.rating,
            notes = if (existingEntity.notes.isNotBlank()) existingEntity.notes else backupTrack.notes,
            customTags = if (existingEntity.customTags.isNotBlank()) existingEntity.customTags else backupTrack.customTags,
            artworkUrl = existingEntity.artworkUrl ?: backupTrack.artworkUrl
        )
    }

    /**
     * Checks if the physical audio file differs from the backup snapshot (e.g. size changed, duration changed).
     */
    fun checkFileModification(
        backup: TrackBackupItem,
        candidate: TrackEntity
    ): Pair<Boolean, String?> {
        // Duration mismatch check (> 3 seconds difference)
        if (backup.durationSeconds > 0 && candidate.durationSeconds > 0) {
            val diffSec = abs(backup.durationSeconds - candidate.durationSeconds)
            if (diffSec > 3) {
                return true to "Duration differs by ${diffSec}s (backup: ${backup.durationSeconds}s, file: ${candidate.durationSeconds}s)"
            }
        }

        // File size mismatch check (> 0.2 MB and > 5% difference)
        if (backup.fileSizeMb > 0.0 && candidate.fileSizeMb > 0.0) {
            val sizeDiff = abs(backup.fileSizeMb - candidate.fileSizeMb)
            val relDiff = sizeDiff / backup.fileSizeMb
            if (sizeDiff > 0.2 && relDiff > 0.05) {
                return true to "File size changed (backup: ${backup.fileSizeMb}MB, file: ${candidate.fileSizeMb}MB)"
            }
        }

        // Modified timestamp check if file exists and both timestamps are non-zero
        if (backup.fileModifiedTimestamp > 0L && candidate.fileModifiedTimestamp > 0L) {
            if (backup.fileModifiedTimestamp != candidate.fileModifiedTimestamp) {
                // If timestamp changed and duration or size also showed subtle changes
                val sizeDiff = abs(backup.fileSizeMb - candidate.fileSizeMb)
                if (sizeDiff > 0.05) {
                    return true to "File timestamp and size updated since backup"
                }
            }
        }

        return false to null
    }
}
