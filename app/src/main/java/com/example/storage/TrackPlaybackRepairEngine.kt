package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.analysis.PlayabilityValidator
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.PlayabilityDiagnosticReport
import com.example.model.PlayabilityStatus
import com.example.model.SourceHealthTier
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
        val alreadyValidCount: Int = 0,
        val permissionRequiredCount: Int = 0,
        val missingCount: Int = 0,
        val formatOrExtractorErrorCount: Int = 0,
        val results: List<RepairResult> = emptyList()
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

        // 2. Multi-tier resolution via central TrackSourceResolver (Strict 8-tier priority)
        val resolution = TrackSourceResolver.resolveTrackSource(context, track)
        if (resolution.isPlayable && resolution.resolvedUriOrPath != null && resolution.resolvedUriOrPath != originalPath) {
            val candidatePath = resolution.resolvedUriOrPath
            val testTrack = track.copy(filePath = candidatePath, resolvedUri = candidatePath)
            val validation = PlayabilityValidator.validateTrack(context, testTrack, forceFresh = true)
            if (validation.status == PlayabilityStatus.PLAYABLE) {
                val dir = if (candidatePath.contains("/")) candidatePath.substringBeforeLast("/") else "/Music"
                val resolvedStoragePath = if (candidatePath.contains("/storage/emulated/0/")) {
                    candidatePath.substringAfter("/storage/emulated/0/").trimStart('/')
                } else if (candidatePath.startsWith("/")) {
                    candidatePath.trimStart('/')
                } else {
                    track.storageRelativePath
                }

                val repairedTrack = track.copy(
                    filePath = candidatePath,
                    directoryPath = dir,
                    storageRelativePath = resolvedStoragePath,
                    playabilityStatus = PlayabilityStatus.PLAYABLE.name,
                    playbackErrorCode = null,
                    playbackErrorMessage = null,
                    lastPlaybackValidation = System.currentTimeMillis(),
                    lastRepairAttempt = System.currentTimeMillis(),
                    resolvedUri = candidatePath
                )
                trackDao.updateTrack(TrackEntity.fromTrack(repairedTrack))
                Log.i(TAG, "SUCCESS via TrackSourceResolver: repaired '${track.title}' -> '$candidatePath' (${resolution.sourceType})")

                return@withContext RepairResult(
                    success = true,
                    track = repairedTrack,
                    previousPath = originalPath,
                    newPath = candidatePath,
                    message = "Successfully reconnected track to '$candidatePath'.",
                    diagnosticReport = validation
                )
            }
        }

        // 3. Multi-tier self-healing pipeline via TrackSelfHealingResolver
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
     * Bulk recovers tracks that were previously incorrectly repaired to SAF document URIs
     * back to verified Android MediaStore content URIs.
     *
     * Returns the count of tracks successfully restored to MediaStore.
     */
    suspend fun recoverIncorrectlyRepairedTracks(
        context: Context,
        trackDao: TrackDao
    ): Int = withContext(Dispatchers.IO) {
        var recoveredCount = 0
        val allTracks = try {
            trackDao.getAllTracksSync().map { it.toTrack() }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching tracks for MediaStore recovery: ${e.message}")
            return@withContext 0
        }

        for (track in allTracks) {
            try {
                val isSaf = track.filePath.startsWith("content://com.android.externalstorage.documents") ||
                        track.filePath.startsWith("content://com.android.providers.downloads") ||
                        (track.resolvedUri?.startsWith("content://com.android.externalstorage.documents") == true)

                if (!isSaf) continue

                // Check if a valid MediaStore URI exists for this track
                val mediaStoreUri = TrackSourceResolver.findMediaStoreUriForTrack(context, track)
                if (mediaStoreUri != null) {
                    val probe = TrackSourceResolver.testMediaStoreUri(context, mediaStoreUri)
                    if (probe.isPlayable && TrackSourceResolver.testContentUriWithMediaExtractor(context, mediaStoreUri)) {
                        val uriStr = mediaStoreUri.toString()
                        val updated = track.copy(
                            filePath = uriStr,
                            resolvedUri = uriStr,
                            playabilityStatus = PlayabilityStatus.PLAYABLE.name,
                            playbackErrorCode = null,
                            playbackErrorMessage = null,
                            lastPlaybackValidation = System.currentTimeMillis(),
                            lastRepairAttempt = System.currentTimeMillis()
                        )
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                        recoveredCount++
                        Log.i(TAG, "RECOVERED: Restored track '${track.title}' from SAF URI to MediaStore URI: $uriStr")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Exception during MediaStore recovery for track '${track.title}': ${t.message}")
            }
        }
        recoveredCount
    }

    /**
     * Batch repairs all unplayable tracks in the library non-destructively.
     * Guarantees 100% progress completion by isolating per-track exceptions.
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
        var alreadyValidCount = 0
        var permissionRequiredCount = 0
        var missingCount = 0
        var formatOrExtractorCount = 0

        unplayable.forEachIndexed { index, track ->
            try {
                onProgress(index + 1, unplayable.size)
                val result = autoRepairTrack(context, track, trackDao)
                results.add(result)
                if (result.success) {
                    if (result.previousPath == result.newPath) {
                        alreadyValidCount++
                    } else {
                        repairedCount++
                    }
                } else {
                    failedCount++
                    when (result.diagnosticReport.status) {
                        PlayabilityStatus.PERMISSION_DENIED,
                        PlayabilityStatus.PERMISSION_REQUIRED -> permissionRequiredCount++
                        PlayabilityStatus.MISSING_FILE,
                        PlayabilityStatus.VOLUME_UNAVAILABLE -> missingCount++
                        PlayabilityStatus.EXTRACTOR_ERROR,
                        PlayabilityStatus.FORMAT_UNRECOGNIZED,
                        PlayabilityStatus.UNSUPPORTED_FORMAT,
                        PlayabilityStatus.DECODER_ERROR,
                        PlayabilityStatus.INVALID_CONTAINER -> formatOrExtractorCount++
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Uncaught exception repairing track '${track.title}': ${t.message}", t)
                failedCount++
                val fallbackReport = PlayabilityDiagnosticReport(
                    trackId = track.id,
                    status = PlayabilityStatus.UNKNOWN_PLAYBACK_ERROR,
                    errorCode = "ERR_REPAIR_EXCEPTION",
                    errorMessage = "Exception during repair: ${t.message}",
                    problemDescription = "Repair attempt encountered an unexpected error.",
                    lastKnownLocation = track.filePath
                )
                results.add(
                    RepairResult(
                        success = false,
                        track = track,
                        previousPath = track.filePath,
                        newPath = null,
                        message = "Error during repair: ${t.message}",
                        diagnosticReport = fallbackReport
                    )
                )
            }
        }

        // Guarantee final progress reaches 100%
        if (unplayable.isNotEmpty()) {
            onProgress(unplayable.size, unplayable.size)
        }

        BatchRepairSummary(
            totalProcessed = unplayable.size,
            totalRepaired = repairedCount,
            totalFailed = failedCount,
            alreadyValidCount = alreadyValidCount,
            permissionRequiredCount = permissionRequiredCount,
            missingCount = missingCount,
            formatOrExtractorErrorCount = formatOrExtractorCount,
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
