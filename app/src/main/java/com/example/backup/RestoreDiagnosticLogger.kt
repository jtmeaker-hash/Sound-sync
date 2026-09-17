package com.example.backup

import android.util.Log

/**
 * Diagnostic logger for the SoundSync backup restoration pipeline.
 * Instruments restore stages, elapsed time, memory usage, thread identification,
 * and tracks background system startup following restore completion.
 */
object RestoreDiagnosticLogger {
    private const val TAG = "SoundSyncRestoreDiagnostic"
    private val logBuffer = mutableListOf<String>()
    private val lock = Any()

    fun reset() {
        synchronized(lock) {
            logBuffer.clear()
        }
    }

    fun dump(): String = synchronized(lock) {
        logBuffer.joinToString("\n")
    }

    private fun addLog(entry: String) {
        synchronized(lock) {
            if (logBuffer.size >= 500) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(entry)
        }
    }

    fun logCoreRestoreComplete(restoredTracks: Int, matchedTracks: Int, restoredFinds: Int, elapsedMs: Long) {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val msg = "CORE_RESTORE_COMPLETE in ${elapsedMs}ms | mem=${usedMemMb}MB | restoredTracks=$restoredTracks, matchedTracks=$matchedTracks, restoredFinds=$restoredFinds"
        addLog(msg)
        Log.i(TAG, "================================================================================")
        Log.i(TAG, "BACKUP CORE RESTORE COMPLETE in ${elapsedMs}ms | mem=${usedMemMb}MB")
        Log.i(TAG, "Restored new tracks: $restoredTracks | Matched tracks: $matchedTracks | Song Finds: $restoredFinds")
        Log.i(TAG, "================================================================================")
    }

    fun logSystemPostRestore(systemName: String, queueCount: Int, details: String = "") {
        val entry = "[POST_RESTORE] ${systemName}_STARTED (queueCount=$queueCount${if (details.isNotBlank()) ", $details" else ""})"
        addLog(entry)
        Log.i(TAG, entry)
    }

    fun logPostRestoreStartup(
        analysisQueuePending: Int,
        brainQueuePending: Int,
        autoBackupSuppressed: Boolean,
        scannerActive: Boolean
    ) {
        val entry = "[POST_RESTORE_STARTUP_STATUS] analysisQueuePending=$analysisQueuePending, brainQueuePending=$brainQueuePending, autoBackupSuppressed=$autoBackupSuppressed, scannerActive=$scannerActive"
        addLog(entry)
        Log.i(TAG, entry)
    }

    fun logStage(stage: RestoreStage, elapsedMs: Long, recordsProcessed: Int, totalRecords: Int, message: String) {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val threadName = Thread.currentThread().name
        val entry = "[$stage] elapsed=${elapsedMs}ms | thread='$threadName' | records=$recordsProcessed/$totalRecords | mem=${usedMemMb}MB | $message"
        addLog(entry)
        Log.d(TAG, entry)
    }

    fun logError(stage: RestoreStage, message: String, error: Throwable?) {
        val entry = "[$stage FAILED] $message: ${error?.message}"
        addLog(entry)
        Log.e(TAG, entry, error)
    }
}
