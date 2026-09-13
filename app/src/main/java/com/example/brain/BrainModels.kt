package com.example.brain

/**
 * Overall lifecycle state of a track in SoundSync's Library Brain.
 */
enum class BrainProcessingState {
    PENDING,
    QUEUED,
    ANALYSING,
    PARTIALLY_COMPLETE,
    COMPLETE,
    NEEDS_REVIEW,
    FAILED,
    MISSING_FILE,
    IGNORED;

    val isActive: Boolean
        get() = this == QUEUED || this == ANALYSING

    val isTerminal: Boolean
        get() = this == COMPLETE || this == FAILED || this == MISSING_FILE || this == IGNORED
}

/**
 * Granular sub-status for an individual analysis module / phase.
 */
enum class BrainSubStatus {
    NOT_STARTED,
    QUEUED,
    RUNNING,
    COMPLETE,
    FAILED,
    SKIPPED,
    NEEDS_REVIEW;

    val isFinished: Boolean
        get() = this == COMPLETE || this == SKIPPED
}

/**
 * Categorical analysis domains managed by Library Brain.
 */
enum class BrainCategory(val displayName: String) {
    FILE_VALIDATION("File Integrity & Path"),
    METADATA("Tags & Metadata"),
    ARTWORK("Album Artwork"),
    WAVEFORM("Waveform Peak & RMS"),
    BPM_KEY("BPM & Musical Key"),
    QUALITY("Audio Quality & Bitrate"),
    REPLAY_GAIN("ReplayGain & Loudness"),
    LYRICS("Lyrics & Timestamps"),
    DUPLICATES("Duplicate Detection")
}

/**
 * Reactive summary snapshot of Library Brain state across the entire library.
 */
data class BrainSummary(
    val totalTracks: Int = 0,
    val completeCount: Int = 0,
    val analysingCount: Int = 0,
    val pendingCount: Int = 0,
    val partiallyCompleteCount: Int = 0,
    val needsReviewCount: Int = 0,
    val failedCount: Int = 0,
    val missingFilesCount: Int = 0,
    val currentJobDescription: String = "Idle",
    val currentTrackTitle: String = "",
    val queueLength: Int = 0,
    val isPaused: Boolean = false,
    val isRunning: Boolean = false,
    val lastRunTimestamp: Long = 0L
)
