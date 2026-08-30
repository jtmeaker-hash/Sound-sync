package com.example.util

import android.os.SystemClock
import android.util.Log

object DjLogger {
    private const val TAG = "SoundSyncPerf"

    private val eventStartTimes = mutableMapOf<String, Long>()
    private val lock = Any()

    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
    }

    fun startTiming(eventTag: String, details: String = "") {
        synchronized(lock) {
            eventStartTimes[eventTag] = SystemClock.elapsedRealtime()
        }
        Log.d(TAG, "[$eventTag] START - $details")
    }

    fun endTiming(eventTag: String, details: String = ""): Long {
        val startTime = synchronized(lock) {
            eventStartTimes.remove(eventTag)
        } ?: SystemClock.elapsedRealtime()
        val elapsedMs = SystemClock.elapsedRealtime() - startTime
        Log.d(TAG, "[$eventTag] END (${elapsedMs}ms) - $details")
        return elapsedMs
    }
}
