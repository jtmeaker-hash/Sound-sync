package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.analysis.TunebatMetadataService
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.ScanSummaryResult
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
     *
     * Skips tracks that already exist in [existingFingerprints] or [existingFilePaths].
     */
    suspend fun scanDeviceAudioStreaming(
        context: Context,
        existingFingerprints: Set<String> = emptySet(),
        existingFilePaths: Set<String> = emptySet(),
        batchSize: Int = 200,
        onBatch: suspend (List<Track>) -> Unit,
        onProgress: (current: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> }
    ): ScanSummaryResult = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver

        val collectionUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val volumeNames = MediaStore.getExternalVolumeNames(context)
                if (volumeNames.isNotEmpty()) {
                    volumeNames.map { MediaStore.Audio.Media.getContentUri(it) }
                } else {
                    listOf(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
                }
            } catch (_: Exception) {
                listOf(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            }
        } else {
            listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
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
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Audio.Media.ALBUM_ID)
        }

        val projection = projectionList.toTypedArray()

        // Filter for music / audio files (exclude notifications/ringtones or 0-duration files)
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 3000)"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val scanStartTime = System.currentTimeMillis()
        com.example.util.DjLogger.startTiming("MEDIA_SCAN_START", "Scanning MediaStore audio repository")
        Log.d(TAG, "Library scan started via MediaStore")

        var totalDiscovered = 0
        var totalImported = 0
        var totalSkipped = 0
        var totalFailed = 0
        val currentBatch = mutableListOf<Track>()

        val seenFingerprints = existingFingerprints.toMutableSet()
        val seenPaths = existingFilePaths.toMutableSet()

        try {
            for (collectionUri in collectionUris) {
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
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

                val total = cursor.count
                totalDiscovered = total
                Log.d(TAG, "MediaStore query completed: $total audio tracks found on storage")
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
                        val rawTrackNum = if (trackCol != -1) cursor.getInt(trackCol) else 0

                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val targetPath = dataPath.ifBlank { contentUri.toString() }

                        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)

                        // Generate stable content fingerprint
                        val fingerprint = AudioFingerprintUtil.generateFingerprint(
                            context = context,
                            uriOrPath = targetPath,
                            fileSizeBytes = sizeBytes,
                            durationSeconds = durationSec
                        )

                        // Check duplicate protection
                        if (seenFingerprints.contains(fingerprint) || seenPaths.contains(targetPath)) {
                            totalSkipped++
                            continue
                        }

                        val title = rawTitle?.takeIf { it.isNotBlank() && it != "<unknown>" }
                            ?: File(dataPath).nameWithoutExtension.takeIf { it.isNotBlank() }
                            ?: "Track $id"

                        onProgress(current, total, title)

                        val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
                        val format = resolveFormat(dataPath, mimeType)
                        val dirPath = resolveDirectory(dataPath)

                        // Parse track and disc number
                        val discNum = if (rawTrackNum >= 1000) (rawTrackNum / 1000) else 1
                        val trackNum = if (rawTrackNum >= 1000) (rawTrackNum % 1000) else rawTrackNum

                        // Compute Rockbox-compatible relative path
                        val storageRelPath = RockboxPathResolver.computeStorageRelativePath(dataPath, dirPath)

                        // Compute fast bitrate from size/duration or format
                        val computedBitrateKbps = if (sizeBytes > 0 && durationSec > 0 && format != "FLAC" && format != "WAV") {
                            ((sizeBytes * 8L) / (durationSec * 1000L)).toInt().coerceIn(64, 320)
                        } else if (format == "FLAC" || format == "WAV" || format == "AIFF") {
                            1411
                        } else {
                            320
                        }

                        val qualityRating = resolveQualityRating(format, computedBitrateKbps)

                        // Priority 1: Extract embedded metadata (ID3 / Vorbis / MP4 tags) if present
                        val embedded = com.example.metadata.AudioEmbeddedMetadataReader.read(context, targetPath)
                        val effectiveTitle = embedded.title?.takeIf(String::isNotBlank) ?: title
                        val effectiveArtist = embedded.artist?.takeIf(String::isNotBlank)
                            ?: if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "Unknown Artist" else rawArtist
                        val effectiveAlbum = embedded.album?.takeIf(String::isNotBlank)
                            ?: if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") "Single" else rawAlbum
                        val effectiveGenre = embedded.genre?.takeIf(String::isNotBlank) ?: "DJ Library"
                        val effectiveBpm = embedded.bpm ?: 0.0
                        val effectiveKey = embedded.camelotKey?.takeIf(String::isNotBlank)
                            ?: embedded.musicalKey?.takeIf(String::isNotBlank) ?: ""
                        val effectiveTrackNum = embedded.trackNumber ?: trackNum
                        val effectiveDiscNum = embedded.discNumber ?: discNum
                        val effectiveYear = embedded.releaseYear
                        val effectiveDate = embedded.releaseDate

                        val track = Track(
                            id = "media_$id",
                            title = effectiveTitle,
                            artist = effectiveArtist,
                            album = effectiveAlbum,
                            albumArtist = embedded.albumArtist.orEmpty(),
                            genre = effectiveGenre,
                            subGenre = "Club",
                            bpm = effectiveBpm,
                            musicalKey = effectiveKey,
                            camelotKey = embedded.camelotKey.orEmpty(),
                            durationSeconds = durationSec,
                            bitrateKbps = computedBitrateKbps,
                            format = format,
                            fileSizeMb = String.format(Locale.US, "%.2f", sizeMb).toDoubleOrNull() ?: sizeMb,
                            filePath = targetPath,
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
                            sourceId = resolveSourceId(dataPath),
                            trackNumber = effectiveTrackNum,
                            discNumber = effectiveDiscNum,
                            releaseDate = effectiveDate,
                            releaseYear = effectiveYear,
                            recordLabel = embedded.recordLabel,
                            barcode = embedded.barcode,
                            isrc = embedded.isrc,
                            storageRelativePath = storageRelPath,
                            contentFingerprint = fingerprint,
                            originalArtist = embedded.artist?.takeIf { !com.example.metadata.repair.ArtistStructureAnalyzer.isArtistMissingOrInvalid(it) }
                                ?: rawArtist?.takeIf { !com.example.metadata.repair.ArtistStructureAnalyzer.isArtistMissingOrInvalid(it) },
                            resolvedArtist = null,
                            metadataSource = if (!com.example.metadata.repair.ArtistStructureAnalyzer.isArtistMissingOrInvalid(effectiveArtist)) "EMBEDDED" else null,
                            metadataConfidence = if (!com.example.metadata.repair.ArtistStructureAnalyzer.isArtistMissingOrInvalid(effectiveArtist)) 100.0 else 0.0
                        )

                        seenFingerprints.add(fingerprint)
                        seenPaths.add(targetPath)
                        currentBatch.add(track)
                        totalImported++

                        // Emit batch to database incrementally
                        if (currentBatch.size >= batchSize) {
                            val batchStartTime = System.currentTimeMillis()
                            Log.d(TAG, "Metadata processing batch saving (${currentBatch.size} tracks)...")
                            onBatch(currentBatch.toList())
                            Log.d(TAG, "Metadata processing batch saved in ${System.currentTimeMillis() - batchStartTime}ms")
                            currentBatch.clear()
                        }
                    } catch (e: Exception) {
                        totalFailed++
                        Log.w(TAG, "Skipping problematic MediaStore entry at row $current: ${e.message}")
                    }
                }
            }
        }

            // Flush final batch
            if (currentBatch.isNotEmpty()) {
                val batchStartTime = System.currentTimeMillis()
                Log.d(TAG, "Metadata processing final batch saving (${currentBatch.size} tracks)...")
                onBatch(currentBatch.toList())
                Log.d(TAG, "Metadata processing final batch saved in ${System.currentTimeMillis() - batchStartTime}ms")
                currentBatch.clear()
            }
            com.example.util.DjLogger.endTiming("MEDIA_SCAN_END", "Total indexed tracks: $totalImported, skipped: $totalSkipped, failed: $totalFailed")
            Log.d(TAG, "Library scan finished. Imported: $totalImported, Skipped: $totalSkipped in ${System.currentTimeMillis() - scanStartTime}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying MediaStore: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore audio: ${e.message}", e)
            throw e
        }

        ScanSummaryResult(
            discovered = totalDiscovered,
            imported = totalImported,
            skipped = totalSkipped,
            failed = totalFailed
        )
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
            var sizeBytes = 0L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex != -1) {
                            sizeBytes = cursor.getLong(sizeIndex)
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

            // Priority 1: Extract embedded metadata (ID3 / Vorbis / MP4 tags) if present
            val embedded = com.example.metadata.AudioEmbeddedMetadataReader.read(context, uri.toString())
            val resolvedTitle = embedded.title?.takeIf(String::isNotBlank)
                ?: if (!title.isNullOrBlank()) title else displayName.substringBeforeLast(".")
            val resolvedArtist = embedded.artist?.takeIf(String::isNotBlank) ?: artist
            val resolvedAlbum = embedded.album?.takeIf(String::isNotBlank) ?: album
            val resolvedGenre = embedded.genre?.takeIf(String::isNotBlank) ?: genre
            val resolvedBpm = embedded.bpm ?: 0.0
            val resolvedKey = embedded.camelotKey?.takeIf(String::isNotBlank)
                ?: embedded.musicalKey?.takeIf(String::isNotBlank) ?: ""

            val fingerprint = AudioFingerprintUtil.generateFingerprint(
                context = context,
                uriOrPath = uri.toString(),
                fileSizeBytes = sizeBytes,
                durationSeconds = durationSec
            )

            Track(
                id = id,
                title = resolvedTitle,
                artist = resolvedArtist,
                album = resolvedAlbum,
                albumArtist = embedded.albumArtist.orEmpty(),
                genre = resolvedGenre,
                subGenre = "Club",
                bpm = resolvedBpm,
                musicalKey = resolvedKey,
                camelotKey = embedded.camelotKey.orEmpty(),
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
                sourceId = customSourceId,
                trackNumber = embedded.trackNumber ?: 0,
                discNumber = embedded.discNumber ?: 1,
                releaseDate = embedded.releaseDate,
                releaseYear = embedded.releaseYear,
                recordLabel = embedded.recordLabel,
                barcode = embedded.barcode,
                isrc = embedded.isrc,
                contentFingerprint = fingerprint
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
            path.matches(Regex(".*/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.*")) -> "usb_ssd"
            path.contains("0000-0000", ignoreCase = true) || path.contains("sdcard1", ignoreCase = true) -> "sd_card"
            path.contains("CloudCache", ignoreCase = true) -> "cloud_vault"
            else -> "internal"
        }
    }
}
