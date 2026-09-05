package com.example.metadata.review

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MetadataReviewItemEntity
import com.example.data.TrackEntity
import com.example.metadata.MetadataFileWriter
import com.example.metadata.history.MetadataHistoryManager
import com.example.model.MetadataScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class MetadataReviewManager(
    private val context: Context,
    private val database: AppDatabase,
    private val historyManager: MetadataHistoryManager = MetadataHistoryManager(context, database)
) {
    private val inboxDao = database.metadataReviewInboxDao()
    private val trackDao = database.trackDao()
    private val fileWriter = MetadataFileWriter(context)

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
        evidenceSummary: String
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
            timestamp = System.currentTimeMillis()
        )
        inboxDao.insertItem(item)
        Log.i(TAG, "Submitted track ${track.id} to metadata review inbox (confidence=${confidenceScore}%)")
        id
    }

    suspend fun acceptAllProposed(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = inboxDao.getItemById(itemId) ?: return@withContext false
        val track = trackDao.getTrackById(item.trackId) ?: return@withContext false

        // Record history for changes
        historyManager.recordChange(track.id, track.filePath, "title", track.title, item.proposedTitle, item.provider, false)
        historyManager.recordChange(track.id, track.filePath, "artist", track.artist, item.proposedArtist, item.provider, false)
        historyManager.recordChange(track.id, track.filePath, "album", track.album, item.proposedAlbum, item.provider, false)
        if (item.proposedGenre != null) {
            historyManager.recordChange(track.id, track.filePath, "genre", track.genre, item.proposedGenre, item.provider, false)
        }
        if (item.proposedYear != null) {
            historyManager.recordChange(track.id, track.filePath, "year", track.releaseYear?.toString(), item.proposedYear.toString(), item.provider, false)
        }

        val updated = track.copy(
            title = item.proposedTitle,
            artist = item.proposedArtist,
            album = item.proposedAlbum,
            genre = item.proposedGenre ?: track.genre,
            releaseYear = item.proposedYear ?: track.releaseYear,
            trackNumber = item.proposedTrackNumber ?: track.trackNumber,
            artworkUrl = item.proposedArtworkUrl ?: track.artworkUrl,
            metadataSource = item.provider,
            metadataConfidence = item.confidenceScore,
            metadataScanState = MetadataScanState.USER_CONFIRMED.name,
            userConfirmedMetadata = true
        )
        trackDao.updateTrack(updated)
        inboxDao.updateStatus(itemId, "ACCEPTED")

        try {
            fileWriter.writeAsync(updated.toTrack())
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing tags to file: ${e.message}")
        }

        Log.i(TAG, "Accepted all proposed metadata for item $itemId (track ${track.id})")
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
        try {
            fileWriter.writeAsync(updated.toTrack())
        } catch (e: Exception) {}
        true
    }

    suspend fun rejectProposal(itemId: String): Boolean = withContext(Dispatchers.IO) {
        inboxDao.updateStatus(itemId, "REJECTED")
        true
    }

    suspend fun ignoreTrack(itemId: String): Boolean = withContext(Dispatchers.IO) {
        inboxDao.updateStatus(itemId, "IGNORED")
        true
    }

    suspend fun bulkAcceptHighConfidence(minConfidence: Double = 80.0): Int = withContext(Dispatchers.IO) {
        val pending = inboxDao.getPendingItems()
        val eligible = pending.filter { it.confidenceScore >= minConfidence }
        var acceptedCount = 0
        for (item in eligible) {
            if (acceptAllProposed(item.id)) {
                acceptedCount++
            }
        }
        acceptedCount
    }

    fun observePendingItems(): Flow<List<MetadataReviewItemEntity>> {
        return inboxDao.observePendingItems()
    }

    fun observePendingCount(): Flow<Int> {
        return inboxDao.observePendingCount()
    }

    suspend fun getPendingItems(): List<MetadataReviewItemEntity> {
        return inboxDao.getPendingItems()
    }
}
