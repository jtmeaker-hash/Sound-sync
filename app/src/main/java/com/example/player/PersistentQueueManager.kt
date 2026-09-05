package com.example.player

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.PlaylistEntity
import com.example.data.PlaylistTrackEntity
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
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
    val smartContinueMode: SmartContinueMode = SmartContinueMode.OFF
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

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _upcomingQueue = MutableStateFlow<List<Track>>(emptyList())
    val upcomingQueue: StateFlow<List<Track>> = _upcomingQueue.asStateFlow()

    private val _playbackHistory = MutableStateFlow<List<Track>>(emptyList())
    val playbackHistory: StateFlow<List<Track>> = _playbackHistory.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(QueueRepeatMode.OFF)
    val repeatMode: StateFlow<QueueRepeatMode> = _repeatMode.asStateFlow()

    private val _smartContinueMode = MutableStateFlow(SmartContinueMode.OFF)
    val smartContinueMode: StateFlow<SmartContinueMode> = _smartContinueMode.asStateFlow()

    fun setSmartContinueMode(mode: SmartContinueMode) {
        _smartContinueMode.value = mode
        saveToDiskAsync()
    }

    // Context fallback provider when the upcoming queue is empty (e.g. playing from an album/folder)
    var contextTrackProvider: (() -> List<Track>)? = null

    // ── Queue Management Operations ──────────────────────────────────────────

    /**
     * Initializes the queue with a list of tracks, setting currentTrack to the chosen start item.
     */
    fun setQueue(tracks: List<Track>, startTrack: Track?, shuffle: Boolean = false) {
        if (tracks.isEmpty()) {
            clearQueue()
            return
        }

        _isShuffleEnabled.value = shuffle
        val current = startTrack ?: tracks.first()
        _currentTrack.value = current

        val remaining = if (shuffle) {
            tracks.filter { it.id != current.id }.shuffled()
        } else {
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
     * Clears playback history.
     */
    fun clearHistory() {
        _playbackHistory.value = emptyList()
        saveToDiskAsync()
    }

    fun setShuffle(enabled: Boolean) {
        if (_isShuffleEnabled.value == enabled) return
        _isShuffleEnabled.value = enabled
        if (enabled && _upcomingQueue.value.isNotEmpty()) {
            _upcomingQueue.value = _upcomingQueue.value.shuffled()
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
     * Advances to the next track, archiving the current track into playback history.
     */
    fun nextTrack(): Track? {
        val current = _currentTrack.value

        // Repeat ONE: replay current track
        if (_repeatMode.value == QueueRepeatMode.ONE && current != null) {
            return current
        }

        // Archive current to history (LIFO for Previous navigation)
        if (current != null) {
            val history = _playbackHistory.value.toMutableList()
            history.add(0, current)
            if (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(history.lastIndex)
            }
            _playbackHistory.value = history
        }

        val upcoming = _upcomingQueue.value.toMutableList()
        if (upcoming.isNotEmpty()) {
            val next = upcoming.removeAt(0)
            _upcomingQueue.value = upcoming
            _currentTrack.value = next
            saveToDiskAsync()
            return next
        }

        // Queue exhausted: Check Repeat ALL
        if (_repeatMode.value == QueueRepeatMode.ALL) {
            val allPlayed = _playbackHistory.value.reversed()
            if (allPlayed.isNotEmpty()) {
                val newQueue = if (_isShuffleEnabled.value) allPlayed.shuffled() else allPlayed
                val next = newQueue.first()
                _upcomingQueue.value = newQueue.drop(1)
                _currentTrack.value = next
                _playbackHistory.value = emptyList()
                saveToDiskAsync()
                return next
            }
        }

        // Smart Queue Assistance (Step 3 Part F)
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
                        saveToDiskAsync()
                        Log.i(TAG, "Smart Continue [${_smartContinueMode.value.label}]: '${recommended.title}'")
                        return recommended
                    }
                }
            }
        }

        // Context fallback (e.g. continue playing library/folder)
        val contextTracks = contextTrackProvider?.invoke()
        if (!contextTracks.isNullOrEmpty()) {
            val available = if (_isShuffleEnabled.value) contextTracks.shuffled() else contextTracks
            val next = available.first()
            _upcomingQueue.value = available.drop(1)
            _currentTrack.value = next
            saveToDiskAsync()
            return next
        }

        // End of playback
        _currentTrack.value = null
        saveToDiskAsync()
        return null
    }

    /**
     * Navigates to the previous track.
     * CRITICAL STEP 2 REQUIREMENT:
     * In shuffle mode, Previous MUST return to the actual previously played track from history,
     * NOT a new random track!
     */
    fun previousTrack(): Track? {
        val history = _playbackHistory.value.toMutableList()
        if (history.isEmpty()) {
            return _currentTrack.value
        }

        val prev = history.removeAt(0)
        _playbackHistory.value = history

        // Current moves to top of upcoming queue
        val current = _currentTrack.value
        if (current != null) {
            val upcoming = _upcomingQueue.value.toMutableList()
            upcoming.add(0, current)
            _upcomingQueue.value = upcoming
        }

        _currentTrack.value = prev
        saveToDiskAsync()
        Log.d(TAG, "Previous track selected: '${prev.title}' (History left: ${history.size})")
        return prev
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
        scope.launch {
            saveToDisk()
        }
    }

    suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("version", 2)
                put("isShuffle", _isShuffleEnabled.value)
                put("repeatMode", _repeatMode.value.name)
                put("smartContinueMode", _smartContinueMode.value.name)
                _currentTrack.value?.let { put("currentTrack", trackToJson(it)) }

                val upcomingArr = JSONArray()
                _upcomingQueue.value.forEach { upcomingArr.put(trackToJson(it)) }
                put("upcomingQueue", upcomingArr)

                val historyArr = JSONArray()
                _playbackHistory.value.take(MAX_HISTORY_SIZE).forEach { historyArr.put(trackToJson(it)) }
                put("playbackHistory", historyArr)
            }

            val tempFile = File(context.filesDir, "$QUEUE_FILENAME.tmp")
            tempFile.writeText(root.toString(2), StandardCharsets.UTF_8)
            if (queueFile.exists()) queueFile.delete()
            tempFile.renameTo(queueFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving queue to disk: ${e.message}")
        }
    }

    fun restoreFromDisk() {
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
            }

            Log.i(TAG, "Restored queue: current='${_currentTrack.value?.title}', upcoming=${_upcomingQueue.value.size}, history=${_playbackHistory.value.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed restoring queue from disk: ${e.message}")
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
