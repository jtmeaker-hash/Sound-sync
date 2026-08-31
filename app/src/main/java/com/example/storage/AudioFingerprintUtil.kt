package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Generates fast, deterministic, and persistent fingerprints for audio files.
 * Combines file header data, sample audio chunks, exact byte size, and duration/acoustic parameters
 * to reliably detect duplicate files across scans without relying purely on filename or reading entire large audio files.
 */
object AudioFingerprintUtil {

    private const val TAG = "AudioFingerprintUtil"
    private const val CHUNK_SIZE = 32768 // 32KB sample chunks for fast hash calculation

    /**
     * Computes a content-based SHA-256 fingerprint for a local file Uri or path.
     */
    fun generateFingerprint(
        context: Context,
        uriOrPath: String,
        fileSizeBytes: Long = 0L,
        durationSeconds: Int = 0
    ): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")

            // Feed structural metadata
            digest.update("size=$fileSizeBytes;dur=$durationSeconds;".toByteArray(Charsets.UTF_8))

            val openedStream = openInputStream(context, uriOrPath)
            if (openedStream != null) {
                openedStream.use { stream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    
                    // 1. Read header chunk (contains ID3 / FLAC / MP4 atom metadata & audio frame header)
                    val bytesRead = stream.read(buffer, 0, CHUNK_SIZE)
                    if (bytesRead > 0) {
                        digest.update(buffer, 0, bytesRead)
                    }

                    // 2. Sample subsequent chunk if available
                    if (fileSizeBytes > CHUNK_SIZE * 2) {
                        val skipBytes = (fileSizeBytes / 2) - CHUNK_SIZE
                        if (skipBytes > 0) {
                            try {
                                stream.skip(skipBytes)
                                val midBytes = stream.read(buffer, 0, CHUNK_SIZE)
                                if (midBytes > 0) {
                                    digest.update(buffer, 0, midBytes)
                                }
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            } else {
                // Fallback deterministic hash when stream cannot be opened
                digest.update(uriOrPath.toByteArray(Charsets.UTF_8))
            }

            val hashBytes = digest.digest()
            val hexString = StringBuilder()
            for (b in hashBytes) {
                hexString.append(String.format(Locale.US, "%02x", b))
            }
            "fp_${hexString.toString().take(32)}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed computing deep fingerprint for $uriOrPath, using property hash: ${e.message}")
            fallbackPropertyFingerprint(uriOrPath, fileSizeBytes, durationSeconds)
        }
    }

    /**
     * Computes fingerprint for DocumentFile.
     */
    fun generateDocumentFileFingerprint(
        context: Context,
        docFile: DocumentFile,
        durationSeconds: Int = 0
    ): String {
        val size = docFile.length()
        val uriStr = docFile.uri.toString()
        return generateFingerprint(context, uriStr, size, durationSeconds)
    }

    /**
     * Fallback lightweight property fingerprint if file stream cannot be opened.
     */
    fun fallbackPropertyFingerprint(
        filePathOrUri: String,
        fileSizeBytes: Long,
        durationSeconds: Int
    ): String {
        val cleanName = filePathOrUri.substringAfterLast('/').substringAfterLast(':')
        val raw = "prop_${cleanName}_${fileSizeBytes}_${durationSeconds}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return "fp_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun openInputStream(context: Context, uriOrPath: String): InputStream? {
        return try {
            if (uriOrPath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(uriOrPath))
            } else {
                val file = if (uriOrPath.startsWith("file://")) {
                    File(Uri.parse(uriOrPath).path ?: uriOrPath.removePrefix("file://"))
                } else {
                    File(uriOrPath)
                }
                if (file.exists() && file.canRead()) {
                    file.inputStream()
                } else if (uriOrPath.startsWith("/")) {
                    File(uriOrPath).inputStream()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
