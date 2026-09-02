package com.example.storage

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.analysis.TunebatMetadataService
import com.example.model.AudioQualityRating
import com.example.model.FolderItem
import com.example.model.MusicPlatform
import com.example.model.StorageSource
import com.example.model.StorageSourceType
import com.example.model.SyncState
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object SafStorageManager {

    private const val TAG = "SafStorageManager"

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma"
    )

    /**
     * Persists SAF permission for a user-chosen folder URI
     */
    fun takePersistablePermissions(context: Context, treeUri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist URI permission for $treeUri", e)
        }
    }

    /**
     * Recursively or shallowly scans a SAF DocumentFile directory for audio files
     */
    suspend fun scanDocumentTree(
        context: Context,
        treeUri: Uri,
        sourceId: String = "saf_custom",
        sourceName: String = "Custom Folder",
        onProgress: (current: Int, title: String) -> Unit = { _, _ -> }
    ): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext tracks

        if (!rootDoc.exists() || !rootDoc.canRead()) {
            Log.w(TAG, "Root document is not readable or does not exist: $treeUri")
            return@withContext tracks
        }

        var scannedCount = 0

        fun scanFolderRecursive(folder: DocumentFile, currentPath: String) {
            val files = folder.listFiles()
            for (file in files) {
                if (file.isDirectory) {
                    val subPath = if (currentPath.endsWith("/")) "$currentPath${file.name}" else "$currentPath/${file.name}"
                    scanFolderRecursive(file, subPath)
                } else if (file.isFile) {
                    val name = file.name ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (AUDIO_EXTENSIONS.contains(ext)) {
                        scannedCount++
                        onProgress(scannedCount, name)

                        val track = extractTrackFromDocumentFile(context, file, currentPath, sourceId)
                        if (track != null) {
                            tracks.add(track)
                        }
                    }
                }
            }
        }

        val rootName = rootDoc.name ?: sourceName
        scanFolderRecursive(rootDoc, "/$rootName")

        tracks
    }

    /**
     * Lists subfolders and audio files directly inside a SAF DocumentFile folder for interactive browsing
     */
    suspend fun listSafDirectory(
        context: Context,
        folderDoc: DocumentFile,
        currentPath: String
    ): Pair<List<FolderItem>, List<Track>> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<FolderItem>()
        val tracks = mutableListOf<Track>()

        try {
            val files = folderDoc.listFiles()
            for (file in files) {
                if (file.isDirectory) {
                    val name = file.name ?: "Folder"
                    val subPath = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                    folders.add(
                        FolderItem(
                            name = name,
                            path = subPath,
                            trackCount = 0,
                            subFolderCount = 0,
                            totalSizeMb = 0.0
                        )
                    )
                } else if (file.isFile) {
                    val name = file.name ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (AUDIO_EXTENSIONS.contains(ext)) {
                        val track = extractTrackFromDocumentFile(context, file, currentPath, "saf_folder")
                        if (track != null) {
                            tracks.add(track)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing SAF directory", e)
        }

        Pair(folders.sortedBy { it.name.lowercase() }, tracks.sortedBy { it.title.lowercase() })
    }

    private fun extractTrackFromDocumentFile(
        context: Context,
        file: DocumentFile,
        folderPath: String,
        sourceId: String
    ): Track? {
        val uri = file.uri
        val name = file.name ?: "Unknown Track"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val sizeBytes = file.length()
        val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
        val format = MediaScannerHelper.resolveFormat(name, file.type ?: "")
        val fallbackTitle = name.substringBeforeLast(".")

        var title = fallbackTitle
        var artist = "Unknown Artist"
        var album = "Single"
        var genre = "DJ Library"
        var durationSec = 210
        var bitrateKbps = if (format == "FLAC" || format == "WAV") 1411 else 320
        var bpm = 0.0
        var musicalKey = ""
        var sampleRate = 44100
        var bitDepth = 16

        val embedded = TunebatMetadataService.extractEmbeddedTags(context, uri.toString())
        if (embedded != null) {
            if (embedded.hasBpm) bpm = embedded.bpm
            if (embedded.hasKey) musicalKey = embedded.musicalKey
        }

        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val mTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val mArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                val mAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val mGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                val mDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                val mSampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                } else null
                val mBitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                } else null

                if (!mTitle.isNullOrBlank()) title = mTitle
                if (!mArtist.isNullOrBlank() && mArtist != "<unknown>") artist = mArtist
                if (!mAlbum.isNullOrBlank() && mAlbum != "<unknown>") album = mAlbum
                if (!mGenre.isNullOrBlank()) genre = mGenre
                if (mDuration != null) {
                    durationSec = (mDuration.toLongOrNull() ?: 0L).let { (it / 1000).toInt().coerceAtLeast(1) }
                }
                if (mBitrate != null) {
                    bitrateKbps = (mBitrate.toIntOrNull() ?: (bitrateKbps * 1000)) / 1000
                }
                if (mSampleRate != null && mSampleRate > 0) sampleRate = mSampleRate
                if (mBitDepth != null && mBitDepth > 0) bitDepth = mBitDepth
            }
        } catch (e: Exception) {
            Log.v(TAG, "Retriever skipped or fallback for $name: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // Infer BPM and Key heuristics from filename if tagged like "128_8A_Artist_Title"
        val cleanName = name.replace("_", " ").replace("-", " ")
        val bpmMatch = Regex("""\b(1[1-3][0-9]|14[0-9]|9[0-9])\s*(?:bpm)?\b""", RegexOption.IGNORE_CASE).find(cleanName)
        if (bpmMatch != null) {
            bpmMatch.groupValues[1].toDoubleOrNull()?.let { if (bpm <= 0.0) bpm = it }
        }

        val keyMatch = Regex("""\b([1-9]|1[0-2])([A-B])\b""", RegexOption.IGNORE_CASE).find(name)
        if (keyMatch != null) {
            if (musicalKey.isBlank()) musicalKey = keyMatch.value.uppercase(Locale.ROOT)
        }

        val qualityRating = when {
            format == "FLAC" && (sampleRate >= 96000 || bitDepth >= 24) -> AudioQualityRating.STUDIO_LOSSLESS
            format == "FLAC" || format == "WAV" || format == "AIFF" -> AudioQualityRating.TRUE_LOSSLESS
            bitrateKbps >= 310 -> AudioQualityRating.TRUE_320
            bitrateKbps >= 240 -> AudioQualityRating.TRUE_256
            bitrateKbps < 160 -> AudioQualityRating.LOW_128
            else -> AudioQualityRating.TRUE_320
        }

        val id = "saf_${uri.toString().hashCode().toLong().let { if (it < 0) -it else it }}"
        val relPath = RockboxPathResolver.computeStorageRelativePath(uri.toString(), folderPath)
        val fingerprint = AudioFingerprintUtil.generateDocumentFileFingerprint(context, file, durationSec)

        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            subGenre = "Club",
            bpm = bpm,
            musicalKey = musicalKey,
            durationSeconds = durationSec,
            bitrateKbps = bitrateKbps,
            format = format,
            fileSizeMb = String.format(Locale.US, "%.2f", sizeMb).toDoubleOrNull() ?: sizeMb,
            filePath = uri.toString(),
            directoryPath = folderPath,
            isOfflineReady = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            energyRating = 7,
            hotCues = listOf(0, (durationSec * 0.15).toInt(), (durationSec * 0.45).toInt(), (durationSec * 0.75).toInt()),
            isAiTagged = false,
            qualityRating = qualityRating,
            dateAdded = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            crateId = "crate_all",
            sourceId = sourceId,
            trackNumber = 0,
            discNumber = 1,
            storageRelativePath = relPath,
            contentFingerprint = fingerprint
        )
    }

    /**
     * Checks if a SAF tree URI is still valid and accessible
     */
    fun isUriAccessible(context: Context, uri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc != null && doc.exists() && doc.canRead()
        } catch (e: Exception) {
            false
        }
    }
}
