package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.StorageSource
import com.example.model.StorageSourceType

@Entity(tableName = "source_folders")
data class SourceFolderEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val path: String,
    val uriString: String,
    val typeName: String,
    val isOnline: Boolean,
    val trackCount: Int,
    val freeSpaceGb: Double,
    val totalSpaceGb: Double,
    val lastScanned: Long
) {
    fun toStorageSource(): StorageSource {
        val type = try { StorageSourceType.valueOf(typeName) } catch (e: Exception) { StorageSourceType.INTERNAL }
        return StorageSource(
            id = id,
            type = type,
            label = label,
            path = path,
            isOnline = isOnline,
            trackCount = trackCount,
            freeSpaceGb = freeSpaceGb,
            totalSpaceGb = totalSpaceGb,
            lastScanned = lastScanned
        )
    }

    companion object {
        fun fromStorageSource(source: StorageSource, uriString: String = ""): SourceFolderEntity {
            return SourceFolderEntity(
                id = source.id,
                label = source.label,
                path = source.path,
                uriString = uriString,
                typeName = source.type.name,
                isOnline = source.isOnline,
                trackCount = source.trackCount,
                freeSpaceGb = source.freeSpaceGb,
                totalSpaceGb = source.totalSpaceGb,
                lastScanned = source.lastScanned
            )
        }
    }
}
