package com.example.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Thread-safe in-app rolling diagnostic and error logger.
 *
 * Maintains a capped circular buffer of recent diagnostic entries (default 100)
 * to ensure memory never grows unbounded while providing deep real-time visibility
 * into system health, background jobs, playback failures, and codec issues.
 *
 * Automatically redacts API keys, secret tokens, bearer credentials, and passwords
 * from log messages and stack traces.
 */
class DiagnosticLogger private constructor() {

    companion object {
        private const val TAG = "DiagnosticLogger"
        const val MAX_LOG_ENTRIES = 100
        const val TEST_ENTRY_CODE = "SELFTEST_LOG_VERIFY"

        // Regex patterns and safe replacements for redacting sensitive secrets, keys, and authorization headers
        private val SENSITIVE_REPLACEMENTS = listOf(
            Pair(
                Pattern.compile("(?i)(api[_-]?key|apikey|secret|token|password|auth|authorization)\\s*[:=]\\s*['\"]?([^'\"\\s&,]+)", Pattern.CASE_INSENSITIVE),
                "$1=[REDACTED]"
            ),
            Pair(
                Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9_.-]{10,}", Pattern.CASE_INSENSITIVE),
                "bearer [REDACTED]"
            ),
            Pair(
                Pattern.compile("(?i)ghp_[A-Za-z0-9]{20,}", Pattern.CASE_INSENSITIVE),
                "[REDACTED_GH_TOKEN]"
            ),
            Pair(
                Pattern.compile("(?i)github_pat_[A-Za-z0-9_]{20,}", Pattern.CASE_INSENSITIVE),
                "[REDACTED_GH_TOKEN]"
            )
        )

        @Volatile
        private var instance: DiagnosticLogger? = null

        fun getInstance(): DiagnosticLogger {
            return instance ?: synchronized(this) {
                instance ?: DiagnosticLogger().also { instance = it }
            }
        }

        /**
         * Redacts potential sensitive secrets, auth tokens, passwords, and keys from [text].
         */
        fun sanitize(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            var sanitized: String = text
            for ((pattern, replacement) in SENSITIVE_REPLACEMENTS) {
                sanitized = pattern.matcher(sanitized).replaceAll(replacement)
            }
            return sanitized
        }
    }

    private val entryIdSequence = AtomicLong(1L)
    private val bufferLock = Any()
    private val entryList = ArrayDeque<DiagnosticLogEntry>(MAX_LOG_ENTRIES + 10)

    private val _entriesFlow = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<DiagnosticLogEntry>> = _entriesFlow.asStateFlow()

    fun log(
        subsystem: DiagnosticSubsystem,
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        trackId: String? = null,
        filePath: String? = null,
        throwable: Throwable? = null,
        recoverable: Boolean = true
    ) {
        val sanitizedMessage = sanitize(message)
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sanitize(sw.toString())
        }

        val entry = DiagnosticLogEntry(
            id = entryIdSequence.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            subsystem = subsystem,
            severity = severity,
            code = code,
            message = sanitizedMessage,
            trackId = trackId,
            filePath = filePath,
            stackTrace = stackTrace,
            recoverable = recoverable
        )

        synchronized(bufferLock) {
            entryList.addFirst(entry) // Newest first
            while (entryList.size > MAX_LOG_ENTRIES) {
                entryList.removeLast()
            }
            _entriesFlow.value = entryList.toList()
        }

        when (severity) {
            DiagnosticSeverity.INFO -> Log.i(TAG, "[$subsystem][$code] $sanitizedMessage")
            DiagnosticSeverity.WARN -> Log.w(TAG, "[$subsystem][$code] $sanitizedMessage", throwable)
            DiagnosticSeverity.ERROR, DiagnosticSeverity.CRITICAL -> Log.e(TAG, "[$subsystem][$code] $sanitizedMessage", throwable)
        }
    }

    fun info(subsystem: DiagnosticSubsystem, code: String, message: String, trackId: String? = null, filePath: String? = null) {
        log(subsystem, DiagnosticSeverity.INFO, code, message, trackId, filePath)
    }

    fun warn(subsystem: DiagnosticSubsystem, code: String, message: String, trackId: String? = null, filePath: String? = null, throwable: Throwable? = null) {
        log(subsystem, DiagnosticSeverity.WARN, code, message, trackId, filePath, throwable)
    }

    fun error(subsystem: DiagnosticSubsystem, code: String, message: String, trackId: String? = null, filePath: String? = null, throwable: Throwable? = null, recoverable: Boolean = true) {
        log(subsystem, DiagnosticSeverity.ERROR, code, message, trackId, filePath, throwable, recoverable)
    }

    fun critical(subsystem: DiagnosticSubsystem, code: String, message: String, trackId: String? = null, filePath: String? = null, throwable: Throwable? = null) {
        log(subsystem, DiagnosticSeverity.CRITICAL, code, message, trackId, filePath, throwable, recoverable = false)
    }

    fun getEntries(): List<DiagnosticLogEntry> {
        synchronized(bufferLock) {
            return entryList.toList()
        }
    }

    fun getRecentErrorCount(): Int {
        synchronized(bufferLock) {
            return entryList.count { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.CRITICAL }
        }
    }

    fun clear() {
        synchronized(bufferLock) {
            entryList.clear()
            _entriesFlow.value = emptyList()
        }
    }

    /**
     * Adds a transient test entry to verify that diagnostic logging is active and functional.
     */
    fun addTestEntry(): Long {
        val entry = DiagnosticLogEntry(
            id = entryIdSequence.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            subsystem = DiagnosticSubsystem.SYSTEM,
            severity = DiagnosticSeverity.INFO,
            code = TEST_ENTRY_CODE,
            message = "SoundSync self-test diagnostic log verification",
            recoverable = true
        )
        synchronized(bufferLock) {
            entryList.addFirst(entry)
            _entriesFlow.value = entryList.toList()
        }
        return entry.id
    }

    /**
     * Removes the transient self-test verification entry if present.
     */
    fun removeTestEntry(id: Long? = null) {
        synchronized(bufferLock) {
            if (id != null) {
                entryList.removeAll { it.id == id }
            } else {
                entryList.removeAll { it.code == TEST_ENTRY_CODE }
            }
            _entriesFlow.value = entryList.toList()
        }
    }
}
