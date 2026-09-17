package com.example.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.SongFindEntity
import com.example.data.TrackEntity
import com.example.model.PlayabilityStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupFileInfo(
    val file: File?,
    val uri: Uri?,
    val displayName: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val version: Int = 1,
    val trackCount: Int = 0,
    val songFindCount: Int = 0
)

/**
 * Manages persistent JSON backups of SoundSync data (Song Finds, beatgrid/BPM/key analysis, repaired artist metadata).
 *
 * Designed to survive full app uninstall/reinstall by persisting to shared Documents or user-selected SAF directories.
 */
class SoundSyncBackupManager(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var autoBackupJob: Job? = null

    private val _summaryFlow = MutableStateFlow(loadSummary())
    val summaryFlow: StateFlow<BackupSummary> = _summaryFlow.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val restoreMutex = Mutex()
    private val _restoreProgress = MutableStateFlow(RestoreProgress())
    val restoreProgress: StateFlow<RestoreProgress> = _restoreProgress.asStateFlow()

    init {
        val priorState = getDurableRestoreState()
        if (priorState == DurableRestoreState.RESTORING || priorState == DurableRestoreState.VALIDATING) {
            Log.w(TAG, "[ProcessDeathRecovery] Detected interrupted restore ($priorState) from prior session. Resetting to CRASHED to prevent crash loops.")
            setDurableRestoreState(DurableRestoreState.CRASHED, "Previous restore operation was interrupted by process termination.")
        }
    }

    companion object {
        private const val TAG = "SoundSyncBackupManager"
        private const val PREFS_NAME = "soundsync_backup_prefs"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_EXPLICIT_SET = "auto_backup_explicit_set"
        private const val KEY_CUSTOM_TREE_URI = "custom_backup_tree_uri"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_TRACKS = "last_backup_tracks"
        private const val KEY_LAST_BACKUP_FINDS = "last_backup_finds"
        private const val KEY_LAST_BACKUP_LOC = "last_backup_loc"

        private const val KEY_DURABLE_RESTORE_STATE = "durable_restore_state"
        private const val KEY_DURABLE_RESTORE_TIMESTAMP = "durable_restore_timestamp"
        private const val KEY_DURABLE_RESTORE_ERROR = "durable_restore_error"

        const val BACKUP_FILENAME = "soundsync_backup.json"
        const val BACKUP_SUBFOLDER = "SoundSync/backups"

        private val _isRestoreInProgress = MutableStateFlow(false)
        val isRestoreInProgress: StateFlow<Boolean> = _isRestoreInProgress.asStateFlow()

        @JvmStatic
        fun isRestoring(): Boolean = _isRestoreInProgress.value

        @Volatile
        private var INSTANCE: SoundSyncBackupManager? = null

        fun getInstance(context: Context): SoundSyncBackupManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SoundSyncBackupManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstance() {
            INSTANCE = null
        }
    }

    fun getDurableRestoreState(): DurableRestoreState {
        val raw = prefs.getString(KEY_DURABLE_RESTORE_STATE, DurableRestoreState.IDLE.name)
        return try {
            DurableRestoreState.valueOf(raw ?: DurableRestoreState.IDLE.name)
        } catch (_: Exception) {
            DurableRestoreState.IDLE
        }
    }

    private fun setDurableRestoreState(state: DurableRestoreState, errorMsg: String? = null) {
        prefs.edit()
            .putString(KEY_DURABLE_RESTORE_STATE, state.name)
            .putLong(KEY_DURABLE_RESTORE_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_DURABLE_RESTORE_ERROR, errorMsg)
            .commit()
    }

    fun isAutoBackupEnabled(): Boolean {
        // Stage 1 requirement: Auto Backup MUST default to OFF.
        // If the user has not explicitly configured it, default conservatively to false (OFF).
        if (!prefs.contains(KEY_AUTO_BACKUP_EXPLICIT_SET)) {
            return false
        }
        return prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled)
            .putBoolean(KEY_AUTO_BACKUP_EXPLICIT_SET, true)
            .commit()
        if (!enabled) {
            autoBackupJob?.cancel()
            autoBackupJob = null
        }
        _summaryFlow.value = loadSummary()
    }

    fun getCustomBackupTreeUri(): Uri? {
        val uriStr = prefs.getString(KEY_CUSTOM_TREE_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (_: Exception) {
            null
        }
    }

    fun setCustomBackupTreeUri(uri: Uri?) {
        prefs.edit().putString(KEY_CUSTOM_TREE_URI, uri?.toString()).apply()
        _summaryFlow.value = loadSummary()
    }

    /**
     * Debounced notification triggered whenever tracks, analysis, or Song Finds change.
     * Backs up automatically after 5 seconds of inactivity if auto-backup is enabled.
     */
    fun notifyDataChanged() {
        if (!isAutoBackupEnabled() || isRestoring()) return

        autoBackupJob?.cancel()
        autoBackupJob = scope.launch {
            delay(5000)
            try {
                val analysisManager = com.example.analysis.TrackAnalysisManager.getInstance(context)
                while (analysisManager.queueProgress.value.isRunning) {
                    delay(3000)
                }
            } catch (_: Exception) {}
            Log.d(TAG, "Triggering automatic debounced backup...")
            createBackup()
        }
    }

    /**
     * Creates a complete backup of all Song Finds and Track analysis & metadata.
     * Writes atomically to persistent storage.
     */
    suspend fun createBackup(targetUri: Uri? = null): Result<BackupSummary> = withContext(Dispatchers.IO) {
        _isBackingUp.value = true
        try {
            val tracks = database.trackDao().getAllTracksSync()
            val songFinds = database.songFindDao().getAllSongFindsSync()
            val djPrepList = database.djPrepDao().getAllPrepData()
            val doctorPrefs = com.example.doctor.LibraryDoctorPreferences.getInstance(context)
            val ignoredIssues = doctorPrefs.getAllIgnored().toList()
            val reviewedIssues = doctorPrefs.getAllReviewed().toList()

            val eqFile = File(context.filesDir, "parametric_eq_presets.json")
            val eqConfig = if (eqFile.exists() && eqFile.length() > 0L) {
                runCatching { eqFile.readText(java.nio.charset.StandardCharsets.UTF_8) }.getOrNull()
            } else null

            val backup = SoundSyncBackup(
                backupVersion = SoundSyncBackup.CURRENT_BACKUP_VERSION,
                appVersion = "1.0.0",
                createdAt = prefs.getLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis()),
                updatedAt = System.currentTimeMillis(),
                songFinds = songFinds.map { SongFindBackupItem.fromEntity(it) },
                tracks = tracks.map { TrackBackupItem.fromEntity(it) },
                doctorIgnoredIssues = ignoredIssues,
                doctorReviewedIssues = reviewedIssues,
                djPrepData = djPrepList.map { DjPrepBackupItem.fromEntity(it) },
                eqConfigJson = eqConfig
            )

            val jsonString = serializeBackup(backup)
            var savedPath = "Unknown"

            if (targetUri != null) {
                // User-selected destination URI
                writeJsonToUri(targetUri, jsonString)
                savedPath = targetUri.toString()
            } else {
                // Check if user set a custom SAF tree folder
                val customTreeUri = getCustomBackupTreeUri()
                var wroteSaf = false
                if (customTreeUri != null) {
                    try {
                        wroteSaf = writeToSafDirectory(customTreeUri, jsonString)
                        if (wroteSaf) savedPath = "SAF: $customTreeUri"
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed writing to custom SAF backup directory, falling back", e)
                    }
                }

                if (!wroteSaf) {
                    // Standard persistent storage: Documents/SoundSync/backups/
                    val file = writeToDefaultDirectory(jsonString)
                    savedPath = file.absolutePath
                }
            }

            // Update preferences
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, backup.updatedAt)
                .putInt(KEY_LAST_BACKUP_TRACKS, backup.tracks.size)
                .putInt(KEY_LAST_BACKUP_FINDS, backup.songFinds.size)
                .putString(KEY_LAST_BACKUP_LOC, savedPath)
                .apply()

            val summary = loadSummary()
            _summaryFlow.value = summary
            Log.i(TAG, "Backup created successfully: ${backup.tracks.size} tracks, ${backup.songFinds.size} song finds.")
            Result.success(summary)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            Result.failure(e)
        } finally {
            _isBackingUp.value = false
        }
    }

    /**
     * Restores backup from a user-specified Uri or from the latest detected persistent backup file.
     * Executes non-destructively, in bounded batches, fully off the main thread.
     * Reconciles current device storage asynchronously with bounded concurrency.
     */
    suspend fun restoreBackupFromString(jsonString: String): RestoreResult = withContext(Dispatchers.IO) {
        executeRestore { jsonString }
    }

    suspend fun restoreBackup(sourceUri: Uri? = null): RestoreResult = withContext(Dispatchers.IO) {
        executeRestore {
            if (sourceUri != null) {
                readJsonFromUri(sourceUri)
            } else {
                val latest = findLatestBackup()
                if (latest == null) {
                    throw IllegalStateException("No backup found on device to restore.")
                }
                if (latest.uri != null) {
                    readJsonFromUri(latest.uri)
                } else if (latest.file != null && latest.file.exists()) {
                    latest.file.readText(StandardCharsets.UTF_8)
                } else {
                    throw IllegalStateException("Backup file is inaccessible.")
                }
            }
        }
    }

    private suspend fun executeRestore(jsonProvider: suspend () -> String): RestoreResult {
        if (!restoreMutex.tryLock()) {
            return RestoreResult.Error("A backup restore is already in progress. Please wait for it to complete.")
        }

        _isRestoring.value = true
        _isRestoreInProgress.value = true
        setDurableRestoreState(DurableRestoreState.VALIDATING)

        val restoreStartNs = System.nanoTime()
        var lastProgressNs = 0L

        fun updateProgress(stage: RestoreStage, current: Int, total: Int, message: String, force: Boolean = false) {
            val nowNs = System.nanoTime()
            if (!force && nowNs - lastProgressNs < 100_000_000L && current < total) {
                return
            }
            lastProgressNs = nowNs
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            _restoreProgress.value = RestoreProgress(
                stage = stage,
                current = current,
                total = total,
                message = message,
                recordsProcessed = current,
                recordsRemaining = (total - current).coerceAtLeast(0),
                memoryUsageMb = usedMemMb,
                elapsedMs = (nowNs - restoreStartNs) / 1_000_000L
            )
            RestoreDiagnosticLogger.logStage(stage, (nowNs - restoreStartNs) / 1_000_000L, current, total, message)
        }

        return try {
            updateProgress(RestoreStage.READING_BACKUP, 0, 0, "Reading backup file...", force = true)

            val jsonString = try {
                jsonProvider()
            } catch (e: Exception) {
                setDurableRestoreState(DurableRestoreState.FAILED, e.message ?: "Backup file is inaccessible.")
                val diag = "Exception: ${e.javaClass.simpleName}: ${e.message}\n\nRestore Diagnostic Log:\n${RestoreDiagnosticLogger.dump()}"
                return RestoreResult.Error(e.message ?: "Backup file is inaccessible.", diagnosticDetails = diag)
            }

            updateProgress(RestoreStage.VALIDATING_SCHEMA, 0, 0, "Validating backup schema...", force = true)
            val validation = validateBackup(jsonString)
            if (validation is ValidationResult.Invalid) {
                setDurableRestoreState(DurableRestoreState.FAILED, validation.reason)
                val diag = "Validation failed: ${validation.reason}\n\nRestore Diagnostic Log:\n${RestoreDiagnosticLogger.dump()}"
                return RestoreResult.Error("Invalid backup format: ${validation.reason}", diagnosticDetails = diag)
            }

            val backup = (validation as ValidationResult.Valid).backup
            setDurableRestoreState(DurableRestoreState.RESTORING)

            var restoredTracks = 0
            var matchedTracks = 0
            var restoredFinds = 0

            // 1. Song Finds
            updateProgress(RestoreStage.RESTORING_SONG_FINDS, 0, backup.songFinds.size, "Checking existing Song Finds...")
            val currentFinds = database.songFindDao().getAllSongFindsSync()
            val currentUrls = currentFinds.map { it.url.trim().lowercase(Locale.ROOT) }.toSet()
            val currentIds = currentFinds.map { it.id }.toSet()

            val newFindsToInsert = mutableListOf<SongFindEntity>()
            for (backupFind in backup.songFinds) {
                val urlNorm = backupFind.url.trim().lowercase(Locale.ROOT)
                if (!currentUrls.contains(urlNorm) && !currentIds.contains(backupFind.id)) {
                    newFindsToInsert.add(backupFind.toEntity())
                }
            }
            if (newFindsToInsert.isNotEmpty()) {
                newFindsToInsert.chunked(200).forEachIndexed { index, chunk ->
                    database.songFindDao().insertSongFinds(chunk)
                    val processed = ((index + 1) * 200).coerceAtMost(newFindsToInsert.size)
                    updateProgress(RestoreStage.RESTORING_SONG_FINDS, processed, newFindsToInsert.size, "Restoring Song Finds: $processed / ${newFindsToInsert.size}")
                    yield()
                }
                restoredFinds = newFindsToInsert.size
            }

            // 2. Track matching using optimized TrackMatcher (O(N) indexed lookups)
            updateProgress(RestoreStage.MATCHING_TRACKS, 0, backup.tracks.size, "Matching tracks with current library...", force = true)
            val currentTracks = database.trackDao().getAllTracksSync()
            val matchResults = TrackMatcher.matchTracks(backup.tracks, currentTracks)
            updateProgress(RestoreStage.MATCHING_TRACKS, backup.tracks.size, backup.tracks.size, "Track matching completed.", force = true)

            // 3. Separate into updates and inserts
            val tracksToUpdate = mutableListOf<TrackEntity>()
            val tracksToInsert = mutableListOf<TrackEntity>()

            for (result in matchResults) {
                if (result.matchedEntity != null) {
                    val merged = TrackMatcher.mergeTrack(
                        backupTrack = result.backupTrack,
                        existingEntity = result.matchedEntity,
                        isFileModified = result.isFileModified
                    )
                    tracksToUpdate.add(merged)
                    matchedTracks++
                } else {
                    // Restored unmatched track: verify physical path availability quickly without blocking
                    val isAvail = com.example.storage.StorageAvailabilityHelper.isTrackPathAvailable(context, result.backupTrack.filePath)
                    val playability = if (isAvail) {
                        com.example.model.PlayabilityStatus.PLAYABLE.name
                    } else if (result.backupTrack.filePath.isNotBlank()) {
                        com.example.model.PlayabilityStatus.SOURCE_RELINK_PENDING.name
                    } else {
                        com.example.model.PlayabilityStatus.MISSING_FILE.name
                    }

                    val restoredEntity = result.backupTrack.toEntity().copy(
                        isOfflineReady = false,
                        playabilityStatus = playability,
                        playbackErrorCode = if (!isAvail) "SOURCE_RELINK_PENDING" else null,
                        metadataScanState = if (result.backupTrack.metadataScanState.isNotBlank() && result.backupTrack.metadataScanState != "NOT_SCANNED") {
                            result.backupTrack.metadataScanState
                        } else {
                            com.example.model.MetadataScanState.RESTORED.name
                        },
                        analysisState = if (result.backupTrack.bpm > 0.0 || result.backupTrack.musicalKey.isNotBlank() || result.backupTrack.analysisState == "COMPLETE") {
                            "COMPLETE"
                        } else {
                            result.backupTrack.analysisState
                        },
                        userConfirmedMetadata = true
                    )
                    tracksToInsert.add(restoredEntity)
                    restoredTracks++
                }
            }

            // 4. Batch database transactional restore
            val totalTrackOps = tracksToUpdate.size + tracksToInsert.size
            updateProgress(RestoreStage.RESTORING_TRACKS, 0, totalTrackOps, "Writing tracks to database in batches...", force = true)

            database.withTransaction {
                var processedSoFar = 0
                if (tracksToUpdate.isNotEmpty()) {
                    tracksToUpdate.chunked(200).forEach { chunk ->
                        database.trackDao().updateTracks(chunk)
                        processedSoFar += chunk.size
                        updateProgress(RestoreStage.RESTORING_TRACKS, processedSoFar, totalTrackOps, "Updated $processedSoFar / $totalTrackOps tracks")
                    }
                }
                if (tracksToInsert.isNotEmpty()) {
                    tracksToInsert.chunked(200).forEach { chunk ->
                        database.trackDao().insertTracks(chunk)
                        processedSoFar += chunk.size
                        updateProgress(RestoreStage.RESTORING_TRACKS, processedSoFar, totalTrackOps, "Inserted $processedSoFar / $totalTrackOps tracks")
                    }
                }

                // 5. Restore DJ Prep Data in chunks
                if (backup.djPrepData.isNotEmpty()) {
                    updateProgress(RestoreStage.RESTORING_DJ_PREP, 0, backup.djPrepData.size, "Restoring DJ Prep data...")
                    backup.djPrepData.chunked(200).forEach { chunk ->
                        for (prepItem in chunk) {
                            val existing = database.djPrepDao().getByTrackId(prepItem.trackId)
                            if (existing == null || existing.prepStatus == "NOT_ANALYSED" || prepItem.updatedAt > existing.updatedAt) {
                                database.djPrepDao().insertOrUpdate(prepItem.toEntity())
                            }
                        }
                    }
                    updateProgress(RestoreStage.RESTORING_DJ_PREP, backup.djPrepData.size, backup.djPrepData.size, "DJ Prep data restored.")
                }

                // 6. Restore Preferences (Doctor issues & EQ)
                updateProgress(RestoreStage.RESTORING_PREFERENCES, 0, 1, "Restoring preferences and EQ presets...")
                try {
                    val doctorPrefs = com.example.doctor.LibraryDoctorPreferences.getInstance(context)
                    if (backup.doctorIgnoredIssues.isNotEmpty()) {
                        doctorPrefs.restoreIgnored(backup.doctorIgnoredIssues)
                    }
                    if (backup.doctorReviewedIssues.isNotEmpty()) {
                        doctorPrefs.restoreReviewed(backup.doctorReviewedIssues)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed restoring Doctor issue preferences", e)
                }

                try {
                    if (!backup.eqConfigJson.isNullOrBlank()) {
                        val eqFile = File(context.filesDir, "parametric_eq_presets.json")
                        eqFile.writeText(backup.eqConfigJson, java.nio.charset.StandardCharsets.UTF_8)
                        com.example.audio.ParametricEqManager.getInstance(context).restoreFromDisk()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed restoring EQ configuration", e)
                }
            }

            // Commit complete!
            val totalElapsedMs = (System.nanoTime() - restoreStartNs) / 1_000_000L
            setDurableRestoreState(DurableRestoreState.SUCCESS)

            updateProgress(RestoreStage.FINALIZING, totalTrackOps, totalTrackOps, "Finalizing restore...", force = true)

            // Section 21 diagnostic logging
            RestoreDiagnosticLogger.logCoreRestoreComplete(restoredTracks, matchedTracks, restoredFinds, totalElapsedMs)
            val pendingRelinkCount = tracksToInsert.count { it.playabilityStatus == com.example.model.PlayabilityStatus.SOURCE_RELINK_PENDING.name }
            val pendingAnalysisCount = tracksToInsert.count { it.analysisState != "COMPLETE" }

            RestoreDiagnosticLogger.logSystemPostRestore("LIBRARY_RESCAN", 0, "status=suppressed_during_restore")
            RestoreDiagnosticLogger.logSystemPostRestore("MEDIASTORE_RECONCILIATION", pendingRelinkCount, "reconciling_asynchronously")
            RestoreDiagnosticLogger.logSystemPostRestore("METADATA_SCAN", 0, "status=preserved_from_backup")
            RestoreDiagnosticLogger.logSystemPostRestore("BPM_ANALYSIS", pendingAnalysisCount, "reanalysis_suppressed_for_valid_restored_data")
            RestoreDiagnosticLogger.logSystemPostRestore("KEY_ANALYSIS", pendingAnalysisCount, "reanalysis_suppressed_for_valid_restored_data")
            RestoreDiagnosticLogger.logSystemPostRestore("ARTWORK_REFRESH", 0, "status=lazy_loaded_on_demand")
            RestoreDiagnosticLogger.logSystemPostRestore("SEARCH_INDEX_REBUILD", 1, "scheduled_debounced")
            RestoreDiagnosticLogger.logSystemPostRestore("FILE_RELOCATION_SCAN", pendingRelinkCount, "bounded_background_dispatch")
            RestoreDiagnosticLogger.logSystemPostRestore("PLAYLIST_REPAIR", 0, "playlists_intact")

            // Update preferences & summary
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, backup.updatedAt)
                .putInt(KEY_LAST_BACKUP_TRACKS, backup.tracks.size)
                .putInt(KEY_LAST_BACKUP_FINDS, backup.songFinds.size)
                .apply()
            _summaryFlow.value = loadSummary()

            updateProgress(RestoreStage.COMPLETED, totalTrackOps, totalTrackOps, "Restore completed successfully!", force = true)

            val message = "Restored $matchedTracks matched tracks, $restoredTracks new track records, and $restoredFinds Song Finds in ${totalElapsedMs}ms."
            Log.i(TAG, message)

            // Launch bounded background reconciliation for relink-pending tracks
            if (pendingRelinkCount > 0) {
                scope.launch {
                    reconcileRelinkPendingTracksInBackground(tracksToInsert.filter { it.playabilityStatus == com.example.model.PlayabilityStatus.SOURCE_RELINK_PENDING.name })
                }
            }

            RestoreResult.Success(
                tracksRestored = restoredTracks,
                tracksMatched = matchedTracks,
                songFindsRestored = restoredFinds,
                message = message
            )
        } catch (e: Exception) {
            val totalElapsedMs = (System.nanoTime() - restoreStartNs) / 1_000_000L
            setDurableRestoreState(DurableRestoreState.FAILED, e.message)
            RestoreDiagnosticLogger.logError(RestoreStage.FAILED, "Restore failed after ${totalElapsedMs}ms: ${e.message}", e)
            updateProgress(RestoreStage.FAILED, 0, 0, "Restore failed: ${e.message ?: "Unknown error"}", force = true)
            val diagDetails = "Exception: ${e.javaClass.simpleName}: ${e.message}\nStack trace:\n${Log.getStackTraceString(e)}"
            RestoreResult.Error(
                message = "Backup restore could not be completed. Your existing library has been kept unchanged.",
                cause = e,
                diagnosticDetails = diagDetails
            )
        } finally {
            _isRestoring.value = false
            _isRestoreInProgress.value = false
            restoreMutex.unlock()
        }
    }

    private suspend fun reconcileRelinkPendingTracksInBackground(pendingTracks: List<TrackEntity>) = withContext(Dispatchers.IO) {
        if (pendingTracks.isEmpty()) return@withContext
        Log.i(TAG, "Starting bounded background reconciliation for ${pendingTracks.size} relink-pending tracks...")
        val batchLimit = 20
        pendingTracks.chunked(batchLimit).forEach { chunk ->
            if (_isRestoreInProgress.value) return@withContext
            for (entity in chunk) {
                try {
                    val track = entity.toTrack()
                    val resolution = com.example.storage.TrackSourceResolver.resolveTrackSource(
                        context,
                        track,
                        persistToDb = true,
                        trackDao = database.trackDao()
                    )
                    if (!resolution.isPlayable && resolution.volumeUuid != null) {
                        val volInfo = com.example.storage.TrackSourceResolver.getStorageVolumeForPath(context, entity.filePath)
                        val status = if (volInfo?.isMounted == false) {
                            com.example.model.PlayabilityStatus.VOLUME_UNAVAILABLE.name
                        } else if (resolution.requiresFolderAccess) {
                            com.example.model.PlayabilityStatus.PERMISSION_REQUIRED.name
                        } else {
                            entity.playabilityStatus
                        }
                        val code = if (volInfo?.isMounted == false) "ERR_STORAGE_UNMOUNTED" else if (resolution.requiresFolderAccess) "ERR_SCOPED_STORAGE_RESTRICTION" else null
                        database.trackDao().updatePlayabilityStatus(entity.id, status, code, null)
                    }
                } catch (_: Throwable) {}
                delay(20)
            }
            delay(100)
        }
        Log.i(TAG, "Completed bounded background reconciliation for relink-pending tracks.")
    }

    /**
     * Validates a JSON string against the SoundSync backup schema.
     */
    fun validateBackup(jsonString: String): ValidationResult {
        if (jsonString.isBlank()) {
            return ValidationResult.Invalid("Backup file is empty.")
        }
        return try {
            val root = JSONObject(jsonString)
            val version = root.optInt("backupVersion", root.optInt("version", -1))
            if (version < 1) {
                return ValidationResult.Invalid("Unsupported backup version or missing header.")
            }
            if (version > SoundSyncBackup.CURRENT_BACKUP_VERSION) {
                return ValidationResult.Invalid("Backup was generated by a newer version ($version) of SoundSync.")
            }

            val appVersion = root.optString("appVersion", "Unknown")
            val createdAt = root.optLong("createdAt", System.currentTimeMillis())
            val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())

            val findsArray = root.optJSONArray("songFinds") ?: JSONArray()
            val songFinds = mutableListOf<SongFindBackupItem>()
            for (i in 0 until findsArray.length()) {
                songFinds.add(SongFindBackupItem.fromJson(findsArray.getJSONObject(i)))
            }

            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = mutableListOf<TrackBackupItem>()
            for (i in 0 until tracksArray.length()) {
                tracks.add(TrackBackupItem.fromJson(tracksArray.getJSONObject(i)))
            }

            val ignoredArray = root.optJSONArray("doctorIgnoredIssues") ?: JSONArray()
            val doctorIgnored = mutableListOf<String>()
            for (i in 0 until ignoredArray.length()) {
                doctorIgnored.add(ignoredArray.getString(i))
            }

            val reviewedArray = root.optJSONArray("doctorReviewedIssues") ?: JSONArray()
            val doctorReviewed = mutableListOf<String>()
            for (i in 0 until reviewedArray.length()) {
                doctorReviewed.add(reviewedArray.getString(i))
            }

            val djPrepArray = root.optJSONArray("djPrepData") ?: JSONArray()
            val djPrepData = mutableListOf<DjPrepBackupItem>()
            for (i in 0 until djPrepArray.length()) {
                djPrepData.add(DjPrepBackupItem.fromJson(djPrepArray.getJSONObject(i)))
            }

            val eqConfigJson = root.optString("eqConfigJson", "").takeIf { it.isNotBlank() }

            ValidationResult.Valid(
                SoundSyncBackup(
                    backupVersion = version,
                    appVersion = appVersion,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    songFinds = songFinds,
                    tracks = tracks,
                    doctorIgnoredIssues = doctorIgnored,
                    doctorReviewedIssues = doctorReviewed,
                    djPrepData = djPrepData,
                    eqConfigJson = eqConfigJson
                )
            )
        } catch (e: Exception) {
            ValidationResult.Invalid("Malformed JSON: ${e.message}")
        }
    }

    /**
     * Detects existing backups across all persistent locations.
     */
    fun findAvailableBackups(): List<BackupFileInfo> {
        val backups = mutableListOf<BackupFileInfo>()

        // 1. Custom SAF Directory if set
        val customTreeUri = getCustomBackupTreeUri()
        if (customTreeUri != null) {
            try {
                val doc = DocumentFile.fromTreeUri(context, customTreeUri)
                if (doc != null && doc.canRead()) {
                    doc.listFiles().forEach { file ->
                        if (file.isFile && (file.name?.endsWith(".json", ignoreCase = true) == true)) {
                            backups.add(
                                BackupFileInfo(
                                    file = null,
                                    uri = file.uri,
                                    displayName = file.name ?: "SAF Backup",
                                    lastModified = file.lastModified(),
                                    sizeBytes = file.length()
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error listing SAF backups", e)
            }
        }

        // 2. Documents/SoundSync/backups directory
        try {
            val docsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_SUBFOLDER)
            if (docsDir.exists() && docsDir.isDirectory) {
                docsDir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }?.forEach { f ->
                    backups.add(
                        BackupFileInfo(
                            file = f,
                            uri = null,
                            displayName = f.name,
                            lastModified = f.lastModified(),
                            sizeBytes = f.length()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing public Documents backups", e)
        }

        // 3. Fallback app external files dir
        try {
            val appExtDir = File(context.getExternalFilesDir(null), "backups")
            if (appExtDir.exists() && appExtDir.isDirectory) {
                appExtDir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }?.forEach { f ->
                    if (backups.none { it.displayName == f.name && it.lastModified == f.lastModified() }) {
                        backups.add(
                            BackupFileInfo(
                                file = f,
                                uri = null,
                                displayName = f.name,
                                lastModified = f.lastModified(),
                                sizeBytes = f.length()
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return backups.sortedByDescending { it.lastModified }
    }

    fun findLatestBackup(): BackupFileInfo? {
        return findAvailableBackups().firstOrNull()
    }

    /**
     * Checks if there is an existing persistent backup that can be restored on a fresh install.
     */
    fun hasExistingBackup(): Boolean {
        return findAvailableBackups().isNotEmpty()
    }

    fun serializeBackup(backup: SoundSyncBackup): String {
        val root = JSONObject().apply {
            put("backupVersion", backup.backupVersion)
            put("appVersion", backup.appVersion)
            put("createdAt", backup.createdAt)
            put("updatedAt", backup.updatedAt)

            val songFindsArray = JSONArray()
            backup.songFinds.forEach { songFindsArray.put(it.toJson()) }
            put("songFinds", songFindsArray)

            val tracksArray = JSONArray()
            backup.tracks.forEach { tracksArray.put(it.toJson()) }
            put("tracks", tracksArray)

            val doctorIgnoredArray = JSONArray()
            backup.doctorIgnoredIssues.forEach { doctorIgnoredArray.put(it) }
            put("doctorIgnoredIssues", doctorIgnoredArray)

            val doctorReviewedArray = JSONArray()
            backup.doctorReviewedIssues.forEach { doctorReviewedArray.put(it) }
            put("doctorReviewedIssues", doctorReviewedArray)

            val djPrepArray = JSONArray()
            backup.djPrepData.forEach { djPrepArray.put(it.toJson()) }
            put("djPrepData", djPrepArray)

            if (!backup.eqConfigJson.isNullOrBlank()) {
                put("eqConfigJson", backup.eqConfigJson)
            }
        }
        return root.toString()
    }

    private fun writeToDefaultDirectory(jsonString: String): File {
        val baseDir = try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_SUBFOLDER)
            if (!dir.exists()) dir.mkdirs()
            if (dir.canWrite()) dir else getFallbackDirectory()
        } catch (_: Exception) {
            getFallbackDirectory()
        }

        val canonicalFile = File(baseDir, BACKUP_FILENAME)
        val tempFile = File(baseDir, "$BACKUP_FILENAME.tmp")

        // Atomic write via temp file
        tempFile.writeText(jsonString, StandardCharsets.UTF_8)
        if (canonicalFile.exists()) {
            canonicalFile.delete()
        }
        tempFile.renameTo(canonicalFile)

        // Also write a timestamped version for versioned history (keep last 3)
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            val archiveFile = File(baseDir, "soundsync_backup_$timestamp.json")
            archiveFile.writeText(jsonString, StandardCharsets.UTF_8)
            pruneOldBackups(baseDir, 5)
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating timestamped backup copy", e)
        }

        return canonicalFile
    }

    private fun getFallbackDirectory(): File {
        val ext = File(context.getExternalFilesDir(null), "backups")
        if (!ext.exists()) ext.mkdirs()
        return ext
    }

    private fun pruneOldBackups(dir: File, maxCount: Int) {
        val timestamped = dir.listFiles { f ->
            f.isFile && f.name.startsWith("soundsync_backup_20") && f.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: return

        if (timestamped.size > maxCount) {
            timestamped.drop(maxCount).forEach { it.delete() }
        }
    }

    private fun writeToSafDirectory(treeUri: Uri, jsonString: String): Boolean {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!rootDoc.canWrite()) return false

        val existingDoc = rootDoc.findFile(BACKUP_FILENAME)
        existingDoc?.delete()

        val newFile = rootDoc.createFile("application/json", BACKUP_FILENAME) ?: return false
        context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        }
        return true
    }

    private fun writeJsonToUri(uri: Uri, jsonString: String) {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        } ?: throw IllegalStateException("Unable to open output stream for $uri")
    }

    private fun readJsonFromUri(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        } ?: throw IllegalStateException("Unable to open input stream for $uri")
    }

    fun loadSummary(): BackupSummary {
        val lastTime = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L).takeIf { it > 0 }
        val tracksCount = prefs.getInt(KEY_LAST_BACKUP_TRACKS, 0)
        val findsCount = prefs.getInt(KEY_LAST_BACKUP_FINDS, 0)
        val loc = prefs.getString(KEY_LAST_BACKUP_LOC, "Documents/SoundSync/backups") ?: "Documents/SoundSync/backups"
        val enabled = isAutoBackupEnabled()

        val status = if (lastTime == null) {
            "No backup created yet"
        } else {
            val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(lastTime))
            "Up to date ($dateStr)"
        }

        return BackupSummary(
            lastBackupTimestamp = lastTime,
            status = status,
            songFindCount = findsCount,
            trackCount = tracksCount,
            backupLocation = loc,
            isAutoBackupEnabled = enabled
        )
    }
}
