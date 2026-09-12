package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.analysis.PlayabilityValidator
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.PlayabilityDiagnosticReport
import com.example.model.PlayabilityStatus
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-reliability automatic and manual repair engine for unplayable SoundSync tracks.
 *
 * Reconnects broken, relocated, or stale media references while preserving 100% of existing
 * track metadata, BPM, key analysis, hot cues, ratings, notes, and playlist associations.
 */
object TrackPlaybackRepairEngine {

    private const val TAG = "SoundSyncPlaybackRepair"

    data class RepairResult(
        val success: Boolean,
        val track: Track,
        val previousPath: String,
        val newPath: String?,
        val message: String,
        val diagnosticReport: PlayabilityDiagnosticReport
    )

    data class BatchRepairSummary(
        val totalProcessed: Int,
        val totalRepaired: Int,
        val totalFailed: Int,
        val results: List<RepairResult>
    )

    /**
     * Attempts safe automatic repair for an unplayable track.
     */
    suspend fun autoRepairTrack(
        context: Context,
        track: Track,
        trackDao: TrackDao
    ): RepairResult = withContext(Dispatchers.IO) {
        val originalPath = track.filePath
        Log.i(TAG, "Starting automatic repair for track '${track.title}' (id=${track.id}, path='$originalPath')")

        // 1. First test if already accessible (e.g. permission was just granted or USB remounted)
        val initialValidation = PlayabilityValidator.validateTrack(context, track, forceFresh = true)
        if (initialValidation.status == PlayabilityStatus.PLAYABLE) {
            val resolved = initialValidation.resolvedPath ?: originalPath
            val updated = track.copy(
                filePath = resolved,
                resolvedUri = resolved,
                playabilityStatus = PlayabilityStatus.PLAYABLE.name,
                playbackErrorCode = null,
                playbackErrorMessage = null,
                lastPlaybackValidation = System.currentTimeMillis(),
                lastRepairAttempt = System.currentTimeMillis()
            )
            trackDao.updateTrack(TrackEntity.fromTrack(updated))
            return@withContext RepairResult(
                success = true,
                track = updated,
                previousPath = originalPath,
                newPath = resolved,
                message = "Track audio file is now accessible and verified playable.",
                diagnosticReport = initialValidation
            )
        }

        // 2. Multi-tier self-healing pipeline via TrackSelfHealingResolver
        val healedPath = TrackSelfHealingResolver.resolveAnyPlayablePath(context, track)

        if (healedPath != null && healedPath != originalPath) {
            val testTrack = track.copy(filePath = healedPath)
            val validation = PlayabilityValidator.validateTrack(context, testTrack, forceFresh = true)

            if (validation.status == PlayabilityStatus.PLAYABLE) {
                // Determine storage relative path
                val dir = if (healedPath.contains("/")) healedPath.substringBeforeLast("/") else "/Music"
                val resolvedStoragePath = if (healedPath.contains("/storage/emulated/0/")) {
                    healedPath.substringAfter("/storage/emulated/0/").trimStart('/')
                } else if (healedPath.startsWith("/")) {
                    healedPath.trimStart('/')
                } else {
                    track.storageRelativePath
                }

                val repairedTrack = track.copy(
                    filePath = healedPath,
                    directoryPath = dir,
                    storageRelativePath = resolvedStoragePath,
                    playabilityStatus = PlayabilityStatus.PLAYABLE.name,
                    playbackErrorCode = null,
                    playbackErrorMessage = null,
                    lastPlaybackValidation = System.currentTimeMillis(),
                    lastRepairAttempt = System.currentTimeMillis(),
                    resolvedUri = healedPath
                )

                // Persist update in database without affecting other metadata
                trackDao.updateTrack(TrackEntity.fromTrack(repairedTrack))
                Log.i(TAG, "SUCCESS: Automatically repaired '${track.title}' -> '$healedPath'")

                return@withContext RepairResult(
                    success = true,
                    track = repairedTrack,
                    previousPath = originalPath,
                    newPath = healedPath,
                    message = "Successfully reconnected track to '$healedPath'.",
                    diagnosticReport = validation
                )
            }
        }

        // 3. Fallback: Search Canonical Storage Locations
        val canonicalPath = CanonicalStorageHelper.resolvePlayableReference(context, originalPath)
        if (canonicalPath != null && canonicalPath != originalPath) {
            val testTrack = track.copy(filePath = canonicalPath)
            val validation = PlayabilityValidator.validateTrack(context, testTrack, forceFresh = true)

            if (validation.status == PlayabilityStatus.PLAYABLE) {
                val repairedTrack = track.copy(
                    filePath = canonicalPath,
                    playabilityStatus = PlayabilityStatus.PLAYABLE.name,
                    playbackErrorCode = null,
                    playbackErrorMessage = null,
                    lastPlaybackValidation = System.currentTimeMillis(),
                    lastRepairAttempt = System.currentTimeMillis(),
                    resolvedUri = canonicalPath
                )
                trackDao.updateTrack(TrackEntity.fromTrack(repairedTrack))
                Log.i(TAG, "SUCCESS via Canonical helper: repaired '${track.title}' -> '$canonicalPath'")

                return@withContext RepairResult(
                    success = true,
                    track = repairedTrack,
                    previousPath = originalPath,
                    newPath = canonicalPath,
                    message = "Successfully resolved file location.",
                    diagnosticReport = validation
                )
            }
        }

        // Failed to repair automatically
        val failedReport = PlayabilityValidator.validateTrack(context, track, forceFresh = true)
        val failedTrack = track.copy(
            playabilityStatus = failedReport.status.name,
            playbackErrorCode = failedReport.errorCode,
            playbackErrorMessage = failedReport.errorMessage,
            lastPlaybackValidation = System.currentTimeMillis(),
            lastRepairAttempt = System.currentTimeMillis()
        )
        trackDao.updateTrack(TrackEntity.fromTrack(failedTrack))
        Log.w(TAG, "Automatic repair failed for '${track.title}': ${failedReport.detectedReason}")

        RepairResult(
            success = false,
            track = failedTrack,
            previousPath = originalPath,
            newPath = null,
            message = "Could not find a matching audio file automatically. Try 'Locate File' manually.",
            diagnosticReport = failedReport
        )
    }

