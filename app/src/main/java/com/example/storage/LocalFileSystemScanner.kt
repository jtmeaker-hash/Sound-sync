package com.example.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.StatFs
import com.example.model.AudioQualityRating
import com.example.model.FolderItem
import com.example.model.MusicPlatform
import com.example.model.StorageSource
import com.example.model.StorageSourceType
import com.example.model.SyncState
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object LocalFileSystemScanner {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma"
    )

    /**
     * Discovers all available storage roots on device (Internal, Downloads, Music, Removable SD/USB)
     */
    fun getAvailableStorageSources(context: Context): List<StorageSource> {
        val sources = mutableListOf<StorageSource>()

        // 1. Primary Internal Storage Music
        val internalMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val internalRoot = Environment.getExternalStorageDirectory()
        val (freeGb, totalGb) = getDiskSpace(internalRoot)

        sources.add(
            StorageSource(
                id = "internal",
                type = StorageSourceType.INTERNAL,
                label = "Internal Music",
                path = internalMusic.absolutePath,
                isOnline = internalMusic.exists() && internalMusic.canRead(),
                trackCount = 0,
                freeSpaceGb = freeGb,
                totalSpaceGb = totalGb
            )
        )

        // 2. Downloads folder
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        sources.add(
            StorageSource(
                id = "downloads",
                type = StorageSourceType.DOWNLOADS,
                label = "Downloads",
                path = downloadsDir.absolutePath,
                isOnline = downloadsDir.exists() && downloadsDir.canRead(),
                trackCount = 0,
                freeSpaceGb = freeGb,
                totalSpaceGb = totalGb
            )
        )

        // 3. Removable / External Storage volumes (SD card & USB OTG)
        try {
            val externalDirs = context.getExternalFilesDirs(null)
            externalDirs.forEachIndexed { index, dir ->
                if (dir != null && !dir.absolutePath.contains("emulated")) {
                    val rootPath = dir.absolutePath.substringBefore("/Android")
                    val fileRoot = File(rootPath)
                    val (extFree, extTotal) = getDiskSpace(fileRoot)
                    val isUsb = rootPath.contains("usb", ignoreCase = true) || rootPath.contains("media_rw", ignoreCase = true)
                    val label = if (isUsb) "USB Drive (${fileRoot.name})" else "MicroSD (${fileRoot.name})"
                    val type = if (isUsb) StorageSourceType.USB_SSD else StorageSourceType.SD_CARD

                    sources.add(
                        StorageSource(
                            id = "removable_$index",
                            type = type,
                            label = label,
                            path = rootPath,
                            isOnline = fileRoot.exists() && fileRoot.canRead(),
                            trackCount = 0,
                            freeSpaceGb = extFree,
                            totalSpaceGb = extTotal
                        )
                    )
                }
            }
        } catch (ignored: Exception) {}

        // 4. Physical /storage mount points (discovers all USB OTG mounts even if app-specific folder not created)
        try {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (file.isDirectory && name != "emulated" && name != "self" && name != "knox") {
                        val rootPath = file.absolutePath
                        if (sources.none { it.path.equals(rootPath, ignoreCase = true) }) {
                            val (extFree, extTotal) = getDiskSpace(file)
                            val isUsb = rootPath.contains("usb", ignoreCase = true) ||
                                    rootPath.contains("media_rw", ignoreCase = true) ||
                                    name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))
                            val label = if (isUsb) "USB Drive ($name)" else "External Storage ($name)"
                            val type = if (isUsb) StorageSourceType.USB_SSD else StorageSourceType.SD_CARD

                            sources.add(
                                StorageSource(
                                    id = "storage_$name",
                                    type = type,
                                    label = label,
                                    path = rootPath,
                                    isOnline = file.exists() && file.canRead(),
                                    trackCount = 0,
                                    freeSpaceGb = extFree,
                                    totalSpaceGb = extTotal
                                )
                            )
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        return sources
    }

    /**
     * Lists subdirectories and audio files directly inside a physical filesystem directory
     */
    suspend fun listDirectoryContents(dirPath: String): Pair<List<FolderItem>, List<File>> = withContext(Dispatchers.IO) {
        val folder = File(dirPath)
        val subFolders = mutableListOf<FolderItem>()
        val audioFiles = mutableListOf<File>()

        if (!folder.exists() || !folder.isDirectory || !folder.canRead()) {
            return@withContext Pair(emptyList(), emptyList())
        }

        val entries = folder.listFiles() ?: return@withContext Pair(emptyList(), emptyList())

        for (entry in entries) {
            if (entry.isDirectory && !entry.name.startsWith(".")) {
                val filesInside = entry.listFiles()
                val audioCount = filesInside?.count { f ->
                    f.isFile && AUDIO_EXTENSIONS.contains(f.extension.lowercase(Locale.ROOT))
                } ?: 0
                val totalBytes = filesInside?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)

                subFolders.add(
                    FolderItem(
                        name = entry.name,
                        path = entry.absolutePath,
                        trackCount = audioCount,
                        subFolderCount = filesInside?.count { it.isDirectory } ?: 0,
                        totalSizeMb = String.format(Locale.US, "%.1f", totalMb).toDoubleOrNull() ?: totalMb
                    )
                )
            } else if (entry.isFile && AUDIO_EXTENSIONS.contains(entry.extension.lowercase(Locale.ROOT))) {
                audioFiles.add(entry)
            }
        }

        Pair(
            subFolders.sortedBy { it.name.lowercase(Locale.ROOT) },
            audioFiles.sortedBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    fun getDiskSpace(directory: File): Pair<Double, Double> {
        return try {
            if (directory.exists()) {
                val stat = StatFs(directory.absolutePath)
                val blockSize = stat.blockSizeLong
                val freeBlocks = stat.availableBlocksLong
                val totalBlocks = stat.blockCountLong
                val freeGb = (freeBlocks * blockSize).toDouble() / (1024.0 * 1024.0 * 1024.0)
                val totalGb = (totalBlocks * blockSize).toDouble() / (1024.0 * 1024.0 * 1024.0)
                Pair(
                    String.format(Locale.US, "%.1f", freeGb).toDoubleOrNull() ?: freeGb,
                    String.format(Locale.US, "%.1f", totalGb).toDoubleOrNull() ?: totalGb
                )
            } else {
                Pair(0.0, 0.0)
            }
        } catch (e: Exception) {
            Pair(64.0, 128.0)
        }
    }

    /**
     * Recursively scans an external filesystem directory (e.g. USB drive or folder) for audio files,
     * reading embedded metadata and streaming Track objects to onBatch.
     */
    suspend fun scanDirectoryForAudioFiles(
        context: Context,
        rootDir: File,
        sourceId: String = "usb_ssd",
        onBatch: suspend (List<Track>) -> Unit,
        onProgress: (current: Int, title: String) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory || !rootDir.canRead()) return@withContext 0

        var count = 0
        val batch = mutableListOf<Track>()

        fun walkDir(dir: File) {
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                if (entry.isDirectory && !entry.name.startsWith(".")) {
                    walkDir(entry)
                } else if (entry.isFile && AUDIO_EXTENSIONS.contains(entry.extension.lowercase(Locale.ROOT))) {
                    count++
                    onProgress(count, entry.name)
                    val track = extractTrackFromFile(context, entry, sourceId)
                    if (track != null) {
                        batch.add(track)
                        if (batch.size >= 50) {
                            val chunk = batch.toList()
                            batch.clear()
                            kotlinx.coroutines.runBlocking { onBatch(chunk) }
                        }
                    }
                }
            }
        }

        walkDir(rootDir)
        if (batch.isNotEmpty()) {
            onBatch(batch.toList())
            batch.clear()
        }
        count
    }

    fun extractTrackFromFile(context: Context, file: File, sourceId: String): Track? {
        val path = file.absolutePath
        val name = file.name
        val ext = file.extension.lowercase(Locale.ROOT)
        val format = when (ext) {
            "flac" -> "FLAC"
            "wav" -> "WAV"
            "m4a", "aac" -> "AAC"
            "ogg", "opus" -> "OGG"
            "aiff", "aif" -> "AIFF"
            else -> "MP3"
        }
        val fallbackTitle = file.nameWithoutExtension
        var title = fallbackTitle
        var artist = "Unknown Artist"
        var album = "Single"
        var genre = "DJ Library"
        var durationSec = 210
        var bitrateKbps = if (format == "FLAC" || format == "WAV") 1411 else 320
        var bpm = 0.0
        var musicalKey = ""

        val embedded = com.example.metadata.AudioEmbeddedMetadataReader.read(context, path)
        if (embedded.title?.isNotBlank() == true) title = embedded.title
        if (embedded.artist?.isNotBlank() == true) artist = embedded.artist
        if (embedded.album?.isNotBlank() == true) album = embedded.album
        if (embedded.genre?.isNotBlank() == true) genre = embedded.genre
        if (embedded.hasBpm) bpm = embedded.bpm ?: 0.0
        if (embedded.hasKey) musicalKey = embedded.camelotKey ?: embedded.musicalKey.orEmpty()

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val mTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val mArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val mAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val mGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val mDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val mBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)

            if (title == fallbackTitle && !mTitle.isNullOrBlank()) title = mTitle
            if (artist == "Unknown Artist" && !mArtist.isNullOrBlank()) artist = mArtist
            if (album == "Single" && !mAlbum.isNullOrBlank()) album = mAlbum
            if (genre == "DJ Library" && !mGenre.isNullOrBlank()) genre = mGenre

            val durMs = mDuration?.toLongOrNull() ?: 0L
            if (durMs > 0) durationSec = (durMs / 1000L).toInt()
            val br = mBitrate?.toIntOrNull() ?: 0
            if (br > 0) bitrateKbps = br / 1000

            retriever.release()
        } catch (_: Exception) {}

        val sizeMb = file.length().toDouble() / (1024.0 * 1024.0)
        val quality = if (format == "FLAC" || format == "WAV" || format == "AIFF") {
            AudioQualityRating.TRUE_LOSSLESS
        } else if (bitrateKbps >= 320) {
            AudioQualityRating.TRUE_320
        } else if (bitrateKbps >= 256) {
            AudioQualityRating.TRUE_256
        } else if (bitrateKbps > 0) {
            AudioQualityRating.LOW_128
        } else {
            AudioQualityRating.UNKNOWN_BITRATE
        }

        val trackId = "usb_" + java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString()
        val dir = file.parent ?: "/"

        return Track(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            subGenre = "Club",
            bpm = bpm,
            bpmConfidence = if (bpm > 0) 0.9 else 0.0,
            musicalKey = musicalKey,
            camelotKey = musicalKey,
            keyConfidence = if (musicalKey.isNotBlank()) 0.9 else 0.0,
            durationSeconds = durationSec,
            bitrateKbps = bitrateKbps,
            format = format,
            fileSizeMb = String.format(Locale.US, "%.1f", sizeMb).toDoubleOrNull() ?: sizeMb,
            filePath = path,
            directoryPath = dir,
            isOfflineReady = true,
            isAvailable = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            energyRating = 7,
            hotCues = listOf(0, (durationSec * 0.15).toInt(), (durationSec * 0.40).toInt(), (durationSec * 0.70).toInt()),
            isAiTagged = false,
            qualityRating = quality,
            dateAdded = System.currentTimeMillis(),
            crateId = "crate_all",
            sourceId = sourceId,
            trackNumber = 0,
            discNumber = 1,
            releaseDate = embedded.releaseDate,
            releaseYear = embedded.releaseYear,
            recordLabel = embedded.recordLabel,
            barcode = embedded.barcode,
            isrc = embedded.isrc,
            musicBrainzRecordingId = embedded.musicBrainzRecordingId,
            musicBrainzReleaseId = embedded.musicBrainzReleaseId,
            musicBrainzArtistId = embedded.musicBrainzArtistId,
            musicBrainzReleaseGroupId = embedded.musicBrainzReleaseGroupId,
            musicBrainzMatchConfidence = if (embedded.hasEmbeddedMusicBrainz) 1.0 else 0.0,
            musicBrainzLastChecked = if (embedded.hasEmbeddedMusicBrainz) System.currentTimeMillis() else null,
            artworkUrl = embedded.musicBrainzReleaseId?.let { "https://coverartarchive.org/release/$it/front-500" },
            storageRelativePath = file.name,
            contentFingerprint = "${file.name}:${file.length()}"
        )
    }
}
