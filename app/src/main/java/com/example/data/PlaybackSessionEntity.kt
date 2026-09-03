package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_sessions",
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["startedAt"]),
        Index(value = ["completed"]),
        Index(value = ["skipped"])
    ]
)
data class PlaybackSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedDurationMs: Long,
    val trackDurationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val playbackContext: String = "LIBRARY", // LIBRARY, PLAYLIST, SEARCH, SHUFFLE, QUEUE, ALBUM, ARTIST, NOW_PLAYING
    val playlistId: String? = null
)
