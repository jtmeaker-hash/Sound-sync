package com.example.storage

import android.content.Context
import android.net.Uri
import com.example.model.Track
import java.io.File

/**
 * Helper to determine physical storage mount status and track availability,
 * specifically handling external USB OTG, SSD, and SD card disconnects.
 */
object StorageAvailabilityHelper {

    /**
     * Extracts the mount root of a storage path (e.g. /storage/XXXX-XXXX or /storage/emulated/0).
     */
    fun getStorageRoot(filePath: String): String? {
        if (filePath.startsWith("content://") || filePath.startsWith("demo://")) return null
        val p = filePath.removePrefix("file://")
        return when {
            p.startsWith("/storage/emulated/") -> "/storage/emulated/0"
            p.startsWith("/storage/") -> {
                val parts = p.split('/')
                if (parts.size >= 3) "/${parts[1]}/${parts[2]}" else null
            }
            p.startsWith("/mnt/media_rw/") -> {
                val parts = p.split('/')
                if (parts.size >= 4) "/${parts[1]}/${parts[2]}/${parts[3]}" else null
            }
            else -> null
        }
    }

    /**
     * Determines whether the track or path is located on external/removable storage (USB drive or SD card).
     */
    fun isExternalStoragePath(path: String): Boolean {
        if (path.startsWith("demo://")) return false
        val p = path.removePrefix("file://")
        val root = getStorageRoot(p)
        if (root != null && !root.contains("emulated")) {
            return true
        }
        return p.contains("usb", ignoreCase = true) ||
                p.contains("media_rw", ignoreCase = true) ||
                p.matches(Regex(".*/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.*"))
    }

    /**
     * Determines whether the track is on an external removable storage medium.
     */
    fun isExternalStorageTrack(track: Track): Boolean {
        if (track.sourceId.contains("usb", ignoreCase = true) ||
            track.sourceId.contains("removable", ignoreCase = true) ||
            track.sourceId.contains("sd", ignoreCase = true)) {
            return true
        }
        return isExternalStoragePath(track.filePath)
    }

    /**
     * Checks whether a track's physical storage file or SAF document is currently mounted and accessible.
     */
    fun isTrackAvailable(context: Context, track: Track): Boolean {
        return isTrackPathAvailable(context, track.filePath)
    }

    /**
     * Cache of storage root availability to eliminate per-track filesystem stat calls.
     */
    private val rootAvailabilityCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Checks if a storage root is mounted and readable.
     * Internal/emulated storage roots are always treated as available.
     * Removable external roots (USB OTG, SD card) are checked via directory exists/canRead.
     */
    fun isRootAvailable(root: String?): Boolean {
        if (root == null || root.contains("emulated")) return true
        return try {
            val dir = File(root)
            dir.exists() && dir.canRead()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Refreshes the cached availability status for the given storage roots.
     * Should be called off the main thread (Dispatchers.IO).
     */
    fun refreshRoots(roots: Collection<String>): Map<String, Boolean> {
        val updated = mutableMapOf<String, Boolean>()
        for (root in roots) {
            val isAvail = isRootAvailable(root)
            rootAvailabilityCache[root] = isAvail
            updated[root] = isAvail
        }
        return updated
    }

    /**
     * Gets the cached availability of a storage root without doing per-file filesystem I/O,
     * computing it on demand if not present.
     */
    fun getCachedRootAvailability(root: String?): Boolean {
        if (root == null || root.contains("emulated")) return true
        return rootAvailabilityCache[root] ?: isRootAvailable(root).also { rootAvailabilityCache[root] = it }
    }

    /**
     * Determines whether a track is available based on its storage root availability,
     * without performing per-file filesystem stat operations.
     */
    fun isTrackRootAvailable(filePath: String, rootAvailabilityMap: Map<String, Boolean>? = null): Boolean {
        if (filePath.startsWith("demo://")) return true
        val root = getStorageRoot(filePath)
        if (root == null || root.contains("emulated")) return true
        if (rootAvailabilityMap != null) {
            return rootAvailabilityMap[root] ?: isRootAvailable(root)
        }
        return getCachedRootAvailability(root)
    }

    fun clearCache() {
        rootAvailabilityCache.clear()
    }

    /**
     * Checks whether a specific file path is currently accessible on device storage.
     */
    fun isTrackPathAvailable(context: Context, filePath: String): Boolean {
        if (filePath.startsWith("demo://")) return true
        if (filePath.startsWith("content://")) {
            return try {
                context.contentResolver.openFileDescriptor(Uri.parse(filePath), "r")?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
        }
        val p = filePath.removePrefix("file://")
        val root = getStorageRoot(p)
        if (root != null && !root.contains("emulated")) {
            val rootDir = File(root)
            if (!rootDir.exists() || !rootDir.canRead()) {
                return false
            }
        }
        val file = File(p)
        return file.exists() && file.canRead()
    }
}
