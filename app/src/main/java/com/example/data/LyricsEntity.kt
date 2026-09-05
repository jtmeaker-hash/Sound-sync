package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lyrics",
    indices = [
        Index(value = ["trackId"], unique = true),
        Index(value = ["source"]),
        Index(value = ["isUserEdited"]),
        Index(value = ["updatedAt"])
    ]
)
data class LyricsEntity(
    @PrimaryKey
    @ColumnInfo(name = "trackId")
    val trackId: String,

    @ColumnInfo(name = "plainLyrics")
    val plainLyrics: String = "",

    @ColumnInfo(name = "syncedLyricsJson")
    val syncedLyricsJson: String = "", // JSON array of LyricLine objects

    @ColumnInfo(name = "isSynced")
    val isSynced: Boolean = false,

    @ColumnInfo(name = "isUserEdited")
    val isUserEdited: Boolean = false,

    @ColumnInfo(name = "source")
    val source: String = "none", // user, embedded_synced, local_lrc, embedded_unsynced, cached_online, online_fetch, none

    @ColumnInfo(name = "offsetMs")
    val offsetMs: Long = 0L,

    @ColumnInfo(name = "remoteLyricsId")
    val remoteLyricsId: String? = null,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
)
