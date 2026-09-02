package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.SongFind

@Entity(
    tableName = "song_finds",
    indices = [
        Index(value = ["url"]),
        Index(value = ["createdAt"])
    ]
)
data class SongFindEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String,
    val sourceAppName: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false
) {
    fun toSongFind(): SongFind {
        return SongFind(
            id = id,
            url = url,
            title = title,
            sourceAppName = sourceAppName,
            notes = notes,
            createdAt = createdAt,
            isCompleted = isCompleted
        )
    }

    companion object {
        fun fromSongFind(find: SongFind): SongFindEntity {
            return SongFindEntity(
                id = find.id,
                url = find.url,
                title = find.title,
                sourceAppName = find.sourceAppName,
                notes = find.notes,
                createdAt = find.createdAt,
                isCompleted = find.isCompleted
            )
        }
    }
}