    /**
     * Manually connects a user-selected file or URI to an existing track.
     */
    suspend fun manualLocateFile(
        context: Context,
        track: Track,
        newPathOrUri: String,
        trackDao: TrackDao
    ): RepairResult = withContext(Dispatchers.IO) {
        val originalPath = track.filePath
        val testTrack = track.copy(filePath = newPathOrUri)
        val validation = PlayabilityValidator.validateTrack(context, testTrack, forceFresh = true)

        if (validation.status == PlayabilityStatus.PLAYABLE) {
            val dir = if (newPathOrUri.contains("/")) newPathOrUri.substringBeforeLast("/") else "/Music"
            val repairedTrack = track.copy(
                filePath = newPathOrUri,
                directoryPath = dir,
                format = validation.containerMime?.substringAfter("audio/")?.uppercase() ?: track.format,
                bitrateKbps = if (validation.bitRateKbps > 0) validation.bitRateKbps else track.bitrateKbps,
                playabilityStatus = PlayabilityStatus.REPAIRED.name,
                playbackErrorCode = null,
                playbackErrorMessage = null,
                lastPlaybackValidation = System.currentTimeMillis(),
                lastRepairAttempt = System.currentTimeMillis(),
                resolvedUri = newPathOrUri
            )

            trackDao.updateTrack(TrackEntity.fromTrack(repairedTrack))
            Log.i(TAG, "Manual locate succeeded for '${track.title}' -> '$newPathOrUri'")

            RepairResult(
                success = true,
                track = repairedTrack,
                previousPath = originalPath,
                newPath = newPathOrUri,
                message = "Track successfully reconnected to selected audio file.",
                diagnosticReport = validation
            )
        } else {
            RepairResult(
                success = false,
                track = track,
                previousPath = originalPath,
                newPath = newPathOrUri,
                message = "The selected file could not be played (${validation.detectedReason}).",
                diagnosticReport = validation
            )
        }
    }

    /**
     * Batch repairs all unplayable tracks in the library non-destructively.
     */
    suspend fun autoRepairAll(
        context: Context,
        tracks: List<Track>,
        trackDao: TrackDao,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BatchRepairSummary = withContext(Dispatchers.IO) {
        val unplayable = tracks.filter { it.hasPlaybackIssue || it.playability == PlayabilityStatus.UNKNOWN }
        val results = mutableListOf<RepairResult>()
        var repairedCount = 0
        var failedCount = 0

        unplayable.forEachIndexed { index, track ->
            onProgress(index + 1, unplayable.size)
            val result = autoRepairTrack(context, track, trackDao)
            results.add(result)
            if (result.success) repairedCount++ else failedCount++
        }

        BatchRepairSummary(
            totalProcessed = unplayable.size,
            totalRepaired = repairedCount,
            totalFailed = failedCount,
            results = results
        )
    }

    /**
     * Groups unplayable tracks by volume UUID when folder permission grant is required.
     */
    fun getAffectedVolumesNeedingPermission(
        context: Context,
        tracks: List<Track>
    ): List<VolumePermissionGroup> {
        return TrackSourceResolver.getAffectedVolumesNeedingPermission(context, tracks)
    }
}
