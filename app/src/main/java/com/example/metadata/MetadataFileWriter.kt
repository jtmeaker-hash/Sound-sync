package com.example.metadata

import android.content.Context
import com.example.model.Track
import com.example.storage.AudioTagWriter
import kotlinx.coroutines.runBlocking

/**
 * File writing boundary. Safely writes confirmed tags to local audio files
 * using format-preserving ID3 atomic tag writers.
 */
sealed interface MetadataWriteResult {
    data object Written : MetadataWriteResult
    data class Unsupported(val reason: String) : MetadataWriteResult
    data class Failed(val reason: String) : MetadataWriteResult
}

class MetadataFileWriter(private val context: Context) {
    suspend fun writeAsync(track: Track): MetadataWriteResult {
        val path = track.filePath
        if (path.startsWith("content://")) {
            return MetadataWriteResult.Unsupported(
                "Writing SAF content requires a format-preserving document provider."
            )
        }
        if (path.startsWith("demo://") || path.isBlank()) {
            return MetadataWriteResult.Unsupported("This track has no writable local file.")
        }
        if (track.format.equals("MP3", ignoreCase = true)) {
            val success = AudioTagWriter.writeConfirmedBpmAndKey(
                context,
                track.filePath,
                track.bpm,
                track.musicalKey
            )
            return if (success) MetadataWriteResult.Written else MetadataWriteResult.Failed("Could not write ID3 tags to MP3 file.")
        }
        return MetadataWriteResult.Unsupported(
            "A format-preserving tag writer is not installed for ${track.format} files."
        )
    }

    fun write(track: Track): MetadataWriteResult {
        return runBlocking { writeAsync(track) }
    }
}
