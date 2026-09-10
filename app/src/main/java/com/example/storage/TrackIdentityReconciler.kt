package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.TrackEntity
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Intelligent Track Identity & Reconciliation Engine.
 *
 * Resolves the disconnect between physical file changes (renamed files, moved folders,
 * SAF vs filesystem representation, MediaStore URI updates) and SoundSync's internal track database identity.
 *
 * Guarantees that:
 *  1. Renamed files (e.g. "song.mp3" -> "Artist - Song.mp3") preserve their existing database record,
 *     including all DJ analysis, BPM, keys, waveforms, cue points, ratings, and playlist references.
 *  2. Moved files between folders on internal storage survive without becoming orphaned duplicates.
 *  3. SAF URIs (content://...) and filesystem paths (/storage/emulated/0/...) for the same file
 *     are recognized as canonically identical.
 *  4. Stale database records pointing to old file locations are relinked to newly discovered locations
 *     without destructive database operations or loss of user metadata.
 *  5. Completed metadata scan states (COMPLETE, VERIFIED, USER_CONFIRMED, RESTORED, etc.) are strictly
 *     preserved across path refreshes and relocations.
 */
object TrackIdentityReconciler {

    private const val TAG = "TrackReconciler"

    data class ReconciliationResult(
        val matchedTrack: TrackEntity?,
        val isRelinked: Boolean,
        val relinkedTrack: TrackEntity?,
        val matchReason: MatchReason,
        val confidence: Float,
        val isModified: Boolean = false
    )

    enum class MatchReason {
        EXACT_PATH_MATCH,
        CANONICAL_PATH_MATCH,
        MEDIA_STORE_ID_MATCH,
        CONTENT_FINGERPRINT_MATCH,
        AUDIO_FINGERPRINT_MATCH,
        FILE_NAME_SIZE_DURATION_MATCH,
        STALE_TRACK_SIZE_DURATION_METADATA_MATCH,
        NO_MATCH
    }

    /**
     * Determines whether a track's metadata scan status is completed or confirmed,
     * protecting it from automatic rescanning during normal library refreshes.
     */
    fun isMetadataScanComplete(state: String?): Boolean {
        if (state.isNullOrBlank()) return false
        val s = state.trim().uppercase(Locale.ROOT)
        return s == "COMPLETE" ||
               s == "VERIFIED" ||
               s == "REVIEW_REQUIRED" ||
               s == "USER_CONFIRMED" ||
               s == "RESTORED" ||
               s == "APPROVED" ||
               s == "APPLIED" ||
               s == "IDENTIFIED"
    }

    /**
     * Initializes fast lookup indexes from all tracks in the database.
     */
    fun buildIndexes(
        context: Context,
        allTracks: Collection<TrackEntity>
    ): Indexes {
        val byFingerprint = mutableMapOf<String, TrackEntity>()
        val byCanonicalPath = mutableMapOf<String, TrackEntity>()
        val byRawPath = mutableMapOf<String, TrackEntity>()
        val byMediaId = mutableMapOf<Long, TrackEntity>()
        val byFileNameAndSize = mutableMapOf<String, TrackEntity>()
        val staleTracks = mutableListOf<TrackEntity>()

        for (track in allTracks) {
            val path = track.filePath.trim()
            if (path.isNotBlank()) {
                byRawPath[path] = track
                val canonical = CanonicalStorageHelper.toCanonicalPath(path)
                if (canonical.isNotBlank()) {
                    byCanonicalPath[canonical] = track
                }

                // Index by filename + size for quick moved-file lookup
                val fileName = extractFileNameFromPath(path)
                if (fileName.isNotBlank() && track.fileSizeMb > 0) {
                    val key = "${fileName.lowercase(Locale.ROOT)}_${(track.fileSizeMb * 100).toInt()}_${track.durationSeconds}"
                    byFileNameAndSize[key] = track
                }
            }

            // Index MediaStore ID
            extractMediaId(track)?.let { mediaId ->
                byMediaId[mediaId] = track
            }

            // Index Content Fingerprints
            val fp = track.contentFingerprint.trim()
            if (fp.isNotBlank()) {
                byFingerprint[fp] = track
                if (fp.startsWith("fp_")) {
                    byFingerprint[fp.removePrefix("fp_")] = track
                } else {
                    byFingerprint["fp_$fp"] = track
                }
            }

            // Mark as candidate stale track if its stored path cannot currently be read
            val isReadable = CanonicalStorageHelper.isReferenceReadable(context, path)
            if (!isReadable) {
                staleTracks.add(track)
            }
        }

        return Indexes(
            byFingerprint = byFingerprint,
            byCanonicalPath = byCanonicalPath,
            byRawPath = byRawPath,
            byMediaId = byMediaId,
            byFileNameAndSize = byFileNameAndSize,
            staleTracks = staleTracks
        )
    }

    data class Indexes(
        val byFingerprint: MutableMap<String, TrackEntity>,
        val byCanonicalPath: MutableMap<String, TrackEntity>,
        val byRawPath: MutableMap<String, TrackEntity>,
        val byMediaId: MutableMap<Long, TrackEntity>,
        val byFileNameAndSize: MutableMap<String, TrackEntity>,
        val staleTracks: MutableList<TrackEntity>
    )

    /**
     * Reconciles a newly discovered audio file against the library indexes.
     */
    fun reconcileCandidate(
        candidatePathOrUri: String,
        candidateFingerprint: String,
        candidateSizeBytes: Long,
        candidateDurationSec: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateAlbum: String,
        candidateIsrc: String? = null,
        candidateModified: Long = 0L,
        candidateMediaId: Long? = null,
        context: Context,
        indexes: Indexes
    ): ReconciliationResult {
        val cleanCandidatePath = candidatePathOrUri.trim()
        val candidateCanonical = CanonicalStorageHelper.toCanonicalPath(cleanCandidatePath)

        // 1. Exact raw path match
        val exactMatch = indexes.byRawPath[cleanCandidatePath]
        if (exactMatch != null) {
            // Check if file content changed at this exact path
            val candSizeMb = candidateSizeBytes.toDouble() / (1024.0 * 1024.0)
            val isModified = exactMatch.fileModifiedTimestamp > 0 &&
                    candidateModified > 0 &&
                    abs(candidateModified - exactMatch.fileModifiedTimestamp) > 3000L &&
                    (candidateSizeBytes > 0 && abs(candidateSizeBytes - (exactMatch.fileSizeMb * 1024 * 1024).toLong()) > 4096)

            if (isModified) {
                val updated = exactMatch.copy(
                    fileModifiedTimestamp = candidateModified,
                    fileSizeMb = candSizeMb,
                    durationSeconds = if (candidateDurationSec > 0) candidateDurationSec else exactMatch.durationSeconds,
                    contentFingerprint = if (candidateFingerprint.isNotBlank()) candidateFingerprint else exactMatch.contentFingerprint
                )
                indexes.byRawPath[cleanCandidatePath] = updated
                return ReconciliationResult(
                    matchedTrack = exactMatch,
                    isRelinked = true,
                    relinkedTrack = updated,
                    matchReason = MatchReason.EXACT_PATH_MATCH,
                    confidence = 1.0f,
                    isModified = true
                )
            }

            // If fingerprint was missing or outdated, update it in place without resetting scan states
            if (candidateFingerprint.isNotBlank() && exactMatch.contentFingerprint.isBlank()) {
                val updated = exactMatch.copy(contentFingerprint = candidateFingerprint)
                indexes.byFingerprint[candidateFingerprint] = updated
                indexes.byRawPath[cleanCandidatePath] = updated
                return ReconciliationResult(
                    matchedTrack = exactMatch,
                    isRelinked = true,
                    relinkedTrack = updated,
                    matchReason = MatchReason.EXACT_PATH_MATCH,
                    confidence = 1.0f
                )
            }
            return ReconciliationResult(
                matchedTrack = exactMatch,
                isRelinked = false,
                relinkedTrack = null,
                matchReason = MatchReason.EXACT_PATH_MATCH,
                confidence = 1.0f
            )
        }

        // 2. Canonical path match (e.g. SAF URI vs direct filesystem path)
        val canonicalMatch = indexes.byCanonicalPath[candidateCanonical]
        if (canonicalMatch != null) {
            val relinked = relinkTrackEntity(
                existing = canonicalMatch,
                newPathOrUri = cleanCandidatePath,
                newDirectoryPath = extractParentDirectory(cleanCandidatePath),
                newFingerprint = candidateFingerprint,
                newModifiedTimestamp = candidateModified,
                newTitle = candidateTitle,
                newArtist = candidateArtist,
                newAlbum = candidateAlbum,
                context = context
            )
            updateIndexes(indexes, canonicalMatch, relinked)
            return ReconciliationResult(
                matchedTrack = canonicalMatch,
                isRelinked = true,
                relinkedTrack = relinked,
                matchReason = MatchReason.CANONICAL_PATH_MATCH,
                confidence = 1.0f
            )
        }

        // 3. MediaStore ID match (Relocated file with same MediaStore ID)
        val effectiveMediaId = candidateMediaId ?: extractMediaIdFromPathOrUri(cleanCandidatePath)
        if (effectiveMediaId != null) {
            val mediaIdMatch = indexes.byMediaId[effectiveMediaId]
            if (mediaIdMatch != null) {
                Log.i(TAG, "Reconciled relocated file by MediaStore ID: '${mediaIdMatch.filePath}' -> '$cleanCandidatePath'")
                val relinked = relinkTrackEntity(
                    existing = mediaIdMatch,
                    newPathOrUri = cleanCandidatePath,
                    newDirectoryPath = extractParentDirectory(cleanCandidatePath),
                    newFingerprint = candidateFingerprint,
                    newModifiedTimestamp = candidateModified,
                    newTitle = candidateTitle,
                    newArtist = candidateArtist,
                    newAlbum = candidateAlbum,
                    context = context
                )
                updateIndexes(indexes, mediaIdMatch, relinked)
                return ReconciliationResult(
                    matchedTrack = mediaIdMatch,
                    isRelinked = true,
                    relinkedTrack = relinked,
                    matchReason = MatchReason.MEDIA_STORE_ID_MATCH,
                    confidence = 1.0f
                )
            }
        }

        // 4. Content fingerprint match (The authoritative audio file identity check)
        // Handles RENAMED files (song.mp3 -> Artist - Song.mp3) and MOVED files (Downloads -> DJ)
        if (candidateFingerprint.isNotBlank()) {
            val fpMatch = indexes.byFingerprint[candidateFingerprint]
                ?: indexes.byFingerprint[candidateFingerprint.removePrefix("fp_")]
                ?: indexes.byFingerprint["fp_$candidateFingerprint"]

            if (fpMatch != null) {
                val isSameLocation = CanonicalStorageHelper.isSamePhysicalFile(fpMatch.filePath, cleanCandidatePath)
                val isOldPathAccessible = CanonicalStorageHelper.isReferenceReadable(context, fpMatch.filePath)

                if (!isSameLocation || !isOldPathAccessible) {
                    Log.i(TAG, "Reconciled moved/renamed file by fingerprint: '${fpMatch.filePath}' -> '$cleanCandidatePath'")
                    val relinked = relinkTrackEntity(
                        existing = fpMatch,
                        newPathOrUri = cleanCandidatePath,
                        newDirectoryPath = extractParentDirectory(cleanCandidatePath),
                        newFingerprint = candidateFingerprint,
                        newModifiedTimestamp = candidateModified,
                        newTitle = candidateTitle,
                        newArtist = candidateArtist,
                        newAlbum = candidateAlbum,
                        context = context
                    )
                    updateIndexes(indexes, fpMatch, relinked)
                    return ReconciliationResult(
                        matchedTrack = fpMatch,
                        isRelinked = true,
                        relinkedTrack = relinked,
                        matchReason = MatchReason.CONTENT_FINGERPRINT_MATCH,
                        confidence = 1.0f
                    )
                } else {
                    return ReconciliationResult(
                        matchedTrack = fpMatch,
                        isRelinked = false,
                        relinkedTrack = null,
                        matchReason = MatchReason.CONTENT_FINGERPRINT_MATCH,
                        confidence = 1.0f
                    )
                }
            }
        }

        // 5. Fast Filename + Size + Duration match for moved files
        val candFileName = extractFileNameFromPath(cleanCandidatePath)
        val candSizeMb = candidateSizeBytes.toDouble() / (1024.0 * 1024.0)
        if (candFileName.isNotBlank() && candSizeMb > 0) {
            val key = "${candFileName.lowercase(Locale.ROOT)}_${(candSizeMb * 100).toInt()}_${candidateDurationSec}"
            val nameSizeMatch = indexes.byFileNameAndSize[key]
            if (nameSizeMatch != null) {
                val isOldPathAccessible = CanonicalStorageHelper.isReferenceReadable(context, nameSizeMatch.filePath)
                if (!isOldPathAccessible || nameSizeMatch.filePath != cleanCandidatePath) {
                    Log.i(TAG, "Reconciled moved file by name+size+duration: '${nameSizeMatch.filePath}' -> '$cleanCandidatePath'")
                    val relinked = relinkTrackEntity(
                        existing = nameSizeMatch,
                        newPathOrUri = cleanCandidatePath,
                        newDirectoryPath = extractParentDirectory(cleanCandidatePath),
                        newFingerprint = candidateFingerprint,
                        newModifiedTimestamp = candidateModified,
                        newTitle = candidateTitle,
                        newArtist = candidateArtist,
                        newAlbum = candidateAlbum,
                        context = context
                    )
                    updateIndexes(indexes, nameSizeMatch, relinked)
                    return ReconciliationResult(
                        matchedTrack = nameSizeMatch,
                        isRelinked = true,
                        relinkedTrack = relinked,
                        matchReason = MatchReason.FILE_NAME_SIZE_DURATION_MATCH,
                        confidence = 0.98f
                    )
                }
            }
        }

        // 6. Stale Track Matching (Fallback for tracks where stored fingerprint was missing or used legacy format)
        val staleMatch = findStaleTrackMatch(
            candidateSizeBytes = candidateSizeBytes,
            candidateDurationSec = candidateDurationSec,
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            candidateIsrc = candidateIsrc,
            candidatePath = cleanCandidatePath,
            staleTracks = indexes.staleTracks
        )

        if (staleMatch != null) {
            val (staleTrack, confidence) = staleMatch
            Log.i(TAG, "Reconciled stale missing track '${staleTrack.title}' (id=${staleTrack.id}) to '$cleanCandidatePath' with confidence $confidence")
            val relinked = relinkTrackEntity(
                existing = staleTrack,
                newPathOrUri = cleanCandidatePath,
                newDirectoryPath = extractParentDirectory(cleanCandidatePath),
                newFingerprint = candidateFingerprint,
                newModifiedTimestamp = candidateModified,
                newTitle = candidateTitle,
                newArtist = candidateArtist,
                newAlbum = candidateAlbum,
                context = context
            )
            updateIndexes(indexes, staleTrack, relinked)
            return ReconciliationResult(
                matchedTrack = staleTrack,
                isRelinked = true,
                relinkedTrack = relinked,
                matchReason = MatchReason.STALE_TRACK_SIZE_DURATION_METADATA_MATCH,
                confidence = confidence
            )
        }

        // 7. No match -> Truly a brand new track
        return ReconciliationResult(
            matchedTrack = null,
            isRelinked = false,
            relinkedTrack = null,
            matchReason = MatchReason.NO_MATCH,
            confidence = 0.0f
        )
    }

    /**
     * Relinks an existing database record to a new file path or URI,
     * strictly preserving all existing metadata scan status, analysis state,
     * BPM, keys, waveforms, cue points, ratings, and tags.
     */
    fun relinkTrackEntity(
        existing: TrackEntity,
        newPathOrUri: String,
        newDirectoryPath: String,
        newFingerprint: String,
        newModifiedTimestamp: Long,
        newTitle: String? = null,
        newArtist: String? = null,
        newAlbum: String? = null,
        context: Context? = null
    ): TrackEntity {
        val relPath = CanonicalStorageHelper.toStorageRelativePath(newPathOrUri)

        // Protect user-edited or high-quality existing metadata
        val finalTitle = when {
            existing.userConfirmedMetadata -> existing.title
            existing.title.isNotBlank() && existing.title != "<unknown>" && !existing.title.startsWith("Track ") -> existing.title
            !newTitle.isNullOrBlank() && newTitle != "<unknown>" -> newTitle
            else -> existing.title
        }

        val finalArtist = when {
            existing.userConfirmedMetadata -> existing.artist
            existing.artist.isNotBlank() && existing.artist != "<unknown>" && existing.artist != "Unknown Artist" -> existing.artist
            !newArtist.isNullOrBlank() && newArtist != "<unknown>" -> newArtist
            else -> existing.artist
        }

        val finalAlbum = when {
            existing.userConfirmedMetadata -> existing.album
            existing.album.isNotBlank() && existing.album != "<unknown>" && existing.album != "Single" -> existing.album
            !newAlbum.isNullOrBlank() && newAlbum != "<unknown>" -> newAlbum
            else -> existing.album
        }

        // Artwork Cache reconciliation
        var finalArtworkCachePath = existing.artworkCachePath
        var finalArtworkUrl = existing.artworkUrl
        var finalArtworkSource = existing.artworkSource

        if (!finalArtworkCachePath.isNullOrBlank()) {
            val f = File(finalArtworkCachePath)
            if (!f.exists() || f.length() == 0L) {
                finalArtworkCachePath = null
            }
        }

        if (finalArtworkCachePath == null && context != null) {
            val cache = com.example.metadata.ArtworkCache(context)
            val cachedFile = cache.getCachedArtworkFileForTrack(existing.id)
                ?: if (finalArtist.isNotBlank() && finalAlbum.isNotBlank()) {
                    cache.getCachedArtworkFile(finalArtist, finalAlbum)
                } else null
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                finalArtworkCachePath = cachedFile.absolutePath
                if (finalArtworkUrl.isNullOrBlank()) {
                    finalArtworkUrl = cachedFile.absolutePath
                }
                if (finalArtworkSource.isNullOrBlank()) {
                    finalArtworkSource = "Artwork Cache"
                }
            }
        }

        // Explicitly preserve all metadata status, analysis state, BPM, Key, Cues, and User flags
        return existing.copy(
            filePath = newPathOrUri,
            storageRelativePath = if (relPath.isNotBlank()) relPath else existing.storageRelativePath,
            contentFingerprint = if (newFingerprint.isNotBlank()) newFingerprint else existing.contentFingerprint,
            fingerprintAlgorithm = if (newFingerprint.isNotBlank()) "SOUNDSYNC_SHA256" else existing.fingerprintAlgorithm,
            fingerprintTimestamp = if (newFingerprint.isNotBlank()) System.currentTimeMillis() else existing.fingerprintTimestamp,
            fileModifiedTimestamp = if (newModifiedTimestamp > 0) newModifiedTimestamp else existing.fileModifiedTimestamp,
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            artworkCachePath = finalArtworkCachePath,
            artworkUrl = finalArtworkUrl,
            artworkSource = finalArtworkSource,
            metadataScanState = existing.metadataScanState,
            metadataScanTimestamp = existing.metadataScanTimestamp,
            userConfirmedMetadata = existing.userConfirmedMetadata,
            metadataWriteState = existing.metadataWriteState,
            analysisState = existing.analysisState,
            analysisVersion = existing.analysisVersion,
            lastAnalysedAt = existing.lastAnalysedAt,
            analysisFailureReason = existing.analysisFailureReason,
            analysisRetryCount = existing.analysisRetryCount,
            bpm = existing.bpm,
            bpmConfidence = existing.bpmConfidence,
            bpmAnalysisVersion = existing.bpmAnalysisVersion,
            bpmLastAnalyzed = existing.bpmLastAnalyzed,
            musicalKey = existing.musicalKey,
            camelotKey = existing.camelotKey,
            keyConfidence = existing.keyConfidence,
            keyAnalysisVersion = existing.keyAnalysisVersion,
            keyLastAnalyzed = existing.keyLastAnalyzed,
            isManualBpm = existing.isManualBpm,
            isManualKey = existing.isManualKey,
            rating = existing.rating,
            customTags = existing.customTags,
            notes = existing.notes,
            composer = existing.composer,
            hotCuesString = existing.hotCuesString,
            energyRating = existing.energyRating,
            isOfflineReady = true
        )
    }

    /**
     * Registers a freshly inserted track into the active memory indexes during scan batches.
     */
    fun registerTrackInIndices(
        track: TrackEntity,
        indexes: Indexes
    ) {
        val path = track.filePath.trim()
        if (path.isNotBlank()) {
            indexes.byRawPath[path] = track
            val can = CanonicalStorageHelper.toCanonicalPath(path)
            if (can.isNotBlank()) {
                indexes.byCanonicalPath[can] = track
            }
            val fileName = extractFileNameFromPath(path)
            if (fileName.isNotBlank() && track.fileSizeMb > 0) {
                val key = "${fileName.lowercase(Locale.ROOT)}_${(track.fileSizeMb * 100).toInt()}_${track.durationSeconds}"
                indexes.byFileNameAndSize[key] = track
            }
        }
        val fp = track.contentFingerprint.trim()
        if (fp.isNotBlank()) {
            indexes.byFingerprint[fp] = track
            if (fp.startsWith("fp_")) {
                indexes.byFingerprint[fp.removePrefix("fp_")] = track
            } else {
                indexes.byFingerprint["fp_$fp"] = track
            }
        }
        extractMediaId(track)?.let { mediaId ->
            indexes.byMediaId[mediaId] = track
        }
    }

    // ── Private Matching Helpers ────────────────────────────────────────────

    private fun extractMediaId(track: TrackEntity): Long? {
        if (track.id.startsWith("media_")) {
            return track.id.removePrefix("media_").toLongOrNull()
        }
        return extractMediaIdFromPathOrUri(track.filePath)
    }

    private fun extractMediaIdFromPathOrUri(pathOrUri: String): Long? {
        if (pathOrUri.startsWith("content://media/")) {
            return try {
                val uri = Uri.parse(pathOrUri)
                uri.lastPathSegment?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun extractFileNameFromPath(pathOrUri: String): String {
        return if (pathOrUri.startsWith("content://")) {
            val seg = pathOrUri.substringAfterLast('/')
            if (seg.contains('.')) seg else ""
        } else {
            File(pathOrUri).name
        }
    }

    private fun findStaleTrackMatch(
        candidateSizeBytes: Long,
        candidateDurationSec: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateIsrc: String?,
        candidatePath: String,
        staleTracks: List<TrackEntity>
    ): Pair<TrackEntity, Float>? {
        if (staleTracks.isEmpty()) return null

        val normCandTitle = normalizeString(candidateTitle)
        val normCandArtist = normalizeString(candidateArtist)
        val candFileName = CanonicalStorageHelper.extractFileName(candidatePath)

        for (stale in staleTracks) {
            // 1. ISRC exact match
            if (!candidateIsrc.isNullOrBlank() && !stale.isrc.isNullOrBlank() && candidateIsrc.equals(stale.isrc, ignoreCase = true)) {
                return stale to 0.99f
            }

            // 2. Exact file size & duration match
            val staleSizeMb = stale.fileSizeMb
            val candSizeMb = candidateSizeBytes.toDouble() / (1024.0 * 1024.0)
            val sizeDiff = abs(candSizeMb - staleSizeMb)
            val durDiff = if (candidateDurationSec > 0 && stale.durationSeconds > 0) abs(candidateDurationSec - stale.durationSeconds) else 999

            if (candidateSizeBytes > 0 && staleSizeMb > 0 && sizeDiff < 0.05 && durDiff <= 2) {
                val normStaleTitle = normalizeString(stale.title)
                val normStaleArtist = normalizeString(stale.artist)

                // Check title or artist similarity
                val titleSimilar = normCandTitle.isNotBlank() && (
                    normCandTitle == normStaleTitle ||
                    normCandTitle.contains(normStaleTitle) ||
                    normStaleTitle.contains(normCandTitle)
                )

                if (titleSimilar) {
                    return stale to 0.95f
                }

                // Check if filename contains stale title or vice versa
                val fileMatchesStale = candFileName.contains(stale.title, ignoreCase = true) ||
                    stale.filePath.contains(normCandTitle, ignoreCase = true)
                if (fileMatchesStale) {
                    return stale to 0.90f
                }

                // If size is nearly byte-exact (diff < 512 bytes) and duration matches exactly
                if (sizeDiff < 0.001 && durDiff == 0) {
                    return stale to 0.88f
                }
            }
        }

        return null
    }

    private fun updateIndexes(
        indexes: Indexes,
        oldTrack: TrackEntity,
        newTrack: TrackEntity
    ) {
        indexes.staleTracks.remove(oldTrack)

        val oldPath = oldTrack.filePath.trim()
        if (oldPath.isNotBlank()) {
            indexes.byRawPath.remove(oldPath)
        }
        val oldCan = CanonicalStorageHelper.toCanonicalPath(oldPath)
        if (oldCan.isNotBlank()) {
            indexes.byCanonicalPath.remove(oldCan)
        }

        val newPath = newTrack.filePath.trim()
        if (newPath.isNotBlank()) {
            indexes.byRawPath[newPath] = newTrack
            val newCan = CanonicalStorageHelper.toCanonicalPath(newPath)
            if (newCan.isNotBlank()) {
                indexes.byCanonicalPath[newCan] = newTrack
            }
            val fileName = extractFileNameFromPath(newPath)
            if (fileName.isNotBlank() && newTrack.fileSizeMb > 0) {
                val key = "${fileName.lowercase(Locale.ROOT)}_${(newTrack.fileSizeMb * 100).toInt()}_${newTrack.durationSeconds}"
                indexes.byFileNameAndSize[key] = newTrack
            }
        }

        val fp = newTrack.contentFingerprint.trim()
        if (fp.isNotBlank()) {
            indexes.byFingerprint[fp] = newTrack
            if (fp.startsWith("fp_")) {
                indexes.byFingerprint[fp.removePrefix("fp_")] = newTrack
            } else {
                indexes.byFingerprint["fp_$fp"] = newTrack
            }
        }

        extractMediaId(newTrack)?.let { mediaId ->
            indexes.byMediaId[mediaId] = newTrack
        }
    }

    private fun extractParentDirectory(pathOrUri: String): String {
        return if (pathOrUri.startsWith("content://")) {
            val docId = CanonicalStorageHelper.toStorageRelativePath(pathOrUri)
            if (docId.contains('/')) docId.substringBeforeLast('/') else ""
        } else {
            val f = File(pathOrUri)
            f.parent ?: ""
        }
    }

    private fun normalizeString(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }
}

