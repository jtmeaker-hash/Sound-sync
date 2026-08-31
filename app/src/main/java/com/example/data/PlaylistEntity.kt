package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.Playlist

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceId: String? = null,
    val backingFileUri: String? = null,
    val backingRelativePath: String? = null,
    val isRockboxCompatible: Boolean = true,
    val isImported: Boolean = false
) {
    fun toPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            sourceId = sourceId,
            backingFileUri = backingFileUri,
            backingRelativePath = backingRelativePath,
            isRockboxCompatible = isRockboxCompatible,
            isImported = isImported
        )
    }

    companion object {
        fun fromPlaylist(playlist: Playlist): PlaylistEntity {
            return PlaylistEntity(
                id = playlist.id,
                name = playlist.name,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                sourceId = playlist.sourceId,
                backingFileUri = playlist.backingFileUri,
                backingRelativePath = playlist.backingRelativePath,
                isRockboxCompatible = playlist.isRockboxCompatible,
                isImported = playlist.isImported
            )
        }
    }
}
