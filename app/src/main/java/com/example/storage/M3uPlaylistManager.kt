package com.example.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.model.Playlist
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class M3uEntry(
    val path: String,
    val durationSeconds: Int = -1,
    val title: String? = null,
    val artist: String? = null
)

data class DiscoveredPlaylist(
    val name: String,
    val uri: Uri,
    val filePath: String?,
    val relativePath: String,
    val lastModified: Long,
    val sizeBytes: Long
)

data class ImportResult(
    val playlist: Playlist,
    val matchedTracks: List<Track>,
    val missingCount: Int,
    val totalEntries: Int
)

data class ExportResult(
    val uriString: String,
    val relativePath: String,
    val trackCount: Int,
    val hasCrossStorageWarning: Boolean
)

/**
 * High-performance, Rockbox-compliant M3U8 Playlist Engine.
 * Reads, parses, generates, imports, exports, and syncs playlists
 * with standard UTF-8 M3U8 format and Rockbox storage-relative paths.
 */
object M3uPlaylistManager {

    private const val TAG = "M3uPlaylistManager"

    /**
     * Parses M3U or M3U8 text into structured entries.
     * Supports:
     * - #EXTM3U header
     * - #EXTINF:seconds,Artist - Title
     * - Forward & backslashes
     * - Blank lines & comments
     * - UTF-8 multi-byte characters
     */
    fun parseM3u(content: String, playlistDir: String = "Playlists"): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        val lines = content.lines()

