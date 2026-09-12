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
    REPAIR_FAILED("Repair Failed", "Unresolved", false, true),
    VOLUME_UNAVAILABLE("Volume Disconnected", "Unmounted", false, true),
    PERMISSION_REQUIRED("Permission Required", "Need Permission", false, true),
    SOURCE_STALE("Stale Media Source", "Source Stale", false, true),
    RELOCATED("Track Relocated", "Relocated", false, true),
    EXTRACTOR_ERROR("Extractor Initialization Error", "Extractor Error", false, true),
    FORMAT_UNRECOGNIZED("Unrecognized Audio Format", "Unrecognized", false, true)
}

/**
 * Standard playback error codes differentiating source accessibility from playback/decoding issues.
 */
object PlaybackErrorCodes {
    const val ERR_SOURCE_PERMISSION = "ERR_SOURCE_PERMISSION"
    const val ERR_SOURCE_MISSING = "ERR_SOURCE_MISSING"
    const val ERR_SOURCE_IO = "ERR_SOURCE_IO"
    const val ERR_FORMAT_UNRECOGNIZED = "ERR_FORMAT_UNRECOGNIZED"
    const val ERR_EXTRACTOR_INIT = "ERR_EXTRACTOR_INIT"
    const val ERR_DECODER_INIT = "ERR_DECODER_INIT"
    const val ERR_AUDIO_CORRUPT = "ERR_AUDIO_CORRUPT"
    const val ERR_MEDIASTORE_STALE = "ERR_MEDIASTORE_STALE"
    const val ERR_SAF_PERMISSION = "ERR_SAF_PERMISSION"
    const val ERR_STORAGE_UNMOUNTED = "ERR_STORAGE_UNMOUNTED"
    const val ERR_SCOPED_STORAGE_RESTRICTION = "ERR_SCOPED_STORAGE_RESTRICTION"
}

/**
 * Hierarchical health rating for audio media sources in SoundSync.
 * Ensures verified playback sources are never replaced with inferior candidates.
 */
enum class SourceHealthTier(val score: Int) {
    VERIFIED_PLAYABLE(100),
    VERIFIED_READABLE(70),
    UNVERIFIED(50),
    PERMISSION_REQUIRED(30),
    STALE(10),
    INVALID(0);

    fun isBetterThan(other: SourceHealthTier): Boolean = this.score > other.score
    fun isAtLeast(other: SourceHealthTier): Boolean = this.score >= other.score
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
    val originalExceptionClass: String? = null,
    val originalExceptionMessage: String? = null,
    val resolvedSourceType: String? = null,
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
            PlayabilityStatus.MEDIASTORE_MISMATCH,
            PlayabilityStatus.VOLUME_UNAVAILABLE,
            PlayabilityStatus.SOURCE_STALE,
            PlayabilityStatus.RELOCATED -> PlayabilityCategory.MISSING_FILES

            PlayabilityStatus.PERMISSION_DENIED,
            PlayabilityStatus.PERMISSION_REQUIRED -> PlayabilityCategory.PERMISSION_PROBLEMS

            PlayabilityStatus.UNSUPPORTED_FORMAT,
            PlayabilityStatus.FORMAT_UNRECOGNIZED,
            PlayabilityStatus.DECODER_ERROR,
            PlayabilityStatus.EXTRACTOR_ERROR,
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
