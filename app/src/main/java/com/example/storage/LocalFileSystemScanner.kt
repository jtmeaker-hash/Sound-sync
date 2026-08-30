package com.example.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.model.FolderItem
import com.example.model.StorageSource
import com.example.model.StorageSourceType
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
}
