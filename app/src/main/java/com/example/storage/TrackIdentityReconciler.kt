package com.example.storage

import android.content.Context
import android.util.Log
import com.example.data.TrackEntity
import java.util.Locale
import kotlin.math.abs

/**
 * Intelligent Track Identity & Reconciliation Engine.
 *
 * Resolves the disconnect between physical file changes (renamed files, moved folders,
 * SAF vs filesystem representation) and SoundSync's internal track database identity.
 *
 * Guarantees that:
 *  1. Renamed files (e.g. "song.mp3" -> "Artist - Song.mp3") preserve their existing database record,
 *     including all DJ analysis, BPM, keys, waveforms, cue points, ratings, and playlist references.
 *  2. Moved files between folders on internal storage survive without becoming orphaned duplicates.
 *  3. SAF URIs (content://...) and filesystem paths (/storage/emulated/0/...) for the same file
 *     are recognized as canonically identical.
 *  4. Stale database records pointing to old file locations are relinked to newly discovered locations
 *     without destructive database operations or loss of user metadata.
 */
object TrackIdentityReconciler {

    private const val TAG = "TrackReconciler"

    data class ReconciliationResult(
        val matchedTrack: TrackEntity?,
        val isRelinked: Boolean,
        val relinkedTrack: TrackEntity?,
        val matchReason: MatchReason,
        val confidence: Float
    )

    enum class MatchReason {
        EXACT_PATH_MATCH,
        CANONICAL_PATH_MATCH,
        CONTENT_FINGERPRINT_MATCH,
        STALE_TRACK_SIZE_DURATION_METADATA_MATCH,
        NO_MATCH
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
        val staleTracks = mutableListOf<TrackEntity>()

        for (track in allTracks) {
            val path = track.filePath.trim()
            if (path.isNotBlank()) {
                byRawPath[path] = track
                val canonical = CanonicalStorageHelper.toCanonicalPath(path)
                if (canonical.isNotBlank()) {
                    byCanonicalPath[canonical] = track
                }
            }

            val fp = track.contentFingerprint.trim()
            if (fp.isNotBlank() && fp.startsWith("fp_")) {
                byFingerprint[fp] = track
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
            staleTracks = staleTracks
        )
    }

    data class Indexes(
        val byFingerprint: MutableMap<String, TrackEntity>,
        val byCanonicalPath: MutableMap<String, TrackEntity>,
        val byRawPath: MutableMap<String, TrackEntity>,
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
        context: Context,
        indexes: Indexes
    ): ReconciliationResult {
        val cleanCandidatePath = candidatePathOrUri.trim()
        val candidateCanonical = CanonicalStorageHelper.toCanonicalPath(cleanCandidatePath)

        // 1. Exact raw path match
        val exactMatch = indexes.byRawPath[cleanCandidatePath]
        if (exactMatch != null) {
            // Already indexed at this exact path.
            // If fingerprint was missing or outdated, update it in place
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
            // Same physical file, but accessed via a different representation (e.g. SAF document URI vs /storage/emulated/0/...)
            val isOldReadable = CanonicalStorageHelper.isReferenceReadable(context, canonicalMatch.filePath)
            // If candidate is a direct working URI (e.g. SAF tree document), relink the track to the working URI
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

        // 3. Content fingerprint match (The ultimate audio file identity check)
        // This handles RENAMED files (song.mp3 -> Artist - Song.mp3) and MOVED files (Download -> Music)
        if (candidateFingerprint.isNotBlank() && candidateFingerprint.startsWith("fp_")) {
            val fpMatch = indexes.byFingerprint[candidateFingerprint]
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

        // 4. Stale Track Matching (Fallback for tracks where stored fingerprint was missing or used legacy format)
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

        // 5. No match -> Truly a brand new track
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
     * preserving all existing metadata, DJ cue points, analysis, playlists, and history.
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
            val f = java.io.File(finalArtworkCachePath)
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
            artworkSource = finalArtworkSource
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
        }
        val fp = track.contentFingerprint.trim()
        if (fp.isNotBlank() && fp.startsWith("fp_")) {
            indexes.byFingerprint[fp] = track
        }
    }

    // ── Private Matching Helpers ────────────────────────────────────────────

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
        }

        val fp = newTrack.contentFingerprint.trim()
        if (fp.isNotBlank() && fp.startsWith("fp_")) {
            indexes.byFingerprint[fp] = newTrack
        }
    }

    private fun extractParentDirectory(pathOrUri: String): String {
        return if (pathOrUri.startsWith("content://")) {
            val docId = CanonicalStorageHelper.toStorageRelativePath(pathOrUri)
            if (docId.contains('/')) docId.substringBeforeLast('/') else ""
        } else {
            val f = java.io.File(pathOrUri)
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
