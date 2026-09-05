package com.example.lyrics

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

object EmbeddedLyricsReader {

    private const val TAG = "EmbeddedLyricsReader"

    /**
     * Attempts to find a local .lrc or plain text lyric file adjacent to the audio track.
     * Priority:
     * 1. Same directory: trackName.lrc
     * 2. Same directory: Artist - Title.lrc
     * 3. Same directory: trackName.txt
     * 4. lyrics/ subdirectory: trackName.lrc
     */
    fun findLocalLrcFile(audioFilePath: String, artist: String? = null, title: String? = null): File? {
        if (audioFilePath.isBlank() || audioFilePath.startsWith("content://")) return null

        try {
            val audioFile = File(audioFilePath)
            val parentDir = audioFile.parentFile ?: return null
            val baseName = audioFile.nameWithoutExtension

            val candidateFiles = mutableListOf<File>()
            candidateFiles.add(File(parentDir, "$baseName.lrc"))
            candidateFiles.add(File(parentDir, "$baseName.LRC"))

            if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                candidateFiles.add(File(parentDir, "$artist - $title.lrc"))
                candidateFiles.add(File(parentDir, "$artist - $title.LRC"))
            }

            // Subdirectory lyrics/
            val subDir = File(parentDir, "lyrics")
            if (subDir.exists() && subDir.isDirectory) {
                candidateFiles.add(File(subDir, "$baseName.lrc"))
            }

            // Plain text fallbacks
            candidateFiles.add(File(parentDir, "$baseName.txt"))

            for (candidate in candidateFiles) {
                if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                    Log.d(TAG, "Found local lyric file: ${candidate.absolutePath}")
                    return candidate
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Error looking up local .lrc file: ${e.message}")
        }
        return null
    }

    /**
     * Reads embedded lyrics using MediaMetadataRetriever and tag scanning.
     */
    fun readEmbeddedLyrics(context: Context, audioFilePath: String): String? {
        if (audioFilePath.isBlank()) return null

        // 1. Try MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            if (audioFilePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(audioFilePath))
            } else {
                retriever.setDataSource(audioFilePath)
            }

            // Android standard MediaMetadataRetriever doesn't have a direct METADATA_KEY_LYRICS,
            // but custom tags or comment fields sometimes contain lyrics
            val comment = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION)
            retriever.release()
            if (!comment.isNullOrBlank() && (comment.contains("\n") || comment.contains("["))) {
                return comment
            }
        } catch (e: Exception) {
            // Ignore retriever failures
        }

        // 2. Direct ID3 / Vorbis comment tag probe for local files
        if (!audioFilePath.startsWith("content://")) {
            try {
                val file = File(audioFilePath)
                if (file.exists() && file.isFile) {
                    return probeFileHeaderForLyrics(file)
                }
            } catch (e: Exception) {
                Log.v(TAG, "Error probing embedded file tags: ${e.message}")
            }
        }

        return null
    }

    private fun probeFileHeaderForLyrics(file: File): String? {
        // Read the first 64KB for ID3v2 USLT / SYLT frames or Vorbis comments
        val maxBytesToRead = minOf(file.length(), 65536L).toInt()
        val buffer = ByteArray(maxBytesToRead)
        file.inputStream().use { it.read(buffer, 0, maxBytesToRead) }

        val content = String(buffer, StandardCharsets.ISO_8859_1)

        // Check for USLT frame (Unsynchronized lyrics)
        val usltIndex = content.indexOf("USLT")
        if (usltIndex in 0..(buffer.size - 20)) {
            val lyricChunk = content.substring(usltIndex + 10, minOf(content.length, usltIndex + 4096))
            val cleanLyrics = lyricChunk.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?'\"-[]:()" }
            if (cleanLyrics.length >= 10 && cleanLyrics.contains("\n")) {
                return cleanLyrics.trim()
            }
        }

        // Check for Vorbis Comment "LYRICS=" or "UNSYNCEDLYRICS="
        val lyricsTagIndex = content.indexOf("LYRICS=")
        if (lyricsTagIndex >= 0) {
            val chunk = content.substring(lyricsTagIndex + 7, minOf(content.length, lyricsTagIndex + 4096))
            val clean = chunk.takeWhile { it != '\u0000' && it != '\u0001' && it != '\u0002' }
            if (clean.length >= 10) {
                return clean.trim()
            }
        }

        return null
    }
}
