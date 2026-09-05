package com.example.lyrics

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import com.example.data.AppDatabase
import com.example.data.LyricsDao
import com.example.data.LyricsEntity
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class LyricsManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    dao: LyricsDao? = null
) {

    companion object {
        private const val TAG = "LyricsManager"

        @Volatile
        private var instance: LyricsManager? = null

        fun getInstance(context: Context): LyricsManager {
            return instance ?: synchronized(this) {
                instance ?: LyricsManager(context.applicationContext).also { instance = it }
            }
        }

        fun createForTesting(context: Context, dao: LyricsDao): LyricsManager {
            return LyricsManager(context, CoroutineScope(Dispatchers.IO + SupervisorJob()), dao)
        }
    }

    private val lyricsDao: LyricsDao = dao ?: AppDatabase.getDatabase(context).lyricsDao()
    private val memoryCache = LruCache<String, TrackLyrics>(50)

    private val _currentTrackLyrics = MutableStateFlow<TrackLyrics?>(null)
    val currentTrackLyrics: StateFlow<TrackLyrics?> = _currentTrackLyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    fun getLyricsDir(): File {
        return File(context.filesDir, "lyrics").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Resolves lyrics adhering to the Step 3 priority order:
     * 1. User-edited local lyrics
     * 2. Embedded synced lyrics
     * 3. Local .lrc
     * 4. Embedded unsynced lyrics
     * 5. Cached online lyrics
     * 6. Online fetch
     */
    suspend fun getLyrics(track: Track, forceRefresh: Boolean = false): TrackLyrics? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            memoryCache.get(track.id)?.let { return@withContext it }
        }

        // 1. Check Room Database (User-edited or cached)
        val dbEntity = lyricsDao.getLyricsForTrack(track.id)
        if (dbEntity != null) {
            val source = LyricsSource.fromString(dbEntity.source)
            if (dbEntity.isUserEdited || !forceRefresh) {
                val lines = TrackLyrics.linesFromJson(dbEntity.syncedLyricsJson)
                val trackLyrics = TrackLyrics(
                    trackId = track.id,
                    plainText = dbEntity.plainLyrics,
                    lines = lines,
                    isSynced = dbEntity.isSynced,
                    isUserEdited = dbEntity.isUserEdited,
                    source = source,
                    offsetMs = dbEntity.offsetMs,
                    remoteLyricsId = dbEntity.remoteLyricsId,
                    updatedAt = dbEntity.updatedAt
                )
                memoryCache.put(track.id, trackLyrics)
                if (dbEntity.isUserEdited) {
                    Log.d(TAG, "Loaded user-edited lyrics from DB for '${track.title}'")
                    return@withContext trackLyrics
                }
            }
        }

        // 2 & 4. Check Local .lrc file adjacent to audio file
        val localLrcFile = EmbeddedLyricsReader.findLocalLrcFile(track.filePath, track.artist, track.title)
        if (localLrcFile != null && localLrcFile.exists()) {
            val lrcText = localLrcFile.readText(StandardCharsets.UTF_8)
            val parsed = LyricsParser.parse(lrcText)
            if (parsed.lines.isNotEmpty() || parsed.plainText.isNotBlank()) {
                val trackLyrics = TrackLyrics(
                    trackId = track.id,
                    plainText = parsed.plainText,
                    lines = parsed.lines,
                    isSynced = parsed.isSynced,
                    isUserEdited = false,
                    source = LyricsSource.LOCAL_LRC,
                    offsetMs = parsed.offsetMs
                )
                persistLyricsToDb(trackLyrics)
                memoryCache.put(track.id, trackLyrics)
                Log.d(TAG, "Loaded lyrics from local file '${localLrcFile.name}' for '${track.title}'")
                return@withContext trackLyrics
            }
        }

        // 3. Check Embedded file tags (USLT / SYLT)
        val embeddedLyricsText = EmbeddedLyricsReader.readEmbeddedLyrics(context, track.filePath)
        if (!embeddedLyricsText.isNullOrBlank()) {
            val parsed = LyricsParser.parse(embeddedLyricsText)
            val source = if (parsed.isSynced) LyricsSource.EMBEDDED_SYNCED else LyricsSource.EMBEDDED_UNSYNCED
            val trackLyrics = TrackLyrics(
                trackId = track.id,
                plainText = parsed.plainText,
                lines = parsed.lines,
                isSynced = parsed.isSynced,
                isUserEdited = false,
                source = source,
                offsetMs = parsed.offsetMs
            )
            persistLyricsToDb(trackLyrics)
            memoryCache.put(track.id, trackLyrics)
            Log.d(TAG, "Loaded embedded lyrics for '${track.title}'")
            return@withContext trackLyrics
        }

        // 5. Check cached database if not refreshed
        if (dbEntity != null) {
            val lines = TrackLyrics.linesFromJson(dbEntity.syncedLyricsJson)
            val trackLyrics = TrackLyrics(
                trackId = track.id,
                plainText = dbEntity.plainLyrics,
                lines = lines,
                isSynced = dbEntity.isSynced,
                isUserEdited = dbEntity.isUserEdited,
                source = LyricsSource.fromString(dbEntity.source),
                offsetMs = dbEntity.offsetMs,
                remoteLyricsId = dbEntity.remoteLyricsId,
                updatedAt = dbEntity.updatedAt
            )
            memoryCache.put(track.id, trackLyrics)
            return@withContext trackLyrics
        }

        // 6. Online Fetch via LRCLIB (Compliant, legitimate synced lyrics provider)
        val remote = LrcLibProvider.fetchLyrics(
            trackTitle = track.title,
            artistName = track.artist,
            albumName = track.album,
            durationSeconds = track.durationSeconds
        )

        if (remote != null) {
            val rawLrc = remote.syncedLyrics ?: remote.plainLyrics ?: ""
            val parsed = LyricsParser.parse(rawLrc)
            val trackLyrics = TrackLyrics(
                trackId = track.id,
                plainText = remote.plainLyrics ?: parsed.plainText,
                lines = parsed.lines,
                isSynced = parsed.isSynced,
                isUserEdited = false,
                source = LyricsSource.ONLINE_FETCH,
                offsetMs = parsed.offsetMs,
                remoteLyricsId = remote.id
            )
            persistLyricsToDb(trackLyrics)
            memoryCache.put(track.id, trackLyrics)
            return@withContext trackLyrics
        }

        null
    }

    suspend fun saveUserEditedLyrics(
        trackId: String,
        lines: List<LyricLine>,
        plainText: String,
        offsetMs: Long = 0L
    ): TrackLyrics = withContext(Dispatchers.IO) {
        val sortedLines = lines.sortedBy { it.timeMs }
        val lyrics = TrackLyrics(
            trackId = trackId,
            plainText = plainText.ifBlank { sortedLines.joinToString("\n") { it.text } },
            lines = sortedLines,
            isSynced = sortedLines.isNotEmpty(),
            isUserEdited = true,
            source = LyricsSource.USER_EDITED,
            offsetMs = offsetMs,
            updatedAt = System.currentTimeMillis()
        )
        persistLyricsToDb(lyrics)
        memoryCache.put(trackId, lyrics)

        // Also save .lrc backup in app storage
        try {
            val file = File(getLyricsDir(), "${trackId}.lrc")
            file.writeText(LyricsParser.toLrcString(sortedLines, offsetMs = offsetMs), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing local .lrc backup: ${e.message}")
        }

        if (_currentTrackLyrics.value?.trackId == trackId) {
            _currentTrackLyrics.value = lyrics
        }
        lyrics
    }

    suspend fun exportToLrcFile(track: Track, destinationFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val lyrics = getLyrics(track) ?: return@withContext false
            val lrcContent = LyricsParser.toLrcString(
                lines = lyrics.lines,
                title = track.title,
                artist = track.artist,
                album = track.album,
                offsetMs = lyrics.offsetMs
            )
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeText(lrcContent, StandardCharsets.UTF_8)
            Log.i(TAG, "Exported .lrc for '${track.title}' to ${destinationFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting .lrc file: ${e.message}")
            false
        }
    }

    suspend fun deleteLyrics(trackId: String) = withContext(Dispatchers.IO) {
        lyricsDao.deleteLyricsForTrack(trackId)
        memoryCache.remove(trackId)
        val file = File(getLyricsDir(), "${trackId}.lrc")
        if (file.exists()) file.delete()
        if (_currentTrackLyrics.value?.trackId == trackId) {
            _currentTrackLyrics.value = null
        }
    }

    fun loadForTrack(track: Track?, forceRefresh: Boolean = false) {
        if (track == null) {
            _currentTrackLyrics.value = null
            return
        }
        scope.launch {
            _isLoadingLyrics.value = true
            val lyrics = getLyrics(track, forceRefresh)
            _currentTrackLyrics.value = lyrics
            _isLoadingLyrics.value = false
        }
    }

    private suspend fun persistLyricsToDb(lyrics: TrackLyrics) {
        val entity = LyricsEntity(
            trackId = lyrics.trackId,
            plainLyrics = lyrics.plainText,
            syncedLyricsJson = TrackLyrics.linesToJson(lyrics.lines),
            isSynced = lyrics.isSynced,
            isUserEdited = lyrics.isUserEdited,
            source = lyrics.source.name,
            offsetMs = lyrics.offsetMs,
            remoteLyricsId = lyrics.remoteLyricsId,
            updatedAt = lyrics.updatedAt
        )
        lyricsDao.insertOrUpdateLyrics(entity)
    }
}
