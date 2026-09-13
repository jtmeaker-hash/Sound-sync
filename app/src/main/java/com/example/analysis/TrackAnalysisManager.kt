package com.example.analysis

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.audio.DjAudioEngine
import com.example.audio.SpectrogramEngine
import com.example.audio.WaveformAnalyzer
import com.example.audio.WaveformCache
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.LocalPcmAudioAnalyzer
import com.example.metadata.MetadataFileWriteQueue
import com.example.metadata.MetadataResolver
import com.example.model.AnalysisState
import com.example.model.AudioQualityRating
import com.example.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * High-performance, persistent background metadata analysis engine for SoundSync.
 *
 * Implements Phase B background enrichment:
 * - Bounded concurrency: strictly 1 expensive DSP decode/STFT at a time.
 * - Playback priority: throttles or pauses heavy analysis when [DjAudioEngine] is playing.
 * - Idempotency: unchanged files with valid analysis are skipped.
 * - Robust error handling: corrupted files are recorded as FAILED without stopping the queue.
 * - Live reactive state for subtle UI progress displays.
 */
/**
 * Explicit scan lifecycle state machine for SoundSync metadata analysis.
 */
enum class ScanLifecycleState {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETE,
    COMPLETE_WITH_ERRORS,
    CANCELLED,
    FAILED;

    val isActive: Boolean
        get() = this == QUEUED || this == RUNNING || this == PAUSED

    val isTerminal: Boolean
        get() = this == COMPLETE || this == COMPLETE_WITH_ERRORS || this == CANCELLED || this == FAILED
}

/**
 * Result of analysing a single audio track.
 */
enum class ProcessOutcome {
    SUCCESS,
    SKIPPED,
    FAILED_TERMINAL,
    RETRYABLE_FAILURE
}