        var currentDuration = -1
        var currentArtist: String? = null
        var currentTitle: String? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                // Header line, continue
                continue
            }

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                // Format: #EXTINF:123,Artist Name - Track Title
                val info = line.substringAfter("#EXTINF:").trim()
                val durationPart = info.substringBefore(",").trim()
                val tagPart = if (info.contains(",")) info.substringAfter(",").trim() else ""

                currentDuration = durationPart.toIntOrNull() ?: -1
                if (tagPart.isNotBlank()) {
                    if (tagPart.contains(" - ")) {
                        currentArtist = tagPart.substringBefore(" - ").trim()
                        currentTitle = tagPart.substringAfter(" - ").trim()
                    } else {
                        currentTitle = tagPart
                    }
                }
                continue
            }

            if (line.startsWith("#")) {
                // Other comment line, ignore
                continue
            }

            // This is a file path entry
            val resolvedPath = RockboxPathResolver.resolveTrackPathFromPlaylistEntry(playlistDir, line)
            entries.add(
                M3uEntry(
                    path = resolvedPath,
                    durationSeconds = currentDuration,
                    title = currentTitle,
                    artist = currentArtist
                )
            )

            // Reset pending EXTINF metadata
            currentDuration = -1
            currentArtist = null
            currentTitle = null
        }

        return entries
    }

    /**
     * Generates a standard UTF-8 M3U8 file string from a list of tracks.
     * Generates clean Rockbox-compatible relative paths from the playlist location.
     */
    fun generateM3u8(
        playlistName: String,
        tracks: List<Track>,
        playlistRelativePath: String = "Playlists/$playlistName.m3u8"
    ): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")

        for (track in tracks) {
            // #EXTINF:duration,Artist - Title
            val duration = track.durationSeconds.coerceAtLeast(0)
            val artist = if (track.artist.isNotBlank()) track.artist else "Unknown Artist"
            val title = if (track.title.isNotBlank()) track.title else "Unknown Track"
            sb.append("#EXTINF:$duration,$artist - $title\n")

            // Compute relative path
            val trackRelPath = if (track.storageRelativePath.isNotBlank()) {
                track.storageRelativePath
            } else {
                RockboxPathResolver.computeStorageRelativePath(track.filePath)
            }

            val relativeToPlaylist = RockboxPathResolver.calculateRelativePath(playlistRelativePath, trackRelPath)
            sb.append(relativeToPlaylist).append("\n")
        }

        return sb.toString()
    }

    /**
     * Imports an M3U / M3U8 file from Uri, parses tracks, matches against all indexed tracks.
     */
    suspend fun importM3uFromUri(
        context: Context,
        uri: Uri,
        allIndexedTracks: List<Track>
    ): ImportResult = withContext(Dispatchers.IO) {
        val fileName = getFileNameFromUri(context, uri)
        val playlistName = fileName.substringBeforeLast(".").ifBlank { "Imported Playlist" }

        val content = try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).readText()
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading playlist URI: $uri", e)
            ""
        }

        val rawRelPath = if (uri.path != null) RockboxPathResolver.computeStorageRelativePath(uri.path!!) else ""
        val playlistDir = if (rawRelPath.contains("/")) rawRelPath.substringBeforeLast("/") else "Playlists"

        val entries = parseM3u(content, playlistDir = playlistDir)
        val (matchedTracks, missingCount) = matchEntriesToTracks(entries, allIndexedTracks)

        val playlistId = "playlist_imported_${System.currentTimeMillis()}_${(100..999).random()}"
        val totalSec = matchedTracks.sumOf { it.durationSeconds }
        val hasCrossStorage = RockboxPathResolver.detectCrossStorageMismatch(matchedTracks)

        val playlist = Playlist(
            id = playlistId,
            name = playlistName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            backingFileUri = uri.toString(),
            backingRelativePath = "$playlistDir/$fileName",
            isRockboxCompatible = true,
            isImported = true,
            trackCount = matchedTracks.size,
            totalDurationSeconds = totalSec,
            tracks = matchedTracks,
            missingTrackCount = missingCount,
            hasCrossStorageWarning = hasCrossStorage
        )

        ImportResult(
            playlist = playlist,
            matchedTracks = matchedTracks,
            missingCount = missingCount,
            totalEntries = entries.size
        )
    }

    /**
     * Exports a playlist to the device's storage in standard Rockbox `/Playlists/Name.m3u8` location.
     * Uses atomic write to prevent corrupt playlist files with Scoped Storage fallbacks.
     */
    suspend fun exportPlaylistToStorage(
        context: Context,
        playlist: Playlist,
        tracks: List<Track>,
        targetTreeUri: Uri? = null
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            val safeName = playlist.name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim().ifBlank { "Playlist" }
            val fileName = "$safeName.m3u8"
            val relativePath = "Playlists/$fileName"
            val m3u8Content = generateM3u8(safeName, tracks, relativePath)
            val hasCrossStorage = RockboxPathResolver.detectCrossStorageMismatch(tracks)

            // If a target SAF tree Uri is specified (e.g. from user-selected SD card folder):
            if (targetTreeUri != null) {
                val treeDoc = DocumentFile.fromTreeUri(context, targetTreeUri)
                if (treeDoc != null && treeDoc.canWrite()) {
                    var playlistsFolder = treeDoc.findFile("Playlists")
                    if (playlistsFolder == null || !playlistsFolder.isDirectory) {
                        playlistsFolder = treeDoc.createDirectory("Playlists") ?: treeDoc
                    }

                    var targetFile = playlistsFolder.findFile(fileName)
                    if (targetFile != null) {
                        targetFile.delete()
                    }
                    targetFile = playlistsFolder.createFile("audio/x-mpegurl", fileName)

                    if (targetFile != null) {
                        context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { os ->
                            OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                                writer.write(m3u8Content)
                                writer.flush()
                            }
                        }
                        return@withContext Result.success(
                            ExportResult(
                                uriString = targetFile.uri.toString(),
                                relativePath = relativePath,
                                trackCount = tracks.size,
                                hasCrossStorageWarning = hasCrossStorage
                            )
                        )
                    }
                }
            }

            // Write to primary external storage /Playlists/, falling back to app external files or internal storage on Scoped Storage restrictions
            val storageDir = Environment.getExternalStorageDirectory()
            val primaryPlaylistsDir = File(storageDir, "Playlists")
            val playlistsDir = try {
                if (primaryPlaylistsDir.exists() || primaryPlaylistsDir.mkdirs()) {
                    primaryPlaylistsDir
                } else {
                    context.getExternalFilesDir("Playlists") ?: File(context.filesDir, "Playlists")
                }
            } catch (_: Exception) {
                context.getExternalFilesDir("Playlists") ?: File(context.filesDir, "Playlists")
            }

            if (!playlistsDir.exists()) {
                playlistsDir.mkdirs()
            }

            val targetFile = File(playlistsDir, fileName)
            val tempFile = File(playlistsDir, "$fileName.tmp_${System.currentTimeMillis()}")

            // Atomic temp-file write
            FileOutputStream(tempFile).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(m3u8Content)
                    writer.flush()
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            val finalFile = if (renamed) targetFile else tempFile

            Result.success(
                ExportResult(
                    uriString = Uri.fromFile(finalFile).toString(),
                    relativePath = relativePath,
                    trackCount = tracks.size,
                    hasCrossStorageWarning = hasCrossStorage
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export playlist '${playlist.name}' to storage", e)
            Result.failure(e)
        }
    }

    /**
     * Matches M3U entries to indexed Tracks with a 4-tier fallback:
     * 1. Exact storage-relative path
     * 2. Normalized relative path
     * 3. Normalized filename match
     * 4. Title & Artist metadata match
     */
    private fun matchEntriesToTracks(
        entries: List<M3uEntry>,
        allIndexedTracks: List<Track>
    ): Pair<List<Track>, Int> {
        val matched = mutableListOf<Track>()
        var missingCount = 0

        // Build lookup maps for O(1) matching
        val pathMap = HashMap<String, Track>()
        val fileNameMap = HashMap<String, Track>()
        val artistTitleMap = HashMap<String, Track>()

        for (track in allIndexedTracks) {
            val relPath = RockboxPathResolver.normalizePath(track.storageRelativePath.ifBlank {
                RockboxPathResolver.computeStorageRelativePath(track.filePath)
            }).lowercase()
            if (relPath.isNotBlank()) pathMap[relPath] = track

            val fileName = File(track.filePath).name.lowercase()
            if (fileName.isNotBlank()) fileNameMap[fileName] = track

            val key = "${track.artist.trim().lowercase()} - ${track.title.trim().lowercase()}"
            artistTitleMap[key] = track
        }

        for (entry in entries) {
            val normalizedPath = RockboxPathResolver.normalizePath(entry.path).lowercase()
            val entryFileName = if (entry.path.contains("/")) entry.path.substringAfterLast("/").lowercase() else entry.path.lowercase()
            val artistTitleKey = if (entry.artist != null && entry.title != null) {
                "${entry.artist.trim().lowercase()} - ${entry.title.trim().lowercase()}"
            } else null

            var found: Track? = pathMap[normalizedPath]
            if (found == null && entryFileName.isNotBlank()) {
                found = fileNameMap[entryFileName]
            }
            if (found == null && artistTitleKey != null) {
                found = artistTitleMap[artistTitleKey]
            }

            if (found != null) {
                matched.add(found)
            } else {
                missingCount++
            }
        }

        return Pair(matched, missingCount)
    }

    /**
     * Auto-discovers existing .m3u and .m3u8 files in storage Playlists directory.
     */
    suspend fun discoverPlaylistsInStorage(context: Context): List<DiscoveredPlaylist> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredPlaylist>()
        try {
            val storageDir = Environment.getExternalStorageDirectory()
            val playlistsDir = File(storageDir, "Playlists")
            if (playlistsDir.exists() && playlistsDir.isDirectory) {
                playlistsDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true)) {
                        discovered.add(
                            DiscoveredPlaylist(
                                name = name.substringBeforeLast("."),
                                uri = Uri.fromFile(file),
                                filePath = file.absolutePath,
                                relativePath = "Playlists/$name",
                                lastModified = file.lastModified(),
                                sizeBytes = file.length()
                            )
                        )
                    }
                }
            }

            // Also check Music/Playlists
            val musicPlaylistsDir = File(storageDir, "Music/Playlists")
            if (musicPlaylistsDir.exists() && musicPlaylistsDir.isDirectory) {
                musicPlaylistsDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true)) {
                        discovered.add(
                            DiscoveredPlaylist(
                                name = name.substringBeforeLast("."),
                                uri = Uri.fromFile(file),
                                filePath = file.absolutePath,
                                relativePath = "Music/Playlists/$name",
                                lastModified = file.lastModified(),
                                sizeBytes = file.length()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning storage for playlist files: ${e.message}")
        }
        discovered
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var result = "playlist.m3u8"
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex) ?: result
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }
        if (result == "playlist.m3u8" && uri.path != null) {
            val cut = uri.path!!.lastIndexOf('/')
            if (cut != -1) {
                result = uri.path!!.substring(cut + 1)
            }
        }
        return result
    }
}
