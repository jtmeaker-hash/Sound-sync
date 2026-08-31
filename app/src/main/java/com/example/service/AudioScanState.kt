package com.example.service

/**
 * Real-time state representing background DocumentFile scanning and indexing operations.
 */
data class AudioScanState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val sourceId: String = "",
    val sourceLabel: String = "",
    val currentDirectory: String = "",
    val currentFile: String = "",
    val filesDiscovered: Int = 0,
    val filesIndexed: Int = 0,
    val filesSkipped: Int = 0,
    val filesFailed: Int = 0,
    val directoriesScanned: Int = 0,
    val currentFormat: String = "",
    val currentBitrate: Int = 0,
    val scanSpeedFilesPerSec: Double = 0.0,
    val elapsedTimeMs: Long = 0L,
    val errorMessage: String? = null,
    val isCompleted: Boolean = false,
    val totalIndexedInLastRun: Int = 0,
    val summaryMessage: String = ""
) {
    val progressFraction: Float
        get() = if (filesDiscovered > 0) {
            ((filesIndexed + filesSkipped + filesFailed).toFloat() / filesDiscovered.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isIdle: Boolean
        get() = !isScanning && !isPaused
}
