package com.example.player

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.PlaylistEntity
import com.example.data.PlaylistTrackEntity
import com.example.model.Track
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

enum class QueueRepeatMode {
    OFF,
    ONE,
    ALL
}

enum class SmartContinueMode(val label: String) {
    OFF("Off"),
    SMART_CONTINUE("Smart Continue"),
    MIX_COMPATIBLE("Mix-Compatible"),
    SIMILAR_MUSIC("Similar Music")
}

data class QueueSnapshot(
    val currentTrack: Track?,
    val upcomingQueue: List<Track>,
    val playbackHistory: List<Track>,
    val isShuffle: Boolean,
    val repeatMode: QueueRepeatMode,
    val smartContinueMode: SmartContinueMode = SmartContinueMode.OFF,
    val forwardHistory: List<Track> = emptyList(),
    val shuffleSequenceTrackIds: List<String> = emptyList(),
    val shuffleIndex: Int = 0
)

/**
 * High-performance, persistent playback queue manager adhering to Step 2 Part A specifications:
 * - Clear separation of Current Track, Upcoming Queue, and Playback History.
 * - Granular queue management (Play next, Add to queue, Reorder, Remove, Clear).
 * - Deterministic shuffle: "Previous" returns to the actual previously played track from history.
 * - Auto-persistence across app/process restarts.
 * - Queue export as playlist.
 * - Seamless integration with Gapless and continuous playback providers.
 */
class PersistentQueueManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "PersistentQueueManager"
        private const val QUEUE_FILENAME = "persistent_playback_queue.json"
        private const val MAX_HISTORY_SIZE = 100

        @Volatile
        private var instance: PersistentQueueManager? = null

        fun getInstance(context: Context): PersistentQueueManager {
            return instance ?: synchronized(this) {
                instance ?: PersistentQueueManager(context.applicationContext).also {
                    instance = it
                    it.restoreFromDisk()
                }
            }
        }
    }

    private val queueFile = File(context.filesDir, QUEUE_FILENAME)
    private val diskMutex = Mutex()
    @Volatile
    private var currentSaveJob: Job? = null

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _upcomingQueue = MutableStateFlow<List<Track>>(emptyList())
    val upcomingQueue: StateFlow<List<Track>> = _upcomingQueue.asStateFlow()

    private val _playbackHistory = MutableStateFlow<List<Track>>(emptyList())
    val playbackHistory: StateFlow<List<Track>> = _playbackHistory.asStateFlow()

    private val _forwardHistory = MutableStateFlow<List<Track>>(emptyList())
    val forwardHistory: StateFlow<List<Track>> = _forwardHistory.asStateFlow()

    private val _historyCursor = MutableStateFlow(-1)
    val historyCursor: StateFlow<Int> = _historyCursor.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(QueueRepeatMode.OFF)
    val repeatMode: StateFlow<QueueRepeatMode> = _repeatMode.asStateFlow()

    private val _smartContinueMode = MutableStateFlow(SmartContinueMode.OFF)
    val smartContinueMode: StateFlow<SmartContinueMode> = _smartContinueMode.asStateFlow()

    private val _shuffleSequenceTrackIds = MutableStateFlow<List<String>>(emptyList())
    val shuffleSequenceTrackIds: StateFlow<List<String>> = _shuffleSequenceTrackIds.asStateFlow()

    private val _shuffleIndex = MutableStateFlow(0)
    val shuffleIndex: StateFlow<Int> = _shuffleIndex.asStateFlow()

    private val _originalQueueTrackIds = MutableStateFlow<List<String>>(emptyList())
    val originalQueueTrackIds: StateFlow<List<String>> = _originalQueueTrackIds.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _wasPlaying = MutableStateFlow(false)
    val wasPlaying: StateFlow<Boolean> = _wasPlaying.asStateFlow()

    fun setSmartContinueMode(mode: SmartContinueMode) {
        _smartContinueMode.value = mode
        saveToDiskAsync()
    }

    fun updatePlaybackPosition(positionMs: Long, wasPlaying: Boolean = false, immediate: Boolean = false) {
        _playbackPositionMs.value = positionMs
        _wasPlaying.value = wasPlaying
        if (immediate) {
            saveToDiskAsync()
        }
    }

    /**
     * Records that a track has started playback.
     * Anti-spam threshold: seeking, rebuffering, or re-binding the same track does not
     * append duplicate entries to history.
     * Manual selection branches off and truncates forward history while preserving the upcoming queue.
     */
    fun recordTrackPlayed(track: Track, fromNavigation: Boolean = false) {
        val prev = _currentTrack.value
        if (prev?.id == track.id) {
            // Anti-spam guard: same continuous track
            _currentTrack.value = track
            return
        }

        if (prev != null && !fromNavigation) {
            val history = _playbackHistory.value.toMutableList()
            history.add(0, prev)
            if (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(history.lastIndex)
            }
            _playbackHistory.value = history
            _historyCursor.value = if (history.isNotEmpty()) history.lastIndex else -1
            // Manual selection: truncate forward history branch
            _forwardHistory.value = emptyList()
        }

        _currentTrack.value = track
        _playbackPositionMs.value = 0L

        // If the selected track was in upcoming queue, remove its first occurrence
        val upcoming = _upcomingQueue.value.toMutableList()
        val upcomingIdx = upcoming.indexOfFirst { it.id == track.id }
        if (upcomingIdx >= 0) {
            upcoming.removeAt(upcomingIdx)
            _upcomingQueue.value = upcoming
        }

        if (_isShuffleEnabled.value) {
            val seqIdx = _shuffleSequenceTrackIds.value.indexOf(track.id)
            if (seqIdx >= 0) {
                _shuffleIndex.value = seqIdx
            } else {
                _shuffleSequenceTrackIds.value = listOf(track.id) + _shuffleSequenceTrackIds.value
                _shuffleIndex.value = 0
            }
        }

        saveToDiskAsync()
    }

    fun pruneDeletedTrack(trackId: String): Boolean {
        var modified = false
        if (_currentTrack.value?.id == trackId) {
            val next = _forwardHistory.value.firstOrNull { it.id != trackId }
                ?: _upcomingQueue.value.firstOrNull { it.id != trackId }
            _currentTrack.value = next
            _playbackPositionMs.value = 0L
            modified = true
        }
        val curUpcoming = _upcomingQueue.value
        val filteredUpcoming = curUpcoming.filter { it.id != trackId }
        if (curUpcoming.size != filteredUpcoming.size) {
            _upcomingQueue.value = filteredUpcoming
            modified = true
        }
        val curHistory = _playbackHistory.value
        val filteredHistory = curHistory.filter { it.id != trackId }
        if (curHistory.size != filteredHistory.size) {
            _playbackHistory.value = filteredHistory
            _historyCursor.value = if (filteredHistory.isNotEmpty()) filteredHistory.lastIndex else -1
            modified = true
        }
        val curForward = _forwardHistory.value
        val filteredForward = curForward.filter { it.id != trackId }
        if (curForward.size != filteredForward.size) {
            _forwardHistory.value = filteredForward
            modified = true
        }
        val curShuffle = _shuffleSequenceTrackIds.value
        val filteredShuffle = curShuffle.filter { it != trackId }
        if (curShuffle.size != filteredShuffle.size) {
            _shuffleSequenceTrackIds.value = filteredShuffle
            _shuffleIndex.value = if (filteredShuffle.isNotEmpty()) {
                _shuffleIndex.value.coerceIn(0, filteredShuffle.lastIndex)
            } else 0
            modified = true
        }
        if (modified) {
            saveToDiskAsync()
        }
        return modified
    }

    // Context fallback provider when the upcoming queue is empty (e.g. playing from an album/folder)
    var contextTrackProvider: (() -> List<Track>)? = null

    // ── Queue Management Operations ──────────────────────────────────────────

    /**
     * Initializes the queue with a list of tracks, setting currentTrack to the chosen start item.
     * Optionally preserves previous playback history (default true) while clearing forward history branch.
     */
    fun setQueue(
        tracks: List<Track>,
        startTrack: Track?,
        shuffle: Boolean = false,
        preserveHistory: Boolean = true
    ) {
        if (tracks.isEmpty()) {
            clearQueue()
            return
        }

        _isShuffleEnabled.value = shuffle
        val current = startTrack ?: tracks.first()
        val prevTrack = _currentTrack.value
        if (prevTrack != null && prevTrack.id != current.id && preserveHistory) {
            val history = _playbackHistory.value.toMutableList()
            history.add(0, prevTrack)
            if (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(history.lastIndex)
            }
            _playbackHistory.value = history
            _historyCursor.value = if (history.isNotEmpty()) history.lastIndex else -1
        } else if (!preserveHistory) {
            _playbackHistory.value = emptyList()
            _historyCursor.value = -1
        }

        _forwardHistory.value = emptyList()
        _currentTrack.value = current
        _originalQueueTrackIds.value = tracks.map { it.id }

        val remaining = if (shuffle) {
            val shuffledRemaining = tracks.filter { it.id != current.id }.shuffled()
            _shuffleSequenceTrackIds.value = listOf(current.id) + shuffledRemaining.map { it.id }
            _shuffleIndex.value = 0
            shuffledRemaining
        } else {
            _shuffleSequenceTrackIds.value = emptyList()
            _shuffleIndex.value = 0
            val idx = tracks.indexOfFirst { it.id == current.id }
            if (idx >= 0 && idx < tracks.lastIndex) {
                tracks.subList(idx + 1, tracks.size)
            } else {
                emptyList()
            }
        }

        _upcomingQueue.value = remaining
        saveToDiskAsync()
        Log.d(TAG, "Queue set with ${tracks.size} tracks. Current: '${current.title}', Upcoming: ${remaining.size}")
    }

    /**
     * Adds a track to play immediately after the current track.
     */
    fun playNext(track: Track) {
        val currentUpcoming = _upcomingQueue.value.toMutableList()
        currentUpcoming.add(0, track)
        _upcomingQueue.value = currentUpcoming
        saveToDiskAsync()
        Log.d(TAG, "Play next: '${track.title}'")
    }

    /**
     * Appends a track to the end of the upcoming queue.
     */
    fun addToQueue(track: Track) {
        if (_currentTrack.value == null) {
            _currentTrack.value = track
        } else {
            val currentUpcoming = _upcomingQueue.value.toMutableList()
            currentUpcoming.add(track)
            _upcomingQueue.value = currentUpcoming
        }
        saveToDiskAsync()
        Log.d(TAG, "Added to queue: '${track.title}'")
    }

    /**
     * Appends multiple tracks to the upcoming queue.
     */
    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (_currentTrack.value == null) {
            setQueue(tracks, tracks.first(), _isShuffleEnabled.value)
        } else {
            val currentUpcoming = _upcomingQueue.value.toMutableList()
            currentUpcoming.addAll(tracks)
            _upcomingQueue.value = currentUpcoming
            saveToDiskAsync()
            Log.d(TAG, "Added ${tracks.size} tracks to queue.")
        }
    }

    /**
     * Removes an item from the upcoming queue at the specified index.
     */
    fun removeFromQueue(index: Int): Track? {
        val currentUpcoming = _upcomingQueue.value.toMutableList()
        if (index in currentUpcoming.indices) {
            val removed = currentUpcoming.removeAt(index)
            _upcomingQueue.value = currentUpcoming
            saveToDiskAsync()
            return removed
        }
        return null
    }

    /**
     * Reorders an item in the upcoming queue.
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentUpcoming = _upcomingQueue.value.toMutableList()
        if (fromIndex in currentUpcoming.indices && toIndex in currentUpcoming.indices && fromIndex != toIndex) {
            val item = currentUpcoming.removeAt(fromIndex)
            currentUpcoming.add(toIndex, item)
            _upcomingQueue.value = currentUpcoming
            saveToDiskAsync()
        }
    }

    /**
     * Clears all upcoming items and resets current track if requested.
     */
    fun clearQueue(clearCurrent: Boolean = false) {
        _upcomingQueue.value = emptyList()
        if (clearCurrent) {
            _currentTrack.value = null
        }
        saveToDiskAsync()
    }

    /**
     * Clears playback history and forward history.
     */
    fun clearHistory() {
        _playbackHistory.value = emptyList()
        _forwardHistory.value = emptyList()
        _historyCursor.value = -1
        saveToDiskAsync()
    }

    fun setShuffle(enabled: Boolean) {
        if (_isShuffleEnabled.value == enabled) return
        _isShuffleEnabled.value = enabled
        val current = _currentTrack.value

        if (enabled) {
            val tracksToShuffle = if (_upcomingQueue.value.isNotEmpty()) {
                _upcomingQueue.value
            } else {
                contextTrackProvider?.invoke()?.filter { it.id != current?.id } ?: emptyList()
            }

            if (tracksToShuffle.isNotEmpty()) {
                if (_originalQueueTrackIds.value.isEmpty()) {
                    _originalQueueTrackIds.value = (if (current != null) listOf(current.id) else emptyList()) +
                        tracksToShuffle.map { it.id }
                }
                val shuffled = tracksToShuffle.shuffled()
                _upcomingQueue.value = shuffled
                _shuffleSequenceTrackIds.value = (if (current != null) listOf(current.id) else emptyList()) + shuffled.map { it.id }
                _shuffleIndex.value = 0
            }
        } else {
            // Turning shuffle off: restore natural order if original order was recorded
            if (_originalQueueTrackIds.value.isNotEmpty()) {
                val remainingIds = _upcomingQueue.value.map { it.id }.toSet()
                val orderedUpcoming = _originalQueueTrackIds.value
                    .filter { it in remainingIds && it != current?.id }
                    .mapNotNull { id -> _upcomingQueue.value.firstOrNull { it.id == id } }
                if (orderedUpcoming.isNotEmpty()) {
                    _upcomingQueue.value = orderedUpcoming
                }
            }
            _shuffleSequenceTrackIds.value = emptyList()
            _shuffleIndex.value = 0
        }
        saveToDiskAsync()
    }

    fun setRepeatMode(mode: QueueRepeatMode) {
        _repeatMode.value = mode
        saveToDiskAsync()
    }

    fun toggleRepeatMode(): QueueRepeatMode {
        val next = when (_repeatMode.value) {
            QueueRepeatMode.OFF -> QueueRepeatMode.ALL
            QueueRepeatMode.ALL -> QueueRepeatMode.ONE
            QueueRepeatMode.ONE -> QueueRepeatMode.OFF
        }
        _repeatMode.value = next
        saveToDiskAsync()
        return next
    }

    // ── Navigation & Continuous Playback ──────────────────────────────────────

    /**
     * Returns the next track that will play, without advancing the queue state.
     * Useful for gapless pre-buffering.
     */
    fun peekNextTrack(): Track? {
        if (_repeatMode.value == QueueRepeatMode.ONE) {
            return _currentTrack.value
        }
        val forward = _forwardHistory.value
        if (forward.isNotEmpty()) {
            return forward.first()
        }
        val upcoming = _upcomingQueue.value
        if (upcoming.isNotEmpty()) {
            return upcoming.first()
        }
        // Fallback to repeat all / context tracks
        if (_repeatMode.value == QueueRepeatMode.ALL) {
            val history = _playbackHistory.value
            if (history.isNotEmpty()) {
                return if (_isShuffleEnabled.value) history.shuffled().firstOrNull() else history.lastOrNull()
            }
        }
        return contextTrackProvider?.invoke()?.firstOrNull()
    }

    /**
     * Advances to the next track:
     * 1. Checks Repeat ONE.
     * 2. Checks Forward History (browser-style forward step from previous history navigation).
     * 3. Checks Upcoming Queue (normal / shuffled order).
     * 4. Checks Repeat ALL, Smart Continue, and Context Provider when queue is exhausted.
     * Archives the current track into playback history (LIFO).
     */
    fun nextTrack(): Track? {
        val current = _currentTrack.value

        // Repeat ONE: replay current track
        if (_repeatMode.value == QueueRepeatMode.ONE && current != null) {
            return current
        }

        // 1. Forward History check (Scenario B)
        val forward = _forwardHistory.value.toMutableList()
        if (forward.isNotEmpty()) {
            val next = forward.removeAt(0)
            _forwardHistory.value = forward

            if (current != null) {
                val history = _playbackHistory.value.toMutableList()
                history.add(0, current)
                if (history.size > MAX_HISTORY_SIZE) {
                    history.removeAt(history.lastIndex)
                }
                _playbackHistory.value = history
                _historyCursor.value = if (history.isNotEmpty()) history.lastIndex else -1
            }

            _currentTrack.value = next
            _playbackPositionMs.value = 0L
            if (_isShuffleEnabled.value) {
                val idx = _shuffleSequenceTrackIds.value.indexOf(next.id)
                if (idx >= 0) _shuffleIndex.value = idx
            }
            saveToDiskAsync()
            Log.d(TAG, "Next track from forward history: '${next.title}' (Forward left: ${forward.size})")
            return next
        }

        // Archive current to history (LIFO for Previous navigation)
        if (current != null) {
            val history = _playbackHistory.value.toMutableList()
            history.add(0, current)
            if (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(history.lastIndex)
            }
            _playbackHistory.value = history
            _historyCursor.value = if (history.isNotEmpty()) history.lastIndex else -1
        }

        // 2. Upcoming Queue check
        val upcoming = _upcomingQueue.value.toMutableList()
        if (upcoming.isNotEmpty()) {
            val next = upcoming.removeAt(0)
            _upcomingQueue.value = upcoming
            _currentTrack.value = next
            _playbackPositionMs.value = 0L
            if (_isShuffleEnabled.value) {
                _shuffleIndex.value = _shuffleIndex.value + 1
            }
            saveToDiskAsync()
            Log.d(TAG, "Next track from upcoming queue: '${next.title}' (Upcoming left: ${upcoming.size})")
            return next
        }

        // 3. Queue exhausted: Check Repeat ALL
        if (_repeatMode.value == QueueRepeatMode.ALL) {
            val allPlayed = _playbackHistory.value.reversed()
            if (allPlayed.isNotEmpty()) {
                val newQueue = if (_isShuffleEnabled.value) {
                    var shuffledCycle = allPlayed.shuffled()
                    if (shuffledCycle.size > 1 && shuffledCycle.first().id == current?.id) {
                        val rot = shuffledCycle.toMutableList()
                        val f = rot.removeAt(0)
                        rot.add(f)
                        shuffledCycle = rot
                    }
                    shuffledCycle
                } else {
                    allPlayed
                }
                val next = newQueue.first()
                _upcomingQueue.value = newQueue.drop(1)
                _currentTrack.value = next
                _playbackPositionMs.value = 0L
                if (_isShuffleEnabled.value) {
                    _shuffleSequenceTrackIds.value = listOf(next.id) + newQueue.drop(1).map { it.id }
                    _shuffleIndex.value = 0
                }
                saveToDiskAsync()
                Log.d(TAG, "Repeat ALL cycle started: '${next.title}'")
                return next
            }
        }

        // 4. Smart Queue Assistance (Step 3 Part F)
        if (_smartContinueMode.value != SmartContinueMode.OFF && current != null) {
            val libraryPool = contextTrackProvider?.invoke() ?: emptyList()
            if (libraryPool.isNotEmpty()) {
                val recentIds = _playbackHistory.value.take(25).map { it.id }.toSet() + current.id
                val candidates = libraryPool.filter { it.id !in recentIds && it.isAvailable }
                if (candidates.isNotEmpty()) {
                    val recommended = when (_smartContinueMode.value) {
                        SmartContinueMode.MIX_COMPATIBLE -> {
                            val compat = candidates.map { c ->
                                Pair(c, com.example.dj.MixCompatibilityEngine.evaluatePair(current, c).overallScore)
                            }.filter { it.second >= 40 }.sortedByDescending { it.second }
                            compat.firstOrNull()?.first
                        }
                        SmartContinueMode.SIMILAR_MUSIC -> {
                            val targetGenre = current.genre.trim().lowercase(java.util.Locale.ROOT)
                            candidates.firstOrNull { it.genre.trim().lowercase(java.util.Locale.ROOT) == targetGenre }
                                ?: candidates.firstOrNull()
                        }
                        SmartContinueMode.SMART_CONTINUE -> {
                            candidates.maxByOrNull { it.rating } ?: candidates.firstOrNull()
                        }
                        SmartContinueMode.OFF -> null
                    }
                    if (recommended != null) {
                        _currentTrack.value = recommended
                        _playbackPositionMs.value = 0L
                        saveToDiskAsync()
                        Log.i(TAG, "Smart Continue [${_smartContinueMode.value.label}]: '${recommended.title}'")
                        return recommended
                    }
                }
            }
        }

        // 5. Context fallback (e.g. continue playing library/folder)
        val contextTracks = contextTrackProvider?.invoke()
        if (!contextTracks.isNullOrEmpty()) {
            val available = if (_isShuffleEnabled.value) contextTracks.shuffled() else contextTracks
            val next = available.first()
            _upcomingQueue.value = available.drop(1)
            _currentTrack.value = next
            _playbackPositionMs.value = 0L
            saveToDiskAsync()
            Log.d(TAG, "Next track from context provider: '${next.title}'")
            return next
        }

        // End of playback
        _currentTrack.value = null
        saveToDiskAsync()
        return null
    }

    /**
     * Navigates to the previous track.
     * CRITICAL UPGRADE 29 REQUIREMENT:
     * Previous MUST return to the actual previously played track from playback history,
     * NOT a decrement of list index, and NOT a random track in shuffle!
     * Pushes current track to forward history for browser-style traversal.
     */
    fun previousTrack(currentPositionMs: Long = 0L, forceHistory: Boolean = false): Track? {
        if (currentPositionMs > 3000L && !forceHistory) {
            return _currentTrack.value
        }
        val history = _playbackHistory.value.toMutableList()
        if (history.isEmpty()) {
            return _currentTrack.value
        }

        val prev = history.removeAt(0)
        _playbackHistory.value = history
        _historyCursor.value = if (history.isNotEmpty()) history.lastIndex else -1

        // Current moves to forward history (browser-style forward step)
        val current = _currentTrack.value
        if (current != null) {
            val forward = _forwardHistory.value.toMutableList()
            forward.add(0, current)
            _forwardHistory.value = forward
        }

        _currentTrack.value = prev
        _playbackPositionMs.value = 0L
        if (_isShuffleEnabled.value) {
            _shuffleIndex.value = (_shuffleIndex.value - 1).coerceAtLeast(0)
        }
        saveToDiskAsync()
        Log.d(TAG, "Previous track selected: '${prev.title}' (History left: ${history.size}, Forward: ${_forwardHistory.value.size})")
        return prev
    }

    fun getSnapshot(): QueueSnapshot {
        return QueueSnapshot(
            currentTrack = _currentTrack.value,
            upcomingQueue = _upcomingQueue.value,
            playbackHistory = _playbackHistory.value,
            isShuffle = _isShuffleEnabled.value,
            repeatMode = _repeatMode.value,
            smartContinueMode = _smartContinueMode.value,
            forwardHistory = _forwardHistory.value,
            shuffleSequenceTrackIds = _shuffleSequenceTrackIds.value,
            shuffleIndex = _shuffleIndex.value
        )
    }

    // ── Playlist Export ───────────────────────────────────────────────────────

    /**
     * Saves the current active queue (current + upcoming) as a saved playlist in the database.
     */
    suspend fun saveQueueAsPlaylist(db: AppDatabase, playlistName: String): String = withContext(Dispatchers.IO) {
        val tracksToSave = mutableListOf<Track>()
        _currentTrack.value?.let { tracksToSave.add(it) }
        tracksToSave.addAll(_upcomingQueue.value)

        if (tracksToSave.isEmpty()) return@withContext ""

        val playlistId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = PlaylistEntity(
            id = playlistId,
            name = playlistName.trim().ifBlank { "Queue Playlist ${System.currentTimeMillis() / 1000}" },
            createdAt = now,
            updatedAt = now,
            isRockboxCompatible = true,
            isImported = false
        )
        db.playlistDao().insertPlaylist(entity)

        val entries = tracksToSave.mapIndexed { idx, t ->
            PlaylistTrackEntity(
                id = 0L,
                playlistId = playlistId,
                trackId = t.id,
                position = idx,
                dateAdded = now
            )
        }
        db.playlistDao().insertPlaylistTracks(entries)
        Log.i(TAG, "Saved queue with ${tracksToSave.size} tracks as playlist '$playlistName' ($playlistId)")
        playlistId
    }

    // ── Disk Persistence ─────────────────────────────────────────────────────
 
    private fun saveToDiskAsync() {
        currentSaveJob?.cancel()
        currentSaveJob = scope.launch {
            saveToDisk()
        }
    }
 
    suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            try {
                val filesDir = context.filesDir
                if (!filesDir.exists()) {
                    filesDir.mkdirs()
                }

                val root = JSONObject().apply {
                    put("version", 4)
                    put("isShuffle", _isShuffleEnabled.value)
                    put("repeatMode", _repeatMode.value.name)
                    put("smartContinueMode", _smartContinueMode.value.name)
                    put("playbackPositionMs", _playbackPositionMs.value)
                    put("wasPlaying", _wasPlaying.value)
                    put("shuffleIndex", _shuffleIndex.value)

                    val shuffleSeqArr = JSONArray()
                    _shuffleSequenceTrackIds.value.forEach { shuffleSeqArr.put(it) }
                    put("shuffleSequenceTrackIds", shuffleSeqArr)

                    val origArr = JSONArray()
                    _originalQueueTrackIds.value.forEach { origArr.put(it) }
                    put("originalQueueTrackIds", origArr)

                    _currentTrack.value?.let { put("currentTrack", trackToJson(it)) }

                    val upcomingArr = JSONArray()
                    _upcomingQueue.value.forEach { upcomingArr.put(trackToJson(it)) }
                    put("upcomingQueue", upcomingArr)

                    val historyArr = JSONArray()
                    _playbackHistory.value.take(MAX_HISTORY_SIZE).forEach { historyArr.put(trackToJson(it)) }
                    put("playbackHistory", historyArr)

                    val forwardArr = JSONArray()
                    _forwardHistory.value.take(MAX_HISTORY_SIZE).forEach { forwardArr.put(trackToJson(it)) }
                    put("forwardHistory", forwardArr)
                }

                val tempFile = File(filesDir, "$QUEUE_FILENAME.${UUID.randomUUID()}.tmp")
                tempFile.writeText(root.toString(2), StandardCharsets.UTF_8)

                var moved = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            queueFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                        moved = true
                    } catch (_: Exception) {
                        try {
                            Files.move(
                                tempFile.toPath(),
                                queueFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                            moved = true
                        } catch (_: Exception) {
                            moved = false
                        }
                    }
                }

                if (!moved) {
                    if (!tempFile.renameTo(queueFile)) {
                        tempFile.copyTo(queueFile, overwrite = true)
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed saving queue to disk: ${e.message}")
            }
        }
    }
 
    fun restoreFromDisk() {
        synchronized(this) {
            if (!queueFile.exists() || queueFile.length() == 0L) return
            try {
                val text = queueFile.readText(StandardCharsets.UTF_8)
                val root = JSONObject(text)
                _isShuffleEnabled.value = root.optBoolean("isShuffle", false)
                _repeatMode.value = runCatching {
                    QueueRepeatMode.valueOf(root.optString("repeatMode", "OFF"))
                }.getOrDefault(QueueRepeatMode.OFF)
                _smartContinueMode.value = runCatching {
                    SmartContinueMode.valueOf(root.optString("smartContinueMode", "OFF"))
                }.getOrDefault(SmartContinueMode.OFF)

                _playbackPositionMs.value = root.optLong("playbackPositionMs", 0L)
                _wasPlaying.value = root.optBoolean("wasPlaying", false)
                _shuffleIndex.value = root.optInt("shuffleIndex", 0)

                val shuffleSeqArr = root.optJSONArray("shuffleSequenceTrackIds")
                if (shuffleSeqArr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until shuffleSeqArr.length()) {
                        list.add(shuffleSeqArr.optString(i))
                    }
                    _shuffleSequenceTrackIds.value = list
                }

                val origArr = root.optJSONArray("originalQueueTrackIds")
                if (origArr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until origArr.length()) {
                        list.add(origArr.optString(i))
                    }
                    _originalQueueTrackIds.value = list
                }

                val currentObj = root.optJSONObject("currentTrack")
                if (currentObj != null) {
                    _currentTrack.value = trackFromJson(currentObj)
                }

                val upcomingArr = root.optJSONArray("upcomingQueue")
                if (upcomingArr != null) {
                    val list = mutableListOf<Track>()
                    for (i in 0 until upcomingArr.length()) {
                        upcomingArr.optJSONObject(i)?.let { list.add(trackFromJson(it)) }
                    }
                    _upcomingQueue.value = list
                }

                val historyArr = root.optJSONArray("playbackHistory")
                if (historyArr != null) {
                    val list = mutableListOf<Track>()
                    for (i in 0 until historyArr.length()) {
                        historyArr.optJSONObject(i)?.let { list.add(trackFromJson(it)) }
                    }
                    _playbackHistory.value = list
                    _historyCursor.value = if (list.isNotEmpty()) list.lastIndex else -1
                }

                val forwardArr = root.optJSONArray("forwardHistory")
                if (forwardArr != null) {
                    val list = mutableListOf<Track>()
                    for (i in 0 until forwardArr.length()) {
                        forwardArr.optJSONObject(i)?.let { list.add(trackFromJson(it)) }
                    }
                    _forwardHistory.value = list
                }

                Log.i(TAG, "Restored queue: current='${_currentTrack.value?.title}', upcoming=${_upcomingQueue.value.size}, history=${_playbackHistory.value.size}, shuffleSeq=${_shuffleSequenceTrackIds.value.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed restoring queue from disk: ${e.message}")
            }
        }
    }

    private fun trackToJson(track: Track): JSONObject {
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
        }
    }

    private fun trackFromJson(json: JSONObject): Track {
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
            isAvailable = json.optBoolean("isAvailable", true)
        )
    }
}
