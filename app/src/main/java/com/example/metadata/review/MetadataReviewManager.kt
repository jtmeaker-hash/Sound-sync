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
import com.example.model.MetadataScanState
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

    suspend fun acceptAllProposed(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId) ?: return@withContext false
        val track = trackDao.getTrackById(item.trackId) ?: return@withContext false
        val settings = settingsStore.load()

        val finalTitle = item.proposedTitle.takeIf { it.isNotBlank() } ?: track.title
        val finalArtist = item.proposedArtist.takeIf { it.isNotBlank() } ?: track.artist
        val shouldReplaceArtwork = settings.replaceExistingArtwork || track.artworkUrl.isNullOrBlank()
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
        trackDao.updateTrack(updated)
        inboxDao.updateStatus(itemId, "ACCEPTED")

        // 2. Physical File Writing with Transactional Rollback (Section 11)
        if (File(track.filePath).exists() && File(track.filePath).canWrite()) {
            val artworkBytes = item.artworkCachePath?.let { path ->
                try { File(path).readBytes() } catch (_: Exception) { null }
            }
            val writeResult = backupManager.writeWithTransactionalRollback(
                track = updated.toTrack(),
                artworkBytes = artworkBytes
            )
            Log.d(TAG, "Write result after approval for ${track.filePath}: $writeResult")
        }

        Log.i(TAG, "Approved and applied metadata for item $itemId (track ${track.id})")
        true
    }

    suspend fun acceptSpecificField(itemId: String, fieldName: String): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId) ?: return@withContext false
        val track = trackDao.getTrackById(item.trackId) ?: return@withContext false

        var updated = track
        when (fieldName.lowercase()) {
            "title" -> {
                historyManager.recordChange(track.id, track.filePath, "title", track.title, item.proposedTitle, item.provider, false)
                updated = updated.copy(title = item.proposedTitle)
            }
            "artist" -> {
                historyManager.recordChange(track.id, track.filePath, "artist", track.artist, item.proposedArtist, item.provider, false)
                updated = updated.copy(artist = item.proposedArtist)
            }
            "album" -> {
                historyManager.recordChange(track.id, track.filePath, "album", track.album, item.proposedAlbum, item.provider, false)
                updated = updated.copy(album = item.proposedAlbum)
            }
            "genre" -> {
                item.proposedGenre?.let {
                    historyManager.recordChange(track.id, track.filePath, "genre", track.genre, it, item.provider, false)
                    updated = updated.copy(genre = it)
                }
            }
            "year" -> {
                item.proposedYear?.let {
                    historyManager.recordChange(track.id, track.filePath, "year", track.releaseYear?.toString(), it.toString(), item.provider, false)
                    updated = updated.copy(releaseYear = it)
                }
            }
        }
        trackDao.updateTrack(updated)
        if (File(updated.filePath).exists() && File(updated.filePath).canWrite()) {
            backupManager.writeWithTransactionalRollback(updated.toTrack())
        }
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
