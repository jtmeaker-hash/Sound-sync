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
            dir.exists()
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
     * Determines whether a storage volume is physically mounted and available.
     * Evaluates testing overrides, StorageManager, MediaStore volumes, persisted SAF trees,
     * and filesystem fallback.
     */
    fun isVolumePhysicallyMounted(context: Context?, volumeUuid: String?): Boolean {
        if (volumeUuid.isNullOrBlank() ||
            volumeUuid.equals("primary", ignoreCase = true) ||
            volumeUuid.equals("internal", ignoreCase = true) ||
            volumeUuid.contains("emulated", ignoreCase = true)
        ) {
            return true
        }

        // 1. Check testing overrides
        if (TrackSourceResolver.mountedVolumesOverrideForTesting.containsKey(volumeUuid.uppercase(java.util.Locale.ROOT))) {
            return TrackSourceResolver.mountedVolumesOverrideForTesting[volumeUuid.uppercase(java.util.Locale.ROOT)] == true
        }

        if (context == null) {
            return try {
                File("/storage/$volumeUuid").exists()
            } catch (_: Throwable) {
                false
            }
        }

        // 2. Query StorageManager.storageVolumes
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
            if (sm != null) {
                val vols = sm.storageVolumes
                if (vols.isNotEmpty()) {
                    val match = vols.firstOrNull { it.uuid?.equals(volumeUuid, ignoreCase = true) == true }
                    if (match != null) {
                        val state = match.state
                        return state == android.os.Environment.MEDIA_MOUNTED || state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY
                    }
                    // Volume list is populated by OS but this UUID is not in the mounted volumes
                    return false
                }
            }
        } catch (_: Throwable) {}

        // 3. MediaStore external volume names (Android Q+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val volNames = android.provider.MediaStore.getExternalVolumeNames(context)
                if (volNames.any { it.equals(volumeUuid, ignoreCase = true) }) {
                    return true
                }
            } catch (_: Throwable) {}
        }

        // 4. Check SAF persisted folder grants for this volume
        try {
            val persistedTrees = SafStorageManager.getPersistedAccessibleFolderUris(context)
            for (treeUri in persistedTrees) {
                if (treeUri.toString().contains(volumeUuid, ignoreCase = true)) {
                    val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                    if (doc != null && doc.canRead()) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {}

        // 5. Direct filesystem directory fallback
        return try {
            File("/storage/$volumeUuid").exists()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Evaluates granular storage and source availability for a track.
     * Accurately distinguishes CONNECTED_READABLE, PERMISSION_LOST, SOURCE_MISSING,
     * VOLUME_UNMOUNTED, STALE_SOURCE, and UNKNOWN.
     */
    fun evaluateStorageAvailability(context: Context, track: Track): StorageAvailabilityReport {
        return evaluateStorageAvailability(context, track.filePath, track.sourceId)
    }

    /**
     * Evaluates granular storage and source availability for a file path or content URI.
     */
    fun evaluateStorageAvailability(
        context: Context,
        filePathOrUri: String,
        sourceId: String? = null
    ): StorageAvailabilityReport {
        val path = filePathOrUri.trim()
        if (path.isBlank()) {
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.SOURCE_MISSING,
                sourceType = ResolvedSourceType.UNKNOWN,
                volumeIdentity = null,
                isReadable = false,
                hasPermission = false,
                details = "Track file path is empty."
            )
        }

        if (path.startsWith("demo://")) {
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.CONNECTED_READABLE,
                sourceType = ResolvedSourceType.RAW_FILE_PATH,
                volumeIdentity = "internal",
                isReadable = true,
                hasPermission = true,
                mountLabelOrPath = "Demo Synthetic Audio",
                details = "SoundSync built-in demo audio"
            )
        }

        val isContent = path.startsWith("content://")
        val isMediaStore = isContent && TrackSourceResolver.isMediaStoreUri(path)
        val isSaf = isContent && !isMediaStore
        val isExternal = isExternalStoragePath(path) || (sourceId != null && (
            sourceId.contains("usb", ignoreCase = true) ||
            sourceId.contains("removable", ignoreCase = true) ||
            sourceId.contains("sd", ignoreCase = true)
        ))

        val sourceType = when {
            isMediaStore -> ResolvedSourceType.MEDIASTORE
            isSaf -> if (path.contains("/tree/")) ResolvedSourceType.SAF_TREE else ResolvedSourceType.SAF_DOCUMENT
            isExternal -> ResolvedSourceType.REMOVABLE_STORAGE_PATH
            else -> ResolvedSourceType.RAW_FILE_PATH
        }

        val volumeId = TrackSourceResolver.extractVolumeUuid(path) ?: if (isExternal) {
            getStorageRoot(path.removePrefix("file://"))?.substringAfterLast('/') ?: "removable"
        } else {
            "primary"
        }

        // Usability priority: if content/document URI or file opens successfully,
        // it is CONNECTED and READABLE. Never report volume disconnected when readable!
        val isReadable = isTrackPathAvailable(context, path)
        if (isReadable) {
            val label = if (volumeId == "primary") "/storage/emulated/0" else "/storage/$volumeId"
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.CONNECTED_READABLE,
                sourceType = sourceType,
                volumeIdentity = volumeId,
                isReadable = true,
                hasPermission = true,
                mountLabelOrPath = label,
                details = "Source is connected and verified readable."
            )
        }

        // Not readable: diagnose root cause
        val isRemovableVol = isExternal || (volumeId != "primary" && volumeId.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")))
        val isMounted = if (isRemovableVol) isVolumePhysicallyMounted(context, volumeId) else true

        if (!isMounted) {
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.VOLUME_UNMOUNTED,
                sourceType = sourceType,
                volumeIdentity = volumeId,
                isReadable = false,
                hasPermission = false,
                mountLabelOrPath = "/storage/$volumeId",
                details = "Storage volume ($volumeId) is unmounted or disconnected."
            )
        }

        val hasAudioPerm = TrackSourceResolver.hasAudioReadPermission(context)

        if (isMediaStore) {
            if (!hasAudioPerm) {
                return StorageAvailabilityReport(
                    state = StorageAvailabilityState.PERMISSION_LOST,
                    sourceType = sourceType,
                    volumeIdentity = volumeId,
                    isReadable = false,
                    hasPermission = false,
                    details = "Android audio read permission is missing or revoked."
                )
            }

            val existsInMediaStore = try {
                context.contentResolver.query(
                    Uri.parse(path),
                    arrayOf(android.provider.MediaStore.Audio.Media._ID),
                    null, null, null
                )?.use { it.moveToFirst() } == true
            } catch (_: Throwable) {
                false
            }

            return if (!existsInMediaStore) {
                StorageAvailabilityReport(
                    state = StorageAvailabilityState.STALE_SOURCE,
                    sourceType = sourceType,
                    volumeIdentity = volumeId,
                    isReadable = false,
                    hasPermission = true,
                    details = "MediaStore entry no longer exists at URI (stale index)."
                )
            } else {
                StorageAvailabilityReport(
                    state = StorageAvailabilityState.SOURCE_MISSING,
                    sourceType = sourceType,
                    volumeIdentity = volumeId,
                    isReadable = false,
                    hasPermission = true,
                    details = "MediaStore entry points to missing or unreadable physical data."
                )
            }
        }

        if (isSaf) {
            val uri = try { Uri.parse(path) } catch (_: Throwable) { null }
            val hasSafGrant = if (uri != null) {
                val persisted = SafStorageManager.getPersistedAccessibleFolderUris(context)
                persisted.any { tree ->
                    uri.toString().startsWith(tree.toString()) ||
                    (volumeId != "primary" && tree.toString().contains(volumeId, ignoreCase = true))
                }
            } else false

            if (!hasSafGrant) {
                return StorageAvailabilityReport(
                    state = StorageAvailabilityState.PERMISSION_LOST,
                    sourceType = sourceType,
                    volumeIdentity = volumeId,
                    isReadable = false,
                    hasPermission = false,
                    details = "SAF folder/document permission is missing or revoked."
                )
            }
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.SOURCE_MISSING,
                sourceType = sourceType,
                volumeIdentity = volumeId,
                isReadable = false,
                hasPermission = true,
                details = "SAF document reference cannot be found or read."
            )
        }

        // Raw file path checks
        val cleanPath = path.removePrefix("file://")
        val file = File(cleanPath)

        if (!hasAudioPerm && !isExternal) {
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.PERMISSION_LOST,
                sourceType = sourceType,
                volumeIdentity = volumeId,
                isReadable = false,
                hasPermission = false,
                details = "Storage permission required to read file."
            )
        }

        if (isRemovableVol) {
            val hasSafGrant = SafStorageManager.getPersistedAccessibleFolderUris(context).any {
                it.toString().contains(volumeId, ignoreCase = true)
            }
            if (!hasSafGrant) {
                return StorageAvailabilityReport(
                    state = StorageAvailabilityState.PERMISSION_LOST,
                    sourceType = sourceType,
                    volumeIdentity = volumeId,
                    isReadable = false,
                    hasPermission = false,
                    mountLabelOrPath = "/storage/$volumeId",
                    details = "Android Scoped Storage restricts direct file access. SAF folder grant required for /storage/$volumeId."
                )
            }
        }

        if (!file.exists()) {
            return StorageAvailabilityReport(
                state = StorageAvailabilityState.SOURCE_MISSING,
                sourceType = sourceType,
                volumeIdentity = volumeId,
                isReadable = false,
                hasPermission = hasAudioPerm,
                details = "File does not exist on storage at $cleanPath."
            )
        }

        return StorageAvailabilityReport(
            state = StorageAvailabilityState.UNKNOWN,
            sourceType = sourceType,
            volumeIdentity = volumeId,
            isReadable = false,
            hasPermission = hasAudioPerm,
            details = "Source file exists but cannot be opened (I/O error)."
        )
    }

    /**
     * Canonical check for whether a track's volume is genuinely disconnected/unmounted.
     * Validated against the authoritative storage availability report.
     */
    fun isRootGenuinelyDisconnected(context: Context, track: Track): Boolean {
        val report = evaluateStorageAvailability(context, track)
        return report.state == StorageAvailabilityState.VOLUME_UNMOUNTED
    }

    /**
     * Checks whether a specific file path or content URI is currently accessible on device storage.
     */
    fun isTrackPathAvailable(context: Context, filePath: String): Boolean {
        if (filePath.startsWith("demo://")) return true
        if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            TrackSourceResolver.contentUriPlayableCheckerForTesting?.let { return it(context, uri) }
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val valid = pfd.fileDescriptor.valid()
                    pfd.close()
                    if (valid) return true
                }
            } catch (_: Throwable) {}

            try {
                val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                if (afd != null) {
                    val valid = afd.fileDescriptor.valid()
                    afd.close()
                    if (valid) return true
                }
            } catch (_: Throwable) {}

            try {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    val buf = ByteArray(1)
                    val r = stream.read(buf)
                    stream.close()
                    if (r >= 0) return true
                }
            } catch (_: Throwable) {}

            return false
        }
        val p = filePath.removePrefix("file://")
        val file = File(p)
        return TrackSourceResolver.isGenuinelyRawReadable(file)
    }
}

/**
 * Granular physical/SAF availability status for audio tracks.
 */
enum class StorageAvailabilityState {
    CONNECTED_READABLE,
    PERMISSION_LOST,
    SOURCE_MISSING,
    VOLUME_UNMOUNTED,
    STALE_SOURCE,
    UNKNOWN
}

/**
 * Canonical availability report detailing source usability, volume identity, and permission state.
 */
data class StorageAvailabilityReport(
    val state: StorageAvailabilityState,
    val sourceType: ResolvedSourceType,
    val volumeIdentity: String?,
    val isReadable: Boolean,
    val hasPermission: Boolean,
    val mountLabelOrPath: String? = null,
    val details: String = ""
)
