package com.example.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.model.Track

/**
 * File writing boundary. Android does not provide a general-purpose, lossless tag
 * writer for every supported container, so unsupported SAF/container cases return
 * a clear failure rather than risking file corruption or dropping unrelated tags.
 */
sealed interface MetadataWriteResult {
    data object Written : MetadataWriteResult
    data class Unsupported(val reason: String) : MetadataWriteResult
    data class Failed(val reason: String) : MetadataWriteResult
}

class MetadataFileWriter(private val context: Context) {
    fun write(track: Track): MetadataWriteResult {
        val path = track.filePath
        if (path.startsWith("content://")) {
            return MetadataWriteResult.Unsupported(
                "Writing SAF content requires a format-preserving document provider."
            )
        }
        if (path.startsWith("demo://") || path.isBlank()) {
            return MetadataWriteResult.Unsupported("This track has no writable local file.")
        }
        return MetadataWriteResult.Unsupported(
            "A format-preserving tag writer is not installed for ${track.format} files."
        )
    }
}
