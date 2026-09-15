package com.example.doctor

/**
 * SoundSync Library Doctor: Audit, health diagnostic, and safe repair models.
 */

enum class DoctorCategory(val displayName: String, val shortDescription: String) {
    MISSING_ARTWORK("Missing Artwork", "Tracks missing front cover art or having unreadable art references"),
    MISSING_ARTIST("Missing / Unknown Artist", "Empty artist, 'Unknown Artist', or artist embedded in title"),
    DUPLICATE_TRACKS("Duplicate Tracks", "Exact or probable duplicate recordings in the library"),
    BROKEN_FILE_PATHS("Broken File Paths", "Database entries whose files cannot be accessed or moved"),
    CORRUPTED_AUDIO("Corrupted Audio", "Files that fail decode probe or have invalid durations"),
    SUSPICIOUS_BPM("Suspicious BPM", "Outlier, zero, or low-confidence tempo estimates"),
    SUSPICIOUS_KEY("Suspicious Key", "Low-confidence or missing musical key / Camelot estimates"),
    LOW_QUALITY_AUDIO("Low Quality Audio", "Files with low bitrate or lossy spectral cutoffs"),
    INCONSISTENT_ALBUMS("Inconsistent Albums", "Near-duplicate album names across the same artist"),
    INCOMPLETE_ANALYSIS("Incomplete Analysis", "Tracks missing DSP, waveforms, or metadata enrichment"),
    MISSING_FILES("Missing Files", "Physical files missing from local storage"),
    FAILED_BACKGROUND_JOBS("Failed Jobs", "Persistent background analysis failures requiring retry")
}

enum class DoctorIssueSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

enum class DoctorReviewStatus {
    OPEN,
    FIXED,
    IGNORED,
    NEEDS_REVIEW
}

data class DoctorIssue(
    val id: String,
    val category: DoctorCategory,
    val trackId: String?,
    val trackTitle: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val problem: String,
    val evidence: String,
    val recommendedAction: String,
    val confidence: Float = 1.0f,
    val currentValue: String? = null,
    val proposedValue: String? = null,
    val isSafeAutoRepair: Boolean = false,
    val severity: DoctorIssueSeverity = DoctorIssueSeverity.WARNING,
    val reviewStatus: DoctorReviewStatus = DoctorReviewStatus.OPEN,
    val secondaryTrackId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class DoctorHealthSummary(
    val healthScore: Int = 100,
    val totalTracks: Int = 0,
    val completeTracks: Int = 0,
    val needsReviewCount: Int = 0,
    val missingFilesCount: Int = 0,
    val failedAnalysisCount: Int = 0,
    val missingArtworkCount: Int = 0,
    val totalIssues: Int = 0,
    val safeAutoRepairCount: Int = 0,
    val categoryCounts: Map<DoctorCategory, Int> = emptyMap()
)

data class DoctorAuditReport(
    val summary: DoctorHealthSummary = DoctorHealthSummary(),
    val issues: List<DoctorIssue> = emptyList(),
    val isAuditing: Boolean = false,
    val auditProgress: Float = 0f,
    val statusMessage: String = "Ready",
    val lastAuditTime: Long = 0L
)

data class DoctorRepairResult(
    val repairedCount: Int,
    val failedCount: Int,
    val queuedBrainJobsCount: Int,
    val details: List<String>
)
