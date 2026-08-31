package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlist_tracks",
    indices = [Index(value = ["playlistId", "position"])]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val dateAdded: Long = System.currentTimeMillis()
)
