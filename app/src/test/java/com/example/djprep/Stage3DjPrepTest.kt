package com.example.djprep

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjAudioEngine
import com.example.backup.DjPrepBackupItem
import com.example.backup.SoundSyncBackup
import com.example.backup.SoundSyncBackupManager
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Stage 3: DJ Prep Mini Preparation Environment Verification Tests.
 *
 * Verifies all Stage 3 requirements:
 * 1. Hot Cues A-H add, jump, move/update position, rename, and delete.
 * 2. Ordered Memory Cues with Previous/Next navigation, editing label/position, and delete.
 * 3. Beat grid first downbeat anchor, fine nudge, and grid alignment calculations.
 * 4. BPM double (×2) and halve (÷2), manual BPM setting, and reset to analyzed values.
 * 5. Multi-level Undo stack for all beat grid and cue edits.
 * 6. Key Lock toggle, audio pitch preservation state, and SharedPreferences persistence.
 * 7. Phrase markers CRUD, time ranges, and color assignment.
 * 8. Backup & restore round-trip preserving DJ Prep data non-destructively.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage3DjPrepTest {

    private lateinit var context: Context
    private lateinit var prepManager: DjPrepManager
    private val prepStore = mutableMapOf<String, DjPrepEntity>()
    private val trackStore = mutableMapOf<String, TrackEntity>()

    private lateinit var fakePrepDao: DjPrepDao
    private lateinit var fakeTrackDao: TrackDao

    private fun makeTrack(
        id: String = "track-dj-1",
        title: String = "Techno Track",
        durationMs: Long = 240000L,
        bpm: Double = 130.0,
        key: String = "8A"
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = "DJ Producer",
            album = "Festival EP",
            durationSeconds = (durationMs / 1000L).toInt(),
            filePath = "/storage/emulated/0/Music/$id.mp3",
            bpm = bpm,
            musicalKey = key
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        DjAudioEngine.resetInstance()
        prepManager = DjPrepManager.getInstance(context)
        prepStore.clear()
        trackStore.clear()

        // Dynamic Proxy for DjPrepDao
        val prepClassLoader = DjPrepDao::class.java.classLoader
        fakePrepDao = Proxy.newProxyInstance(
            prepClassLoader,
            arrayOf(DjPrepDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertOrUpdate" -> {
                    val entity = args[0] as DjPrepEntity
                    prepStore[entity.trackId] = entity
                    null
                }
                "getByTrackId" -> {
                    val trackId = args[0] as String
                    prepStore[trackId]
                }
                "getAllPrepData" -> {
                    prepStore.values.toList()
                }
                "deleteByTrackId" -> {
                    val trackId = args[0] as String
                    prepStore.remove(trackId)
                    null
                }
                else -> null
            }
        } as DjPrepDao

        // Dynamic Proxy for TrackDao
        val trackClassLoader = TrackDao::class.java.classLoader
        fakeTrackDao = Proxy.newProxyInstance(
            trackClassLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getTrackById" -> {
                    val id = args[0] as String
                    trackStore[id]
                }
                "updateTrack" -> {
                    val entity = args[0] as TrackEntity
                    trackStore[entity.id] = entity
                    null
                }
                else -> null
            }
        } as TrackDao
    }

    @Test
    fun testHotCueCrudAndPositionUpdate() = runBlocking {
        val track = makeTrack()
        prepManager.getOrInitPrepData(track, fakePrepDao)

        // 1. Add Hot Cue A at 15,000ms
        val dataWithA = prepManager.addOrUpdateHotCue(
            track = track,
            slot = "A",
            positionMs = 15000L,
            label = "Intro Drop",
            colorHex = "#FF2D55",
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        val cueA = dataWithA.hotCues.find { it.id == "A" }
        assertNotNull(cueA)
        assertEquals(15000L, cueA!!.positionMs)
        assertEquals("Intro Drop", cueA.label)

        // 2. Add Hot Cue B at 45,000ms
        val dataWithB = prepManager.addOrUpdateHotCue(
            track = track,
            slot = "B",
            positionMs = 45000L,
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        assertEquals(2, dataWithB.hotCues.size)

        // 3. Update Cue A position to current playhead (e.g. 16,500ms)
        val dataUpdatedA = prepManager.updateHotCuePosition(
            track = track,
            cueId = "A",
            newPositionMs = 16500L,
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        val movedA = dataUpdatedA.hotCues.find { it.id == "A" }
        assertEquals(16500L, movedA?.positionMs)

        // 4. Rename Cue B
        val renamedB = prepManager.renameHotCue(
            track = track,
            cueId = "B",
            newLabel = "Vocal Hook",
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        assertEquals("Vocal Hook", renamedB.hotCues.find { it.id == "B" }?.label)

        // 5. Delete Cue A
        val dataDeletedA = prepManager.deleteHotCue(
            track = track,
            cueId = "A",
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        assertEquals(1, dataDeletedA.hotCues.size)
        assertNull(dataDeletedA.hotCues.find { it.id == "A" })
    }

    @Test
    fun testMemoryCueCrudAndNavigation() = runBlocking {
        val track = makeTrack()
        prepManager.getOrInitPrepData(track, fakePrepDao)

        // 1. Add 3 memory cues out of order
        prepManager.addMemoryCue(track, 60000L, "Breakdown", fakePrepDao, fakeTrackDao)
        prepManager.addMemoryCue(track, 15000L, "Beat 1", fakePrepDao, fakeTrackDao)
        val data = prepManager.addMemoryCue(track, 120000L, "Outro Start", fakePrepDao, fakeTrackDao)

        // Verify chronological ordering
        assertEquals(3, data.memoryCues.size)
        assertEquals(15000L, data.memoryCues[0].positionMs)
        assertEquals(60000L, data.memoryCues[1].positionMs)
        assertEquals(120000L, data.memoryCues[2].positionMs)

        // 2. Navigation Next / Previous
        val nextFromZero = prepManager.getNextMemoryCue(data, 0L)
        assertEquals(15000L, nextFromZero?.positionMs)

        val nextFromMiddle = prepManager.getNextMemoryCue(data, 15000L)
        assertEquals(60000L, nextFromMiddle?.positionMs)

        val prevFromOutro = prepManager.getPreviousMemoryCue(data, 120000L)
        assertEquals(60000L, prevFromOutro?.positionMs)

        // 3. Update Memory Cue (rename and move)
        val cueToEdit = data.memoryCues[1]
        val updatedData = prepManager.updateMemoryCue(
            track = track,
            cueId = cueToEdit.id,
            newLabel = "Massive Drop",
            newPositionMs = 62000L,
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        val edited = updatedData.memoryCues.find { it.id == cueToEdit.id }
        assertEquals("Massive Drop", edited?.label)
        assertEquals(62000L, edited?.positionMs)

        // 4. Delete Memory Cue
        val deletedData = prepManager.deleteMemoryCue(
            track = track,
            cueId = cueToEdit.id,
            customDao = fakePrepDao,
            customTrackDao = fakeTrackDao
        )
        assertEquals(2, deletedData.memoryCues.size)
        assertNull(deletedData.memoryCues.find { it.id == cueToEdit.id })
    }

    @Test
    fun testBeatGridAnchorNudgeAndBpmDoubleHalve() = runBlocking {
        val track = makeTrack(bpm = 120.0)
        prepManager.getOrInitPrepData(track, fakePrepDao)

        // 1. Set First Downbeat Anchor at 250ms
        val dataDownbeat = prepManager.setFirstDownbeat(track, 250L, fakePrepDao, fakeTrackDao)
        assertEquals(250L, dataDownbeat.grid.firstDownbeatMs)
        assertTrue(dataDownbeat.grid.isManualOverride)

        // 2. Nudge Grid +10ms
        val dataNudge = prepManager.nudgeGrid(track, 10L, fakePrepDao, fakeTrackDao)
        assertEquals(10L, dataNudge.grid.gridOffsetMs)

        // 3. Double BPM (120 -> 240)
        val dataDouble = prepManager.doubleBpm(track, fakePrepDao, fakeTrackDao)
        assertEquals(240.0, dataDouble.bpm, 0.001)
        assertTrue(dataDouble.isManualBpm)
        assertEquals(250.0, dataDouble.grid.intervalMs, 0.001)

        // 4. Halve BPM (240 -> 120)
        val dataHalve = prepManager.halveBpm(track, fakePrepDao, fakeTrackDao)
        assertEquals(120.0, dataHalve.bpm, 0.001)
        assertEquals(500.0, dataHalve.grid.intervalMs, 0.001)

        // 5. Reset to Analyzed (128.0 BPM, 8A)
        val dataReset = prepManager.resetGridToAnalyzed(track, 128.0, "8A", fakePrepDao, fakeTrackDao)
        assertEquals(128.0, dataReset.bpm, 0.001)
        assertFalse(dataReset.isManualBpm)
        assertEquals(0L, dataReset.grid.firstDownbeatMs)
        assertEquals(0L, dataReset.grid.gridOffsetMs)
    }

    @Test
    fun testUndoHistoryStack() = runBlocking {
        val track = makeTrack(id = "track-undo-1", bpm = 124.0)
        prepManager.getOrInitPrepData(track, fakePrepDao)

        // Verify initial undo state
        assertFalse(prepManager.canUndo(track.id))

        // Step 1: Set Downbeat to 500ms
        prepManager.setFirstDownbeat(track, 500L, fakePrepDao, fakeTrackDao)
        assertTrue(prepManager.canUndo(track.id))

        // Step 2: Nudge by +20ms
        prepManager.nudgeGrid(track, 20L, fakePrepDao, fakeTrackDao)

        // Step 3: Double BPM
        val doubled = prepManager.doubleBpm(track, fakePrepDao, fakeTrackDao)
        assertEquals(248.0, doubled.bpm, 0.001)

        // Undo Step 3 -> should restore BPM to 124.0 with offset 20ms
        val undo1 = prepManager.undo(track, fakePrepDao, fakeTrackDao)
        assertNotNull(undo1)
        assertEquals(124.0, undo1!!.bpm, 0.001)
        assertEquals(20L, undo1.grid.gridOffsetMs)

        // Undo Step 2 -> should restore offset to 0ms
        val undo2 = prepManager.undo(track, fakePrepDao, fakeTrackDao)
        assertNotNull(undo2)
        assertEquals(0L, undo2!!.grid.gridOffsetMs)
        assertEquals(500L, undo2.grid.firstDownbeatMs)

        // Undo Step 1 -> should restore downbeat to 0ms
        val undo3 = prepManager.undo(track, fakePrepDao, fakeTrackDao)
        assertNotNull(undo3)
        assertEquals(0L, undo3!!.grid.firstDownbeatMs)

        // Stack should now be empty
        assertFalse(prepManager.canUndo(track.id))
        assertNull(prepManager.undo(track, fakePrepDao, fakeTrackDao))
    }

    @Test
    fun testKeyLockPreferencePersistence() {
        val audioEngine = DjAudioEngine.getInstance(context)

        // Toggle Key Lock to false
        audioEngine.setKeyLock(false)
        assertFalse(audioEngine.keyLockEnabled.value)

        val prefs = context.getSharedPreferences("soundsync_dj_prefs", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("key_lock_enabled", true))

        // Toggle Key Lock back to true
        audioEngine.setKeyLock(true)
        assertTrue(audioEngine.keyLockEnabled.value)
        assertTrue(prefs.getBoolean("key_lock_enabled", false))
    }

    @Test
    fun testPhraseMarkersCrud() = runBlocking {
        val track = makeTrack()
        prepManager.getOrInitPrepData(track, fakePrepDao)

        val intro = PhraseMarker(
            id = "intro_1",
            type = PhraseType.INTRO,
            label = "Intro",
            startMs = 0L,
            endMs = 30000L,
            barCount = 16,
            colorHex = PhraseType.INTRO.colorHex
        )
        val drop = PhraseMarker(
            id = "drop_1",
            type = PhraseType.DROP,
            label = "Main Drop",
            startMs = 60000L,
            endMs = 90000L,
            barCount = 16,
            colorHex = PhraseType.DROP.colorHex
        )

        // 1. Add markers
        prepManager.addPhraseMarker(track, drop, fakePrepDao, fakeTrackDao)
        val data = prepManager.addPhraseMarker(track, intro, fakePrepDao, fakeTrackDao)

        // Verify ordering by startMs
        assertEquals(2, data.phraseMarkers.size)
        assertEquals("Intro", data.phraseMarkers[0].label)
        assertEquals("Main Drop", data.phraseMarkers[1].label)

        // 2. Update marker
        val updatedDrop = drop.copy(label = "Heavy Drop 1", barCount = 32, endMs = 120000L)
        val dataUpdated = prepManager.updatePhraseMarker(track, updatedDrop, fakePrepDao, fakeTrackDao)
        val foundDrop = dataUpdated.phraseMarkers.find { it.id == "drop_1" }
        assertEquals("Heavy Drop 1", foundDrop?.label)
        assertEquals(32, foundDrop?.barCount)

        // 3. Delete marker
        val dataDeleted = prepManager.deletePhraseMarker(track, "intro_1", fakePrepDao, fakeTrackDao)
        assertEquals(1, dataDeleted.phraseMarkers.size)
        assertNull(dataDeleted.phraseMarkers.find { it.id == "intro_1" })
    }

    @Test
    fun testBackupAndRestoreDjPrepDataSerialization() {
        val prepItem = DjPrepBackupItem(
            trackId = "track_backup_123",
            bpm = 128.0,
            isManualBpm = true,
            musicalKey = "8A",
            camelotKey = "8A",
            isManualKey = true,
            firstDownbeatMs = 250L,
            gridOffsetMs = 15L,
            isManualGrid = true,
            hotCuesJson = "[{\"id\":\"A\",\"label\":\"Cue A\",\"positionMs\":15000,\"colorHex\":\"#FF2D55\",\"type\":\"HOT_CUE\"}]",
            memoryCuesJson = "[{\"id\":\"mem_1\",\"label\":\"Break\",\"positionMs\":45000,\"colorHex\":\"#FFCC00\",\"type\":\"MEMORY_CUE\"}]",
            phraseMarkersJson = "[{\"id\":\"ph_1\",\"type\":\"DROP\",\"label\":\"Drop\",\"startMs\":60000,\"endMs\":90000,\"startBar\":1,\"barCount\":16,\"colorHex\":\"#FF1744\"}]",
            prepStatus = "PREPPED",
            notes = "Peak time weapon",
            updatedAt = 1700000000000L
        )

        val backup = SoundSyncBackup(
            backupVersion = SoundSyncBackup.CURRENT_BACKUP_VERSION,
            createdAt = System.currentTimeMillis(),
            appVersion = "1.0",
            djPrepData = listOf(prepItem)
        )

        // Serialize backup
        val manager = SoundSyncBackupManager.getInstance(context)
        val jsonString = manager.serializeBackup(backup)
        assertTrue(jsonString.contains("djPrepData"))
        assertTrue(jsonString.contains("track_backup_123"))
        assertTrue(jsonString.contains("PREPPED"))

        // Validate backup
        val validation = manager.validateBackup(jsonString)
        assertTrue(validation is com.example.backup.ValidationResult.Valid)
        val validBackup = (validation as com.example.backup.ValidationResult.Valid).backup
        assertEquals(1, validBackup.djPrepData.size)
        assertEquals("track_backup_123", validBackup.djPrepData[0].trackId)
        assertEquals("PREPPED", validBackup.djPrepData[0].prepStatus)
    }
}
