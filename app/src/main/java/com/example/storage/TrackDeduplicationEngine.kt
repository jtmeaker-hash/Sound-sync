package com.example.storage

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import java.util.Locale

/**
 * Authoritative Library Deduplication Engine for SoundSync.
 *
 * Solves the duplicate / 1-second ghost track bug by identifying database rows
 * that point to the exact same physical audio file on storage.
 *
 * Guarantees:
 *  1. One physical audio file on storage has at most ONE SoundSync library entry.
 *  2. NEVER deduplicates by title, artist, album, or duration alone (different masters,
 *     remixes, edits, and format copies remain completely distinct).
 *  3. Resolves 0:01 dummy duration ghost entries by selecting the valid full-length record
 *     as canonical and merging all DJ analysis, BPM, keys, cues, and user tags.
 *  4. Repoints all foreign key relations (playlists, playback history, metadata reviews,
 *     lyrics, backups) before deleting redundant rows.
 *  5. NEVER modifies, moves, renames, or deletes any physical audio file on storage.
 */
object TrackDeduplicationEngine {

    private const val TAG = "TrackDeduplication"

    data class DeduplicationReport(
        val totalTracksExamined: Int,
        val duplicateGroupsFound: Int,
        val duplicateRowsRemoved: Int,
        val mergedTrackIds: List<String>
    )

    /**
     * Executes a full library audit and safe deduplication migration.
     */
    suspend fun deduplicateLibrary(
        context: Context,
        database: AppDatabase
    ): DeduplicationReport {
        val allTracks = database.trackDao().getAllTracksList()
        if (allTracks.size <= 1) {
            return DeduplicationReport(
                totalTracksExamined = allTracks.size,
                duplicateGroupsFound = 0,
                duplicateRowsRemoved = 0,
                mergedTrackIds = emptyList()
            )
        }

        val groups = findDuplicateGroups(context, allTracks)
        if (groups.isEmpty()) {
            Log.d(TAG, "No duplicate physical tracks found in library (${allTracks.size} tracks verified).")
            return DeduplicationReport(
                totalTracksExamined = allTracks.size,
                duplicateGroupsFound = 0,
                duplicateRowsRemoved = 0,
                mergedTrackIds = emptyList()
            )
        }

        Log.i(TAG, "Found ${groups.size} duplicate groups across ${allTracks.size} tracks. Starting merge...")

        val trackDao = database.trackDao()
        val playlistDao = database.playlistDao()
        val sessionDao = database.playbackSessionDao()
        val historyDao = database.metadataHistoryDao()
        val reviewDao = database.metadataReviewInboxDao()
        val lyricsDao = database.lyricsDao()
        val backupDao = database.metadataBackupDao()

        var totalRemoved = 0
        val mergedIds = mutableListOf<String>()

        for (group in groups) {
            val canonical = pickCanonicalTrack(context, group)
            val duplicates = group.filter { it.id != canonical.id }
            if (duplicates.isEmpty()) continue

            // Build merged canonical entity
            var merged = canonical
            for (dup in duplicates) {
                merged = mergeTrackData(context, canonical = merged, duplicate = dup)
            }

            // Ensure physicalMediaKey is populated
            if (merged.physicalMediaKey.isBlank()) {
                merged = merged.copy(
                    physicalMediaKey = PhysicalMediaIdentifier.computePhysicalMediaKey(
                        context,
                        merged.filePath,
                        merged.id,
                        merged.mediaStoreId,
                        merged.mediaStoreVolume
                    )
                )
            }

            // Execute merge & repoint for each duplicate
            for (dup in duplicates) {
                try {
                    // 1. Repoint playlist entries
                    val playlistEntries = playlistDao.getPlaylistEntriesForTrack(dup.id)
                    for (entry in playlistEntries) {
                        val existingInPlaylist = playlistDao.getTracksForPlaylistSync(entry.playlistId)
                        val alreadyHasCanonical = existingInPlaylist.any { it.trackId == canonical.id }
                        if (alreadyHasCanonical) {
                            playlistDao.removeTrackByEntryId(entry.id)
                        } else {
                            playlistDao.repointPlaylistTrackEntry(entry.id, canonical.id)
                        }
                    }

                    // 2. Repoint playback sessions
                    sessionDao.repointTrackId(oldTrackId = dup.id, newTrackId = canonical.id)

                    // 3. Repoint metadata history
                    historyDao.repointTrackId(oldTrackId = dup.id, newTrackId = canonical.id)

                    // 4. Repoint or clean metadata review items
                    val existingReview = reviewDao.getPendingItemForTrack(canonical.id)
                    if (existingReview != null) {
                        reviewDao.deleteForTrack(dup.id)
                    } else {
                        reviewDao.repointTrackId(oldTrackId = dup.id, newTrackId = canonical.id)
                    }

                    // 5. Repoint or clean lyrics
                    val canonicalLyrics = lyricsDao.getLyricsForTrack(canonical.id)
                    val duplicateLyrics = lyricsDao.getLyricsForTrack(dup.id)
                    if (canonicalLyrics != null) {
                        if (duplicateLyrics != null) {
                            lyricsDao.deleteLyricsForTrack(dup.id)
                        }
                    } else if (duplicateLyrics != null) {
                        lyricsDao.deleteLyricsForTrack(dup.id)
                        lyricsDao.insertOrUpdateLyrics(duplicateLyrics.copy(trackId = canonical.id))
                    }

                    // 6. Repoint metadata backups
                    backupDao.repointTrackId(oldTrackId = dup.id, newTrackId = canonical.id)

                    // 7. Delete duplicate track row
                    trackDao.deleteTrackById(dup.id)
                    totalRemoved++
                    mergedIds.add(dup.id)
                    Log.d(TAG, "Removed duplicate track row: ${dup.id} (merged into ${canonical.id})")
                } catch (e: Exception) {
                    Log.e(TAG, "Error repointing relations from duplicate ${dup.id} to ${canonical.id}: ${e.message}", e)
                }
            }

            // Update canonical track in database
            trackDao.updateTrack(merged)
        }

        Log.i(TAG, "Deduplication completed. Groups: ${groups.size}, Removed duplicate rows: $totalRemoved")
        return DeduplicationReport(
            totalTracksExamined = allTracks.size,
            duplicateGroupsFound = groups.size,
            duplicateRowsRemoved = totalRemoved,
            mergedTrackIds = mergedIds
        )
    }

