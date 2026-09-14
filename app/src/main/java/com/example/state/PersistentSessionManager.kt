package com.example.state

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.carmode.CarDisplayMode
import com.example.data.TrackDao
import com.example.model.ExplorerSortOption
import com.example.model.Track
import com.example.model.WaveformStyle
import com.example.player.QueueRepeatMode
import com.example.player.SmartContinueMode
import com.example.storage.ScanStatus
import com.example.ui.theme.ProDarkVariant
import com.example.ui.theme.ProLibraryDensity
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Authoritative Persistent Application & Playback State Manager for SoundSync (Upgrade 27).
 *
 * Implements atomic persistence, debounced high-frequency writes (playback position throttled
 * to 3000ms), immediate lifecycle checkpoints, safe schema versioning, and resilient repair
 * for deleted files, missing folders, and interrupted scans.
 */
class PersistentSessionManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "PersistentSessionMgr"
        private const val SESSION_FILENAME = "persistent_app_session.json"
        private const val POSITION_WRITE_THROTTLE_MS = 3000L
        private const val END_OF_TRACK_REVERT_BUFFER_MS = 2000L

        @Volatile
        private var instance: PersistentSessionManager? = null

        fun getInstance(context: Context): PersistentSessionManager {
            return instance ?: synchronized(this) {
                instance ?: PersistentSessionManager(context.applicationContext).also {
                    instance = it
                    it.restoreFromDisk()
                }
            }
        }
    }

    private val sessionFile = File(context.filesDir, SESSION_FILENAME)
    private val diskMutex = Mutex()

    @Volatile
    private var pendingSaveJob: Job? = null
    @Volatile
    private var lastPositionSaveTimestamp: Long = 0L

    private val _sessionState = MutableStateFlow(PersistentAppSession())
    val sessionState: StateFlow<PersistentAppSession> = _sessionState.asStateFlow()

    // ── Session Update Operations ─────────────────────────────────────────────

    /**
     * Updates playback position and playing state. Position writes are throttled to every 3s
     * during continuous playback unless [immediate] is true (e.g. pause, stop, track change).
     */
    fun updatePlaybackPosition(
        track: Track?,
        positionMs: Long,
        wasPlaying: Boolean,
        immediate: Boolean = false
    ) {
        val sanitizedPos = sanitizePlaybackPosition(track, positionMs)
        val current = _sessionState.value
        val updatedPlayback = current.playback.copy(
            currentTrack = track,
            playbackPositionMs = sanitizedPos,
            wasPlaying = wasPlaying,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        _sessionState.value = current.copy(playback = updatedPlayback)

        val now = System.currentTimeMillis()
        if (immediate || (now - lastPositionSaveTimestamp >= POSITION_WRITE_THROTTLE_MS)) {
            lastPositionSaveTimestamp = now
            saveToDiskAsync()
        } else {
            scheduleDebouncedSave()
        }
    }

    /**
     * Updates complete queue and shuffle state.
     */
    fun updateQueueState(
        currentTrack: Track?,
        upcomingQueue: List<Track>,
        playbackHistory: List<Track>,
        isShuffleEnabled: Boolean,
        shuffleSequenceTrackIds: List<String>,
        shuffleIndex: Int,
        originalQueueTrackIds: List<String>,
        repeatMode: QueueRepeatMode,
        smartContinueMode: SmartContinueMode,
        historyCursor: Int = -1,
        forwardHistory: List<Track> = emptyList(),
        immediate: Boolean = true
    ) {
        val current = _sessionState.value
        val updatedQueue = PersistentQueueSession(
            currentTrack = currentTrack,
            upcomingQueue = upcomingQueue,
            playbackHistory = playbackHistory,
            forwardHistory = forwardHistory,
            isShuffleEnabled = isShuffleEnabled,
            shuffleSequenceTrackIds = shuffleSequenceTrackIds,
            shuffleIndex = shuffleIndex,
            originalQueueTrackIds = originalQueueTrackIds,
            repeatMode = repeatMode,
            smartContinueMode = smartContinueMode,
            historyCursor = if (historyCursor >= 0) historyCursor else (if (playbackHistory.isNotEmpty()) playbackHistory.lastIndex else -1)
        )
        val updatedPlayback = current.playback.copy(currentTrack = currentTrack)
        _sessionState.value = current.copy(queue = updatedQueue, playback = updatedPlayback)

        if (immediate) {
            saveToDiskAsync()
        } else {
            scheduleDebouncedSave()
        }
    }

    /**
     * Updates Library UI state: sorting, filters, last browsed directory, and tabs.
     */
    fun updateLibraryUi(
        sortOption: ExplorerSortOption? = null,
        sortAscending: Boolean? = null,
        searchQuery: String? = null,
        selectedCrateId: String? = null,
        selectedGenreFilter: String? = null,
        selectedPlatformFilter: String? = null,
        hideUnavailableTracks: Boolean? = null,
        currentDirectoryPath: String? = null,
        currentStorageSourceId: String? = null,
        selectedTab: String? = null,
        selectedLocalCategory: String? = null,
        selectedAlbumName: String? = null,
        selectedArtistName: String? = null,
        selectedPlaylistId: String? = null,
        selectedFolderPath: String? = null,
        scrollAnchorTrackId: String? = null,
        scrollItemIndex: Int? = null,
        scrollItemOffset: Int? = null,
        immediate: Boolean = false
    ) {
        val current = _sessionState.value
        val curUi = current.libraryUi
        val updatedUi = curUi.copy(
            sortOption = sortOption ?: curUi.sortOption,
            sortAscending = sortAscending ?: curUi.sortAscending,
            searchQuery = searchQuery ?: curUi.searchQuery,
            selectedCrateId = selectedCrateId ?: curUi.selectedCrateId,
            selectedGenreFilter = if (selectedGenreFilter != null) selectedGenreFilter else curUi.selectedGenreFilter,
            selectedPlatformFilter = if (selectedPlatformFilter != null) selectedPlatformFilter else curUi.selectedPlatformFilter,
            hideUnavailableTracks = hideUnavailableTracks ?: curUi.hideUnavailableTracks,
            currentDirectoryPath = currentDirectoryPath ?: curUi.currentDirectoryPath,
            currentStorageSourceId = currentStorageSourceId ?: curUi.currentStorageSourceId,
            selectedTab = selectedTab ?: curUi.selectedTab,
            selectedLocalCategory = selectedLocalCategory ?: curUi.selectedLocalCategory,
            selectedAlbumName = if (selectedAlbumName != null) selectedAlbumName else curUi.selectedAlbumName,
            selectedArtistName = if (selectedArtistName != null) selectedArtistName else curUi.selectedArtistName,
            selectedPlaylistId = if (selectedPlaylistId != null) selectedPlaylistId else curUi.selectedPlaylistId,
            selectedFolderPath = if (selectedFolderPath != null) selectedFolderPath else curUi.selectedFolderPath,
            scrollAnchorTrackId = if (scrollAnchorTrackId != null) scrollAnchorTrackId else curUi.scrollAnchorTrackId,
            scrollItemIndex = scrollItemIndex ?: curUi.scrollItemIndex,
            scrollItemOffset = scrollItemOffset ?: curUi.scrollItemOffset
        )
        _sessionState.value = current.copy(libraryUi = updatedUi)

        if (immediate) {
            saveToDiskAsync()
        } else {
            scheduleDebouncedSave()
        }
    }

    /**
     * Updates appearance and mode states: Theme, dark variant, density, waveform, Car Mode.
     */
    fun updateAppearance(
        themeMode: ThemeMode? = null,
        proDarkVariant: ProDarkVariant? = null,
        libraryDensity: ProLibraryDensity? = null,
        waveformStyle: WaveformStyle? = null,
        isTrackGridView: Boolean? = null,
        isCarModeActive: Boolean? = null,
        carModeKeepAwake: Boolean? = null,
        carModeNightMode: Boolean? = null,
        carModeDisplayMode: CarDisplayMode? = null,
        carModeSmartShuffle: Boolean? = null,
        immediate: Boolean = true
    ) {
        val current = _sessionState.value
        val curApp = current.appearance
        val updatedApp = curApp.copy(
            themeMode = themeMode ?: curApp.themeMode,
            proDarkVariant = proDarkVariant ?: curApp.proDarkVariant,
            libraryDensity = libraryDensity ?: curApp.libraryDensity,
            waveformStyle = waveformStyle ?: curApp.waveformStyle,
            isTrackGridView = isTrackGridView ?: curApp.isTrackGridView,
            isCarModeActive = isCarModeActive ?: curApp.isCarModeActive,
            carModeKeepAwake = carModeKeepAwake ?: curApp.carModeKeepAwake,
            carModeNightMode = carModeNightMode ?: curApp.carModeNightMode,
            carModeDisplayMode = carModeDisplayMode ?: curApp.carModeDisplayMode,
            carModeSmartShuffle = carModeSmartShuffle ?: curApp.carModeSmartShuffle
        )
        _sessionState.value = current.copy(appearance = updatedApp)

        if (immediate) {
            saveToDiskAsync()
        } else {
            scheduleDebouncedSave()
        }
    }

    /**
     * Updates scanner / analysis checkpoint for resuming interrupted jobs without starting from zero.
     */
    fun saveScannerCheckpoint(checkpoint: PersistentScannerCheckpoint, immediate: Boolean = true) {
        val current = _sessionState.value
        _sessionState.value = current.copy(scannerCheckpoint = checkpoint)
        if (immediate) {
            saveToDiskAsync()
        } else {
            scheduleDebouncedSave()
        }
    }

    fun clearScannerCheckpoint(immediate: Boolean = true) {
        val current = _sessionState.value
        _sessionState.value = current.copy(scannerCheckpoint = null)
        if (immediate) {
            saveToDiskAsync()
        }
    }

    // ── Validation, Fallback & Repair ─────────────────────────────────────────

    /**
     * Validates persisted session state against the local filesystem and Room DB.
     * Safely prunes deleted tracks, adjusts missing folders, and clamps playback positions.
     */
    suspend fun validateAndRepair(
        trackDao: TrackDao? = null,
        fileExistsCheck: ((String) -> Boolean)? = null
    ): PersistentAppSession {
        val session = _sessionState.value

        suspend fun isAccessible(track: Track): Boolean {
            if (fileExistsCheck != null) {
                return fileExistsCheck(track.filePath)
            }
            return try {
                if (trackDao != null) {
                    val inDb = runCatching { trackDao.getTrackById(track.id) != null }.getOrDefault(false)
                    if (inDb) {
                        true
                    } else if (track.filePath.startsWith("content://") || !track.resolvedUri.isNullOrBlank()) {
                        true
                    } else {
                        val f = File(track.filePath)
                        f.exists() && f.canRead()
                    }
                } else if (track.filePath.startsWith("content://") || !track.resolvedUri.isNullOrBlank()) {
                    true
                } else {
                    val f = File(track.filePath)
                    f.exists() && f.canRead()
                }
            } catch (_: Exception) {
                false
            }
        }

        // 1. Repair upcoming queue
        val repairedUpcomingList = mutableListOf<Track>()
        for (item in session.queue.upcomingQueue) {
            if (isAccessible(item)) {
                repairedUpcomingList.add(item)
            }
        }

        // 2. Repair history & forward history
        val repairedHistory = mutableListOf<Track>()
        for (item in session.queue.playbackHistory) {
            if (isAccessible(item)) {
                repairedHistory.add(item)
            }
        }
        val repairedForward = mutableListOf<Track>()
        for (item in session.queue.forwardHistory) {
            if (isAccessible(item)) {
                repairedForward.add(item)
            }
        }

        // 3. Repair current track
        var repairedCurrent = session.playback.currentTrack
        var repairedPos = session.playback.playbackPositionMs
        if (repairedCurrent != null && !isAccessible(repairedCurrent)) {
            Log.w(TAG, "Restored current track '${repairedCurrent.title}' is inaccessible. Promoting next upcoming track.")
            if (repairedForward.isNotEmpty()) {
                repairedCurrent = repairedForward.removeAt(0)
                repairedPos = 0L
            } else if (repairedUpcomingList.isNotEmpty()) {
                repairedCurrent = repairedUpcomingList.removeAt(0)
                repairedPos = 0L
            } else {
                repairedCurrent = null
                repairedPos = 0L
            }
        } else if (repairedCurrent != null) {
            repairedPos = sanitizePlaybackPosition(repairedCurrent, repairedPos)
        }
        val repairedUpcoming = repairedUpcomingList.toList()

            // 4. Repair shuffle sequence
            val validIds = (listOfNotNull(repairedCurrent) + repairedUpcoming + repairedHistory + repairedForward).map { it.id }.toSet()
            val repairedShuffleSeq = session.queue.shuffleSequenceTrackIds.filter { validIds.contains(it) }
            val repairedShuffleIdx = if (repairedShuffleSeq.isNotEmpty()) {
                session.queue.shuffleIndex.coerceIn(0, repairedShuffleSeq.lastIndex)
            } else {
                0
            }

            // 5. Repair browsed folder
            var repairedDir = session.libraryUi.currentDirectoryPath
            var repairedSourceId = session.libraryUi.currentStorageSourceId
            if (repairedDir.isNotBlank() && repairedDir != "/") {
                try {
                    val dirFile = File(repairedDir)
                    if (!dirFile.exists() || !dirFile.isDirectory) {
                        Log.w(TAG, "Last browsed folder '$repairedDir' is gone. Falling back to root.")
                        repairedDir = ""
                        repairedSourceId = "all"
                    }
                } catch (_: Exception) {
                    repairedDir = ""
                    repairedSourceId = "all"
                }
            }

            val repairedSession = session.copy(
                playback = session.playback.copy(
                    currentTrack = repairedCurrent,
                    playbackPositionMs = repairedPos,
                    wasPlaying = session.playback.wasPlaying
                ),
                queue = session.queue.copy(
                    currentTrack = repairedCurrent,
                    upcomingQueue = repairedUpcoming,
                    playbackHistory = repairedHistory,
                    forwardHistory = repairedForward,
                    shuffleSequenceTrackIds = repairedShuffleSeq,
                    shuffleIndex = repairedShuffleIdx
                ),
                libraryUi = session.libraryUi.copy(
                    currentDirectoryPath = repairedDir,
                    currentStorageSourceId = repairedSourceId
                )
            )

            _sessionState.value = repairedSession
            return repairedSession
    }

    private fun sanitizePlaybackPosition(track: Track?, positionMs: Long): Long {
        if (track == null || positionMs <= 0L) return 0L
        val durationMs = track.durationSeconds * 1000L
        if (durationMs > 2000L && positionMs >= (durationMs - END_OF_TRACK_REVERT_BUFFER_MS)) {
            // Avoid restoring to the final fraction of a completed track
            return 0L
        }
        return positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    }

    // ── Disk Persistence & Atomic Writes ──────────────────────────────────────

    private fun scheduleDebouncedSave() {
        pendingSaveJob?.cancel()
        pendingSaveJob = scope.launch {
            delay(1000L)
            saveToDisk()
        }
    }

    fun saveToDiskAsync() {
        pendingSaveJob?.cancel()
        pendingSaveJob = scope.launch {
            saveToDisk()
        }
    }

    /**
     * Flushes the current session to disk immediately, blocking if required by app termination.
     */
    fun flushImmediate() {
        pendingSaveJob?.cancel()
        try {
            runBlocking(Dispatchers.IO) {
                saveToDisk()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed during immediate session flush: ${e.message}")
        }
    }

    suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            try {
                val filesDir = context.filesDir
                if (!filesDir.exists()) {
                    filesDir.mkdirs()
                }

                val json = sessionToJson(_sessionState.value)
                val tempFile = File(filesDir, "$SESSION_FILENAME.${UUID.randomUUID()}.tmp")
                tempFile.writeText(json.toString(2), StandardCharsets.UTF_8)

                var moved = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            sessionFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                        moved = true
                    } catch (_: Exception) {
                        try {
                            Files.move(
                                tempFile.toPath(),
                                sessionFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                            moved = true
                        } catch (_: Exception) {
                            moved = false
                        }
                    }
                }

                if (!moved) {
                    if (!tempFile.renameTo(sessionFile)) {
                        tempFile.copyTo(sessionFile, overwrite = true)
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed saving session to disk: ${e.message}")
            }
        }
    }

    fun restoreFromDisk(): PersistentAppSession {
        synchronized(this) {
            if (!sessionFile.exists() || sessionFile.length() == 0L) {
                return _sessionState.value
            }
            try {
                val text = sessionFile.readText(StandardCharsets.UTF_8)
                val root = JSONObject(text)
                val parsed = sessionFromJson(root)
                _sessionState.value = parsed
                Log.i(TAG, "Restored session: track='${parsed.playback.currentTrack?.title}', pos=${parsed.playback.playbackPositionMs}ms, wasPlaying=${parsed.playback.wasPlaying}, upcoming=${parsed.queue.upcomingQueue.size}, sort=${parsed.libraryUi.sortOption.displayName}, theme=${parsed.appearance.themeMode.name}")
                return parsed
            } catch (e: Exception) {
                Log.w(TAG, "Session file corrupt or parse error: ${e.message}. Preserving default session without wiping library.")
                try {
                    val corruptBackup = File(context.filesDir, "$SESSION_FILENAME.corrupt.${System.currentTimeMillis()}")
                    sessionFile.renameTo(corruptBackup)
                } catch (_: Exception) {}
                return _sessionState.value
            }
        }
    }

    // ── JSON Serialization & Deserialization ──────────────────────────────────

    private fun sessionToJson(session: PersistentAppSession): JSONObject {
        return JSONObject().apply {
            put("version", session.version)

            // Playback
            put("playback", JSONObject().apply {
                session.playback.currentTrack?.let { put("currentTrack", trackToJson(it)) }
                put("playbackPositionMs", session.playback.playbackPositionMs)
                put("wasPlaying", session.playback.wasPlaying)
                put("lastUpdatedTimestamp", session.playback.lastUpdatedTimestamp)
            })

            // Queue
            put("queue", JSONObject().apply {
                session.queue.currentTrack?.let { put("currentTrack", trackToJson(it)) }
                val upArr = JSONArray()
                session.queue.upcomingQueue.forEach { upArr.put(trackToJson(it)) }
                put("upcomingQueue", upArr)

                val histArr = JSONArray()
                session.queue.playbackHistory.take(100).forEach { histArr.put(trackToJson(it)) }
                put("playbackHistory", histArr)

                val fwdArr = JSONArray()
                session.queue.forwardHistory.take(100).forEach { fwdArr.put(trackToJson(it)) }
                put("forwardHistory", fwdArr)

                put("isShuffleEnabled", session.queue.isShuffleEnabled)
                val shuffleSeqArr = JSONArray()
                session.queue.shuffleSequenceTrackIds.forEach { shuffleSeqArr.put(it) }
                put("shuffleSequenceTrackIds", shuffleSeqArr)
                put("shuffleIndex", session.queue.shuffleIndex)

                val origArr = JSONArray()
                session.queue.originalQueueTrackIds.forEach { origArr.put(it) }
                put("originalQueueTrackIds", origArr)

                put("repeatMode", session.queue.repeatMode.name)
                put("smartContinueMode", session.queue.smartContinueMode.name)
                put("historyCursor", session.queue.historyCursor)
            })

            // Library UI
            put("libraryUi", JSONObject().apply {
                put("sortOption", session.libraryUi.sortOption.name)
                put("sortAscending", session.libraryUi.sortAscending)
                put("searchQuery", session.libraryUi.searchQuery)
                put("selectedCrateId", session.libraryUi.selectedCrateId)
                session.libraryUi.selectedGenreFilter?.let { put("selectedGenreFilter", it) }
                session.libraryUi.selectedPlatformFilter?.let { put("selectedPlatformFilter", it) }
                put("hideUnavailableTracks", session.libraryUi.hideUnavailableTracks)
                put("currentDirectoryPath", session.libraryUi.currentDirectoryPath)
                put("currentStorageSourceId", session.libraryUi.currentStorageSourceId)
                put("selectedTab", session.libraryUi.selectedTab)
                put("selectedLocalCategory", session.libraryUi.selectedLocalCategory)
                session.libraryUi.selectedAlbumName?.let { put("selectedAlbumName", it) }
                session.libraryUi.selectedArtistName?.let { put("selectedArtistName", it) }
                session.libraryUi.selectedPlaylistId?.let { put("selectedPlaylistId", it) }
                session.libraryUi.selectedFolderPath?.let { put("selectedFolderPath", it) }
                session.libraryUi.scrollAnchorTrackId?.let { put("scrollAnchorTrackId", it) }
                put("scrollItemIndex", session.libraryUi.scrollItemIndex)
                put("scrollItemOffset", session.libraryUi.scrollItemOffset)
            })

            // Appearance
            put("appearance", JSONObject().apply {
                put("themeMode", session.appearance.themeMode.name)
                put("proDarkVariant", session.appearance.proDarkVariant.name)
                put("libraryDensity", session.appearance.libraryDensity.name)
                put("waveformStyle", session.appearance.waveformStyle.name)
                put("isTrackGridView", session.appearance.isTrackGridView)
                put("isCarModeActive", session.appearance.isCarModeActive)
                put("carModeKeepAwake", session.appearance.carModeKeepAwake)
                put("carModeNightMode", session.appearance.carModeNightMode)
                put("carModeDisplayMode", session.appearance.carModeDisplayMode.name)
                put("carModeSmartShuffle", session.appearance.carModeSmartShuffle)
            })

            // Scanner Checkpoint
            session.scannerCheckpoint?.let { cp ->
                put("scannerCheckpoint", JSONObject().apply {
                    put("scanType", cp.scanType)
                    put("status", cp.status.name)
                    put("isRunning", cp.isRunning)
                    put("isPaused", cp.isPaused)
                    put("sourceId", cp.sourceId)
                    put("sourceUri", cp.sourceUri)
                    put("activeDirectory", cp.activeDirectory)
                    put("lastProcessedTrackId", cp.lastProcessedTrackId)
                    put("lastProcessedFilePath", cp.lastProcessedFilePath)
                    put("processedCount", cp.processedCount)
                    put("totalDiscoveredCount", cp.totalDiscoveredCount)
                    put("completedSuccess", cp.completedSuccess)
                    put("completedSkipped", cp.completedSkipped)
                    put("failedCount", cp.failedCount)
                    put("timestamp", cp.timestamp)
                })
            }
        }
    }

    private fun sessionFromJson(root: JSONObject): PersistentAppSession {
        val version = root.optInt("version", PersistentAppSession.CURRENT_SCHEMA_VERSION)

        // Playback
        val pbObj = root.optJSONObject("playback")
        val playback = if (pbObj != null) {
            val track = pbObj.optJSONObject("currentTrack")?.let { trackFromJson(it) }
            PersistentPlaybackSession(
                currentTrack = track,
                playbackPositionMs = pbObj.optLong("playbackPositionMs", 0L),
                wasPlaying = pbObj.optBoolean("wasPlaying", false),
                lastUpdatedTimestamp = pbObj.optLong("lastUpdatedTimestamp", System.currentTimeMillis())
            )
        } else {
            PersistentPlaybackSession()
        }

        // Queue
        val qObj = root.optJSONObject("queue")
        val queue = if (qObj != null) {
            val track = qObj.optJSONObject("currentTrack")?.let { trackFromJson(it) }
            val upcoming = mutableListOf<Track>()
            qObj.optJSONArray("upcomingQueue")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { upcoming.add(trackFromJson(it)) }
                }
            }
            val history = mutableListOf<Track>()
            qObj.optJSONArray("playbackHistory")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { history.add(trackFromJson(it)) }
                }
            }
            val shuffleSeq = mutableListOf<String>()
            qObj.optJSONArray("shuffleSequenceTrackIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    shuffleSeq.add(arr.optString(i))
                }
            }
            val origQueue = mutableListOf<String>()
            qObj.optJSONArray("originalQueueTrackIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    origQueue.add(arr.optString(i))
                }
            }
            val fwdArr = qObj.optJSONArray("forwardHistory")
            val forwardHistory = mutableListOf<Track>()
            if (fwdArr != null) {
                for (i in 0 until fwdArr.length()) {
                    fwdArr.optJSONObject(i)?.let { forwardHistory.add(trackFromJson(it)) }
                }
            }
            PersistentQueueSession(
                currentTrack = track,
                upcomingQueue = upcoming,
                playbackHistory = history,
                forwardHistory = forwardHistory,
                isShuffleEnabled = qObj.optBoolean("isShuffleEnabled", false),
                shuffleSequenceTrackIds = shuffleSeq,
                shuffleIndex = qObj.optInt("shuffleIndex", 0),
                originalQueueTrackIds = origQueue,
                repeatMode = runCatching { QueueRepeatMode.valueOf(qObj.optString("repeatMode", "OFF")) }.getOrDefault(QueueRepeatMode.OFF),
                smartContinueMode = runCatching { SmartContinueMode.valueOf(qObj.optString("smartContinueMode", "OFF")) }.getOrDefault(SmartContinueMode.OFF),
                historyCursor = qObj.optInt("historyCursor", if (history.isNotEmpty()) history.lastIndex else -1)
            )
        } else {
            PersistentQueueSession()
        }

        // Library UI
        val uiObj = root.optJSONObject("libraryUi")
        val libraryUi = if (uiObj != null) {
            PersistentLibraryUiSession(
                sortOption = runCatching { ExplorerSortOption.valueOf(uiObj.optString("sortOption", "NAME_ASC")) }.getOrDefault(ExplorerSortOption.NAME_ASC),
                sortAscending = uiObj.optBoolean("sortAscending", true),
                searchQuery = uiObj.optString("searchQuery", ""),
                selectedCrateId = uiObj.optString("selectedCrateId", "crate_all"),
                selectedGenreFilter = uiObj.optString("selectedGenreFilter").takeIf { it.isNotBlank() },
                selectedPlatformFilter = uiObj.optString("selectedPlatformFilter").takeIf { it.isNotBlank() },
                hideUnavailableTracks = uiObj.optBoolean("hideUnavailableTracks", false),
                currentDirectoryPath = uiObj.optString("currentDirectoryPath", ""),
                currentStorageSourceId = uiObj.optString("currentStorageSourceId", "all"),
                selectedTab = uiObj.optString("selectedTab", "LOCAL"),
                selectedLocalCategory = uiObj.optString("selectedLocalCategory", "SONGS"),
                selectedAlbumName = uiObj.optString("selectedAlbumName").takeIf { it.isNotBlank() },
                selectedArtistName = uiObj.optString("selectedArtistName").takeIf { it.isNotBlank() },
                selectedPlaylistId = uiObj.optString("selectedPlaylistId").takeIf { it.isNotBlank() },
                selectedFolderPath = uiObj.optString("selectedFolderPath").takeIf { it.isNotBlank() },
                scrollAnchorTrackId = uiObj.optString("scrollAnchorTrackId").takeIf { it.isNotBlank() },
                scrollItemIndex = uiObj.optInt("scrollItemIndex", 0),
                scrollItemOffset = uiObj.optInt("scrollItemOffset", 0)
            )
        } else {
            PersistentLibraryUiSession()
        }

        // Appearance
        val appObj = root.optJSONObject("appearance")
        val appearance = if (appObj != null) {
            PersistentAppearanceSession(
                themeMode = runCatching { ThemeMode.valueOf(appObj.optString("themeMode", "DEFAULT")) }.getOrDefault(ThemeMode.DEFAULT),
                proDarkVariant = runCatching { ProDarkVariant.valueOf(appObj.optString("proDarkVariant", "BLACK_WHITE")) }.getOrDefault(ProDarkVariant.BLACK_WHITE),
                libraryDensity = runCatching { ProLibraryDensity.valueOf(appObj.optString("libraryDensity", "COMPACT")) }.getOrDefault(ProLibraryDensity.COMPACT),
                waveformStyle = runCatching { WaveformStyle.valueOf(appObj.optString("waveformStyle", "DETAILED")) }.getOrDefault(WaveformStyle.DETAILED),
                isTrackGridView = appObj.optBoolean("isTrackGridView", false),
                isCarModeActive = appObj.optBoolean("isCarModeActive", false),
                carModeKeepAwake = appObj.optBoolean("carModeKeepAwake", true),
                carModeNightMode = appObj.optBoolean("carModeNightMode", false),
                carModeDisplayMode = runCatching { CarDisplayMode.valueOf(appObj.optString("carModeDisplayMode", "ARTWORK")) }.getOrDefault(CarDisplayMode.ARTWORK),
                carModeSmartShuffle = appObj.optBoolean("carModeSmartShuffle", true)
            )
        } else {
            PersistentAppearanceSession()
        }

        // Scanner Checkpoint
        val cpObj = root.optJSONObject("scannerCheckpoint")
        val scannerCheckpoint = if (cpObj != null) {
            PersistentScannerCheckpoint(
                scanType = cpObj.optString("scanType", "METADATA_ANALYSIS"),
                status = runCatching { ScanStatus.valueOf(cpObj.optString("status", "IDLE")) }.getOrDefault(ScanStatus.IDLE),
                isRunning = cpObj.optBoolean("isRunning", false),
                isPaused = cpObj.optBoolean("isPaused", false),
                sourceId = cpObj.optString("sourceId", ""),
                sourceUri = cpObj.optString("sourceUri", ""),
                activeDirectory = cpObj.optString("activeDirectory", ""),
                lastProcessedTrackId = cpObj.optString("lastProcessedTrackId", ""),
                lastProcessedFilePath = cpObj.optString("lastProcessedFilePath", ""),
                processedCount = cpObj.optInt("processedCount", 0),
                totalDiscoveredCount = cpObj.optInt("totalDiscoveredCount", 0),
                completedSuccess = cpObj.optInt("completedSuccess", 0),
                completedSkipped = cpObj.optInt("completedSkipped", 0),
                failedCount = cpObj.optInt("failedCount", 0),
                timestamp = cpObj.optLong("timestamp", System.currentTimeMillis())
            )
        } else {
            null
        }

        return PersistentAppSession(
            version = version,
            playback = playback,
            queue = queue,
            libraryUi = libraryUi,
            appearance = appearance,
            scannerCheckpoint = scannerCheckpoint
        )
    }

    fun trackToJson(track: Track): JSONObject {
        return JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("genre", track.genre)
            put("bpm", track.bpm)
            put("musicalKey", track.musicalKey)
            put("camelotKey", track.camelotKey)
            put("durationSeconds", track.durationSeconds)
            put("filePath", track.filePath)
            put("artworkUrl", track.artworkUrl.orEmpty())
            put("artworkCachePath", track.artworkCachePath.orEmpty())
            put("format", track.format)
            put("bitrateKbps", track.bitrateKbps)
            put("isAvailable", track.isAvailable)
            put("dateAdded", track.dateAdded)
        }
    }

    fun trackFromJson(json: JSONObject): Track {
        return Track(
            id = json.optString("id"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            genre = json.optString("genre"),
            bpm = json.optDouble("bpm", 0.0),
            musicalKey = json.optString("musicalKey"),
            camelotKey = json.optString("camelotKey"),
            durationSeconds = json.optInt("durationSeconds", 0),
            filePath = json.optString("filePath"),
            artworkUrl = json.optString("artworkUrl").takeIf { it.isNotBlank() },
            artworkCachePath = json.optString("artworkCachePath").takeIf { it.isNotBlank() },
            format = json.optString("format", "MP3"),
            bitrateKbps = json.optInt("bitrateKbps", 320),
            isAvailable = json.optBoolean("isAvailable", true),
            dateAdded = json.optLong("dateAdded", System.currentTimeMillis())
        )
    }
}
