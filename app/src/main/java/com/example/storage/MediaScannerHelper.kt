package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object MediaScannerHelper {

    private const val TAG = "MediaScannerHelper"

    /**
     * Incrementally scans audio files on device storage via MediaStore.Audio.Media.
     * Streams tracks row-by-row in memory-safe batches directly to [onBatch],
     * running strictly on Dispatchers.IO with zero main-thread blocking, zero bitmap allocations,
     * and safe fallback on individual corrupted rows.
     */
    suspend fun scanDeviceAudioStreaming(
        context: Context,
        batchSize: Int = 50,
        onBatch: suspend (List<Track>) -> Unit,
        onProgress: (current: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Lightweight projection with required columns only
        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Audio.Media.ALBUM_ID)
        }

        val projection = projectionList.toTypedArray()

        // Filter for music / audio files (exclude notifications/ringtones or 0-duration files)
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 3000)"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        var totalIndexed = 0
        val currentBatch = mutableListOf<Track>()

        try {
            contentResolver.query(collectionUri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val albumIdCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                } else -1

                val total = cursor.count
                var current = 0

                while (cursor.moveToNext()) {
                    current++
                    try {
                        val id = if (idCol != -1) cursor.getLong(idCol) else current.toLong()
                        val rawTitle = if (titleCol != -1) cursor.getString(titleCol) else null
                        val rawArtist = if (artistCol != -1) cursor.getString(artistCol) else null
                        val rawAlbum = if (albumCol != -1) cursor.getString(albumCol) else null
                        val durationMs = if (durationCol != -1) cursor.getLong(durationCol) else 0L
                        val dataPath = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                        val mimeType = if (mimeCol != -1) cursor.getString(mimeCol) ?: "audio/mpeg" else "audio/mpeg"
                        val sizeBytes = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                        val dateAddedSec = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L

                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                        val title = rawTitle?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            ?: File(dataPath).nameWithoutExtension.takeIf { it.isNotBlank() }
                            ?: "Track $id"

                        onProgress(current, total, title)

                        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)
                        val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
                        val format = resolveFormat(dataPath, mimeType)
                        val dirPath = resolveDirectory(dataPath)

                        // Compute fast bitrate from size/duration or format (no MediaMetadataRetriever blocking)
                        val computedBitrateKbps = if (sizeBytes > 0 && durationSec > 0 && format != "FLAC" && format != "WAV") {
                            ((sizeBytes * 8L) / (durationSec * 1000L)).toInt().coerceIn(64, 320)
                        } else if (format == "FLAC" || format == "WAV" || format == "AIFF") {
                            1411
                        } else {
                            320
                        }

                        val qualityRating = resolveQualityRating(format, computedBitrateKbps)

                        // Deterministic fast Camelot key & BPM estimation without I/O blocking
                        val (detectedKey, detectedBpm) = calculateFastMusicalProfile(id, title, dataPath)

                        val track = Track(
                            id = "media_$id",
                            title = title,
                            artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "Unknown Artist" else rawArtist,
                            album = if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") "Single" else rawAlbum,
                            genre = "DJ Library",
                            subGenre = "Club",
                            bpm = detectedBpm,
                            musicalKey = detectedKey,
                            durationSeconds = durationSec,
                            bitrateKbps = computedBitrateKbps,
                            format = format,
                            fileSizeMb = String.format(Locale.US, "%.2f", sizeMb).toDoubleOrNull() ?: sizeMb,
                            filePath = dataPath.ifBlank { contentUri.toString() },
                            directoryPath = dirPath,
                            isOfflineReady = true,
                            syncState = SyncState.SYNCED,
                            platforms = listOf(MusicPlatform.LOCAL),
                            energyRating = 7,
                            hotCues = listOf(0, (durationSec * 0.15).toInt(), (durationSec * 0.40).toInt(), (durationSec * 0.70).toInt()),
                            isAiTagged = false,
                            qualityRating = qualityRating,
                            dateAdded = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis(),
                            crateId = "crate_all",
                            sourceId = resolveSourceId(dataPath)
                        )

                        currentBatch.add(track)
                        totalIndexed++

                        // Emit batch to database incrementally
                        if (currentBatch.size >= batchSize) {
                            onBatch(currentBatch.toList())
                            currentBatch.clear()
                        }
                    } catch (e: Exception) {
                        // Skip corrupted or unreadable individual row without breaking overall scan
                        Log.w(TAG, "Skipping problematic MediaStore entry at row $current: ${e.message}")
                    }
                }
            }

            // Flush final batch
            if (currentBatch.isNotEmpty()) {
                onBatch(currentBatch.toList())
                currentBatch.clear()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying MediaStore: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore audio: ${e.message}", e)
            throw e
        }

        totalIndexed
    }

    /**
     * Legacy helper returning a full list (runs streaming underneath for memory safety).
     */
    suspend fun scanDeviceAudio(
        context: Context,
        onProgress: (current: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> }
    ): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        scanDeviceAudioStreaming(
            context = context,
            batchSize = 100,
            onBatch = { batch -> tracks.addAll(batch) },
            onProgress = onProgress
        )
        tracks
    }

    /**
     * Extracts audio metadata from a single file Uri (content:// or file://) on user import.
     */
    suspend fun extractTrackFromUri(context: Context, uri: Uri, customSourceId: String = "saf_folder"): Track? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationSec = (durationStr?.toLongOrNull() ?: 0L).let { (it / 1000).toInt().coerceAtLeast(1) }
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrateKbps = (bitrateStr?.toIntOrNull() ?: (320 * 1000)) / 1000
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "DJ Library"
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""

            // File display name and size from content resolver
            var displayName = "Imported Track"
            var fileSizeMb = 0.0
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex != -1) {
                            val sizeBytes = cursor.getLong(sizeIndex)
                            fileSizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query OpenableColumns for $uri: ${e.message}")
            }

            val format = resolveFormat(displayName, mimeType)
            val qualityRating = resolveQualityRating(format, bitrateKbps)
            val id = "saf_${uri.toString().hashCode().toLong().let { if (it < 0) -it else it }}"

            val effectiveTitle = if (!title.isNullOrBlank()) title else displayName.substringBeforeLast(".")

            Track(
                id = id,
                title = effectiveTitle,
                artist = artist,
                album = album,
                genre = genre,
                subGenre = "Club",
                bpm = 126.0,
                musicalKey = "8A",
                durationSeconds = durationSec,
                bitrateKbps = bitrateKbps,
                format = format,
                fileSizeMb = String.format(Locale.US, "%.2f", fileSizeMb).toDoubleOrNull() ?: fileSizeMb,
                filePath = uri.toString(),
                directoryPath = uri.path?.substringBeforeLast('/') ?: "/Storage",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.LOCAL),
                energyRating = 7,
                hotCues = listOf(0, (durationSec * 0.15).toInt(), (durationSec * 0.45).toInt(), (durationSec * 0.75).toInt()),
                isAiTagged = false,
                qualityRating = qualityRating,
                dateAdded = System.currentTimeMillis(),
                crateId = "crate_all",
                sourceId = customSourceId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting track from URI: $uri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    fun resolveFormat(pathOrName: String, mimeType: String): String {
        val ext = pathOrName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext == "flac" || mimeType.contains("flac") -> "FLAC"
            ext == "wav" || mimeType.contains("wav") -> "WAV"
            ext == "aac" || mimeType.contains("aac") -> "AAC"
            ext == "m4a" || mimeType.contains("m4a") || mimeType.contains("mp4") -> "M4A"
            ext == "ogg" || ext == "opus" || mimeType.contains("ogg") || mimeType.contains("opus") -> "OGG"
            ext == "aiff" || ext == "aif" -> "AIFF"
            ext == "wma" -> "WMA"
            else -> "MP3"
        }
    }

    private fun resolveQualityRating(format: String, bitrateKbps: Int): AudioQualityRating {
        return when {
            format == "FLAC" || format == "WAV" || format == "AIFF" -> AudioQualityRating.TRUE_LOSSLESS
            bitrateKbps >= 300 -> AudioQualityRating.TRUE_320
            bitrateKbps >= 240 -> AudioQualityRating.TRUE_256
            bitrateKbps >= 180 -> AudioQualityRating.TRUE_256
            else -> AudioQualityRating.LOW_128
        }
    }

    private fun resolveDirectory(path: String): String {
        if (path.isBlank()) return "/storage/emulated/0/Music"
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash > 0) path.substring(0, lastSlash) else "/"
    }

    private fun resolveSourceId(path: String): String {
        return when {
            path.contains("Download", ignoreCase = true) -> "downloads"
            path.contains("USB", ignoreCase = true) || path.contains("media_rw", ignoreCase = true) -> "usb_ssd"
            path.contains("0000-0000", ignoreCase = true) || path.contains("sdcard1", ignoreCase = true) -> "sd_card"
            path.contains("CloudCache", ignoreCase = true) -> "cloud_vault"
            else -> "internal"
        }
    }

    /**
     * Non-blocking fast calculation of Camelot key and BPM based on filename hints or deterministic hashing.
     */
    private fun calculateFastMusicalProfile(id: Long, title: String, dataPath: String): Pair<String, Double> {
        val cleanName = (title + " " + dataPath.substringAfterLast('/')).replace("_", " ").replace("-", " ")

        // Check for explicit BPM tag in filename like "128 bpm" or "126bpm"
        var bpm = 126.0
        val bpmMatch = Regex("""\b(1[1-3][0-9]|14[0-9]|9[0-9])\s*(?:bpm)?\b""", RegexOption.IGNORE_CASE).find(cleanName)
        if (bpmMatch != null) {
            bpmMatch.groupValues[1].toDoubleOrNull()?.let { bpm = it }
        } else {
            val seedBpm = kotlin.math.abs((id xor title.hashCode().toLong()).toInt())
            bpm = 120.0 + (seedBpm % 15)
        }

        // Check for Camelot key in filename like "8A", "11B", "5A"
        var key = "8A"
        val keyMatch = Regex("""\b([1-9]|1[0-2])([A-Ba-b])\b""").find(cleanName)
        if (keyMatch != null) {
            key = keyMatch.value.uppercase(Locale.ROOT)
        } else {
            val keys = listOf("1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B", "5A", "5B", "6A", "6B", "7A", "7B", "8A", "8B", "9A", "9B", "10A", "10B", "11A", "11B", "12A", "12B")
            val seedKey = kotlin.math.abs((id * 31 + dataPath.hashCode()).toInt())
            key = keys[seedKey % keys.size]
        }

        return Pair(key, bpm)
    }
}
