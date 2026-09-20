package com.example

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Custom Application class for SoundSync.
 * Installs a robust global uncaught exception handler to prevent silent process exits,
 * captures diagnostic crash dumps to persistent disk, and records memory/lifecycle metrics.
 */
class SoundSyncApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        com.example.diagnostics.PerformanceDiagnostics.startTiming("AppInitialization")
        instance = this
        setupGlobalCrashHandler()
        Log.i(TAG, "SoundSyncApplication initialized. SDK: ${Build.VERSION.SDK_INT}, PID: ${Process.myPid()}")
        com.example.diagnostics.PerformanceDiagnostics.endTiming("AppInitialization")
    }

    private fun setupGlobalCrashHandler() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "FATAL UNCAUGHT EXCEPTION on thread '${thread.name}' (id=${thread.id}): ${throwable.message}", throwable)

                // Record crash for startup loop recovery
                try {
                    com.example.util.CrashProtectionManager.recordCrash(this)
                } catch (_: Throwable) {}

                // Write crash report to internal storage so it survives process death
                val crashDir = File(filesDir, "crashes")
                if (!crashDir.exists()) {
                    crashDir.mkdirs()
                }
                val crashFile = File(crashDir, "last_crash.txt")

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))

                val runtime = Runtime.getRuntime()
                val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val totalMb = runtime.totalMemory() / (1024 * 1024)
                val maxMb = runtime.maxMemory() / (1024 * 1024)

                val report = buildString {
                    appendLine("=== SoundSync Fatal Crash Report ===")
                    appendLine("Timestamp: ${System.currentTimeMillis()} (${Date()})")
                    appendLine("Thread: ${thread.name} (id=${thread.id})")
                    appendLine("Exception Type: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine("Cause: ${throwable.cause?.javaClass?.name}: ${throwable.cause?.message}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Brand: ${Build.BRAND}, Product: ${Build.PRODUCT})")
                    appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Memory: Used=${usedMb}MB, Allocated=${totalMb}MB, MaxHeap=${maxMb}MB")
                    appendLine("Process PID: ${Process.myPid()}")
                    appendLine("--- Stack Trace ---")
                    appendLine(sw.toString())
                }

                crashFile.writeText(report)
            } catch (loggingError: Throwable) {
                Log.e(TAG, "Failed to write persistent crash dump: ${loggingError.message}", loggingError)
            } finally {
                // Delegate to original system handler so Android can gracefully manage the process death
                originalHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val TAG = "SoundSyncApp"

        @Volatile
        var instance: SoundSyncApplication? = null
            private set
    }
}
