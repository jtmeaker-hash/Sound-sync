package com.example.analysis

import android.content.Context
import android.util.Log
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.PlayabilityDiagnosticReport
import com.example.model.PlayabilityStatus
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Background orchestrator for SoundSync playback health & validation.
 *
 * Runs low-priority, non-blocking asynchronous playback/decode probes to detect
 * unplayable tracks in the library, and maintains real-time diagnostic reports.
 */
object TrackPlaybackHealthManager {

    private const val TAG = "PlaybackHealthManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var validationJob: Job? = null
    private val concurrencySemaphore = Semaphore(2)

    private val reportCache = ConcurrentHashMap<String, PlayabilityDiagnosticReport>()

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating.asStateFlow()

    private val _validationProgress = MutableStateFlow(0f)
    val validationProgress: StateFlow<Float> = _validationProgress.asStateFlow()

    private val _currentValidationTrack = MutableStateFlow<String?>(null)
    val currentValidationTrack: StateFlow<String?> = _currentValidationTrack.asStateFlow()

    /**
     * Gets a cached diagnostic report or generates one asynchronously.
     */
    suspend fun getOrProbeReport(
        context: Context,
        track: Track,
        forceFresh: Boolean = false
    ): PlayabilityDiagnosticReport {
        if (!forceFresh) {
            val cached = reportCache[track.id]
            if (cached != null && System.currentTimeMillis() - cached.validationTimestamp < 60_000L) {
                return cached
            }
        }

        val report = PlayabilityValidator.validateTrack(context, track, forceFresh = forceFresh)
        reportCache[track.id] = report
        return report
    }

    /**
     * Rapidly validates an individual track and immediately updates the database.
     */
    suspend fun validateSingleTrack(
        context: Context,
        track: Track,
        trackDao: TrackDao
    ): PlayabilityDiagnosticReport {
        val report = PlayabilityValidator.validateTrack(context, track, forceFresh = true)
        reportCache[track.id] = report

        trackDao.updatePlayabilityStatus(
            id = track.id,
            status = report.status.name,
            errorCode = report.errorCode,
            errorMessage = report.errorMessage,
            timestamp = report.validationTimestamp,
            resolvedUri = report.resolvedPath,
            fileSize = report.fileSizeBytes,
            fileModified = report.fileModifiedTimestamp
        )

        return report
    }

    /**
     * Enqueues a background validation scan for all or pending tracks in the library.
     * Does NOT block the UI or library scans.
     */
    fun enqueueBackgroundValidation(
        context: Context,
        trackDao: TrackDao,
        onlyUnvalidated: Boolean = true
    ) {
        if (validationJob?.isActive == true) {
            Log.d(TAG, "Validation already running in background.")
            return
        }

        validationJob = scope.launch {
            _isValidating.value = true
            _validationProgress.value = 0f
            try {
                val tracksToValidate = if (onlyUnvalidated) {
                    trackDao.getTracksNeedingPlayabilityValidation()
                } else {
                    trackDao.getAllTracksList()
                }

                if (tracksToValidate.isEmpty()) {
                    Log.d(TAG, "No tracks need playback validation.")
                    _isValidating.value = false
                    return@launch
                }

                Log.i(TAG, "Starting background playability validation for ${tracksToValidate.size} tracks...")
                val total = tracksToValidate.size

                tracksToValidate.forEachIndexed { index, trackEntity ->
                    concurrencySemaphore.withPermit {
                        _currentValidationTrack.value = trackEntity.title
                        val track = trackEntity.toTrack()

                        // Check if file is already unchanged and verified
                        val cleanPath = track.filePath.removePrefix("file://")
                        val file = File(cleanPath)
                        val shouldSkip = !track.filePath.startsWith("content://") &&
                                file.exists() &&
                                track.playabilityStatus == PlayabilityStatus.PLAYABLE.name &&
                                track.validationFileSize == file.length() &&
                                track.validationModifiedTimestamp == file.lastModified() &&
                                track.lastPlaybackValidation != null &&
                                onlyUnvalidated

                        if (!shouldSkip) {
                            val report = PlayabilityValidator.validateTrack(context, track, quickCheckOnly = false)
                            reportCache[track.id] = report

                            trackDao.updatePlayabilityStatus(
                                id = track.id,
                                status = report.status.name,
                                errorCode = report.errorCode,
                                errorMessage = report.errorMessage,
                                timestamp = report.validationTimestamp,
                                resolvedUri = report.resolvedPath,
                                fileSize = report.fileSizeBytes,
                                fileModified = report.fileModifiedTimestamp
                            )
                        }

                        _validationProgress.value = (index + 1).toFloat() / total
                    }
                }

                Log.i(TAG, "Background playability validation complete for $total tracks.")
            } catch (e: Exception) {
                Log.w(TAG, "Background validation interrupted: ${e.message}")
            } finally {
                _isValidating.value = false
                _currentValidationTrack.value = null
                _validationProgress.value = 1f
            }
        }
    }

    /**
     * Clears all cached diagnostic reports.
     */
    fun clearCache() {
        reportCache.clear()
    }
}
