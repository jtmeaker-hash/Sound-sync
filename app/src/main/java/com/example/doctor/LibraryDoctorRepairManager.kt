package com.example.doctor

import android.content.Context
import android.util.Log
import com.example.brain.BrainCategory
import com.example.brain.LibraryBrain
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.storage.TrackSelfHealingResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SoundSync Library Doctor Repair Manager:
 * Orchestrates safe automated repairs and destructive manual repairs.
 * All background repair jobs are dispatched exclusively to Library Brain.
 */
class LibraryDoctorRepairManager(
    private val context: Context,
    database: AppDatabase = AppDatabase.getDatabase(context),
    private val brain: LibraryBrain = LibraryBrain.getInstance(context),
    private val preferences: LibraryDoctorPreferences = LibraryDoctorPreferences.getInstance(context),
    trackDaoOverride: TrackDao? = null
) {
    companion object {
        private const val TAG = "DoctorRepairManager"
    }

    private val trackDao: TrackDao = trackDaoOverride ?: database.trackDao()

    /**
     * Executes Fix All for all safe, high-confidence, non-destructive issues.
     */
    suspend fun repairSafeIssues(issues: List<DoctorIssue>): DoctorRepairResult = withContext(Dispatchers.IO) {
        val safeIssues = issues.filter { it.isSafeAutoRepair && it.reviewStatus == DoctorReviewStatus.OPEN }
        var repairedCount = 0
        var queuedBrainJobs = 0
        var failedCount = 0
        val details = mutableListOf<String>()

        Log.i(TAG, "Starting safe auto-repair on ${safeIssues.size} issues...")

        for (issue in safeIssues) {
            try {
                when (issue.category) {
                    DoctorCategory.MISSING_ARTWORK -> {
                        issue.trackId?.let { tid ->
                            brain.requestCategoryRepairForTrack(tid, BrainCategory.ARTWORK)
                            preferences.markFixed(issue.id)
                            queuedBrainJobs++
                            repairedCount++
                            details.add("Queued artwork fetch for '${issue.trackTitle}' in Library Brain")
                        }
                    }

                    DoctorCategory.MISSING_ARTIST -> {
                        issue.trackId?.let { tid ->
                            val entity = trackDao.getTrackById(tid)
                            if (entity != null) {
                                // If embedded artist in title was identified
                                if (issue.problem.contains("embedded", ignoreCase = true) && issue.proposedValue != null) {
                                    val parts = issue.trackTitle.split(" - ", limit = 2)
                                    if (parts.size == 2) {
                                        val newArtist = parts[0].trim()
                                        val newTitle = parts[1].trim()
                                        trackDao.updateTrack(entity.copy(artist = newArtist, title = newTitle))
                                        preferences.markFixed(issue.id)
                                        repairedCount++
                                        details.add("Split embedded title for '${entity.title}' -> Artist: '$newArtist', Title: '$newTitle'")
                                    }
                                } else {
                                    brain.requestCategoryRepairForTrack(tid, BrainCategory.METADATA)
                                    preferences.markFixed(issue.id)
                                    queuedBrainJobs++
                                    repairedCount++
                                    details.add("Queued metadata enrichment for '${issue.trackTitle}' in Library Brain")
                                }
                            }
                        }
                    }

                    DoctorCategory.SUSPICIOUS_KEY -> {
                        issue.trackId?.let { tid ->
                            brain.requestCategoryRepairForTrack(tid, BrainCategory.BPM_KEY)
                            preferences.markFixed(issue.id)
                            queuedBrainJobs++
                            repairedCount++
                            details.add("Queued harmonic key re-analysis for '${issue.trackTitle}' in Library Brain")
                        }
                    }

                    DoctorCategory.INCOMPLETE_ANALYSIS -> {
                        issue.trackId?.let { tid ->
                            brain.reanalyseTrack(tid)
                            preferences.markFixed(issue.id)
                            queuedBrainJobs++
                            repairedCount++
                            details.add("Queued complete analysis for '${issue.trackTitle}' in Library Brain")
                        }
                    }

                    DoctorCategory.FAILED_BACKGROUND_JOBS -> {
                        issue.trackId?.let { tid ->
                            brain.reanalyseTrack(tid)
                            preferences.markFixed(issue.id)
                            queuedBrainJobs++
                            repairedCount++
                            details.add("Reset and requeued failed job for '${issue.trackTitle}' in Library Brain")
                        }
                    }

                    DoctorCategory.BROKEN_FILE_PATHS -> {
                        issue.trackId?.let { tid ->
                            val entity = trackDao.getTrackById(tid)
                            if (entity != null) {
                                val healed = TrackSelfHealingResolver.healTrack(context, entity.toTrack(), trackDao)
                                if (healed != null) {
                                    preferences.markFixed(issue.id)
                                    repairedCount++
                                    details.add("Self-healed broken path for '${entity.title}'")
                                } else {
                                    failedCount++
                                }
                            }
                        }
                    }

                    else -> {
                        // Unsafe or manual issue skipped
                    }
                }
            } catch (e: Exception) {
                failedCount++
                Log.e(TAG, "Failed repairing issue ${issue.id}: ${e.message}", e)
                details.add("Failed repairing '${issue.trackTitle}': ${e.message}")
            }
        }

        Log.i(TAG, "Safe repair finished. Repaired: $repairedCount (Brain queued: $queuedBrainJobs), Failed: $failedCount")
        DoctorRepairResult(
            repairedCount = repairedCount,
            failedCount = failedCount,
            queuedBrainJobsCount = queuedBrainJobs,
            details = details
        )
    }

    /**
     * Repairs a single issue individually.
     */
    suspend fun repairSingleIssue(issue: DoctorIssue): Boolean = withContext(Dispatchers.IO) {
        val res = repairSafeIssues(listOf(issue))
        res.repairedCount > 0
    }

    /**
     * Destructive operation: Removes a stale database entry whose file no longer exists.
     * Requires explicit confirmation from UI.
     */
    suspend fun removeStaleDatabaseEntry(trackId: String, issueId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            trackDao.deleteTrackById(trackId)
            preferences.markFixed(issueId)
            Log.i(TAG, "Removed stale database record for track $trackId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting stale track $trackId: ${e.message}", e)
            false
        }
    }

    /**
     * Destructive operation: Deletes duplicate track from library and optionally deletes physical file.
     * Requires explicit confirmation from UI.
     */
    suspend fun deleteDuplicateTrack(
        trackId: String,
        filePath: String,
        deletePhysicalFile: Boolean,
        issueId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (deletePhysicalFile && !filePath.startsWith("content://")) {
                val f = File(filePath)
                if (f.exists()) {
                    f.delete()
                    Log.i(TAG, "Deleted physical duplicate file: $filePath")
                }
            }
            trackDao.deleteTrackById(trackId)
            preferences.markFixed(issueId)
            Log.i(TAG, "Deleted duplicate track record $trackId from library database")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing duplicate track $trackId: ${e.message}", e)
            false
        }
    }

    /**
     * Manual adjustment: Updates track BPM.
     */
    suspend fun updateTrackBpm(trackId: String, newBpm: Double, issueId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entity = trackDao.getTrackById(trackId) ?: return@withContext false
            val updated = entity.copy(
                bpm = newBpm,
                bpmConfidence = 1.0,
                bpmAnalysisVersion = "v2_doctor_adjusted",
                bpmLastAnalyzed = System.currentTimeMillis()
            )
            trackDao.updateTrack(updated)
            preferences.markFixed(issueId)
            Log.i(TAG, "Updated BPM for track $trackId to $newBpm")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating BPM for track $trackId: ${e.message}", e)
            false
        }
    }

    /**
     * Manual adjustment: Updates track musical key.
     */
    suspend fun updateTrackKey(trackId: String, newKey: String, camelotKey: String, issueId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entity = trackDao.getTrackById(trackId) ?: return@withContext false
            val updated = entity.copy(
                musicalKey = newKey,
                camelotKey = camelotKey,
                keyConfidence = 1.0,
                keyAnalysisVersion = "v2_doctor_adjusted",
                keyLastAnalyzed = System.currentTimeMillis()
            )
            trackDao.updateTrack(updated)
            preferences.markFixed(issueId)
            Log.i(TAG, "Updated musical key for track $trackId to $newKey ($camelotKey)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating key for track $trackId: ${e.message}", e)
            false
        }
    }

    /**
     * Standardizes album name across an artist's tracks.
     */
    suspend fun standardizeAlbum(artist: String, oldAlbum: String, targetAlbum: String, issueId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val allTracks = trackDao.getAllTracksList()
            var modified = 0
            for (t in allTracks) {
                if (t.artist.equals(artist, ignoreCase = true) && t.album.trim().equals(oldAlbum.trim(), ignoreCase = true)) {
                    trackDao.updateTrack(t.copy(album = targetAlbum))
                    modified++
                }
            }
            preferences.markFixed(issueId)
            Log.i(TAG, "Standardized album to '$targetAlbum' across $modified tracks for artist '$artist'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error standardizing album: ${e.message}", e)
            false
        }
    }

    fun ignoreIssue(issueId: String) {
        preferences.ignoreIssue(issueId)
    }

    fun unignoreIssue(issueId: String) {
        preferences.unignoreIssue(issueId)
    }

    fun markReviewed(issueId: String) {
        preferences.markReviewed(issueId)
    }
}
