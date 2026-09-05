package com.example.analysis

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.audio.DjAudioEngine
import com.example.audio.SpectrogramEngine
import com.example.audio.WaveformAnalyzer
import com.example.audio.WaveformCache
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.LocalPcmAudioAnalyzer
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
class TrackAnalysisManager private constructor(
    private val context: Context
) {

    data class QueueProgress(
        val isRunning: Boolean = false,
        val isPausedForPlayback: Boolean = false,
        val processedCount: Int = 0,
        val totalCount: Int = 0,
        val currentTrackTitle: String = "",
        val failedCount: Int = 0,
        val statusMessage: String = "Idle"
    )

    private val db = AppDatabase.getDatabase(context)
    private val trackDao = db.trackDao()

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
     */
    fun enqueueDiscoveredTracks(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        scope.launch {
            try {
                trackDao.queueTracksByIds(trackIds)
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing tracks for analysis: ${e.message}")
            }
            triggerQueueProcessing()
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

        LibraryAnalysisWorker.enqueueWork(context)

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
        Log.d(TAG, "Background library analysis loop started.")
        var consecutiveEmptyBatches = 0
        var sessionProcessed = 0
        var sessionTotal = 0
        try {
            sessionTotal = trackDao.getPendingAnalysisCount()
        } catch (_: Exception) {}

        _queueProgress.value = QueueProgress(
            isRunning = true,
            isPausedForPlayback = false,
            processedCount = 0,
            totalCount = sessionTotal,
            currentTrackTitle = "",
            failedCount = 0,
            statusMessage = if (sessionTotal > 0) "Preparing library analysis…" else "Analysing library…"
        )

        try {
            while (isActive) {
                if (!isBackgroundAnalysisEnabled) {
                    Log.d(TAG, "Background analysis disabled in settings. Pausing worker.")
                    break
                }

                // Check playback state and apply priority throttle
                val isPlaying = audioEngineRef?.isPlaying?.value == true
                if (isPlaying) {
                    when (analyseWhilePlayingMode) {
                        "PAUSED" -> {
                            _queueProgress.value = _queueProgress.value.copy(
                                isRunning = true,
                                isPausedForPlayback = true,
                                statusMessage = "Analysis paused during playback"
                            )
                            delay(1000)
                            continue
                        }
                        "OFF" -> {
                            _queueProgress.value = _queueProgress.value.copy(
                                isRunning = false,
                                isPausedForPlayback = true,
                                statusMessage = "Analysis stopped during playback"
                            )
                            delay(2000)
                            continue
                        }
                        else -> { // "REDUCED"
                            // Throttle with small pause and yield CPU
                            yield()
                            delay(250)
                        }
                    }
                }

                // Refresh remaining count to keep sessionTotal accurate if new tracks were added
                try {
                    val remaining = trackDao.getPendingAnalysisCount()
                    sessionTotal = maxOf(sessionTotal, sessionProcessed + remaining)
                } catch (_: Exception) {}

                // Retrieve priority track if requested
                var currentEntity: TrackEntity? = null
                val prioId = priorityTrackId
                if (prioId != null) {
                    priorityTrackId = null
                    currentEntity = trackDao.getTrackById(prioId)
                }

                if (currentEntity == null) {
                    val batch = trackDao.getTracksNeedingAnalysis(limit = 10)
                    if (batch.isEmpty()) {
                        consecutiveEmptyBatches++
                        if (consecutiveEmptyBatches >= 2) {
                            Log.d(TAG, "All queued tracks analysed. Background analysis complete.")
                            _queueProgress.value = _queueProgress.value.copy(
                                isRunning = false,
                                isPausedForPlayback = false,
                                currentTrackTitle = "",
                                totalCount = sessionProcessed,
                                processedCount = sessionProcessed,
                                statusMessage = "Library analysis complete"
                            )
                            break
                        }
                        delay(1500)
                        continue
                    }
                    consecutiveEmptyBatches = 0
                    currentEntity = batch.first()
                }

                val track = currentEntity.toTrack()
                _queueProgress.value = _queueProgress.value.copy(
                    isRunning = true,
                    isPausedForPlayback = isPlaying && analyseWhilePlayingMode == "REDUCED",
                    totalCount = sessionTotal,
                    currentTrackTitle = track.title,
                    statusMessage = if (isPlaying) "Analysing (reduced priority) • ${track.title}" else "Analysing • ${track.title}"
                )

                // Process single track within DSP semaphore
                val success = processSingleTrack(track)
                sessionProcessed++
                if (sessionTotal > 0 && sessionProcessed > sessionTotal) {
                    sessionTotal = sessionProcessed
                }

                val prev = _queueProgress.value
                _queueProgress.value = prev.copy(
                    processedCount = sessionProcessed,
                    totalCount = sessionTotal,
                    failedCount = if (success) prev.failedCount else prev.failedCount + 1
                )
                onProgressUpdate?.invoke(sessionProcessed, sessionTotal, track.title)

                // Safe pacing interval
                delay(if (isPlaying) 150 else 40)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Analysis loop cancelled cleanly.")
        } catch (e: Throwable) {
            Log.e(TAG, "Unhandled error in analysis loop", e)
        } finally {
            _queueProgress.value = _queueProgress.value.copy(
                isRunning = false,
                isPausedForPlayback = false,
                currentTrackTitle = "",
                statusMessage = "Library analysis complete"
            )
        }
    }

    /**
     * Performs Phase B analysis on a single track.
     * Guaranteed to never throw out of this function.
     */
    private suspend fun processSingleTrack(track: Track): Boolean = withContext(Dispatchers.IO) {
        val file = if (!track.filePath.startsWith("content://")) File(track.filePath) else null
        val fileModTime = file?.lastModified() ?: track.dateAdded
        val fileSize = file?.length() ?: 0L

        // Fast skip check: file unchanged, analysis version current, has valid BPM, Key, and Waveform
        val hasWaveform = WaveformCache.contains(WaveformCache.getCacheKey(track, context), context)
        val hasBpmAndKey = track.hasValidBpm && track.hasValidKey

        if (track.analysisVersion >= CURRENT_ANALYSIS_VERSION &&
            track.analysisState == AnalysisState.COMPLETE &&
            fileModTime == track.fileModifiedTimestamp &&
            hasBpmAndKey && hasWaveform
        ) {
            Log.d(TAG, "Track '${track.title}' already has valid analysis. Skipping.")
            return@withContext true
        }

        trackDao.updateTrackAnalysisStatus(
            id = track.id,
            state = AnalysisState.ANALYSING.name,
            lastAnalysedAt = System.currentTimeMillis(),
            reason = null,
            retryCount = track.analysisRetryCount
        )

        var updatedTrack = track

        try {
            // 1. Read embedded tags for accurate local metadata if missing
            try {
                val embedded = AudioEmbeddedMetadataReader.read(context, track.filePath)
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
            if (!hasWaveform) {
                dspSemaphore.withPermit {
                    try {
                        WaveformAnalyzer.analyze(context, updatedTrack)
                    } catch (e: Exception) {
                        Log.w(TAG, "Waveform generation error for '${track.title}': ${e.message}")
                    }
                }
            }

            // 4. Quick acoustic quality rating if unrated
            if (updatedTrack.qualityRating == AudioQualityRating.UNKNOWN_BITRATE) {
                try {
                    val specAnalysis = SpectrogramEngine.analyzeTrack(context, updatedTrack)
                    updatedTrack = updatedTrack.copy(qualityRating = specAnalysis.qualityRating)
                } catch (e: Exception) {
                    Log.d(TAG, "Spectrogram acoustic rating non-fatal error for '${track.title}': ${e.message}")
                }
            }

            // 5. Apple iTunes Search & TheAudioDB artwork resolution (with Missing Artist support)
            if (!updatedTrack.isAppleIdentified) {
                try {
                    val res = metadataResolver.resolveTrackMetadata(
                        track = updatedTrack,
                        forceRefresh = false,
                        embedArtworkToFile = true
                    )
                    updatedTrack = res.updatedTrack
                } catch (e: Exception) {
                    Log.d(TAG, "Apple / TheAudioDB metadata resolution non-fatal: ${e.message}")
                }
            }

            // Save completed track back to Room DB
            val finalTrack = updatedTrack.copy(
                analysisState = AnalysisState.COMPLETE,
                analysisVersion = CURRENT_ANALYSIS_VERSION,
                lastAnalysedAt = System.currentTimeMillis(),
                analysisFailureReason = null,
                fileModifiedTimestamp = fileModTime
            )

            trackDao.updateTrack(TrackEntity.fromTrack(finalTrack))
            true
        } catch (e: Exception) {
            val retryCount = track.analysisRetryCount + 1
            val newState = if (retryCount >= 3) AnalysisState.FAILED else AnalysisState.PARTIAL
            Log.e(TAG, "Analysis failed for track '${track.title}' (attempt $retryCount): ${e.message}")

            trackDao.updateTrackAnalysisStatus(
                id = track.id,
                state = newState.name,
                lastAnalysedAt = System.currentTimeMillis(),
                reason = e.message ?: "Unknown error",
                retryCount = retryCount
            )
            false
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
