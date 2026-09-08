package com.example.storage

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 8: Centralized Per-File Concurrency Locking.
 *
 * Guarantees that background analysis, metadata reading/writing, and file operations
 * never concurrently access or mutate the same audio file, preventing race conditions
 * and file corruption.
 */
object FileLockManager {

    private const val TAG = "FileLockManager"
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Normalizes a file path or URI string to a consistent lock key.
     */
    fun normalizeKey(filePathOrUri: String): String {
        if (filePathOrUri.isBlank()) return "empty"
        return try {
            if (filePathOrUri.startsWith("content://")) {
                filePathOrUri.trim()
            } else {
                val clean = filePathOrUri.removePrefix("file://")
                File(clean).canonicalPath
            }
        } catch (_: Throwable) {
            filePathOrUri.trim()
        }
    }

    /**
     * Obtains the Mutex for a specific file path or URI.
     */
    private fun getMutex(key: String): Mutex {
        return fileLocks.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Executes [block] while holding the exclusive lock for [filePathOrUri].
     */
    suspend fun <T> withFileLock(filePathOrUri: String, block: suspend () -> T): T {
        val key = normalizeKey(filePathOrUri)
        val mutex = getMutex(key)
        return mutex.withLock {
            block()
        }
    }

    /**
     * Checks if a file currently has an active lock held.
     */
    fun isFileLocked(filePathOrUri: String): Boolean {
        val key = normalizeKey(filePathOrUri)
        val mutex = fileLocks[key] ?: return false
        return mutex.isLocked
    }

    /**
     * Returns current number of tracked mutexes.
     */
    fun activeLockCount(): Int = fileLocks.size
}