class TrackAnalysisManager private constructor(
    private val context: Context
) {

    data class QueueProgress(
        val state: ScanLifecycleState = ScanLifecycleState.IDLE,
        val isRunning: Boolean = false,
        val isPausedForPlayback: Boolean = false,
        val processedCount: Int = 0,
        val totalCount: Int = 0,
        val completedSuccess: Int = 0,
        val completedSkipped: Int = 0,
        val failedTerminal: Int = 0,
        val pendingCount: Int = 0,
        val inProgressCount: Int = 0,
        val currentTrackTitle: String = "",
        val failedCount: Int = 0,
        val statusMessage: String = "Idle",
        val runId: String = ""
    )

    private val db by lazy { AppDatabase.getDatabase(context) }
    private val defaultTrackDao by lazy { db.trackDao() }

    @androidx.annotation.VisibleForTesting
    var trackDaoOverrideForTesting: TrackDao? = null

    private val trackDao: TrackDao get() = trackDaoOverrideForTesting ?: defaultTrackDao
    private val scanStateManager = com.example.storage.ScanStateManager(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null
    private val jobMutex = Mutex()

    // Bounded concurrency: exactly 1 expensive audio decoder / DSP at a time
    private val dspSemaphore = Semaphore(1)

    private val pcmAnalyzer = LocalPcmAudioAnalyzer(context)
    private val metadataResolver = MetadataResolver(context)

    private val prefs: SharedPreferences = context.getSharedPreferences("soundsync_analysis_prefs", Context.MODE_PRIVATE)

    private val _queueProgress = MutableStateFlow(QueueProgress())
    val queueProgress: StateFlow<QueueProgress> = _queueProgress.asStateFlow()

    private var audioEngineRef: DjAudioEngine? = null

    // Immediate priority track (e.g. user selected track that isn't analyzed yet)
    @Volatile
    private var priorityTrackId: String? = null

    var isBackgroundAnalysisEnabled: Boolean
        get() = prefs.getBoolean("bg_analysis_enabled", true)
        set(value) = prefs.edit().putBoolean("bg_analysis_enabled", value).apply()

    var analyseWhilePlayingMode: String
        get() = prefs.getString("analyse_while_playing_mode", "REDUCED") ?: "REDUCED"
        set(value) = prefs.edit().putString("analyse_while_playing_mode", value).apply()

    fun attachAudioEngine(audioEngine: DjAudioEngine) {
        this.audioEngineRef = audioEngine
    }

    /**
     * Prioritises [track] to be processed immediately at the front of the queue
     * without blocking UI or playback threads.
     */
    fun prioritizeTrack(track: Track) {
        priorityTrackId = track.id
        scope.launch {
            try {
                trackDao.updateTrackAnalysisStatus(
                    id = track.id,
                    state = AnalysisState.QUEUED.name,
                    lastAnalysedAt = null,
                    reason = null,
                    retryCount = 0
                )
            } catch (_: Exception) {}
            triggerQueueProcessing()
        }
    }

    /**
     * Enqueues newly discovered tracks for background analysis.
     * Skips tracks that have already completed analysis and metadata scanning.
     */
    fun enqueueDiscoveredTracks(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        scope.launch {
            try {
                val tracks = trackDao.getTracksByIds(trackIds)
                val needingAnalysis = tracks.filter { entity ->
                    entity.analysisState != AnalysisState.COMPLETE.name &&
                    (entity.analysisState in listOf(AnalysisState.NOT_ANALYSED.name, AnalysisState.QUEUED.name, AnalysisState.PARTIAL.name))
                }.map { it.id }

                if (needingAnalysis.isNotEmpty()) {
                    trackDao.queueTracksByIds(needingAnalysis)
                    triggerQueueProcessing()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing tracks for analysis: ${e.message}")
            }
        }
    }

    /**
     * Marks all tracks in the library for full reanalysis.
     */
    fun reanalyseAllTracks() {
        scope.launch {
            try {
                trackDao.markAllForReanalysis()
            } catch (e: Exception) {
                Log.e(TAG, "Error marking all tracks for reanalysis: ${e.message}")
            }
            triggerQueueProcessing()
        }
    }

    /**
     * Marks tracks missing BPM, Key, or artwork for analysis.
     */
    fun analyseMissingTracks() {
        scope.launch {
            try {
                trackDao.markMissingForAnalysis()
            } catch (e: Exception) {
                Log.e(TAG, "Error marking missing tracks for analysis: ${e.message}")
            }
            triggerQueueProcessing()
        }
    }

    /**
     * Triggers the queue worker if background analysis is enabled.
     */
    fun triggerQueueProcessing() {
        if (!isBackgroundAnalysisEnabled) return

        scope.launch {
            jobMutex.withLock {
                if (analysisJob?.isActive == true) {
                    return@withLock
                }
                analysisJob = launchAnalysisLoop()
            }
        }
    }

    suspend fun getPendingCount(): Int = withContext(Dispatchers.IO) {
        try {
            trackDao.getPendingAnalysisCount()
        } catch (_: Exception) {
            0
        }
    }

    suspend fun runAnalysisLoopSuspended(
        onProgressUpdate: ((processed: Int, total: Int, currentTrackTitle: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val job = jobMutex.withLock {
            if (analysisJob?.isActive == true) {
                analysisJob
            } else {
                val j = launchAnalysisLoop(onProgressUpdate)
                analysisJob = j
                j
            }
        }
        job?.join()
    }

    private fun launchAnalysisLoop(
        onProgressUpdate: ((processed: Int, total: Int, currentTrackTitle: String) -> Unit)? = null
    ): Job = scope.launch {
        val runId = "scan_${System.currentTimeMillis()}"
        Log.d(TAG, "[$runId] Background library analysis loop started.")

        var totalEligible = 0
        try {
            totalEligible = trackDao.getPendingAnalysisCount()
        } catch (_: Exception) {}

        scanStateManager.status = com.example.storage.ScanStatus.RUNNING
        scanStateManager.activeRunId = runId

        if (totalEligible <= 0) {
            Log.d(TAG, "[$runId] No tracks need analysis. Scan complete immediately.")
            scanStateManager.status = com.example.storage.ScanStatus.COMPLETE
            scanStateManager.lastScanTime = System.currentTimeMillis()
            scanStateManager.lastScannedCount = 0
            scanStateManager.activeRunId = null

            _queueProgress.value = QueueProgress(
                state = ScanLifecycleState.COMPLETE,
                isRunning = false,
                isPausedForPlayback = false,
                processedCount = 0,
                totalCount = 0,
                completedSuccess = 0,
                completedSkipped = 0,
                failedTerminal = 0,
                pendingCount = 0,
                inProgressCount = 0,
                currentTrackTitle = "",
                failedCount = 0,
                statusMessage = "Metadata scan complete",
                runId = runId
            )
            return@launch
        }

        var completedSuccess = 0
        var completedSkipped = 0
        var failedTerminal = 0
        var consecutiveEmptyBatches = 0
        val processedIdsInThisRun = mutableSetOf<String>()

        _queueProgress.value = QueueProgress(
            state = ScanLifecycleState.RUNNING,
            isRunning = true,
            isPausedForPlayback = false,
            processedCount = 0,
            totalCount = totalEligible,
            completedSuccess = 0,
            completedSkipped = 0,
            failedTerminal = 0,
            pendingCount = totalEligible,
            inProgressCount = 0,
            currentTrackTitle = "",
            failedCount = 0,
            statusMessage = "Scanning metadata · 0 / $totalEligible",
            runId = runId
        )

        var terminalState = ScanLifecycleState.COMPLETE

        try {
            while (isActive) {
                if (!isBackgroundAnalysisEnabled) {
                    Log.d(TAG, "[$runId] Background analysis disabled in settings. Pausing worker.")
                    terminalState = ScanLifecycleState.PAUSED
                    break
                }

                val isPlaying = audioEngineRef?.isPlaying?.value == true
                if (isPlaying) {
                    when (analyseWhilePlayingMode) {
                        "PAUSED" -> {
                            _queueProgress.value = _queueProgress.value.copy(
                                state = ScanLifecycleState.PAUSED,
                                isRunning = true,
                                isPausedForPlayback = true,
                                statusMessage = "Analysis paused during playback"
                            )
                            delay(1000)
                            continue
                        }
                        "OFF" -> {
                            _queueProgress.value = _queueProgress.value.copy(
                                state = ScanLifecycleState.PAUSED,
                                isRunning = false,
                                isPausedForPlayback = true,
                                statusMessage = "Analysis stopped during playback"
                            )
                            delay(2000)
                            continue
                        }
                        else -> { // "REDUCED"
                            yield()
                            delay(250)
                        }
                    }
                }

                // Deterministic accounting: check if new tracks were discovered
                try {
                    val remaining = trackDao.getPendingAnalysisCount()
                    val currentProcessed = completedSuccess + completedSkipped + failedTerminal
                    if (currentProcessed + remaining > totalEligible) {
                        totalEligible = currentProcessed + remaining
                    }
                } catch (_: Exception) {}

                var currentEntity: TrackEntity? = null
                val prioId = priorityTrackId
                if (prioId != null) {
                    priorityTrackId = null
                    currentEntity = trackDao.getTrackById(prioId)
                }

                if (currentEntity == null) {
                    val batch = trackDao.getTracksNeedingAnalysis(limit = 10)
                    val unhandled = batch.filter { it.id !in processedIdsInThisRun }

                    if (batch.isEmpty() || unhandled.isEmpty()) {
                        consecutiveEmptyBatches++
                        if (consecutiveEmptyBatches >= 2) {
                            Log.d(TAG, "[$runId] All eligible tracks reached terminal state.")
                            break
                        }
                        delay(500)
                        continue
                    }
                    consecutiveEmptyBatches = 0
                    currentEntity = unhandled.first()
                }

                val entity = currentEntity ?: continue
                val trackId = entity.id
                val track = entity.toTrack()
                val currentProcessed = completedSuccess + completedSkipped + failedTerminal
                val pending = (totalEligible - currentProcessed).coerceAtLeast(0)

                _queueProgress.value = _queueProgress.value.copy(
                    state = ScanLifecycleState.RUNNING,
                    isRunning = true,
                    isPausedForPlayback = isPlaying && analyseWhilePlayingMode == "REDUCED",
                    totalCount = totalEligible,
                    processedCount = currentProcessed,
                    completedSuccess = completedSuccess,
                    completedSkipped = completedSkipped,
                    failedTerminal = failedTerminal,
                    pendingCount = pending,
                    inProgressCount = 1,
                    currentTrackTitle = track.title,
                    statusMessage = if (isPlaying) "Scanning metadata (reduced priority) · $currentProcessed / $totalEligible • ${track.title}"
                                    else "Scanning metadata · $currentProcessed / $totalEligible • ${track.title}"
                )

                val outcome = processSingleTrackWithOutcome(track)
                processedIdsInThisRun.add(trackId)

                when (outcome) {
                    ProcessOutcome.SUCCESS -> completedSuccess++
                    ProcessOutcome.SKIPPED -> completedSkipped++
                    ProcessOutcome.FAILED_TERMINAL -> failedTerminal++
                    ProcessOutcome.RETRYABLE_FAILURE -> {
                        val check = trackDao.getTrackById(trackId)
                        if (check == null || check.analysisRetryCount >= 3 || check.analysisState == AnalysisState.FAILED.name) {
                            failedTerminal++
                        }
                    }
                }

                val newProcessed = completedSuccess + completedSkipped + failedTerminal
                val newPending = (totalEligible - newProcessed).coerceAtLeast(0)

                _queueProgress.value = _queueProgress.value.copy(
                    processedCount = newProcessed,
                    totalCount = totalEligible,
                    completedSuccess = completedSuccess,
                    completedSkipped = completedSkipped,
                    failedTerminal = failedTerminal,
                    pendingCount = newPending,
                    inProgressCount = 0,
                    failedCount = failedTerminal
                )

                onProgressUpdate?.invoke(newProcessed, totalEligible, track.title)
                delay(if (isPlaying) 200 else 60)
            }

            terminalState = if (failedTerminal > 0) ScanLifecycleState.COMPLETE_WITH_ERRORS else ScanLifecycleState.COMPLETE
        } catch (e: CancellationException) {
            Log.d(TAG, "[$runId] Analysis loop cancelled cleanly.")
            terminalState = ScanLifecycleState.CANCELLED
        } catch (e: Throwable) {
            Log.e(TAG, "[$runId] Unhandled error in analysis loop", e)
            terminalState = ScanLifecycleState.FAILED
        } finally {
            val totalFinal = completedSuccess + completedSkipped + failedTerminal
            val finalMsg = when (terminalState) {
                ScanLifecycleState.COMPLETE -> "Metadata scan complete"
                ScanLifecycleState.COMPLETE_WITH_ERRORS -> "Metadata scan complete · $failedTerminal unavailable sources"
                ScanLifecycleState.CANCELLED -> "Metadata scan cancelled"
                ScanLifecycleState.FAILED -> "Metadata scan failed"
                ScanLifecycleState.PAUSED -> "Metadata scan paused"
                else -> "Metadata scan complete"
            }

            scanStateManager.status = when (terminalState) {
                ScanLifecycleState.COMPLETE -> com.example.storage.ScanStatus.COMPLETE
                ScanLifecycleState.COMPLETE_WITH_ERRORS -> com.example.storage.ScanStatus.COMPLETE_WITH_ERRORS
                ScanLifecycleState.CANCELLED -> com.example.storage.ScanStatus.CANCELLED
                ScanLifecycleState.FAILED -> com.example.storage.ScanStatus.FAILED
                ScanLifecycleState.PAUSED -> com.example.storage.ScanStatus.PAUSED
                else -> com.example.storage.ScanStatus.IDLE
            }
            scanStateManager.lastScanTime = System.currentTimeMillis()
            scanStateManager.lastScannedCount = completedSuccess + completedSkipped
            scanStateManager.activeRunId = null

            Log.d(TAG, "[$runId] Analysis loop terminal transition: state=$terminalState, msg='$finalMsg'")
            _queueProgress.value = QueueProgress(
                state = terminalState,
                isRunning = false,
                isPausedForPlayback = false,
                processedCount = totalFinal,
                totalCount = totalEligible,
                completedSuccess = completedSuccess,
                completedSkipped = completedSkipped,
                failedTerminal = failedTerminal,
                pendingCount = 0,
                inProgressCount = 0,
                currentTrackTitle = "",
                failedCount = failedTerminal,
                statusMessage = finalMsg,
                runId = runId
            )
        }
    }

    /**
     * Performs Phase B analysis on a single track.
     * Guaranteed to never throw out of this function.
     */
    suspend fun processSingleTrack(track: Track): Boolean {
        val outcome = processSingleTrackWithOutcome(track)
        return outcome == ProcessOutcome.SUCCESS || outcome == ProcessOutcome.SKIPPED
    }

    /**
     * Detailed track processing returning explicit execution outcome.
     */
    suspend fun processSingleTrackWithOutcome(track: Track): ProcessOutcome = withContext(Dispatchers.IO) {
        val file = if (!track.filePath.startsWith("content://")) File(track.filePath) else null
        val fileModTime = file?.lastModified() ?: track.dateAdded

        // Fast skip check: file unchanged and analysis version current
        if (track.analysisVersion >= CURRENT_ANALYSIS_VERSION &&
            track.analysisState == AnalysisState.COMPLETE &&
            fileModTime == track.fileModifiedTimestamp
        ) {
            Log.d(TAG, "Track '${track.title}' already has valid analysis. Skipping.")
            return@withContext ProcessOutcome.SKIPPED
        }

        var updatedTrack = track

        // Pre-check playability and storage availability before opening media streams
        val isPathAccessible = com.example.storage.StorageAvailabilityHelper.isTrackPathAvailable(context, track.filePath)
        if (!isPathAccessible || track.hasPlaybackIssue ||
            track.playability == com.example.model.PlayabilityStatus.VOLUME_UNAVAILABLE ||
            track.playability == com.example.model.PlayabilityStatus.PERMISSION_REQUIRED ||
            track.playability == com.example.model.PlayabilityStatus.PERMISSION_DENIED ||
            track.playability == com.example.model.PlayabilityStatus.READ_ERROR ||
            track.playability == com.example.model.PlayabilityStatus.MISSING_FILE
        ) {
            val playablePath = com.example.storage.TrackSelfHealingResolver.resolveAnyPlayablePath(context, track)
            if (playablePath != null && playablePath != track.filePath) {
                trackDao.updateFilePath(track.id, playablePath)
                trackDao.updatePlayabilityStatus(track.id, com.example.model.PlayabilityStatus.PLAYABLE.name, null, null)
                updatedTrack = track.copy(filePath = playablePath, resolvedUri = playablePath)
            } else if (!isPathAccessible) {
                val avail = com.example.storage.StorageAvailabilityHelper.evaluateStorageAvailability(context, track)
                val status = when (avail.state) {
                    com.example.storage.StorageAvailabilityState.VOLUME_UNMOUNTED -> com.example.model.PlayabilityStatus.VOLUME_UNAVAILABLE.name
                    com.example.storage.StorageAvailabilityState.PERMISSION_LOST -> com.example.model.PlayabilityStatus.PERMISSION_DENIED.name
                    com.example.storage.StorageAvailabilityState.STALE_SOURCE -> com.example.model.PlayabilityStatus.STALE_URI.name
                    else -> track.playabilityStatus
                }
                val code = when (avail.state) {
                    com.example.storage.StorageAvailabilityState.VOLUME_UNMOUNTED -> "ERR_STORAGE_UNMOUNTED"
                    com.example.storage.StorageAvailabilityState.PERMISSION_LOST -> "ERR_SCOPED_STORAGE_RESTRICTION"
                    com.example.storage.StorageAvailabilityState.STALE_SOURCE -> "ERR_MEDIASTORE_STALE"
                    else -> (track.playbackErrorCode ?: "ERR_SOURCE_INACCESSIBLE")
                }
                trackDao.updatePlayabilityStatus(track.id, status, code, avail.details.ifBlank { "Storage file inaccessible during analysis" })
                val newRetry = (track.analysisRetryCount + 1).coerceAtMost(3)
                val isTerminal = newRetry >= 3 || avail.state == com.example.storage.StorageAvailabilityState.VOLUME_UNMOUNTED || avail.state == com.example.storage.StorageAvailabilityState.SOURCE_MISSING
                val stateName = if (isTerminal) AnalysisState.FAILED.name else AnalysisState.PARTIAL.name
                trackDao.updateTrackAnalysisStatus(
                    id = track.id,
                    state = stateName,
                    lastAnalysedAt = System.currentTimeMillis(),
                    reason = "Source file inaccessible ($code)",
                    retryCount = if (isTerminal) 3 else newRetry
                )
                Log.d(TAG, "Track '${track.title}' source path is not accessible. Handled as ${if (isTerminal) "FAILED_TERMINAL" else "RETRYABLE"}.")
                return@withContext if (isTerminal) ProcessOutcome.FAILED_TERMINAL else ProcessOutcome.RETRYABLE_FAILURE
            }
        }

        try {
            // 1. Read embedded tags for accurate local metadata if missing
            try {
                val embedded = AudioEmbeddedMetadataReader.read(context, updatedTrack.filePath, includeArtworkBytes = false)
                if (embedded != null) {
                    var modified = false
                    var t = updatedTrack
                    if (t.bpm <= 0.0 && embedded.hasBpm) {
                        t = t.copy(bpm = embedded.bpm ?: 0.0, isManualBpm = false)
                        modified = true
                    }
                    if (t.musicalKey.isBlank() && embedded.hasKey) {
                        t = t.copy(
                            musicalKey = embedded.musicalKey.orEmpty(),
                            camelotKey = embedded.camelotKey.orEmpty(),
                            isManualKey = false
                        )
                        modified = true
                    }
                    if (t.trackNumber == 0 && (embedded.trackNumber ?: 0) > 0) {
                        t = t.copy(trackNumber = embedded.trackNumber ?: 0)
                        modified = true
                    }
                    if (t.discNumber == 1 && (embedded.discNumber ?: 1) > 1) {
                        t = t.copy(discNumber = embedded.discNumber ?: 1)
                        modified = true
                    }
                    if (t.isrc.isNullOrBlank() && !embedded.isrc.isNullOrBlank()) {
                        t = t.copy(isrc = embedded.isrc)
                        modified = true
                    }
                    if (t.durationSeconds <= 1 && embedded.durationSeconds > 1) {
                        t = t.copy(durationSeconds = embedded.durationSeconds)
                        modified = true
                    }
                    if (modified) {
                        updatedTrack = t
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Embedded tag read non-fatal error for '${track.title}': ${e.message}")
            }

            // 2. Perform DSP detection for BPM and Key if still missing
            if (!updatedTrack.hasValidBpm || !updatedTrack.hasValidKey) {
                dspSemaphore.withPermit {
                    try {
                        val dspResult = pcmAnalyzer.analyze(updatedTrack)
                        var t = updatedTrack
                        if (t.bpm <= 0.0 && (dspResult.bpm ?: 0.0) > 0.0) {
                            t = t.copy(
                                bpm = dspResult.bpm ?: 0.0,
                                bpmConfidence = dspResult.bpmConfidence,
                                bpmAnalysisVersion = "v2_dsp",
                                bpmLastAnalyzed = System.currentTimeMillis()
                            )
                        }
                        if (t.musicalKey.isBlank() && !dspResult.musicalKey.isNullOrBlank()) {
                            t = t.copy(
                                musicalKey = dspResult.musicalKey.orEmpty(),
                                camelotKey = dspResult.camelotKey.orEmpty(),
                                keyConfidence = dspResult.keyConfidence,
                                keyAnalysisVersion = "v2_dsp",
                                keyLastAnalyzed = System.currentTimeMillis()
                            )
                        }
                        updatedTrack = t
                    } catch (e: Exception) {
                        Log.w(TAG, "PCM DSP detection error for '${track.title}': ${e.message}")
                    }
                }
            }

            // 3. Generate Waveform if missing
            val hasWaveform = WaveformCache.contains(WaveformCache.getCacheKey(updatedTrack, context), context)
            if (!hasWaveform) {
                dspSemaphore.withPermit {
                    try {
                        WaveformAnalyzer.analyze(context, updatedTrack)
                    } catch (e: Exception) {
                        Log.w(TAG, "Waveform generation error for '${track.title}': ${e.message}")
                    }
                }
            }

            // 4. Lightweight container & bitrate quality rating probe if unrated
            if (updatedTrack.qualityRating == AudioQualityRating.UNKNOWN_BITRATE) {
                try {
                    val bitrateInfo = com.example.audio.BitrateProbe.probe(context, updatedTrack.filePath, updatedTrack.durationSeconds)
                    val lowerPath = updatedTrack.filePath.lowercase()
                    val rating = when {
                        lowerPath.endsWith(".flac") -> AudioQualityRating.TRUE_LOSSLESS
                        lowerPath.endsWith(".wav") -> AudioQualityRating.TRUE_LOSSLESS
                        lowerPath.endsWith(".aiff") || lowerPath.endsWith(".aif") || lowerPath.endsWith(".alac") -> AudioQualityRating.TRUE_LOSSLESS
                        bitrateInfo.encodedBitrateKbps >= 310 -> AudioQualityRating.TRUE_320
                        bitrateInfo.encodedBitrateKbps >= 240 -> AudioQualityRating.TRUE_256
                        bitrateInfo.encodedBitrateKbps >= 160 -> AudioQualityRating.TRUE_256
                        bitrateInfo.encodedBitrateKbps > 0 -> AudioQualityRating.LOW_128
                        else -> AudioQualityRating.UNKNOWN_BITRATE
                    }
                    if (rating != AudioQualityRating.UNKNOWN_BITRATE) {
                        updatedTrack = updatedTrack.copy(qualityRating = rating)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Quality rating probe non-fatal error for '${track.title}': ${e.message}")
                }
            }

            // 5. Apple iTunes Search & TheAudioDB artwork resolution (with Missing Artist support)
            if (!updatedTrack.isAppleIdentified) {
                try {
                    val res = metadataResolver.resolveTrackMetadata(
                        track = updatedTrack,
                        forceRefresh = false,
                        embedArtworkToFile = false // Safe non-destructive background scan (Stage 9)
                    )
                    updatedTrack = res.updatedTrack
                } catch (e: Exception) {
                    Log.d(TAG, "Apple / TheAudioDB metadata resolution non-fatal: ${e.message}")
                }
            }

            // Defensive duration preservation: Never overwrite a known-valid duration (> 1) with an invalid duration (<= 1)
            val preservedDurationSec = when {
                updatedTrack.durationSeconds > 1 -> updatedTrack.durationSeconds
                track.durationSeconds > 1 -> track.durationSeconds
                else -> updatedTrack.durationSeconds
            }

            // Save completed track back to Room DB
            val finalTrack = updatedTrack.copy(
                durationSeconds = preservedDurationSec,
                analysisState = AnalysisState.COMPLETE,
                analysisVersion = CURRENT_ANALYSIS_VERSION,
                lastAnalysedAt = System.currentTimeMillis(),
                analysisFailureReason = null,
                fileModifiedTimestamp = fileModTime
            )

            trackDao.updateTrack(TrackEntity.fromTrack(finalTrack))

            // Non-Destructive Scanning (Stage 9):
            // Analyzed BPM, Key, Waveform are persisted in Room DB.
            // Only enqueue physical audio file write if explicitly permitted and approval is not required.
            try {
                val metaSettings = com.example.metadata.MetadataSettingsStore(context).load()
                if (metaSettings.writeToFileEnabled && !metaSettings.writeMetadataOnlyAfterApproval) {
                    MetadataFileWriteQueue.getInstance(context).enqueue(finalTrack)
                }
            } catch (queueEx: Exception) {
                Log.w(TAG, "Failed to check or enqueue file write after analysis: ${queueEx.message}")
            }

            ProcessOutcome.SUCCESS
        } catch (e: Exception) {
            val retryCount = track.analysisRetryCount + 1
            val isTerminal = retryCount >= 3
            val newState = if (isTerminal) AnalysisState.FAILED else AnalysisState.PARTIAL
            Log.e(TAG, "Analysis failed for track '${track.title}' (attempt $retryCount): ${e.message}")

            trackDao.updateTrackAnalysisStatus(
                id = track.id,
                state = newState.name,
                lastAnalysedAt = System.currentTimeMillis(),
                reason = e.message ?: "Unknown error",
                retryCount = if (isTerminal) 3 else retryCount
            )
            if (isTerminal) ProcessOutcome.FAILED_TERMINAL else ProcessOutcome.RETRYABLE_FAILURE
        }
    }

    companion object {
        private const val TAG = "TrackAnalysisManager"
        const val CURRENT_ANALYSIS_VERSION = 2

        @Volatile
        private var INSTANCE: TrackAnalysisManager? = null

        fun getInstance(context: Context): TrackAnalysisManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackAnalysisManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
