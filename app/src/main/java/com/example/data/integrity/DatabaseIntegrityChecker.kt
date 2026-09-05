package com.example.data.integrity

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class IntegrityIssue(
    val id: String,
    val type: IntegrityIssueType,
    val trackId: String?,
    val title: String,
    val description: String,
    val isAutoRepairable: Boolean
)

enum class IntegrityIssueType {
    ORPHANED_TRACK,
    DUPLICATE_FILE_REFERENCE,
    BROKEN_METADATA,
    INVALID_ARTWORK,
    MISSING_ANALYSIS,
    STALE_FILE_PATH,
    BROKEN_PLAYLIST_REFERENCE
}

data class IntegrityReport(
    val totalTracks: Int = 0,
    val healthyTracks: Int = 0,
    val missingFilesCount: Int = 0,
    val duplicateRecordsCount: Int = 0,
    val brokenMetadataCount: Int = 0,
    val invalidArtworkCount: Int = 0,
    val missingAnalysisCount: Int = 0,
    val brokenPlaylistCount: Int = 0,
    val autoRepairableCount: Int = 0,
    val manualReviewCount: Int = 0,
    val issues: List<IntegrityIssue> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanTimestamp: Long = 0L
)

class DatabaseIntegrityChecker(
    private val context: Context,
    private val database: AppDatabase
) {
    private val trackDao = database.trackDao()
    private val playlistDao = database.playlistDao()

    private val _reportFlow = MutableStateFlow(IntegrityReport())
    val reportFlow: StateFlow<IntegrityReport> = _reportFlow.asStateFlow()

    companion object {
        private const val TAG = "IntegrityChecker"
    }

    suspend fun scanIntegrity(): IntegrityReport = withContext(Dispatchers.IO) {
        _reportFlow.value = _reportFlow.value.copy(isScanning = true)
        Log.i(TAG, "Starting library and database integrity scan...")

        val allTracks = trackDao.getAllTracksList()
        val allPlaylists = playlistDao.getAllPlaylistsSync()
        val issues = mutableListOf<IntegrityIssue>()

        var missingFiles = 0
        var duplicateRecords = 0
        var brokenMetadata = 0
        var invalidArtwork = 0
        var missingAnalysis = 0
        var brokenPlaylistRefs = 0

        // 1. Check physical file existence & duplicate physical paths
        val seenPaths = mutableMapOf<String, String>() // normalized path -> trackId
        for (track in allTracks) {
            val normPath = track.filePath.trim()
            val exists = fileExists(normPath)

            if (!exists) {
                missingFiles++
                issues.add(
                    IntegrityIssue(
                        id = "missing_${track.id}",
                        type = IntegrityIssueType.ORPHANED_TRACK,
                        trackId = track.id,
                        title = "File Missing: ${track.title}",
                        description = "Physical file cannot be accessed at path: ${track.filePath}",
                        isAutoRepairable = false // User review to avoid deleting tracks whose SD card is temporarily detached
                    )
                )
            } else {
                val existingTrackId = seenPaths[normPath]
                if (existingTrackId != null) {
                    duplicateRecords++
                    issues.add(
                        IntegrityIssue(
                            id = "dup_path_${track.id}",
                            type = IntegrityIssueType.DUPLICATE_FILE_REFERENCE,
                            trackId = track.id,
                            title = "Duplicate File Reference: ${track.title}",
                            description = "Multiple database records ($existingTrackId and ${track.id}) point to the exact same file path.",
                            isAutoRepairable = true
                        )
                    )
                } else {
                    seenPaths[normPath] = track.id
                }
            }

            // 2. Check broken metadata
            val hasBlankTitle = track.title.isBlank()
            val hasBlankArtist = track.artist.isBlank()
            val hasInvalidBpm = track.bpm < 0.0 || track.bpm > 500.0
            if (hasBlankTitle || hasBlankArtist || hasInvalidBpm) {
                brokenMetadata++
                issues.add(
                    IntegrityIssue(
                        id = "meta_${track.id}",
                        type = IntegrityIssueType.BROKEN_METADATA,
                        trackId = track.id,
                        title = "Corrupt/Incomplete Metadata: ${if (hasBlankTitle) "Untitled" else track.title}",
                        description = buildString {
                            if (hasBlankTitle) append("Title is empty. ")
                            if (hasBlankArtist) append("Artist is empty. ")
                            if (hasInvalidBpm) append("BPM (${track.bpm}) out of realistic range. ")
                        },
                        isAutoRepairable = true
                    )
                )
            }

            // 3. Check invalid artwork cache
            if (!track.artworkCachePath.isNullOrBlank()) {
                val artFile = File(track.artworkCachePath)
                if (!artFile.exists() || artFile.length() == 0L) {
                    invalidArtwork++
                    issues.add(
                        IntegrityIssue(
                            id = "art_${track.id}",
                            type = IntegrityIssueType.INVALID_ARTWORK,
                            trackId = track.id,
                            title = "Stale Artwork Cache: ${track.title}",
                            description = "Cached artwork file does not exist at ${track.artworkCachePath}",
                            isAutoRepairable = true
                        )
                    )
                }
            }

            // 4. Missing analysis
            val lacksBpm = track.bpm <= 0.0
            val lacksKey = track.musicalKey.isBlank() && track.camelotKey.isBlank()
            val lacksFingerprint = track.contentFingerprint.isBlank()
            if (lacksBpm || lacksKey || lacksFingerprint) {
                missingAnalysis++
                issues.add(
                    IntegrityIssue(
                        id = "analysis_${track.id}",
                        type = IntegrityIssueType.MISSING_ANALYSIS,
                        trackId = track.id,
                        title = "Missing Analysis: ${track.title}",
                        description = buildString {
                            if (lacksBpm) append("Missing BPM. ")
                            if (lacksKey) append("Missing Key. ")
                            if (lacksFingerprint) append("Missing Fingerprint. ")
                        },
                        isAutoRepairable = true
                    )
                )
            }
        }

        // 5. Check playlist foreign-key integrity
        val trackIdSet = allTracks.map { it.id }.toSet()
        for (playlist in allPlaylists) {
            val playlistTracks = playlistDao.getTracksForPlaylistSync(playlist.id)
            for (pt in playlistTracks) {
                if (pt.trackId !in trackIdSet) {
                    brokenPlaylistRefs++
                    issues.add(
                        IntegrityIssue(
                            id = "pl_${playlist.id}_${pt.id}",
                            type = IntegrityIssueType.BROKEN_PLAYLIST_REFERENCE,
                            trackId = pt.trackId,
                            title = "Broken Playlist Entry in '${playlist.name}'",
                            description = "Playlist refers to track ${pt.trackId} which is not present in library database.",
                            isAutoRepairable = true
                        )
                    )
                }
            }
        }

        val autoRepairable = issues.count { it.isAutoRepairable }
        val manualReview = issues.count { !it.isAutoRepairable }
        val healthy = allTracks.size - missingFiles - duplicateRecords - brokenMetadata

        val report = IntegrityReport(
            totalTracks = allTracks.size,
            healthyTracks = healthy.coerceAtLeast(0),
            missingFilesCount = missingFiles,
            duplicateRecordsCount = duplicateRecords,
            brokenMetadataCount = brokenMetadata,
            invalidArtworkCount = invalidArtwork,
            missingAnalysisCount = missingAnalysis,
            brokenPlaylistCount = brokenPlaylistRefs,
            autoRepairableCount = autoRepairable,
            manualReviewCount = manualReview,
            issues = issues,
            isScanning = false,
            lastScanTimestamp = System.currentTimeMillis()
        )

        _reportFlow.value = report
        Log.i(TAG, "Integrity scan complete: total=${allTracks.size}, issues=${issues.size}, repairable=$autoRepairable")
        report
    }

    suspend fun repairSafeIssues(): Int = withContext(Dispatchers.IO) {
        val currentReport = _reportFlow.value
        val safeIssues = currentReport.issues.filter { it.isAutoRepairable }
        var repairedCount = 0

        for (issue in safeIssues) {
            try {
                when (issue.type) {
                    IntegrityIssueType.BROKEN_PLAYLIST_REFERENCE -> {
                        // Extract playlistTrack id from issue.id: "pl_{playlistId}_{ptId}"
                        val parts = issue.id.split("_")
                        if (parts.size >= 3) {
                            val ptId = parts.last().toLongOrNull()
                            if (ptId != null) {
                                playlistDao.removeTrackByEntryId(ptId)
                                repairedCount++
                            }
                        }
                    }
                    IntegrityIssueType.INVALID_ARTWORK -> {
                        issue.trackId?.let { tid ->
                            val track = trackDao.getTrackById(tid)
                            if (track != null) {
                                trackDao.updateTrack(track.copy(artworkCachePath = null, artworkSource = null))
                                repairedCount++
                            }
                        }
                    }
                    IntegrityIssueType.DUPLICATE_FILE_REFERENCE -> {
                        // Keep primary, delete duplicate row safely
                        issue.trackId?.let { tid ->
                            trackDao.deleteTrackById(tid)
                            repairedCount++
                        }
                    }
                    IntegrityIssueType.BROKEN_METADATA -> {
                        issue.trackId?.let { tid ->
                            val track = trackDao.getTrackById(tid)
                            if (track != null) {
                                var fixed = track
                                if (fixed.title.isBlank()) {
                                    val fallbackTitle = File(fixed.filePath).nameWithoutExtension.ifBlank { "Track" }
                                    fixed = fixed.copy(title = fallbackTitle)
                                }
                                if (fixed.artist.isBlank()) {
                                    fixed = fixed.copy(artist = "Unknown Artist")
                                }
                                if (fixed.bpm < 0.0 || fixed.bpm > 500.0) {
                                    fixed = fixed.copy(bpm = 0.0, bpmConfidence = 0.0)
                                }
                                trackDao.updateTrack(fixed)
                                repairedCount++
                            }
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed repairing issue ${issue.id}: ${e.message}", e)
            }
        }

        // Re-scan after repairs
        scanIntegrity()
        repairedCount
    }

    private fun fileExists(pathOrUri: String): Boolean {
        return try {
            if (pathOrUri.isBlank()) return false
            if (pathOrUri.startsWith("content://")) {
                val uri = Uri.parse(pathOrUri)
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } else {
                val cleanPath = if (pathOrUri.startsWith("file://")) {
                    Uri.parse(pathOrUri).path ?: pathOrUri.removePrefix("file://")
                } else pathOrUri
                File(cleanPath).exists()
            }
        } catch (e: Exception) {
            false
        }
    }
}
