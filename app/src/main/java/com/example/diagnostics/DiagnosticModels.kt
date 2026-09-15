package com.example.diagnostics

import com.example.model.Track
import com.example.ui.RepeatMode

/**
 * Subsystem identifiers for structured diagnostic events and error logging.
 */
enum class DiagnosticSubsystem {
    PLAYBACK,
    WAVEFORM,
    QUEUE,
    AUDIO_OUTPUT,
    LIBRARY_BRAIN,
    METADATA,
    STORAGE,
    DATABASE,
    NETWORK,
    DECODER,
    SYSTEM
}

/**
 * Severity levels for diagnostic logs.
 */
enum class DiagnosticSeverity {
    INFO,
    WARN,
    ERROR,
    CRITICAL
}

/**
 * Structured entry in the in-app rolling diagnostic error log.
 */
data class DiagnosticLogEntry(
    val id: Long,
    val timestamp: Long,
    val subsystem: DiagnosticSubsystem,
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val trackId: String? = null,
    val filePath: String? = null,
    val stackTrace: String? = null,
    val recoverable: Boolean = true
)

/**
 * Live technical snapshot of the playback subsystem.
 */
data class PlaybackDiagnosticsSnapshot(
    val playbackState: String, // "PLAYING", "PAUSED", "BUFFERING", "IDLE", "ENDED"
    val isPlaying: Boolean,
    val currentTrackId: String?,
    val currentTrackTitle: String?,
    val currentArtist: String?,
    val filePathOrUri: String?,
    val fileExists: Boolean,
    val decoderInUse: String,
    val containerFormat: String,
    val codecMime: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val reportedBitrateKbps: Int,
    val durationMs: Long,
    val currentPositionMs: Long,
    val playbackProgress: Float,
    val playbackSpeed: Float,
    val audioSessionId: Int,
    val hasAudioFocus: Boolean,
    val repeatMode: RepeatMode,
    val isShuffleEnabled: Boolean
)

/**
 * Live technical snapshot of the waveform sync and drift subsystem.
 */
data class WaveformDiagnosticsSnapshot(
    val isGenerated: Boolean,
    val sourceVersion: String,
    val audioDurationMs: Long,
    val playbackPositionMs: Long,
    val waveformCursorPositionMs: Long,
    val expectedPositionMs: Long,
    val driftMs: Long,
    val hasSyncWarning: Boolean,
    val warningMessage: String? = null
)

/**
 * Live technical snapshot of the queue subsystem.
 */
data class QueueDiagnosticsSnapshot(
    val queueSize: Int,
    val currentIndex: Int,
    val currentItem: Track?,
    val previousItem: Track?,
    val nextItem: Track?,
    val isShuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val historyCount: Int,
    val isTransitionArmed: Boolean,
    val lastTransitionResult: String
)

/**
 * Live technical snapshot of audio output routing and Bluetooth.
 */
data class AudioOutputDiagnosticsSnapshot(
    val bluetoothState: String, // "ENABLED", "DISABLED", "UNAVAILABLE"
    val connectedDevices: List<String>,
    val activeRoute: String, // "BUILTIN_SPEAKER", "WIRED_HEADPHONES", "BLUETOOTH_A2DP", "USB_AUDIO", "OTHER"
    val lastConnectEvent: String?,
    val lastDisconnectEvent: String?,
    val autoPauseOnDisconnectFired: Boolean,
    val lastAudioFocusEvent: String?,
    val lastNoisyEvent: String?
)

/**
 * Live technical snapshot of app and host device health.
 */
data class AppHealthDiagnosticsSnapshot(
    val appVersion: String,
    val buildNumber: Int,
    val databaseVersion: Int,
    val androidVersion: String,
    val sdkInt: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val availableStorageBytes: Long,
    val totalStorageBytes: Long,
    val usedHeapBytes: Long,
    val maxHeapBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val recentErrorCount: Int,
    val networkStatus: String,
    val isIgnoringBatteryOptimizations: Boolean,
    val mediaPermissionGranted: Boolean,
    val notificationPermissionGranted: Boolean
)

/**
 * Live technical snapshot of metadata and enrichment operations.
 */
data class MetadataDiagnosticsSnapshot(
    val lastProviderAttempted: String?,
    val lastSearchQuery: String?,
    val lastResultCount: Int,
    val lastSelectedResult: String?,
    val confidenceScore: Double,
    val artworkSource: String?,
    val lyricsSource: String?,
    val lastMetadataError: String?
)

/**
 * Status of an individual module during the SoundSync Self-Test.
 */
enum class SelfTestStatus {
    IDLE,
    RUNNING,
    PASS,
    WARNING,
    FAIL,
    SKIPPED
}

/**
 * Overall system health rating derived from the self-test execution.
 */
enum class OverallHealth {
    GOOD,
    WARNING,
    DEGRADED,
    CRITICAL
}

/**
 * Detailed outcome of a single self-test module.
 */
data class SelfTestResult(
    val moduleName: String,
    val status: SelfTestStatus,
    val summary: String,
    val technicalDetails: String,
    val likelyCause: String? = null,
    val suggestedFix: String? = null,
    val durationMs: Long = 0L
)

/**
 * Aggregate state of the SoundSync Self-Test suite.
 */
data class SelfTestSuiteState(
    val isRunning: Boolean = false,
    val overallHealth: OverallHealth = OverallHealth.GOOD,
    val results: Map<String, SelfTestResult> = emptyMap(),
    val progress: Float = 0f,
    val completedCount: Int = 0,
    val totalCount: Int = 11
)
