package com.example.storage

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Stage 8: Centralized Per-File Concurrency Locking.
 *
 * Guarantees that background analysis, metadata reading/writing, and file operations
 * never concurrently access or mutate the same audio file, preventing race conditions
 * and file corruption.
 *
 * Fully re-entrant: if the current coroutine context already holds the lock for a given
 * normalized file path or URI, nested calls proceed immediately without deadlocking.
 */
object FileLockManager {

    private const val TAG = "FileLockManager"
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    private class FileLockElement(val lockKeyName: String) : AbstractCoroutineContextElement(Key(lockKeyName)) {
        data class Key(val lockKeyName: String) : CoroutineContext.Key<FileLockElement>
    }

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
     * If the calling coroutine already holds this file lock, the block is executed
     * re-entrantly without re-locking.
     */
    suspend fun <T> withFileLock(filePathOrUri: String, block: suspend () -> T): T {
        val key = normalizeKey(filePathOrUri)
        val lockKey = FileLockElement.Key(key)

        // If already held in this coroutine context, execute re-entrantly
        if (coroutineContext[lockKey] != null) {
            return block()
        }

        val mutex = getMutex(key)
        return mutex.withLock {
            withContext(coroutineContext + FileLockElement(key)) {
                block()
            }
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
