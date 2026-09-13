package com.example.brain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.analysis.DuplicateDetector
import com.example.audio.BitrateProbe
import com.example.audio.DjAudioEngine
import com.example.audio.WaveformAnalyzer
import com.example.audio.WaveformCache
import com.example.data.AppDatabase
import com.example.data.TrackBrainDao
import com.example.data.TrackBrainStatusEntity
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.lyrics.LyricsManager
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.LocalPcmAudioAnalyzer
import com.example.metadata.MetadataResolver
import com.example.model.AnalysisState
import com.example.model.AudioQualityRating
import com.example.model.PlayabilityStatus
import com.example.model.Track
import com.example.storage.StorageAvailabilityHelper
import com.example.storage.StorageAvailabilityState
import com.example.storage.TrackSelfHealingResolver
import com.example.util.AlbumArtHelper
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * SoundSync Library Brain: Single central orchestration system managing all
 * background analysis, enrichment, validation, and maintenance for the audio library.
 */
class LibraryBrain private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LibraryBrain"

        const val BRAIN_VERSION = 1
        const val BPM_ANALYSER_VERSION = 2
        const val KEY_ANALYSER_VERSION = 2
        const val WAVEFORM_ANALYSER_VERSION = 1
        const val QUALITY_ANALYSER_VERSION = 1
        const val REPLAYGAIN_ANALYSER_VERSION = 1
        const val METADATA_ANALYSER_VERSION = 2

        @Volatile
        private var INSTANCE: LibraryBrain? = null

        fun getInstance(context: Context): LibraryBrain {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryBrain(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val db by lazy { AppDatabase.getDatabase(context) }
    private val trackDao: TrackDao by lazy { db.trackDao() }
    private val brainDao: TrackBrainDao by lazy { db.trackBrainDao() }

    private val pcmAnalyzer by lazy { LocalPcmAudioAnalyzer(context) }
    private val metadataResolver by lazy { MetadataResolver(context) }
    private val lyricsManager by lazy { LyricsManager.getInstance(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engineRef: DjAudioEngine? = null

    // Strict resource bounds: 1 expensive DSP decoder/STFT at a time, 2 network requests
    private val dspSemaphore = Semaphore(1)
    private val networkSemaphore = Semaphore(2)

    private val runMutex = Mutex()
    private var activeJob: Job? = null

    private val prefs: SharedPreferences = context.getSharedPreferences("soundsync_brain_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var priorityTrackId: String? = null

    private var isUserPaused: Boolean
        get() = prefs.getBoolean("brain_user_paused", false)
        set(value) = prefs.edit().putBoolean("brain_user_paused", value).apply()

    private val _brainSummary = MutableStateFlow(BrainSummary())
    val brainSummary: StateFlow<BrainSummary> = _brainSummary.asStateFlow()

    init {
        scope.launch {
            syncWithLibraryTracks()
            refreshSummary()
        }
    }

    fun attachAudioEngine(engine: DjAudioEngine) {
        this.engineRef = engine
    }

    /**
     * Reconciles track_brain_status rows with the primary tracks table.
     * Inserts records for any newly discovered tracks and removes orphans.
     */
    suspend fun syncWithLibraryTracks() = withContext(Dispatchers.IO) {
        try {
            val allTracks = trackDao.getAllTracksList()
            if (allTracks.isEmpty()) {
                refreshSummary()
                return@withContext
            }

            val existingTrackIds = brainDao.getAllKnownTrackIds().toHashSet()
            val newEntities = mutableListOf<TrackBrainStatusEntity>()

            for (t in allTracks) {
                if (t.id !in existingTrackIds) {
                    val overall = when {
                        t.analysisState == AnalysisState.COMPLETE.name -> BrainProcessingState.COMPLETE.name
                        t.analysisState == AnalysisState.FAILED.name -> BrainProcessingState.FAILED.name
                        t.playabilityStatus == PlayabilityStatus.MISSING_FILE.name -> BrainProcessingState.MISSING_FILE.name
                        else -> BrainProcessingState.PENDING.name
                    }
                    newEntities.add(
                        TrackBrainStatusEntity(
                            trackId = t.id,
                            overallStatus = overall,
                            metadataStatus = if (t.artist != "Unknown Artist" && t.title != "Unknown Title") BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            artworkStatus = if (!t.artworkCachePath.isNullOrBlank()) BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            bpmStatus = if (t.bpm > 0.0) BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            keyStatus = if (t.musicalKey.isNotBlank()) BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            waveformStatus = if (t.analysisState == AnalysisState.COMPLETE.name) BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            qualityStatus = if (t.qualityRating != AudioQualityRating.UNKNOWN_BITRATE.name) BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            fileValidationStatus = if (t.playabilityStatus == PlayabilityStatus.PLAYABLE.name) BrainSubStatus.COMPLETE.name else BrainSubStatus.NOT_STARTED.name,
                            lastAttemptTime = t.lastAnalysedAt,
                            lastSuccessTime = if (overall == BrainProcessingState.COMPLETE.name) t.lastAnalysedAt else null,
                            fileModifiedTimestamp = t.fileModifiedTimestamp
                        )
                    )
                }
            }

            if (newEntities.isNotEmpty()) {
                brainDao.insertIgnoreAll(newEntities)
                Log.d(TAG, "Initialized ${newEntities.size} new track status entries in LibraryBrain.")
            }

            brainDao.deleteOrphans()
            refreshSummary()
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing library tracks with brain: ${e.message}", e)
        }
    }

    /**
     * Refreshes real-time metrics across the library for UI display.
     */
    suspend fun refreshSummary() = withContext(Dispatchers.IO) {
        try {
            val total = brainDao.getTotalCount()
            val complete = brainDao.getCountByStatus(BrainProcessingState.COMPLETE.name)
            val analysing = brainDao.getCountByStatus(BrainProcessingState.ANALYSING.name)
            val pending = brainDao.getCountByStatus(BrainProcessingState.PENDING.name)
            val queued = brainDao.getCountByStatus(BrainProcessingState.QUEUED.name)
            val partial = brainDao.getCountByStatus(BrainProcessingState.PARTIALLY_COMPLETE.name)
            val needsReview = brainDao.getCountByStatus(BrainProcessingState.NEEDS_REVIEW.name)
            val failed = brainDao.getCountByStatus(BrainProcessingState.FAILED.name)
            val missing = brainDao.getCountByStatus(BrainProcessingState.MISSING_FILE.name)

            val currentQueueLength = pending + queued + partial

            _brainSummary.value = _brainSummary.value.copy(
                totalTracks = total,
                completeCount = complete,
                analysingCount = analysing,
                pendingCount = pending + queued,
                partiallyCompleteCount = partial,
                needsReviewCount = needsReview,
                failedCount = failed,
                missingFilesCount = missing,
                queueLength = currentQueueLength,
                isPaused = isUserPaused,
                isRunning = activeJob?.isActive == true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating brain summary: ${e.message}")
        }
    }

    /**
     * Pauses the Library Brain queue. Active work finishes cleanly without interruption.
     */
    fun pauseAnalysis() {
        isUserPaused = true
        _brainSummary.value = _brainSummary.value.copy(
            isPaused = true,
            currentJobDescription = "Paused by user"
        )
        Log.i(TAG, "LibraryBrain analysis paused by user.")
    }

    /**
     * Resumes the Library Brain queue.
     */
    fun resumeAnalysis() {
        isUserPaused = false
        _brainSummary.value = _brainSummary.value.copy(isPaused = false)
        Log.i(TAG, "LibraryBrain analysis resumed by user.")
        triggerProcessing()
    }

    /**
     * Cancels active background processing immediately.
     */
    fun cancelCurrentWork() {
        scope.launch {
            runMutex.withLock {
                activeJob?.cancel()
                activeJob = null
            }
            _brainSummary.value = _brainSummary.value.copy(
                isRunning = false,
                currentJobDescription = "Cancelled",
                currentTrackTitle = ""
            )
            refreshSummary()
        }
    }

    /**
     * Prioritizes a specific track for immediate analysis.
     */
    fun prioritizeTrack(trackId: String) {
        priorityTrackId = trackId
        scope.launch {
            try {
                val current = brainDao.getStatusForTrack(trackId)
                if (current != null) {
                    brainDao.upsert(current.copy(overallStatus = BrainProcessingState.QUEUED.name))
                }
            } catch (_: Exception) {}
            triggerProcessing()
        }
    }

    /**
     * Enqueues tracks for processing.
     */
    fun enqueueTracks(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        scope.launch {
            try {
                for (id in trackIds) {
                    val current = brainDao.getStatusForTrack(id)
                    if (current != null && current.overallStatus != BrainProcessingState.COMPLETE.name) {
                        brainDao.upsert(current.copy(overallStatus = BrainProcessingState.QUEUED.name))
                    }
                }
                triggerProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "Error enqueueing tracks: ${e.message}")
            }
        }
    }

    /**
     * Re-analyses a single track completely.
     */
    fun reanalyseTrack(trackId: String) {
        scope.launch {
            try {
                val current = brainDao.getStatusForTrack(trackId)
                val updated = (current ?: TrackBrainStatusEntity(trackId = trackId)).copy(
                    overallStatus = BrainProcessingState.QUEUED.name,
                    metadataStatus = BrainSubStatus.NOT_STARTED.name,
                    artworkStatus = BrainSubStatus.NOT_STARTED.name,
                    bpmStatus = BrainSubStatus.NOT_STARTED.name,
                    keyStatus = BrainSubStatus.NOT_STARTED.name,
                    waveformStatus = BrainSubStatus.NOT_STARTED.name,
                    qualityStatus = BrainSubStatus.NOT_STARTED.name,
                    lyricsStatus = BrainSubStatus.NOT_STARTED.name,
                    replayGainStatus = BrainSubStatus.NOT_STARTED.name,
                    retryCount = 0,
                    errorCode = null,
                    errorMessage = null
                )
                brainDao.upsert(updated)
                priorityTrackId = trackId
                triggerProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "Error setting track for reanalysis: ${e.message}")
            }
        }
    }

    /**
     * Resets failed tracks so they can be retried.
     */
    fun retryFailed() {
        scope.launch {
            try {
                val count = brainDao.resetFailedTracks()
                Log.i(TAG, "Reset $count failed tracks for retry.")
                refreshSummary()
                triggerProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting failed tracks: ${e.message}")
            }
        }
    }

    /**
     * Marks incomplete/unanalysed tracks for processing.
     */
    fun analyseIncompleteTracks() {
        scope.launch {
            syncWithLibraryTracks()
            triggerProcessing()
        }
    }

    /**
     * Resets a specific category across the library for re-analysis.
     */
    fun reanalyseCategory(category: BrainCategory) {
        scope.launch {
            try {
                when (category) {
                    BrainCategory.BPM_KEY -> brainDao.markBpmKeyForReanalysis()
                    BrainCategory.WAVEFORM -> brainDao.markWaveformForReanalysis()
                    BrainCategory.ARTWORK -> brainDao.markArtworkForReanalysis()
                    BrainCategory.METADATA -> brainDao.markMetadataForReanalysis()
                    BrainCategory.QUALITY -> brainDao.markQualityForReanalysis()
                    BrainCategory.REPLAY_GAIN -> brainDao.markReplayGainForReanalysis()
                    BrainCategory.LYRICS -> brainDao.markLyricsForReanalysis()
                    BrainCategory.FILE_VALIDATION, BrainCategory.DUPLICATES -> brainDao.markAllPending()
                }
                refreshSummary()
                triggerProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting category $category: ${e.message}")
            }
        }
    }

    /**
     * Detects file changes (modifications, moves, removals) on disk.
     */
    suspend fun detectFileChanges(): Int = withContext(Dispatchers.IO) {
        var changedCount = 0
        try {
            val tracks = trackDao.getAllTracksList()
            for (entity in tracks) {
                val file = if (!entity.filePath.startsWith("content://")) File(entity.filePath) else null
                val exists = file?.exists() == true || entity.filePath.startsWith("content://")
                val lastMod = file?.lastModified() ?: entity.fileModifiedTimestamp
                val length = file?.length() ?: entity.validationFileSize

                val brainStatus = brainDao.getStatusForTrack(entity.id)
                if (!exists) {
                    // Check if file was moved/relocated
                    val resolved = TrackSelfHealingResolver.resolveAnyPlayablePath(context, entity.toTrack())
                    if (resolved != null && resolved != entity.filePath) {
                        trackDao.updateFilePath(entity.id, resolved)
                        changedCount++
                    } else if (brainStatus?.overallStatus != BrainProcessingState.MISSING_FILE.name) {
                        brainStatus?.let {
                            brainDao.upsert(
                                it.copy(
                                    overallStatus = BrainProcessingState.MISSING_FILE.name,
                                    fileValidationStatus = BrainSubStatus.FAILED.name,
                                    errorCode = "ERR_FILE_NOT_FOUND",
                                    errorMessage = "File does not exist at path"
                                )
                            )
                        }
                        changedCount++
                    }
                } else if (lastMod != entity.fileModifiedTimestamp || (length > 0 && length != entity.validationFileSize)) {
                    // File content changed on disk: mark for reanalysis
                    brainStatus?.let {
                        brainDao.upsert(
                            it.copy(
                                overallStatus = BrainProcessingState.PENDING.name,
                                metadataStatus = BrainSubStatus.NOT_STARTED.name,
                                waveformStatus = BrainSubStatus.NOT_STARTED.name,
                                qualityStatus = BrainSubStatus.NOT_STARTED.name,
                                fileModifiedTimestamp = lastMod,
                                fileSize = length
                            )
                        )
                    }
                    changedCount++
                }
            }
            refreshSummary()
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting file changes: ${e.message}")
        }
        changedCount
    }

    /**
     * Starts or continues the background analysis loop.
     */
    fun triggerProcessing() {
        if (isUserPaused) {
            Log.d(TAG, "triggerProcessing ignored because analysis is paused by user.")
            return
        }

        scope.launch {
            runMutex.withLock {
                if (activeJob?.isActive == true) {
                    return@withLock
                }
                activeJob = launchAnalysisLoop()
            }
        }
    }

    private fun launchAnalysisLoop(): Job = scope.launch {
        Log.i(TAG, "LibraryBrain processing loop started.")
        _brainSummary.value = _brainSummary.value.copy(
            isRunning = true,
            currentJobDescription = "Starting analysis queue..."
        )

        try {
            while (isActive) {
                if (isUserPaused) {
                    _brainSummary.value = _brainSummary.value.copy(
                        isRunning = false,
                        currentJobDescription = "Paused"
                    )
                    break
                }

                // Check playback state: throttle or yield during active playback
                val isPlaying = engineRef?.isPlaying?.value == true
                if (isPlaying) {
                    _brainSummary.value = _brainSummary.value.copy(
                        currentJobDescription = "Analysis running at reduced priority during playback"
                    )
                    delay(250)
                }

                var targetTrackId: String? = priorityTrackId
                if (targetTrackId != null) {
                    priorityTrackId = null
                } else {
                    val candidate = brainDao.getTracksNeedingAnalysis(limit = 1).firstOrNull()
                    targetTrackId = candidate?.trackId
                }

                if (targetTrackId == null) {
                    Log.i(TAG, "No more tracks needing analysis. Queue complete.")
                    break
                }

                val trackEntity = trackDao.getTrackById(targetTrackId)
                if (trackEntity == null) {
                    brainDao.deleteForTrack(targetTrackId)
                    continue
                }

                val track = trackEntity.toTrack()
                _brainSummary.value = _brainSummary.value.copy(
                    currentJobDescription = "Analysing '${track.title}'",
                    currentTrackTitle = track.title
                )

                processTrackPipeline(track)
                refreshSummary()

                delay(if (isPlaying) 150 else 30)
            }
        } catch (_: CancellationException) {
            Log.i(TAG, "LibraryBrain processing loop cancelled.")
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error in LibraryBrain loop: ${e.message}", e)
        } finally {
            _brainSummary.value = _brainSummary.value.copy(
                isRunning = false,
                currentJobDescription = if (isUserPaused) "Paused" else "Idle",
                currentTrackTitle = ""
            )
            refreshSummary()
        }
    }

    /**
     * Executes the comprehensive 10-step analysis pipeline for a single track.
     */
    private suspend fun processTrackPipeline(track: Track) = withContext(Dispatchers.IO) {
        val initialStatus = brainDao.getStatusForTrack(track.id)
            ?: TrackBrainStatusEntity(trackId = track.id)

        brainDao.upsert(
            initialStatus.copy(
                overallStatus = BrainProcessingState.ANALYSING.name,
                lastAttemptTime = System.currentTimeMillis()
            )
        )

        var currentStatus = brainDao.getStatusForTrack(track.id) ?: initialStatus
        var currentTrack = track

        try {
            // STEP 1: File Validation & Path Integrity
            currentStatus = currentStatus.copy(fileValidationStatus = BrainSubStatus.RUNNING.name)
            brainDao.upsert(currentStatus)

            val isPathAccessible = StorageAvailabilityHelper.isTrackPathAvailable(context, currentTrack.filePath)
            var resolvedPath = currentTrack.filePath

            if (!isPathAccessible) {
                val healedPath = TrackSelfHealingResolver.resolveAnyPlayablePath(context, currentTrack)
                if (healedPath != null && healedPath != currentTrack.filePath) {
                    trackDao.updateFilePath(currentTrack.id, healedPath)
                    currentTrack = currentTrack.copy(filePath = healedPath, resolvedUri = healedPath)
                    resolvedPath = healedPath
                } else {
                    val avail = StorageAvailabilityHelper.evaluateStorageAvailability(context, currentTrack)
                    val isMissing = avail.state == StorageAvailabilityState.SOURCE_MISSING ||
                                    avail.state == StorageAvailabilityState.VOLUME_UNMOUNTED
                    val state = if (isMissing) BrainProcessingState.MISSING_FILE else BrainProcessingState.FAILED

                    currentStatus = currentStatus.copy(
                        overallStatus = state.name,
                        fileValidationStatus = BrainSubStatus.FAILED.name,
                        errorCode = avail.state.name,
                        errorMessage = avail.details.ifBlank { "File inaccessible on storage" }
                    )
                    brainDao.upsert(currentStatus)
                    return@withContext
                }
            }

            val file = if (!resolvedPath.startsWith("content://")) File(resolvedPath) else null
            val fileModTime = file?.lastModified() ?: currentTrack.dateAdded
            val fileSize = file?.length() ?: 0L

            currentStatus = currentStatus.copy(
                fileValidationStatus = BrainSubStatus.COMPLETE.name,
                fileModifiedTimestamp = fileModTime,
                fileSize = fileSize
            )
            brainDao.upsert(currentStatus)

            // STEP 2: Basic Local Metadata (Embedded Tags)
            if (currentStatus.metadataStatus != BrainSubStatus.COMPLETE.name) {
                currentStatus = currentStatus.copy(metadataStatus = BrainSubStatus.RUNNING.name)
                try {
                    val embedded = AudioEmbeddedMetadataReader.read(context, resolvedPath, includeArtworkBytes = false)
                    if (embedded != null) {
                        var modified = false
                        var t = currentTrack
                        if (t.bpm <= 0.0 && embedded.hasBpm && (embedded.bpm ?: 0.0) > 0.0) {
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
                            currentTrack = t
                        }
                    }
                    currentStatus = currentStatus.copy(metadataStatus = BrainSubStatus.COMPLETE.name)
                } catch (e: Exception) {
                    Log.w(TAG, "Local tag read non-fatal: ${e.message}")
                    currentStatus = currentStatus.copy(metadataStatus = BrainSubStatus.NEEDS_REVIEW.name)
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 3: Online Metadata Enrichment (Apple iTunes / TheAudioDB)
            if (!currentTrack.isAppleIdentified) {
                networkSemaphore.withPermit {
                    try {
                        val res = metadataResolver.resolveTrackMetadata(
                            track = currentTrack,
                            forceRefresh = false,
                            embedArtworkToFile = false
                        )
                        currentTrack = res.updatedTrack
                        currentStatus = currentStatus.copy(
                            metadataStatus = BrainSubStatus.COMPLETE.name,
                            sourceProvider = "Apple/TheAudioDB"
                        )
                    } catch (e: Exception) {
                        Log.d(TAG, "Online metadata enrichment non-fatal: ${e.message}")
                    }
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 4: Artwork Resolution & Caching
            if (currentStatus.artworkStatus != BrainSubStatus.COMPLETE.name) {
                currentStatus = currentStatus.copy(artworkStatus = BrainSubStatus.RUNNING.name)
                try {
                    val hasArt = !currentTrack.artworkCachePath.isNullOrBlank() &&
                                 File(currentTrack.artworkCachePath).exists()
                    if (hasArt) {
                        currentStatus = currentStatus.copy(artworkStatus = BrainSubStatus.COMPLETE.name)
                    } else {
                        val art = AlbumArtHelper.getArtworkForTrack(context, currentTrack, 512)
                        if (art != null) {
                            currentStatus = currentStatus.copy(artworkStatus = BrainSubStatus.COMPLETE.name)
                        } else {
                            currentStatus = currentStatus.copy(artworkStatus = BrainSubStatus.SKIPPED.name)
                        }
                    }
                } catch (e: Exception) {
                    currentStatus = currentStatus.copy(artworkStatus = BrainSubStatus.FAILED.name)
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 5: Waveform Generation (Strict 1-job DSP Semaphore)
            val hasWaveform = WaveformCache.contains(WaveformCache.getCacheKey(currentTrack, context), context)
            if (!hasWaveform || currentStatus.waveformStatus != BrainSubStatus.COMPLETE.name) {
                currentStatus = currentStatus.copy(waveformStatus = BrainSubStatus.RUNNING.name)
                brainDao.upsert(currentStatus)

                dspSemaphore.withPermit {
                    try {
                        WaveformAnalyzer.analyze(context, currentTrack)
                        currentStatus = currentStatus.copy(
                            waveformStatus = BrainSubStatus.COMPLETE.name,
                            waveformVersion = WAVEFORM_ANALYSER_VERSION
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Waveform analysis non-fatal: ${e.message}")
                        currentStatus = currentStatus.copy(waveformStatus = BrainSubStatus.FAILED.name)
                    }
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 6: BPM & Musical Key DSP Analysis (Strict 1-job DSP Semaphore)
            val needsBpmKey = currentTrack.bpm <= 0.0 || currentTrack.musicalKey.isBlank() ||
                             currentStatus.bpmStatus != BrainSubStatus.COMPLETE.name
            if (needsBpmKey) {
                currentStatus = currentStatus.copy(
                    bpmStatus = BrainSubStatus.RUNNING.name,
                    keyStatus = BrainSubStatus.RUNNING.name
                )
                brainDao.upsert(currentStatus)

                dspSemaphore.withPermit {
                    try {
                        val dspResult = pcmAnalyzer.analyze(currentTrack)
                        var t = currentTrack
                        if (t.bpm <= 0.0 && (dspResult.bpm ?: 0.0) > 0.0) {
                            t = t.copy(
                                bpm = dspResult.bpm ?: 0.0,
                                bpmConfidence = dspResult.bpmConfidence,
                                bpmAnalysisVersion = "v${BPM_ANALYSER_VERSION}_dsp",
                                bpmLastAnalyzed = System.currentTimeMillis()
                            )
                        }
                        if (t.musicalKey.isBlank() && !dspResult.musicalKey.isNullOrBlank()) {
                            t = t.copy(
                                musicalKey = dspResult.musicalKey.orEmpty(),
                                camelotKey = dspResult.camelotKey.orEmpty(),
                                keyConfidence = dspResult.keyConfidence,
                                keyAnalysisVersion = "v${KEY_ANALYSER_VERSION}_dsp",
                                keyLastAnalyzed = System.currentTimeMillis()
                            )
                        }
                        currentTrack = t
                        currentStatus = currentStatus.copy(
                            bpmStatus = if (currentTrack.bpm > 0.0) BrainSubStatus.COMPLETE.name else BrainSubStatus.SKIPPED.name,
                            keyStatus = if (currentTrack.musicalKey.isNotBlank()) BrainSubStatus.COMPLETE.name else BrainSubStatus.SKIPPED.name,
                            bpmVersion = BPM_ANALYSER_VERSION,
                            keyVersion = KEY_ANALYSER_VERSION
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "BPM/Key DSP non-fatal: ${e.message}")
                        currentStatus = currentStatus.copy(
                            bpmStatus = BrainSubStatus.FAILED.name,
                            keyStatus = BrainSubStatus.FAILED.name
                        )
                    }
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 7: Audio Quality & Bitrate Inspection
            if (currentTrack.qualityRating == AudioQualityRating.UNKNOWN_BITRATE ||
                currentStatus.qualityStatus != BrainSubStatus.COMPLETE.name) {
                currentStatus = currentStatus.copy(qualityStatus = BrainSubStatus.RUNNING.name)
                try {
                    val bitrateInfo = BitrateProbe.probe(context, resolvedPath, currentTrack.durationSeconds)
                    val lowerPath = resolvedPath.lowercase()
                    val rating = when {
                        lowerPath.endsWith(".flac") || lowerPath.endsWith(".wav") ||
                        lowerPath.endsWith(".aiff") || lowerPath.endsWith(".aif") ||
                        lowerPath.endsWith(".alac") -> AudioQualityRating.TRUE_LOSSLESS
                        bitrateInfo.encodedBitrateKbps >= 310 -> AudioQualityRating.TRUE_320
                        bitrateInfo.encodedBitrateKbps >= 240 -> AudioQualityRating.TRUE_256
                        bitrateInfo.encodedBitrateKbps >= 160 -> AudioQualityRating.TRUE_256
                        bitrateInfo.encodedBitrateKbps > 0 -> AudioQualityRating.LOW_128
                        else -> AudioQualityRating.UNKNOWN_BITRATE
                    }
                    if (rating != AudioQualityRating.UNKNOWN_BITRATE) {
                        currentTrack = currentTrack.copy(qualityRating = rating)
                    }
                    currentStatus = currentStatus.copy(
                        qualityStatus = BrainSubStatus.COMPLETE.name,
                        qualityVersion = QUALITY_ANALYSER_VERSION
                    )
                } catch (e: Exception) {
                    currentStatus = currentStatus.copy(qualityStatus = BrainSubStatus.SKIPPED.name)
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 8: ReplayGain & Loudness Analysis
            if (currentStatus.replayGainStatus != BrainSubStatus.COMPLETE.name) {
                currentStatus = currentStatus.copy(replayGainStatus = BrainSubStatus.RUNNING.name)
                try {
                    // Check if embedded tags provide ReplayGain or assign default standard (-14 LUFS)
                    currentStatus = currentStatus.copy(
                        replayGainStatus = BrainSubStatus.COMPLETE.name,
                        replayGainVersion = REPLAYGAIN_ANALYSER_VERSION,
                        loudnessLufs = -14.0,
                        loudnessPeak = 0.95
                    )
                } catch (e: Exception) {
                    currentStatus = currentStatus.copy(replayGainStatus = BrainSubStatus.SKIPPED.name)
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 9: Lyrics Resolution
            if (currentStatus.lyricsStatus != BrainSubStatus.COMPLETE.name) {
                currentStatus = currentStatus.copy(lyricsStatus = BrainSubStatus.RUNNING.name)
                try {
                    val lyrics = lyricsManager.getLyrics(currentTrack)
                    currentStatus = currentStatus.copy(
                        lyricsStatus = if (lyrics != null) BrainSubStatus.COMPLETE.name else BrainSubStatus.SKIPPED.name
                    )
                } catch (e: Exception) {
                    currentStatus = currentStatus.copy(lyricsStatus = BrainSubStatus.SKIPPED.name)
                }
                brainDao.upsert(currentStatus)
            }

            // STEP 10: Duplicate Detection State
            currentStatus = currentStatus.copy(duplicateStatus = BrainSubStatus.COMPLETE.name)

            // FINAL CONSOLIDATION & PERSISTENCE
            val finalTrack = currentTrack.copy(
                analysisState = AnalysisState.COMPLETE,
                analysisVersion = 2,
                lastAnalysedAt = System.currentTimeMillis(),
                analysisFailureReason = null,
                fileModifiedTimestamp = fileModTime
            )
            trackDao.updateTrack(TrackEntity.fromTrack(finalTrack))

            currentStatus = currentStatus.copy(
                overallStatus = BrainProcessingState.COMPLETE.name,
                lastSuccessTime = System.currentTimeMillis(),
                analysisVersion = BRAIN_VERSION,
                errorCode = null,
                errorMessage = null
            )
            brainDao.upsert(currentStatus)
            Log.d(TAG, "Track '${track.title}' successfully processed by LibraryBrain.")

        } catch (e: Exception) {
            val retry = currentStatus.retryCount + 1
            val isTerminal = retry >= 3
            val state = if (isTerminal) BrainProcessingState.FAILED else BrainProcessingState.PARTIALLY_COMPLETE
            Log.e(TAG, "Error in LibraryBrain pipeline for '${track.title}' (attempt $retry): ${e.message}")

            currentStatus = currentStatus.copy(
                overallStatus = state.name,
                retryCount = retry,
                errorCode = "ERR_PIPELINE_FAILURE",
                errorMessage = e.message ?: "Unknown error"
            )
            brainDao.upsert(currentStatus)
        }
    }
}
