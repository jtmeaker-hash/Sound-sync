package com.example.djprep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Stage 28: DJ Track Preparation Environment Manager.
 *
 * Coordinates durable DJ prep data, beat grid manipulation, cue markers,
 * memory cues, phrase markers, metronome audio preview, and manual override syncing.
 */
class DjPrepManager private constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DjPrepManager"

        @Volatile
        private var instance: DjPrepManager? = null

        fun getInstance(context: Context): DjPrepManager {
            return instance ?: synchronized(this) {
                instance ?: DjPrepManager(context.applicationContext).also { instance = it }
            }
        }

        fun createForTesting(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DjPrepManager {
            return DjPrepManager(context, scope)
        }
    }

    private val db: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    private val prepDao: DjPrepDao by lazy { db.djPrepDao() }
    private val trackDao: TrackDao by lazy { db.trackDao() }

    // Active track DJ Prep state
    private val _activePrepData = MutableStateFlow<DjPrepTrackData?>(null)
    val activePrepData: StateFlow<DjPrepTrackData?> = _activePrepData.asStateFlow()

    // Metronome Click Preview State
    private val _isMetronomeEnabled = MutableStateFlow(false)
    val isMetronomeEnabled: StateFlow<Boolean> = _isMetronomeEnabled.asStateFlow()

    fun setMetronomeEnabled(enabled: Boolean) {
        _isMetronomeEnabled.value = enabled
    }

    fun toggleMetronome() {
        _isMetronomeEnabled.value = !_isMetronomeEnabled.value
    }

    // ── Load & Initialize Prep Data ──────────────────────────────────────────

    suspend fun getOrInitPrepData(track: Track, customDao: DjPrepDao? = null): DjPrepTrackData = withContext(Dispatchers.IO) {
        val dao = customDao ?: prepDao
        val existing = dao.getByTrackId(track.id)
        if (existing != null) {
            val data = existing.toDjPrepTrackData()
            _activePrepData.value = data
            return@withContext data
        }

        // Initialize default prep data from track attributes
        val defaultStatus = if (track.hasValidBpm && track.hasValidKey) {
            PrepStatus.ANALYSED
        } else {
            PrepStatus.NOT_ANALYSED
        }

        // Initial Hot Cues start empty until set by the user
        val initialHotCues = emptyList<CuePoint>()

        val initialData = DjPrepTrackData(
            trackId = track.id,
            bpm = if (track.bpm > 0.0) track.bpm else 120.0,
            isManualBpm = track.isManualBpm,
            musicalKey = track.musicalKey,
            camelotKey = track.camelotKey,
            isManualKey = track.isManualKey,
            grid = BeatGridData(
                bpm = if (track.bpm > 0.0) track.bpm else 120.0,
                firstDownbeatMs = 0L,
                gridOffsetMs = 0L,
                isManualOverride = false
            ),
            hotCues = initialHotCues,
            memoryCues = emptyList(),
            phraseMarkers = emptyList(),
            prepStatus = defaultStatus,
            notes = track.notes,
            updatedAt = System.currentTimeMillis()
        )

        dao.insertOrUpdate(DjPrepEntity.fromDjPrepTrackData(initialData))
        _activePrepData.value = initialData
        return@withContext initialData
    }

    // ── Cue Markers (Hot Cues A-H) ───────────────────────────────────────────

    suspend fun setHotCue(
        track: Track,
        slot: String,
        positionMs: Long,
        label: String? = null,
        colorHex: String? = null,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = addOrUpdateHotCue(track, slot, positionMs, label, colorHex, customDao, customTrackDao)

    suspend fun addOrUpdateHotCue(
        track: Track,
        slot: String,
        positionMs: Long,
        label: String? = null,
        colorHex: String? = null,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val cleanSlot = slot.trim().uppercase()
        val defaultColor = when (cleanSlot) {
            "A" -> CuePoint.DEFAULT_HOT_CUE_COLORS[0]
            "B" -> CuePoint.DEFAULT_HOT_CUE_COLORS[1]
            "C" -> CuePoint.DEFAULT_HOT_CUE_COLORS[2]
            "D" -> CuePoint.DEFAULT_HOT_CUE_COLORS[3]
            "E" -> CuePoint.DEFAULT_HOT_CUE_COLORS[4]
            "F" -> CuePoint.DEFAULT_HOT_CUE_COLORS[5]
            "G" -> CuePoint.DEFAULT_HOT_CUE_COLORS[6]
            "H" -> CuePoint.DEFAULT_HOT_CUE_COLORS[7]
            else -> "#FF3344"
        }

        val newCue = CuePoint(
            id = cleanSlot,
            label = label?.ifBlank { null } ?: "Hot Cue $cleanSlot",
            positionMs = positionMs.coerceAtLeast(0L),
            colorHex = colorHex ?: defaultColor,
            type = CueType.HOT_CUE
        )

        val updatedList = current.hotCues.filter { it.id != cleanSlot }.toMutableList()
        updatedList.add(newCue)
        updatedList.sortBy { it.id }

        val updated = current.copy(
            hotCues = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun deleteHotCue(
        track: Track,
        cueId: String,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedList = current.hotCues.filter { it.id != cueId }
        val updated = current.copy(
            hotCues = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun renameHotCue(
        track: Track,
        cueId: String,
        newLabel: String,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedList = current.hotCues.map {
            if (it.id.equals(cueId, ignoreCase = true)) it.copy(label = newLabel) else it
        }
        val updated = current.copy(
            hotCues = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    // ── Memory Cues ──────────────────────────────────────────────────────────

    suspend fun addMemoryCue(
        track: Track,
        positionMs: Long,
        label: String? = null,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val newId = "mem_${System.currentTimeMillis()}"
        val newCue = CuePoint(
            id = newId,
            label = label?.ifBlank { null } ?: "Memory Cue ${current.memoryCues.size + 1}",
            positionMs = positionMs.coerceAtLeast(0L),
            colorHex = "#FFCC00",
            type = CueType.MEMORY_CUE
        )

        val updatedList = (current.memoryCues + newCue).sortedBy { it.positionMs }
        val updated = current.copy(
            memoryCues = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun deleteMemoryCue(
        track: Track,
        cueId: String,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedList = current.memoryCues.filter { it.id != cueId }
        val updated = current.copy(
            memoryCues = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    fun getNextMemoryCue(prepData: DjPrepTrackData, currentPositionMs: Long): CuePoint? {
        return prepData.memoryCues.firstOrNull { it.positionMs > currentPositionMs + 50L }
    }

    fun getPreviousMemoryCue(prepData: DjPrepTrackData, currentPositionMs: Long): CuePoint? {
        return prepData.memoryCues.lastOrNull { it.positionMs < currentPositionMs - 50L }
    }

    // ── Beat Grid & BPM Correction ───────────────────────────────────────────

    suspend fun setFirstDownbeat(
        track: Track,
        downbeatMs: Long,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedGrid = current.grid.copy(
            firstDownbeatMs = downbeatMs.coerceAtLeast(0L),
            isManualOverride = true
        )
        val updated = current.copy(
            grid = updatedGrid,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun nudgeGrid(
        track: Track,
        offsetDeltaMs: Long,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val newOffset = current.grid.gridOffsetMs + offsetDeltaMs
        val updatedGrid = current.grid.copy(
            gridOffsetMs = newOffset,
            isManualOverride = true
        )
        val updated = current.copy(
            grid = updatedGrid,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun doubleBpm(
        track: Track,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val newBpm = (current.bpm * 2.0).coerceIn(40.0, 300.0)
        val updated = current.copy(
            bpm = newBpm,
            isManualBpm = true,
            grid = current.grid.copy(bpm = newBpm, isManualOverride = true),
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun halveBpm(
        track: Track,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val newBpm = (current.bpm / 2.0).coerceIn(40.0, 300.0)
        val updated = current.copy(
            bpm = newBpm,
            isManualBpm = true,
            grid = current.grid.copy(bpm = newBpm, isManualOverride = true),
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun setBpm(
        track: Track,
        newBpm: Double,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val clampedBpm = newBpm.coerceIn(40.0, 300.0)
        val updated = current.copy(
            bpm = clampedBpm,
            isManualBpm = true,
            grid = current.grid.copy(bpm = clampedBpm, isManualOverride = true),
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun resetGridToAnalyzed(
        track: Track,
        analyzedBpm: Double,
        analyzedKey: String,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val validBpm = if (analyzedBpm in 40.0..300.0) analyzedBpm else 120.0
        val updated = current.copy(
            bpm = validBpm,
            isManualBpm = false,
            musicalKey = analyzedKey,
            isManualKey = false,
            grid = BeatGridData(
                bpm = validBpm,
                firstDownbeatMs = 0L,
                gridOffsetMs = 0L,
                isManualOverride = false
            ),
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    // ── Key Correction ───────────────────────────────────────────────────────

    suspend fun setKey(
        track: Track,
        musicalKey: String,
        camelotKey: String? = null,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val resolvedCamelot = camelotKey ?: com.example.analysis.TunebatMetadataService.normalizeCamelotKey(musicalKey)
        val updated = current.copy(
            musicalKey = musicalKey,
            camelotKey = resolvedCamelot,
            isManualKey = true,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    // ── Phrase Markers ───────────────────────────────────────────────────────

    suspend fun addPhraseMarker(
        track: Track,
        phrase: PhraseMarker,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedList = (current.phraseMarkers + phrase).sortedBy { it.startMs }
        val updated = current.copy(
            phraseMarkers = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun deletePhraseMarker(
        track: Track,
        phraseId: String,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedList = current.phraseMarkers.filter { it.id != phraseId }
        val updated = current.copy(
            phraseMarkers = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun updatePhraseMarker(
        track: Track,
        phrase: PhraseMarker,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updatedList = current.phraseMarkers.map { if (it.id == phrase.id) phrase else it }
        val updated = current.copy(
            phraseMarkers = updatedList,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    fun calculateBeatGrid(firstBeatMs: Long, bpm: Double, durationMs: Long): List<Long> {
        if (bpm <= 0.0 || durationMs <= 0L) return emptyList()
        val interval = 60_000.0 / bpm
        val beats = mutableListOf<Long>()
        var current = firstBeatMs
        while (current <= durationMs) {
            if (current >= 0L) {
                beats.add(current)
            }
            current += Math.round(interval)
        }
        return beats
    }

    // ── Prep Status ──────────────────────────────────────────────────────────

    suspend fun setPrepStatus(
        track: Track,
        status: PrepStatus,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ): DjPrepTrackData = withContext(Dispatchers.IO) {
        val current = getOrInitPrepData(track, customDao)
        val updated = current.copy(
            prepStatus = status,
            updatedAt = System.currentTimeMillis()
        )

        saveAndSync(updated, customDao, customTrackDao)
        _activePrepData.value = updated
        updated
    }

    suspend fun batchSetPrepStatus(
        trackIds: List<String>,
        status: PrepStatus,
        customDao: DjPrepDao? = null
    ) = withContext(Dispatchers.IO) {
        val dao = customDao ?: prepDao
        val now = System.currentTimeMillis()
        dao.batchUpdatePrepStatus(trackIds, status.name, now)
        _activePrepData.value?.let { current ->
            if (current.trackId in trackIds) {
                _activePrepData.value = current.copy(prepStatus = status, updatedAt = now)
            }
        }
    }

    suspend fun setPrepStatus(
        trackId: String,
        status: PrepStatus,
        customDao: DjPrepDao? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val dao = customDao ?: prepDao
        val now = System.currentTimeMillis()
        dao.updatePrepStatus(trackId, status.name, now)
        _activePrepData.value?.let { current ->
            if (current.trackId == trackId) {
                _activePrepData.value = current.copy(prepStatus = status, updatedAt = now)
            }
        }
        true
    }

    suspend fun getPrepData(
        trackId: String,
        customDao: DjPrepDao? = null
    ): DjPrepTrackData? = withContext(Dispatchers.IO) {
        val dao = customDao ?: prepDao
        dao.getByTrackId(trackId)?.toDjPrepTrackData()
    }

    // ── Persistence & DB Synchronization ──────────────────────────────────────

    private suspend fun saveAndSync(
        data: DjPrepTrackData,
        customDao: DjPrepDao? = null,
        customTrackDao: TrackDao? = null
    ) {
        val dao = customDao ?: prepDao
        dao.insertOrUpdate(DjPrepEntity.fromDjPrepTrackData(data))

        // Also sync manual BPM, manual Key, and hotCues into TrackEntity in Room
        val tDao = customTrackDao ?: trackDao
        val existingTrack = tDao.getTrackById(data.trackId)
        if (existingTrack != null) {
            val cuesCsv = data.hotCues.map { (it.positionMs / 1000L).toInt() }.joinToString(",")
            val updatedEntity = existingTrack.copy(
                bpm = data.bpm,
                isManualBpm = data.isManualBpm,
                musicalKey = data.musicalKey,
                camelotKey = data.camelotKey,
                isManualKey = data.isManualKey,
                hotCuesString = if (cuesCsv.isNotBlank()) cuesCsv else existingTrack.hotCuesString
            )
            tDao.updateTrack(updatedEntity)
        }
    }

    // ── Metronome Click Generator ─────────────────────────────────────────────

    private var metronomeAudioTrack: AudioTrack? = null
    private var highTickBytes: ByteArray? = null
    private var lowTickBytes: ByteArray? = null

    init {
        try {
            generateTickSounds()
        } catch (e: Exception) {
            Log.w(TAG, "Failed initializing tick sounds: ${e.message}")
        }
    }

    fun generateTickPcm(isDownbeat: Boolean): ByteArray {
        val sampleRate = 44100
        val freq = if (isDownbeat) 2200.0 else 1200.0
        val durationSamples = (sampleRate * 0.015).toInt() // 15ms duration
        val buffer = ByteArray(durationSamples * 2)
        for (i in 0 until durationSamples) {
            // Exponential decay envelope
            val env = Math.exp(-i.toDouble() / (sampleRate * 0.003))
            val sample = (sin(2.0 * PI * freq * i / sampleRate) * env * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            val idx = i * 2
            buffer[idx] = (sample.toInt() and 0xFF).toByte()
            buffer[idx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun generateTickSounds() {
        highTickBytes = generateTickPcm(isDownbeat = true)
        lowTickBytes = generateTickPcm(isDownbeat = false)
    }

    fun playMetronomeTick(isDownbeat: Boolean) {
        if (!_isMetronomeEnabled.value) return
        scope.launch(Dispatchers.IO) {
            try {
                val data = if (isDownbeat) highTickBytes else lowTickBytes
                if (data == null) return@launch

                val sampleRate = 44100
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(data.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(data, 0, data.size)
                track.play()
                kotlinx.coroutines.delay(30)
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }
}
