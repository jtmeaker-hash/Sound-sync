package com.example.metadata

import android.os.Build
import android.util.Log
import com.example.model.Track

/**
 * Authoritative diagnostic logger for physical tag write failures.
 *
 * Ensures failures in the physical audio tag writing pipeline are never swallowed
 * and are formatted with complete forensic diagnostic information for Logcat.
 */
object PhysicalTagWriteLogger {

    fun logFailure(
        tag: String = "MetadataFileWriter",
        track: Track,
        uri: String,
        filePath: String?,
        mimeType: String,
        extension: String,
        isWritable: Boolean,
        exception: Throwable?
    ) {
        val exClass = exception?.javaClass?.name ?: "None"
        val exMsg = exception?.message ?: "Unknown write failure"
        val stackTrace = exception?.let { Log.getStackTraceString(it) } ?: "No stack trace available"

        val logMessage = buildString {
            appendLine("PHYSICAL_TAG_WRITE_FAILED")
            appendLine("Track: ${track.title} (${track.artist}) [id=${track.id}]")
            appendLine("URI: $uri")
            appendLine("File path if available: ${filePath ?: "N/A"}")
            appendLine("MIME type: $mimeType")
            appendLine("Extension: $extension")
            appendLine("Writable: $isWritable")
            appendLine("Android API level: ${Build.VERSION.SDK_INT}")
            appendLine("Exception class: $exClass")
            appendLine("Exception message: $exMsg")
            appendLine("Stack trace:")
            append(stackTrace)
        }
        Log.e(tag, logMessage)
    }
}
