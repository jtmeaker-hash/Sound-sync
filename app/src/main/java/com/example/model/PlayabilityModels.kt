package com.example.model

/**
 * Granular playability status for audio tracks in SoundSync.
 */
enum class PlayabilityStatus(
    val displayName: String,
    val shortBadge: String,
    val isPlayable: Boolean,
    val isProblem: Boolean
) {
    PLAYABLE("Playable", "OK", true, false),
    UNPLAYABLE("Unplayable", "Can't Play", false, true),
    CHECKING("Checking...", "Checking", false, false),
    UNKNOWN("Unknown", "Unverified", true, false),
    MISSING_FILE("File Missing", "Missing File", false, true),
    STALE_URI("Stale File Reference", "Moved File", false, true),
    PERMISSION_DENIED("Permission Denied", "Permission Required", false, true),
    UNSUPPORTED_FORMAT("Unsupported Audio Format", "Unsupported Audio", false, true),
    DECODER_ERROR("Audio Decoder Failure", "Decoder Error", false, true),
    CORRUPTED_FILE("Corrupted File", "File Error", false, true),
    INVALID_CONTAINER("Invalid Media Container", "Invalid File", false, true),
    ZERO_AUDIO_STREAMS("No Audio Streams Found", "No Audio", false, true),
    READ_ERROR("Storage Read Error", "Read Error", false, true),
    MEDIASTORE_MISMATCH("Media Library Disconnected", "Storage Disconnected", false, true),
    UNKNOWN_PLAYBACK_ERROR("Playback Error", "Can't Play", false, true),
    REPAIRING("Repairing...", "Repairing", false, false),
    REPAIRED("Repaired & Playable", "Repaired", true, false),
    REPAIR_FAILED("Repair Failed", "Unresolved", false, true)
}

/**
 * Broad categorization of playback issues for UI filtering and batch operations.
 */
enum class PlayabilityCategory(val title: String) {
    ALL("All Issues"),
    MISSING_FILES("Missing Files"),
    PERMISSION_PROBLEMS("Permission Problems"),
    UNSUPPORTED_AUDIO("Unsupported Audio"),
    FILE_ERRORS("File Errors"),
    UNKNOWN("Other Issues")
}

/**
 * Complete diagnostic report for a single track's playback health.
 */
data class PlayabilityDiagnosticReport(
    val trackId: String,
    val status: PlayabilityStatus,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val technicalDetails: String? = null,
    val problemDescription: String = "",
    val detectedReason: String = "",
    val lastKnownLocation: String = "",
    val resolvedPath: String? = null,
    val containerMime: String? = null,
    val audioCodec: String? = null,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bitRateKbps: Int = 0,
    val fileSizeBytes: Long = 0L,
    val fileModifiedTimestamp: Long = 0L,
    val isFileAccessible: Boolean = false,
    val isMediaStoreEntryValid: Boolean = false,
    val isContainerReadable: Boolean = false,
    val isAudioStreamFound: Boolean = false,
    val isDecoderInitialized: Boolean = false,
    val isSampleDecoded: Boolean = false,
    val validationTimestamp: Long = System.currentTimeMillis(),
    val availableActions: List<RepairActionType> = listOf(
        RepairActionType.FIX_AUTOMATICALLY,
        RepairActionType.LOCATE_FILE,
        RepairActionType.RESCAN_TRACK,
        RepairActionType.REMOVE_FROM_LIBRARY
    )
) {
    val category: PlayabilityCategory
        get() = when (status) {
            PlayabilityStatus.MISSING_FILE,
            PlayabilityStatus.STALE_URI,
            PlayabilityStatus.MEDIASTORE_MISMATCH -> PlayabilityCategory.MISSING_FILES

            PlayabilityStatus.PERMISSION_DENIED -> PlayabilityCategory.PERMISSION_PROBLEMS

            PlayabilityStatus.UNSUPPORTED_FORMAT,
            PlayabilityStatus.DECODER_ERROR,
            PlayabilityStatus.ZERO_AUDIO_STREAMS -> PlayabilityCategory.UNSUPPORTED_AUDIO

            PlayabilityStatus.CORRUPTED_FILE,
            PlayabilityStatus.INVALID_CONTAINER,
            PlayabilityStatus.READ_ERROR -> PlayabilityCategory.FILE_ERRORS

            else -> PlayabilityCategory.UNKNOWN
        }
}

/**
 * User-triggerable repair and diagnosis actions.
 */
enum class RepairActionType(val label: String, val description: String) {
    FIX_AUTOMATICALLY("Fix Automatically", "Search storage and reconnect file reference"),
    LOCATE_FILE("Locate File", "Pick the file manually from storage"),
    RESCAN_TRACK("Rescan & Validate", "Re-run full decode health probe"),
    REMOVE_FROM_LIBRARY("Remove from Library", "Delete this broken record from SoundSync"),
    REQUEST_PERMISSION("Grant Permission", "Request Android storage permissions"),
    VIEW_TECHNICAL_DETAILS("Technical Details", "Inspect decoder and container details")
}

/**
 * Playback error event dispatched during active playback.
 */
data class PlaybackErrorEvent(
    val track: Track,
    val status: PlayabilityStatus,
    val errorMessage: String,
    val wasAutoSkipped: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