    /**
     * Groups tracks strictly by physical media identity.
     * Never groups tracks based on title, artist, album, or duration.
     */
    fun findDuplicateGroups(
        context: Context?,
        tracks: List<TrackEntity>
    ): List<List<TrackEntity>> {
        if (tracks.size <= 1) return emptyList()

        // Disjoint Set / Union-Find over track indices
        val parent = IntArray(tracks.size) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }

        // Precompute canonical paths, physical keys, and storage relative paths
        val physicalKeys = tracks.map { track ->
            if (track.physicalMediaKey.isNotBlank()) {
                track.physicalMediaKey
            } else {
                PhysicalMediaIdentifier.computePhysicalMediaKey(
                    context,
                    track.filePath,
                    track.id,
                    track.mediaStoreId,
                    track.mediaStoreVolume
                )
            }
        }

        val canonicalPaths = tracks.map { track ->
            CanonicalStorageHelper.toCanonicalPath(context, track.filePath)
        }

        val relativePaths = tracks.map { track ->
            if (track.storageRelativePath.isNotBlank()) {
                track.storageRelativePath.lowercase(Locale.ROOT)
            } else {
                CanonicalStorageHelper.toStorageRelativePath(context, track.filePath).lowercase(Locale.ROOT)
            }
        }

        val storageVolumes = tracks.map { track ->
            val volFromTrack = track.mediaStoreVolume?.lowercase(Locale.ROOT)
            if (volFromTrack != null && volFromTrack.isNotBlank()) {
                if (volFromTrack == "external" || volFromTrack == "external_primary") "primary" else volFromTrack
            } else {
                CanonicalStorageHelper.extractStorageVolume(track.filePath)
            }
        }

