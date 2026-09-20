package com.example.diagnostics

import android.os.SystemClock
import android.util.Log

object PerformanceDiagnostics {
    private const val TAG = "SoundSyncPerf"
    private val startTimes = mutableMapOf<String, Long>()

    fun startTiming(event: String) {
        startTimes[event] = SystemClock.elapsedRealtime()
        Log.d(TAG, "START: $event")
    }

    fun endTiming(event: String) {
        val start = startTimes.remove(event)
        if (start != null) {
            val duration = SystemClock.elapsedRealtime() - start
            Log.d(TAG, "END: $event took ${duration}ms")
        }
    }

    fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        Log.d(TAG, "MEMORY: Used=${usedMb}MB / Max=${maxMb}MB")
    }
}
