package com.example.metadata.history

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MetadataHistoryEntity
import com.example.data.TrackEntity
import com.example.metadata.MetadataFileWriter
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class MetadataHistoryManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private val historyDao = database.metadataHistoryDao()
    private val trackDao = database.trackDao()
    private val fileWriter = MetadataFileWriter(context)

    companion object {
        private const val TAG = "MetadataHistoryManager"
    }

    suspend fun recordChange(
        trackId: String,
        filePath: String,
        fieldChanged: String,
        previousValue: String?,
        newValue: String?,
        source: String,
        isAutomatic: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (previousValue == newValue) return@withContext

        val entry = MetadataHistoryEntity(
            id = UUID.randomUUID().toString(),
            trackId = trackId,
            filePath = filePath,
            fieldChanged = fieldChanged,
            previousValue = previousValue,
            newValue = newValue,
            source = source,
            timestamp = System.currentTimeMillis(),
            isAutomatic = isAutomatic
        )
        historyDao.insertHistory(entry)
        historyDao.pruneOldEntries(5000)
    }

    suspend fun recordBatchChanges(entries: List<MetadataHistoryEntity>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        historyDao.insertHistories(entries)
        historyDao.pruneOldEntries(5000)
    }

    suspend fun undoLastChange(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val historyList = historyDao.getHistoryForTrack(trackId)
        val latest = historyList.firstOrNull() ?: return@withContext false
        undoEntry(latest)
    }

    suspend fun undoEntry(entry: MetadataHistoryEntity): Boolean = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(entry.trackId) ?: return@withContext false
        val currentTrackModel = track.toTrack()

        val revertedTrack = revertFieldOnTrack(track, entry.fieldChanged, entry.previousValue)
        trackDao.updateTrack(revertedTrack)

        // Record the undo itself in history
        val undoEntry = MetadataHistoryEntity(
            id = UUID.randomUUID().toString(),
            trackId = entry.trackId,
            filePath = entry.filePath,
            fieldChanged = entry.fieldChanged,
            previousValue = entry.newValue,
            newValue = entry.previousValue,
            source = "UNDO",
            timestamp = System.currentTimeMillis(),
            isAutomatic = false
        )
        historyDao.insertHistory(undoEntry)

        // Also attempt write to file if supported
        try {
            fileWriter.writeAsync(revertedTrack.toTrack())
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating file tag on undo: ${e.message}")
        }

        Log.i(TAG, "Reverted ${entry.fieldChanged} on track ${entry.trackId} from '${entry.newValue}' to '${entry.previousValue}'")
        true
    }

    suspend fun undoBulk(historyIds: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (id in historyIds) {
            val entries = historyDao.getRecentHistory(500)
            val entry = entries.find { it.id == id }
            if (entry != null && undoEntry(entry)) {
                count++
            }
        }
        count
    }

    fun observeHistoryForTrack(trackId: String): Flow<List<MetadataHistoryEntity>> {
        return historyDao.observeHistoryForTrack(trackId)
    }

    fun observeRecentHistory(limit: Int = 100): Flow<List<MetadataHistoryEntity>> {
        return historyDao.observeRecentHistory(limit)
    }

    suspend fun getHistoryForTrack(trackId: String): List<MetadataHistoryEntity> {
        return historyDao.getHistoryForTrack(trackId)
    }

    private fun revertFieldOnTrack(
        entity: TrackEntity,
        field: String,
        targetValue: String?
    ): TrackEntity {
        return when (field.lowercase()) {
            "title" -> entity.copy(title = targetValue ?: entity.title)
            "artist" -> entity.copy(artist = targetValue ?: entity.artist)
            "album" -> entity.copy(album = targetValue ?: entity.album)
            "albumartist" -> entity.copy(albumArtist = targetValue ?: entity.albumArtist)
            "genre" -> entity.copy(genre = targetValue ?: entity.genre)
            "year", "releaseyear" -> entity.copy(releaseYear = targetValue?.toIntOrNull())
            "tracknumber" -> entity.copy(trackNumber = targetValue?.toIntOrNull() ?: 0)
            "discnumber" -> entity.copy(discNumber = targetValue?.toIntOrNull() ?: 1)
            "bpm" -> entity.copy(bpm = targetValue?.toDoubleOrNull() ?: 0.0)
            "musicalkey" -> entity.copy(musicalKey = targetValue ?: "")
            "camelotkey" -> entity.copy(camelotKey = targetValue ?: "")
            "artworkurl" -> entity.copy(artworkUrl = targetValue)
            "artworkcachepath" -> entity.copy(artworkCachePath = targetValue)
            "notes" -> entity.copy(notes = targetValue ?: "")
            "composer" -> entity.copy(composer = targetValue ?: "")
            "customtags" -> entity.copy(customTags = targetValue ?: "")
            "rating" -> entity.copy(rating = targetValue?.toIntOrNull() ?: 0)
            else -> entity
        }
    }
}
