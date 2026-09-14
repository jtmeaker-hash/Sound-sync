package com.example.metadata.review

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MetadataReviewItemEntity
import com.example.data.TrackEntity
import com.example.metadata.MetadataSettingsStore
import com.example.metadata.backup.MetadataBackupManager
import com.example.metadata.history.MetadataHistoryManager
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.MetadataFileWriteQueue
import com.example.metadata.MetadataWriteResult
import com.example.model.MetadataScanState
import com.example.model.MetadataWriteState
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MetadataReviewManager(
    private val context: Context,
    private val database: AppDatabase,
    private val historyManager: MetadataHistoryManager = MetadataHistoryManager(context, database),
    private val backupManager: MetadataBackupManager = MetadataBackupManager(context, database, historyManager),
    private val settingsStore: MetadataSettingsStore = MetadataSettingsStore(context)
) {
    private val inboxDao = database.metadataReviewInboxDao()
    private val trackDao = database.trackDao()

    companion object {
        private const val TAG = "MetadataReviewManager"
    }

    suspend fun submitForReview(
        track: TrackEntity,
        proposedArtist: String,
        proposedTitle: String,
        proposedAlbum: String,
        proposedGenre: String? = null,
        proposedYear: Int? = null,
        proposedTrackNumber: Int? = null,
        proposedArtworkUrl: String? = null,
        provider: String,
        confidenceScore: Double,
        evidenceSummary: String,
        matchStatus: MetadataScanState = MetadataScanState.REVIEW_REQUIRED,
        candidatesJson: String? = null,
        originalArtworkUrl: String? = track.artworkUrl,
        artworkCachePath: String? = null,
        originalMetadataBackupJson: String? = null
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val item = MetadataReviewItemEntity(
            id = id,
            trackId = track.id,
            filePath = track.filePath,
            originalArtist = track.artist,
            originalTitle = track.title,
            originalAlbum = track.album,
            proposedArtist = proposedArtist,
            proposedTitle = proposedTitle,
            proposedAlbum = proposedAlbum,
            proposedGenre = proposedGenre,
            proposedYear = proposedYear,
            proposedTrackNumber = proposedTrackNumber,
            proposedArtworkUrl = proposedArtworkUrl,
            provider = provider,
            confidenceScore = confidenceScore,
            evidenceSummary = evidenceSummary,
            status = "PENDING",
            timestamp = System.currentTimeMillis(),
            originalArtworkUrl = originalArtworkUrl,
            artworkCachePath = artworkCachePath,
            matchStatus = matchStatus.name,
            candidatesJson = candidatesJson,
            originalMetadataBackupJson = originalMetadataBackupJson
        )
        inboxDao.insertItem(item)
        Log.i(TAG, "Submitted track ${track.id} to metadata review inbox (status=$matchStatus, confidence=${confidenceScore}%)")
        id
    }

    /**
     * Submits or merges a manual cover art modification into the MD Approval queue.
     * Merges with any existing pending metadata changes for the same track to prevent duplicates.
     */
    suspend fun submitManualArtworkChange(
        track: Track,
        newArtworkCachePath: String,
        newArtworkUrl: String,
        oldArtworkPathOrUrl: String?,
        artworkMimeType: String = "image/jpeg"
    ): String = withContext(Dispatchers.IO) {
        val existingItem = inboxDao.getPendingItemForTrack(track.id)
        val id = if (existingItem != null) {
            val mergedEvidence = if (existingItem.evidenceSummary.contains("Manual Cover", ignoreCase = true)) {
                existingItem.evidenceSummary
            } else {
                "${existingItem.evidenceSummary} + Manual Cover"
            }
            val mergedItem = existingItem.copy(
                proposedArtworkUrl = newArtworkUrl,
                artworkCachePath = newArtworkCachePath,
                originalArtworkUrl = oldArtworkPathOrUrl ?: existingItem.originalArtworkUrl ?: track.artworkUrl ?: track.artworkCachePath,
                timestamp = System.currentTimeMillis(),
                evidenceSummary = mergedEvidence
            )
            inboxDao.insertItem(mergedItem)
            Log.i(TAG, "METADATA_APPROVAL_CREATED: Merged manual artwork into pending approval item ${existingItem.id} for track ${track.id}")
            existingItem.id
        } else {
            val newId = UUID.randomUUID().toString()
            val newItem = MetadataReviewItemEntity(
                id = newId,
                trackId = track.id,
                filePath = track.filePath,
                originalArtist = track.artist,
                originalTitle = track.title,
                originalAlbum = track.album,
                proposedArtist = track.artist,
                proposedTitle = track.title,
                proposedAlbum = track.album,
                proposedGenre = track.genre,
                proposedYear = track.releaseYear,
                proposedTrackNumber = track.trackNumber.takeIf { it > 0 },
                proposedArtworkUrl = newArtworkUrl,
                provider = "Manual Cover",
                confidenceScore = 100.0,
                evidenceSummary = "Manual cover selected in Track Inspector",
                status = "PENDING",
                timestamp = System.currentTimeMillis(),
                originalArtworkUrl = oldArtworkPathOrUrl ?: track.artworkUrl ?: track.artworkCachePath,
                artworkCachePath = newArtworkCachePath,
                matchStatus = "REVIEW_REQUIRED"
            )
            inboxDao.insertItem(newItem)
            Log.i(TAG, "METADATA_APPROVAL_CREATED: Created pending approval item $newId for track ${track.id} (Manual Cover)")
            newId
        }

        try {
            trackDao.updateMetadataWriteState(track.id, MetadataWriteState.PENDING_APPROVAL.name)
        } catch (_: Throwable) {}
        id
    }

    suspend fun acceptAllProposed(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId) ?: return@withContext false
        val track = trackDao.getTrackById(item.trackId) ?: return@withContext false
        val settings = settingsStore.load()

        val finalTitle = item.proposedTitle.takeIf { it.isNotBlank() } ?: track.title
        val finalArtist = item.proposedArtist.takeIf { it.isNotBlank() } ?: track.artist
        val shouldReplaceArtwork = settings.replaceExistingArtwork || track.artworkUrl.isNullOrBlank() || item.provider == "Manual Cover"
        val finalArtworkUrl = if (shouldReplaceArtwork) (item.proposedArtworkUrl ?: track.artworkUrl) else track.artworkUrl
        val finalArtworkCachePath = if (shouldReplaceArtwork) (item.artworkCachePath ?: track.artworkCachePath) else track.artworkCachePath

        // 1. Transactional Pre-Write Backup (Sections 10 & 11)
        if (settings.keepOriginalMetadataBackup) {
            backupManager.savePreWriteBackup(track)
        }

        // Record history for changes
        if (finalTitle != track.title) {
            historyManager.recordChange(track.id, track.filePath, "title", track.title, finalTitle, item.provider, false)
        }
        if (finalArtist != track.artist) {
            historyManager.recordChange(track.id, track.filePath, "artist", track.artist, finalArtist, item.provider, false)
        }
        if (item.proposedAlbum != track.album) {
            historyManager.recordChange(track.id, track.filePath, "album", track.album, item.proposedAlbum, item.provider, false)
        }
        if (item.proposedGenre != null && item.proposedGenre != track.genre) {
            historyManager.recordChange(track.id, track.filePath, "genre", track.genre, item.proposedGenre, item.provider, false)
        }
        if (item.proposedYear != null && item.proposedYear != track.releaseYear) {
            historyManager.recordChange(track.id, track.filePath, "year", track.releaseYear?.toString(), item.proposedYear.toString(), item.provider, false)
        }

        val updated = track.copy(
            title = finalTitle,
            artist = finalArtist,
            album = item.proposedAlbum,
            genre = item.proposedGenre ?: track.genre,
            releaseYear = item.proposedYear ?: track.releaseYear,
            trackNumber = item.proposedTrackNumber ?: track.trackNumber,
            artworkUrl = finalArtworkUrl,
            artworkCachePath = finalArtworkCachePath,
            metadataSource = item.provider,
            metadataConfidence = item.confidenceScore,
            metadataScanState = MetadataScanState.APPLIED.name,
            userConfirmedMetadata = true
        )

        // 2. Physical File Writing with Scoped Storage MediaStore Permission Handling
        val hasPhysicalFile = (File(track.filePath).exists() && File(track.filePath).isFile) || track.filePath.startsWith("content://")
        if (hasPhysicalFile) {
            val artworkBytes = item.artworkCachePath?.let { path ->
                try { File(path).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }
            }
            val writeQueue = try {
                MetadataFileWriteQueue.getInstance(context)
            } catch (_: Throwable) { null }

            val writeResult = writeQueue?.writeDirect(
                track = updated.toTrack(),
                artworkBytes = artworkBytes
            )

            if (writeResult != null && writeResult !is MetadataWriteResult.Written && writeResult !is MetadataWriteResult.AlreadyInSync && writeResult !is MetadataWriteResult.Partial) {
                Log.e(TAG, "ARTWORK_WRITE_FAILED: Physical file write failed for item $itemId: $writeResult")
                return@withContext false
            }
        }

        val finalWriteState = if (item.provider == "Manual Cover" || item.artworkCachePath != null) {
            MetadataWriteState.ARTWORK_SAVED.name
        } else {
            MetadataWriteState.FILE_WRITE_SUCCESS.name
        }
        val finalTrackToSave = updated.copy(metadataWriteState = finalWriteState)
        trackDao.updateTrack(finalTrackToSave)
        inboxDao.updateStatus(itemId, "ACCEPTED")
        com.example.util.AlbumArtHelper.invalidateTrack(finalTrackToSave.id, finalTrackToSave.artist, finalTrackToSave.album)

        Log.i(TAG, "Approved and applied metadata for item $itemId (track ${track.id})")
        true
    }

    suspend fun acceptSpecificField(itemId: String, fieldName: String): Boolean = withContext(Dispatchers.IO) {
        acceptSelectedFields(itemId, setOf(fieldName))
    }

    /**
     * Applies only the explicitly selected fields from the proposed candidate (Upgrade 26).
     * Protected by pre-write backup, history recording, and transactional file rollback.
     */
    suspend fun acceptSelectedFields(itemId: String, fieldNames: Set<String>): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId) ?: return@withContext false
        val track = trackDao.getTrackById(item.trackId) ?: return@withContext false
        val settings = settingsStore.load()

        if (settings.keepOriginalMetadataBackup) {
            backupManager.savePreWriteBackup(track)
        }

        var updated = track
        val provMap = com.example.metadata.merge.TrackFieldProvenance.parse(track.fieldProvenanceJson).toMutableMap()

        for (field in fieldNames) {
            when (field.lowercase()) {
                "title" -> {
                    if (item.proposedTitle.isNotBlank()) {
                        historyManager.recordChange(track.id, track.filePath, "title", track.title, item.proposedTitle, item.provider, false)
                        updated = updated.copy(title = item.proposedTitle)
                        provMap["title"] = com.example.metadata.merge.MetadataSourceProvenance.USER_EDIT
                    }
                }
                "artist" -> {
                    if (item.proposedArtist.isNotBlank()) {
                        historyManager.recordChange(track.id, track.filePath, "artist", track.artist, item.proposedArtist, item.provider, false)
                        updated = updated.copy(artist = item.proposedArtist)
                        provMap["artist"] = com.example.metadata.merge.MetadataSourceProvenance.USER_EDIT
                    }
                }
                "album" -> {
                    if (item.proposedAlbum.isNotBlank()) {
                        historyManager.recordChange(track.id, track.filePath, "album", track.album, item.proposedAlbum, item.provider, false)
                        updated = updated.copy(album = item.proposedAlbum)
                        provMap["album"] = com.example.metadata.merge.MetadataSourceProvenance.USER_EDIT
                    }
                }
                "genre" -> {
                    item.proposedGenre?.let {
                        historyManager.recordChange(track.id, track.filePath, "genre", track.genre, it, item.provider, false)
                        updated = updated.copy(genre = it)
                        provMap["genre"] = com.example.metadata.merge.MetadataSourceProvenance.USER_EDIT
                    }
                }
                "year" -> {
                    item.proposedYear?.let {
                        historyManager.recordChange(track.id, track.filePath, "year", track.releaseYear?.toString(), it.toString(), item.provider, false)
                        updated = updated.copy(releaseYear = it)
                        provMap["year"] = com.example.metadata.merge.MetadataSourceProvenance.USER_EDIT
                    }
                }
                "artwork" -> {
                    val artUrl = item.artworkCachePath ?: item.proposedArtworkUrl
                    if (!artUrl.isNullOrBlank()) {
                        updated = updated.copy(
                            artworkUrl = item.proposedArtworkUrl ?: updated.artworkUrl,
                            artworkCachePath = item.artworkCachePath ?: updated.artworkCachePath,
                            artworkSource = item.provider
                        )
                        provMap["artwork"] = com.example.metadata.merge.MetadataSourceProvenance.USER_EDIT
                    }
                }
            }
        }

        val finalTrack = updated.copy(
            fieldProvenanceJson = com.example.metadata.merge.TrackFieldProvenance.toJson(provMap),
            metadataScanState = MetadataScanState.APPLIED.name,
            userConfirmedMetadata = true
        )
        // Physical File Writing with Scoped Storage MediaStore Permission Handling
        val hasPhysicalFile = (File(finalTrack.filePath).exists() && File(finalTrack.filePath).isFile) || finalTrack.filePath.startsWith("content://")
        if (hasPhysicalFile) {
            val artworkBytes = if (fieldNames.contains("artwork")) {
                item.artworkCachePath?.let { path ->
                    try { File(path).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }
                }
            } else null

            val writeQueue = try {
                MetadataFileWriteQueue.getInstance(context)
            } catch (_: Throwable) { null }

            val writeResult = writeQueue?.writeDirect(
                track = finalTrack.toTrack(),
                artworkBytes = artworkBytes
            )

            if (writeResult != null && writeResult !is MetadataWriteResult.Written && writeResult !is MetadataWriteResult.AlreadyInSync && writeResult !is MetadataWriteResult.Partial) {
                Log.e(TAG, "ARTWORK_WRITE_FAILED: Physical file write failed for item $itemId with fields $fieldNames: $writeResult")
                return@withContext false
            }
        }

        val finalWriteState = if (fieldNames.contains("artwork") || item.provider == "Manual Cover") {
            MetadataWriteState.ARTWORK_SAVED.name
        } else {
            finalTrack.metadataWriteState
        }
        val finalTrackToSave = finalTrack.copy(metadataWriteState = finalWriteState)
        trackDao.updateTrack(finalTrackToSave)
        inboxDao.updateStatus(itemId, "ACCEPTED")
        com.example.util.AlbumArtHelper.invalidateTrack(finalTrackToSave.id, finalTrackToSave.artist, finalTrackToSave.album)

        Log.i(TAG, "Accepted selected fields ($fieldNames) for item $itemId (track ${track.id})")
        true
    }

    /**
     * Explicitly dismisses the candidate proposal and confirms the existing local metadata (Upgrade 26).
     */
    suspend fun keepLocal(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId) ?: return@withContext false
        val track = trackDao.getTrackById(item.trackId)
        if (track != null) {
            val provMap = com.example.metadata.merge.TrackFieldProvenance.parse(track.fieldProvenanceJson).toMutableMap()
            provMap["title"] = com.example.metadata.merge.LocalFirstMetadataMerger.inferFieldProvenance(track.toTrack(), "title")
            provMap["artist"] = com.example.metadata.merge.LocalFirstMetadataMerger.inferFieldProvenance(track.toTrack(), "artist")
            trackDao.updateTrack(
                track.copy(
                    metadataScanState = MetadataScanState.APPROVED.name,
                    userConfirmedMetadata = true,
                    fieldProvenanceJson = com.example.metadata.merge.TrackFieldProvenance.toJson(provMap)
                )
            )
        }
        inboxDao.updateStatus(itemId, "KEPT_LOCAL")
        Log.i(TAG, "Dismissed proposal and kept local metadata for item $itemId (track ${item.trackId})")
        true
    }

    suspend fun rejectProposal(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId)
        if (item != null) {
            val track = trackDao.getTrackById(item.trackId)
            if (track != null) {
                trackDao.updateTrack(track.copy(metadataScanState = MetadataScanState.REJECTED.name))
            }
        }
        inboxDao.updateStatus(itemId, "REJECTED")
        true
    }

    suspend fun ignoreTrack(itemId: String): Boolean = withContext(Dispatchers.IO) {
        inboxDao.updateStatus(itemId, "IGNORED")
        true
    }

    /**
     * Approves ONLY items that SoundSync has classified as VERIFIED (Section 7).
     * Items requiring review are never approved by this method.
     */
    suspend fun approveAllVerified(): Int = withContext(Dispatchers.IO) {
        val verifiedItems = inboxDao.getPendingVerifiedItems()
        var count = 0
        for (item in verifiedItems) {
            if (acceptAllProposed(item.id)) {
                count++
            }
        }
        Log.i(TAG, "Approved all verified items ($count applied)")
        count
    }

    suspend fun approveMultiple(itemIds: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (id in itemIds) {
            if (acceptAllProposed(id)) {
                count++
            }
        }
        count
    }

    suspend fun rejectMultiple(itemIds: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (id in itemIds) {
            if (rejectProposal(id)) {
                count++
            }
        }
        count
    }

    suspend fun restoreTrack(trackId: String): Boolean = withContext(Dispatchers.IO) {
        backupManager.restoreTrack(trackId)
    }

    suspend fun restoreMultiple(trackIds: List<String>): Int = withContext(Dispatchers.IO) {
        backupManager.restoreTracks(trackIds)
    }

    suspend fun restoreAll(): Int = withContext(Dispatchers.IO) {
        backupManager.restoreAll()
    }

    suspend fun bulkAcceptHighConfidence(minConfidence: Double = 95.0): Int = withContext(Dispatchers.IO) {
        approveAllVerified()
    }

    fun observePendingItems(): Flow<List<MetadataReviewItemEntity>> {
        return inboxDao.observePendingItems()
    }

    fun observePendingCount(): Flow<Int> {
        return inboxDao.observePendingCount()
    }

    fun observeModifiedTracksCount(): Flow<Int> {
        return backupManager.observeModifiedTracksCount()
    }

    suspend fun getPendingItems(): List<MetadataReviewItemEntity> {
        return inboxDao.getPendingItems()
    }
}