        // 1. Index by physicalMediaKey (only valid non-empty keys, excluding demo://)
        val keyIndex = mutableMapOf<String, Int>()
        for (i in tracks.indices) {
            val key = physicalKeys[i]
            if (key.isNotBlank() && !key.startsWith("demo:") && !key.startsWith("uri:demo")) {
                val existing = keyIndex[key]
                if (existing != null) {
                    union(existing, i)
                } else {
                    keyIndex[key] = i
                }
            }
        }

        // 2. Index by MediaStore ID + Volume
        val mediaStoreIndex = mutableMapOf<String, Int>()
        for (i in tracks.indices) {
            val msId = tracks[i].mediaStoreId
                ?: (if (tracks[i].id.startsWith("media_")) tracks[i].id.removePrefix("media_").toLongOrNull() else null)
            if (msId != null && msId > 0L) {
                val vol = (tracks[i].mediaStoreVolume ?: PhysicalMediaIdentifier.extractVolumeFromUri(tracks[i].filePath) ?: "external").lowercase(Locale.ROOT)
                val msKey = "$vol:$msId"
                val existing = mediaStoreIndex[msKey]
                if (existing != null) {
                    union(existing, i)
                } else {
                    mediaStoreIndex[msKey] = i
                }
            }
        }

        // 3. Index by canonical filesystem path (excluding content:// and demo://)
        val canonicalIndex = mutableMapOf<String, Int>()
        for (i in tracks.indices) {
            val can = canonicalPaths[i]
            if (can.isNotBlank() && !can.startsWith("content://") && !can.startsWith("demo://")) {
                val existing = canonicalIndex[can.lowercase(Locale.ROOT)]
                if (existing != null) {
                    union(existing, i)
                } else {
                    canonicalIndex[can.lowercase(Locale.ROOT)] = i
                }
            }
        }

        // 4. Compare storage-relative paths on the same physical storage root
        for (i in 0 until tracks.size - 1) {
            val relI = relativePaths[i]
            if (relI.isBlank()) continue
            val volI = storageVolumes[i]

            for (j in i + 1 until tracks.size) {
                val relJ = relativePaths[j]
                if (relJ.isBlank() || relI != relJ) continue
                val volJ = storageVolumes[j]

                // Both must be on the same volume (e.g. both internal/primary or same SD card UUID)
                if (volI == volJ) {
                    union(i, j)
                }
            }
        }

        // 5. Cross-check raw path vs resolvedUri
        val pathIndex = mutableMapOf<String, Int>()
        for (i in tracks.indices) {
            val p = tracks[i].filePath.trim()
            if (p.isNotBlank() && !p.startsWith("demo://")) {
                val existing = pathIndex[p]
                if (existing != null) {
                    union(existing, i)
                } else {
                    pathIndex[p] = i
                }
            }
            val res = tracks[i].resolvedUri?.trim()
            if (!res.isNullOrBlank() && !res.startsWith("demo://")) {
                val existing = pathIndex[res]
                if (existing != null) {
                    union(existing, i)
                } else {
                    pathIndex[res] = i
                }
            }
        }

        // Group into clusters
        val groupsByRoot = mutableMapOf<Int, MutableList<TrackEntity>>()
        for (i in tracks.indices) {
            val root = find(i)
            groupsByRoot.getOrPut(root) { mutableListOf() }.add(tracks[i])
        }

        return groupsByRoot.values.filter { it.size > 1 }
    }

    /**
     * Determines which track entity in a duplicate group is the canonical record.
     * Priority:
     *  1. Valid duration (> 1 second) strongly beats 0:01 ghost duration.
     *  2. Playable status (PLAYABLE/REPAIRED beats UNPLAYABLE/UNKNOWN).
     *  3. DJ Analysis completeness (COMPLETE beats QUEUED/NOT_ANALYSED).
     *  4. User confirmed metadata.
     *  5. Earlier dateAdded.
     */
    fun pickCanonicalTrack(context: Context?, group: List<TrackEntity>): TrackEntity {
        require(group.isNotEmpty()) { "Group must contain at least one track" }
        if (group.size == 1) return group.first()

        return group.maxByOrNull { scoreTrackForCanonical(context, it) } ?: group.first()
    }

    private fun scoreTrackForCanonical(context: Context?, track: TrackEntity): Long {
        var score = 0L

        // 1. Duration > 1 second is paramount (eliminates 0:01 dummy duration ghost entries)
        if (track.durationSeconds > 1) {
            score += 1_000_000L
        }

        // 2. Playability
        when (track.playabilityStatus) {
            "PLAYABLE", "REPAIRED" -> score += 100_000L
            "UNKNOWN" -> score += 50_000L
            else -> score += 0L
        }

        // Direct path read check if context is provided
        if (context != null && CanonicalStorageHelper.isReferenceReadable(context, track.filePath)) {
            score += 40_000L
        }

        // 3. Analysis status & audio intelligence
        if (track.analysisState == "COMPLETE") {
            score += 50_000L
        }
        if (track.bpm > 0.0) {
            score += 20_000L
        }
        if (track.camelotKey.isNotBlank()) {
            score += 20_000L
        }
        if (track.hotCuesString.isNotBlank() && track.hotCuesString != "0,32,64,128") {
            score += 10_000L
        }

        // 4. Metadata quality
        if (track.userConfirmedMetadata) {
            score += 50_000L
        }
        if (track.metadataScanState in listOf("COMPLETE", "VERIFIED", "USER_CONFIRMED", "APPROVED", "APPLIED", "IDENTIFIED")) {
            score += 30_000L
        }
        if (track.isAiTagged) {
            score += 10_000L
        }
        if (!track.artworkCachePath.isNullOrBlank() || !track.artworkUrl.isNullOrBlank()) {
            score += 10_000L
        }

        // 5. MediaStore presence
        if (track.mediaStoreId != null) {
            score += 5_000L
        }

        // 6. Tie breaker: prefer older library entry (preserves original dateAdded)
        score += ((Long.MAX_VALUE / 4) - track.dateAdded).coerceAtLeast(0L) % 1_000L

        return score
    }

    /**
     * Merges metadata and analysis fields from a duplicate track into the canonical track.
     */
    fun mergeTrackData(
        context: Context?,
        canonical: TrackEntity,
        duplicate: TrackEntity
    ): TrackEntity {
        // Duration: prefer valid duration > 1
        val finalDuration = when {
            canonical.durationSeconds > 1 -> canonical.durationSeconds
            duplicate.durationSeconds > 1 -> duplicate.durationSeconds
            canonical.durationSeconds > 0 -> canonical.durationSeconds
            duplicate.durationSeconds > 0 -> duplicate.durationSeconds
            else -> 0
        }

        // DJ Analysis (BPM, Key, cues)
        val finalBpm = if (canonical.bpm > 0.0) canonical.bpm else duplicate.bpm
        val finalBpmConf = if (canonical.bpm > 0.0) canonical.bpmConfidence else duplicate.bpmConfidence
        val finalBpmVersion = canonical.bpmAnalysisVersion ?: duplicate.bpmAnalysisVersion
        val finalBpmTime = canonical.bpmLastAnalyzed ?: duplicate.bpmLastAnalyzed

        val finalKey = if (canonical.camelotKey.isNotBlank()) canonical.musicalKey else duplicate.musicalKey
        val finalCamelot = if (canonical.camelotKey.isNotBlank()) canonical.camelotKey else duplicate.camelotKey
        val finalKeyConf = if (canonical.camelotKey.isNotBlank()) canonical.keyConfidence else duplicate.keyConfidence
        val finalKeyVersion = canonical.keyAnalysisVersion ?: duplicate.keyAnalysisVersion
        val finalKeyTime = canonical.keyLastAnalyzed ?: duplicate.keyLastAnalyzed

        val finalAnalysisState = if (canonical.analysisState == "COMPLETE") {
            canonical.analysisState
        } else if (duplicate.analysisState == "COMPLETE") {
            duplicate.analysisState
        } else {
            canonical.analysisState
        }
        val finalLastAnalysedAt = canonical.lastAnalysedAt ?: duplicate.lastAnalysedAt

        // Metadata & Artwork
        val finalUserConfirmed = canonical.userConfirmedMetadata || duplicate.userConfirmedMetadata
        val finalTitle = when {
            canonical.userConfirmedMetadata -> canonical.title
            duplicate.userConfirmedMetadata -> duplicate.title
            canonical.title.isNotBlank() && canonical.title != "<unknown>" && !canonical.title.startsWith("Track ") -> canonical.title
            duplicate.title.isNotBlank() && duplicate.title != "<unknown>" && !duplicate.title.startsWith("Track ") -> duplicate.title
            else -> canonical.title
        }

        val finalArtist = when {
            canonical.userConfirmedMetadata -> canonical.artist
            duplicate.userConfirmedMetadata -> duplicate.artist
            canonical.artist.isNotBlank() && canonical.artist != "<unknown>" && canonical.artist != "Unknown Artist" -> canonical.artist
            duplicate.artist.isNotBlank() && duplicate.artist != "<unknown>" && duplicate.artist != "Unknown Artist" -> duplicate.artist
            else -> canonical.artist
        }

        val finalAlbum = when {
            canonical.userConfirmedMetadata -> canonical.album
            duplicate.userConfirmedMetadata -> duplicate.album
            canonical.album.isNotBlank() && canonical.album != "<unknown>" && canonical.album != "Single" -> canonical.album
            duplicate.album.isNotBlank() && duplicate.album != "<unknown>" && duplicate.album != "Single" -> duplicate.album
            else -> canonical.album
        }

        val finalArtworkCache = canonical.artworkCachePath ?: duplicate.artworkCachePath
        val finalArtworkUrl = canonical.artworkUrl ?: duplicate.artworkUrl
        val finalArtworkSource = canonical.artworkSource ?: duplicate.artworkSource

        // MediaStore identity
        val finalMsId = canonical.mediaStoreId ?: duplicate.mediaStoreId
        val finalMsVolume = canonical.mediaStoreVolume ?: duplicate.mediaStoreVolume
        val finalPhysicalKey = when {
            canonical.physicalMediaKey.isNotBlank() -> canonical.physicalMediaKey
            duplicate.physicalMediaKey.isNotBlank() -> duplicate.physicalMediaKey
            else -> PhysicalMediaIdentifier.computePhysicalMediaKey(
                context,
                canonical.filePath,
                canonical.id,
                finalMsId,
                finalMsVolume
            )
        }

        // Cues & User rating/notes
        val finalCues = if (canonical.hotCuesString != "0,32,64,128" && canonical.hotCuesString.isNotBlank()) {
            canonical.hotCuesString
        } else if (duplicate.hotCuesString.isNotBlank()) {
            duplicate.hotCuesString
        } else {
            canonical.hotCuesString
        }

        val finalRating = if (canonical.rating > 0) canonical.rating else duplicate.rating
        val finalTags = if (canonical.customTags.isNotBlank()) canonical.customTags else duplicate.customTags
        val finalNotes = if (canonical.notes.isNotBlank()) canonical.notes else duplicate.notes

        // Playability & Resolved URI
        val finalPlayability = if (canonical.playabilityStatus == "PLAYABLE") {
            canonical.playabilityStatus
        } else if (duplicate.playabilityStatus == "PLAYABLE") {
            duplicate.playabilityStatus
        } else {
            canonical.playabilityStatus
        }

        val finalResolvedUri = canonical.resolvedUri ?: duplicate.resolvedUri

        return canonical.copy(
            durationSeconds = finalDuration,
            bpm = finalBpm,
            bpmConfidence = finalBpmConf,
            bpmAnalysisVersion = finalBpmVersion,
            bpmLastAnalyzed = finalBpmTime,
            musicalKey = finalKey,
            camelotKey = finalCamelot,
            keyConfidence = finalKeyConf,
            keyAnalysisVersion = finalKeyVersion,
            keyLastAnalyzed = finalKeyTime,
            analysisState = finalAnalysisState,
            lastAnalysedAt = finalLastAnalysedAt,
            userConfirmedMetadata = finalUserConfirmed,
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            artworkCachePath = finalArtworkCache,
            artworkUrl = finalArtworkUrl,
            artworkSource = finalArtworkSource,
            mediaStoreId = finalMsId,
            mediaStoreVolume = finalMsVolume,
            physicalMediaKey = finalPhysicalKey,
            hotCuesString = finalCues,
            rating = finalRating,
            customTags = finalTags,
            notes = finalNotes,
            playabilityStatus = finalPlayability,
            resolvedUri = finalResolvedUri
        )
    }
}
