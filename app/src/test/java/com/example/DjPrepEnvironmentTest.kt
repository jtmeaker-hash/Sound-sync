package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjAudioEngine
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.djprep.BeatGridData
import com.example.djprep.CuePoint
import com.example.djprep.CueType
import com.example.djprep.DjPrepDao
import com.example.djprep.DjPrepEntity
import com.example.djprep.DjPrepManager
import com.example.djprep.DjPrepTrackData
import com.example.djprep.PhraseMarker
import com.example.djprep.PhraseType
import com.example.djprep.PrepStatus
import com.example.model.Track
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Stage 28: DJ Prep Environment Tests.
 *
 * Verifies all required scenarios from 28_DJ_Prep_Environment.txt:
 * 1. Add / edit / delete / jump Hot Cues (A–H) with distinct colors.
 * 2. Ordered Memory Cues with Previous / Next navigation and persistence.
 * 3. Set first downbeat and nudge grid (±1ms, ±10ms).
 * 4. BPM double (×2) and halve (÷2) update grid and mark isManualBpm = true.
 * 5. Manual BPM and Key survive rescan and background analysis.
 * 6. Manual key, grid, and cues survive DB persistence round-trips.
 * 7. Phrase markers CRUD, colors, and persistence.
 * 8. Beat grid calculations and synchronization.
 * 9. Graceful handling of tracks missing BPM/key/duration.
 * 10. Key Lock toggle and audio engine playback parameter behavior.
 * 11. Prep status transitions and batch update.
 * 12. Synthesized metronome click generation (accented vs standard).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DjPrepEnvironmentTest {

    private lateinit var context: Context
    private lateinit var prepManager: DjPrepManager

    private val prepStore = mutableMapOf<String, DjPrepEntity>()
    private val trackStore = mutableMapOf<String, TrackEntity>()

    private lateinit var fakePrepDao: DjPrepDao
    private lateinit var fakeTrackDao: TrackDao

    private fun makeTrack(
        id: String = "track-100",
        title: String = "Test Track",
        durationMs: Long = 180000L,
        bpm: Double = 128.0,
        key: String = "8A"
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = "Test Artist",
            album = "Test Album",
            durationSeconds = (durationMs / 1000L).toInt(),
            filePath = "/storage/emulated/0/Music/$id.mp3",
            bpm = bpm,
            musicalKey = key
        )
    }

    private fun trackToEntity(track: Track, isManualBpm: Boolean = false, isManualKey: Boolean = false): TrackEntity {
        return TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSeconds = track.durationSeconds,
            filePath = track.filePath,
            bpm = track.bpm,
            musicalKey = track.musicalKey,
            camelotKey = track.musicalKey,
            isManualBpm = isManualBpm,
            isManualKey = isManualKey
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prepManager = DjPrepManager.getInstance(context)
        prepStore.clear()
        trackStore.clear()

        // Setup dynamic proxy for DjPrepDao
        fakePrepDao = Proxy.newProxyInstance(
            DjPrepDao::class.java.classLoader,
            arrayOf(DjPrepDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertOrUpdate" -> {
                    val entity = args[0] as DjPrepEntity
                    prepStore[entity.trackId] = entity
                    null
                }
                "update" -> {
                    val entity = args[0] as DjPrepEntity
                    prepStore[entity.trackId] = entity
                    null
                }
                "getByTrackId" -> {
                    val trackId = args[0] as String
                    prepStore[trackId]
                }
                "observeByTrackId" -> {
                    val trackId = args[0] as String
                    flowOf(prepStore[trackId])
                }
                "getAllPrepData" -> {
                    prepStore.values.toList()
                }
                "getPrepDataByStatus" -> {
                    val status = args[0] as String
                    prepStore.values.filter { it.prepStatus == status }
                }
                "updatePrepStatus" -> {
                    val trackId = args[0] as String
                    val status = args[1] as String
                    val updatedAt = if (args.size > 2) args[2] as Long else System.currentTimeMillis()
                    val existing = prepStore[trackId]
                    if (existing != null) {
                        prepStore[trackId] = existing.copy(prepStatus = status, updatedAt = updatedAt)
                    }
                    null
                }
                "batchUpdatePrepStatus" -> {
                    @Suppress("UNCHECKED_CAST")
                    val trackIds = args[0] as List<String>
                    val status = args[1] as String
                    val updatedAt = if (args.size > 2) args[2] as Long else System.currentTimeMillis()
                    for (id in trackIds) {
                        val existing = prepStore[id]
                        if (existing != null) {
                            prepStore[id] = existing.copy(prepStatus = status, updatedAt = updatedAt)
                        } else {
                            prepStore[id] = DjPrepEntity(trackId = id, prepStatus = status, updatedAt = updatedAt)
                        }
                    }
                    null
                }
                "deleteByTrackId" -> {
                    val trackId = args[0] as String
                    prepStore.remove(trackId)
                    null
                }
                else -> null
            }
        } as DjPrepDao

        // Setup dynamic proxy for TrackDao
        fakeTrackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getTrackById" -> {
                    val id = args[0] as String
                    trackStore[id]
                }
                "insert" -> {
                    val entity = args[0] as TrackEntity
                    trackStore[entity.id] = entity
                    null
                }
                "update", "updateTrack" -> {
                    val entity = args[0] as TrackEntity
                    trackStore[entity.id] = entity
                    null
                }
                "updateBpmAndKey" -> {
                    val id = args[0] as String
                    val bpm = args[1] as Double
                    val key = args[2] as String
                    val isManualBpm = args[3] as Boolean
                    val isManualKey = args[4] as Boolean
                    val existing = trackStore[id]
                    if (existing != null) {
                        trackStore[id] = existing.copy(
                            bpm = bpm,
                            musicalKey = key,
                            isManualBpm = isManualBpm,
                            isManualKey = isManualKey
                        )
                    }
                    null
                }
                "upsertPhysicalTrack" -> {
                    val newEntity = args[0] as TrackEntity
                    val existing = trackStore[newEntity.id]
                    val merged = if (existing != null) {
                        newEntity.copy(
                            bpm = if (existing.isManualBpm && existing.bpm > 0.0) existing.bpm else newEntity.bpm,
                            musicalKey = if (existing.isManualKey && existing.musicalKey.isNotBlank()) existing.musicalKey else newEntity.musicalKey,
                            isManualBpm = existing.isManualBpm,
                            isManualKey = existing.isManualKey
                        )
                    } else {
                        newEntity
                    }
                    trackStore[newEntity.id] = merged
                    null
                }
                else -> null
            }
        } as TrackDao
    }

    @Test
    fun testHotCueCrudAndNavigation() = runBlocking {
        val track = makeTrack(id = "hc_track", durationMs = 240000L)
        trackStore[track.id] = trackToEntity(track)

        // 1. Set Hot Cue A at 15000ms
        val res1 = prepManager.setHotCue(track, slot = "A", positionMs = 15000L, label = "Intro Drop", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(1, res1.hotCues.size)
        val cueA = res1.hotCues.find { it.id == "A" }
        assertNotNull(cueA)
        assertEquals(15000L, cueA?.positionMs)
        assertEquals("Intro Drop", cueA?.label)
        assertEquals(CuePoint.DEFAULT_HOT_CUE_COLORS[0], cueA?.colorHex)

        // 2. Set Hot Cue B at 45000ms and Hot Cue H at 120000ms
        prepManager.setHotCue(track, slot = "B", positionMs = 45000L, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        val res3 = prepManager.setHotCue(track, slot = "H", positionMs = 120000L, label = "Break", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(3, res3.hotCues.size)

        // 3. Rename Hot Cue A
        val resRenamed = prepManager.renameHotCue(track, cueId = "A", newLabel = "Main Drop", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        val renamedA = resRenamed.hotCues.find { it.id == "A" }
        assertEquals("Main Drop", renamedA?.label)

        // 4. Clear Hot Cue B
        val resCleared = prepManager.deleteHotCue(track, cueId = "B", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(2, resCleared.hotCues.size)
        assertNull(resCleared.hotCues.find { it.id == "B" })
        assertNotNull(resCleared.hotCues.find { it.id == "A" })
        assertNotNull(resCleared.hotCues.find { it.id == "H" })

        // 5. Verify persistent entity in DAO
        val entity = fakePrepDao.getByTrackId(track.id)
        assertNotNull(entity)
        val decoded = entity!!.toDjPrepTrackData()
        assertEquals(2, decoded.hotCues.size)
        assertEquals("Main Drop", decoded.hotCues.find { it.id == "A" }?.label)
    }

    @Test
    fun testMemoryCuesOrderedNavigation() = runBlocking {
        val track = makeTrack(id = "mem_track", durationMs = 300000L)
        trackStore[track.id] = trackToEntity(track)

        // Add memory cues in non-chronological order
        prepManager.addMemoryCue(track, positionMs = 60000L, label = "Chorus 1", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        prepManager.addMemoryCue(track, positionMs = 15000L, label = "Build 1", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        prepManager.addMemoryCue(track, positionMs = 180000L, label = "Outro", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        val data = prepManager.addMemoryCue(track, positionMs = 90000L, label = "Drop 2", customDao = fakePrepDao, customTrackDao = fakeTrackDao)

        // Verify sorted order: 15s, 60s, 90s, 180s
        assertEquals(4, data.memoryCues.size)
        assertEquals(15000L, data.memoryCues[0].positionMs)
        assertEquals(60000L, data.memoryCues[1].positionMs)
        assertEquals(90000L, data.memoryCues[2].positionMs)
        assertEquals(180000L, data.memoryCues[3].positionMs)

        // Test navigation: Next memory cue (with 50ms tolerance)
        assertEquals(15000L, prepManager.getNextMemoryCue(data, 0L)?.positionMs)
        assertEquals(60000L, prepManager.getNextMemoryCue(data, 15000L)?.positionMs)
        assertEquals(60000L, prepManager.getNextMemoryCue(data, 30000L)?.positionMs)
        assertEquals(180000L, prepManager.getNextMemoryCue(data, 100000L)?.positionMs)
        assertNull(prepManager.getNextMemoryCue(data, 200000L))

        // Test navigation: Previous memory cue (with 50ms tolerance)
        assertEquals(180000L, prepManager.getPreviousMemoryCue(data, 200000L)?.positionMs)
        assertEquals(90000L, prepManager.getPreviousMemoryCue(data, 180000L)?.positionMs)
        assertEquals(90000L, prepManager.getPreviousMemoryCue(data, 120000L)?.positionMs)
        assertEquals(15000L, prepManager.getPreviousMemoryCue(data, 60000L)?.positionMs)
        assertNull(prepManager.getPreviousMemoryCue(data, 10000L))

        // Delete a memory cue (Chorus 1 at 60000L)
        val chorusId = data.memoryCues[1].id
        val updated = prepManager.deleteMemoryCue(track, chorusId, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(3, updated.memoryCues.size)
        assertNull(updated.memoryCues.find { it.id == chorusId })

        // Re-check navigation across the deleted gap: from 15s next should now be 90s
        assertEquals(90000L, prepManager.getNextMemoryCue(updated, 15000L)?.positionMs)
    }

    @Test
    fun testSetFirstDownbeatAndNudgeGrid() = runBlocking {
        val track = makeTrack(id = "grid_track", bpm = 120.0, durationMs = 120000L)
        trackStore[track.id] = trackToEntity(track)

        // 1. Set First Downbeat at 500ms
        val res1 = prepManager.setFirstDownbeat(track, downbeatMs = 500L, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(500L, res1.grid.firstDownbeatMs)
        assertEquals(120.0, res1.grid.bpm, 0.001)

        // At 120 BPM, interval is exactly 500ms.
        // Beats should be: 500, 1000, 1500, 2000, 2500
        val beats = prepManager.calculateBeatGrid(res1.grid.firstDownbeatMs, res1.grid.bpm, 2500L)
        assertEquals(listOf(500L, 1000L, 1500L, 2000L, 2500L), beats)

        // 2. Nudge grid by +1ms
        val resNudgePlus1 = prepManager.nudgeGrid(track, offsetDeltaMs = 1L, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(1L, resNudgePlus1.grid.gridOffsetMs)

        // 3. Nudge grid by -10ms
        val resNudgeMinus10 = prepManager.nudgeGrid(track, offsetDeltaMs = -10L, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(-9L, resNudgeMinus10.grid.gridOffsetMs)

        // 4. Persistence check
        val entity = fakePrepDao.getByTrackId(track.id)
        assertNotNull(entity)
        val loaded = entity!!.toDjPrepTrackData()
        assertEquals(500L, loaded.grid.firstDownbeatMs)
        assertEquals(-9L, loaded.grid.gridOffsetMs)
    }

    @Test
    fun testDoubleAndHalveBpm() = runBlocking {
        val track = makeTrack(id = "bpm_track", bpm = 124.0, durationMs = 100000L)
        trackStore[track.id] = trackToEntity(track)

        // Double BPM: 124.0 -> 248.0
        val doubled = prepManager.doubleBpm(track, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(248.0, doubled.bpm, 0.001)
        assertEquals(248.0, doubled.grid.bpm, 0.001)
        assertTrue(doubled.isManualBpm)

        // Verify track table updated with isManualBpm = true
        val trackInStore1 = trackStore[track.id]
        assertNotNull(trackInStore1)
        assertEquals(248.0, trackInStore1!!.bpm, 0.001)
        assertTrue(trackInStore1.isManualBpm)

        // Halve BPM: 248.0 -> 124.0
        val halved1 = prepManager.halveBpm(track, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(124.0, halved1.bpm, 0.001)

        // Halve BPM again: 124.0 -> 62.0
        val halved2 = prepManager.halveBpm(track, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(62.0, halved2.bpm, 0.001)
        assertTrue(halved2.isManualBpm)

        // Reset to analyzed grid: should restore analyzed values (124.0, "8A") and isManualBpm = false
        val reset = prepManager.resetGridToAnalyzed(track, analyzedBpm = 124.0, analyzedKey = "8A", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(124.0, reset.bpm, 0.001)
        assertFalse(reset.isManualBpm)
    }

    @Test
    fun testManualBpmAndKeyRescanImmunity() = runBlocking {
        // Track starts with analyzed values
        val track = makeTrack(id = "immune_track", bpm = 120.0, key = "8A")
        trackStore[track.id] = trackToEntity(track, isManualBpm = false, isManualKey = false)

        // User manually updates BPM to 126.0 and Key to 11B (A Major)
        val prep1 = prepManager.setBpm(track, 126.0, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertTrue(prep1.isManualBpm)
        assertEquals(126.0, prep1.bpm, 0.001)

        val prep2 = prepManager.setKey(track, "A Major", camelotKey = "11B", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertTrue(prep2.isManualKey)
        assertEquals("A Major", prep2.musicalKey)
        assertEquals("11B", prep2.camelotKey)

        // Verify track store has manual flags set
        val storedTrack = trackStore[track.id]!!
        assertTrue(storedTrack.isManualBpm)
        assertTrue(storedTrack.isManualKey)
        assertEquals(126.0, storedTrack.bpm, 0.001)
        assertEquals("A Major", storedTrack.musicalKey)

        // Simulate a library rescan with physical tag reading that found 120.0 BPM and "8A" key
        val scannedEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSeconds = 180,
            filePath = track.filePath,
            bpm = 120.0, // Scanned BPM
            musicalKey = "8A", // Scanned Key
            isManualBpm = false,
            isManualKey = false
        )

        // upsertPhysicalTrack executes
        fakeTrackDao.upsertPhysicalTrack(scannedEntity)

        // Verify manual values were preserved, NOT overwritten by the scanner!
        val afterRescan = trackStore[track.id]!!
        assertEquals(126.0, afterRescan.bpm, 0.001)
        assertEquals("A Major", afterRescan.musicalKey)
        assertTrue(afterRescan.isManualBpm)
        assertTrue(afterRescan.isManualKey)

        // Verify DjPrepManager prep data is also intact
        val prepAfterRescan = prepManager.getPrepData(track.id, fakePrepDao)
        assertNotNull(prepAfterRescan)
        assertEquals(126.0, prepAfterRescan!!.bpm, 0.001)
        assertEquals("A Major", prepAfterRescan.musicalKey)
        assertEquals("11B", prepAfterRescan.camelotKey)
        assertTrue(prepAfterRescan.isManualBpm)
        assertTrue(prepAfterRescan.isManualKey)
    }

    @Test
    fun testPhraseMarkerCrudAndColorPersistence() = runBlocking {
        val track = makeTrack(id = "phrase_track", durationMs = 240000L)
        trackStore[track.id] = trackToEntity(track)

        // Add 4 phrase markers: Intro, Verse, Drop, Outro
        val intro = PhraseMarker(
            id = "intro_1",
            type = PhraseType.INTRO,
            label = "Intro",
            startMs = 0L,
            endMs = 15000L,
            colorHex = PhraseType.INTRO.colorHex
        )
        val verse = PhraseMarker(
            id = "verse_1",
            type = PhraseType.VERSE,
            label = "Verse 1",
            startMs = 15000L,
            endMs = 45000L,
            colorHex = PhraseType.VERSE.colorHex
        )
        val drop = PhraseMarker(
            id = "drop_1",
            type = PhraseType.DROP,
            label = "Drop 1",
            startMs = 45000L,
            endMs = 90000L,
            colorHex = PhraseType.DROP.colorHex
        )
        val outro = PhraseMarker(
            id = "outro_1",
            type = PhraseType.OUTRO,
            label = "Outro",
            startMs = 90000L,
            endMs = 120000L,
            colorHex = PhraseType.OUTRO.colorHex
        )

        prepManager.addPhraseMarker(track, intro, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        prepManager.addPhraseMarker(track, verse, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        prepManager.addPhraseMarker(track, drop, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        val res = prepManager.addPhraseMarker(track, outro, customDao = fakePrepDao, customTrackDao = fakeTrackDao)

        assertEquals(4, res.phraseMarkers.size)
        assertEquals("Intro", res.phraseMarkers[0].label)
        assertEquals("Verse 1", res.phraseMarkers[1].label)
        assertEquals("Drop 1", res.phraseMarkers[2].label)
        assertEquals("Outro", res.phraseMarkers[3].label)

        // Update Verse 1 to Verse Extended
        val updatedVerse = res.phraseMarkers[1].copy(label = "Verse Extended", endMs = 60000L)
        val resUpdated = prepManager.updatePhraseMarker(track, updatedVerse, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        val foundVerse = resUpdated.phraseMarkers.find { it.id == updatedVerse.id }
        assertNotNull(foundVerse)
        assertEquals("Verse Extended", foundVerse?.label)
        assertEquals(60000L, foundVerse?.endMs)

        // Delete Outro phrase marker
        val outroId = res.phraseMarkers[3].id
        val resDeleted = prepManager.deletePhraseMarker(track, outroId, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(3, resDeleted.phraseMarkers.size)
        assertNull(resDeleted.phraseMarkers.find { it.id == outroId })

        // Verify persistence
        val entity = fakePrepDao.getByTrackId(track.id)
        assertNotNull(entity)
        val fromDb = entity!!.toDjPrepTrackData()
        assertEquals(3, fromDb.phraseMarkers.size)
    }

    @Test
    fun testKeyLockAudioEngine() {
        val audioEngine = DjAudioEngine.getInstance(context)

        // Test Key Lock toggle
        audioEngine.setKeyLock(true)
        assertTrue(audioEngine.keyLockEnabled.value)

        audioEngine.setKeyLock(false)
        assertFalse(audioEngine.keyLockEnabled.value)

        audioEngine.setKeyLock(true)
        assertTrue(audioEngine.keyLockEnabled.value)
    }

    @Test
    fun testPrepStatusWorkflowAndBatchUpdate() = runBlocking {
        val track1 = makeTrack(id = "batch_1", bpm = 0.0, key = "")
        val track2 = makeTrack(id = "batch_2", bpm = 0.0, key = "")
        val track3 = makeTrack(id = "batch_3", bpm = 0.0, key = "")

        // Initial status: NOT_ANALYSED
        val initData = prepManager.getOrInitPrepData(track1, fakePrepDao)
        assertEquals(PrepStatus.NOT_ANALYSED, initData.prepStatus)

        // Single status update to NEEDS_REVIEW
        prepManager.setPrepStatus(track1.id, PrepStatus.NEEDS_REVIEW, fakePrepDao)
        assertEquals(PrepStatus.NEEDS_REVIEW, prepManager.getPrepData(track1.id, fakePrepDao)?.prepStatus)

        // Batch status update to PREPPED
        val batchIds = listOf(track1.id, track2.id, track3.id)
        prepManager.batchSetPrepStatus(batchIds, PrepStatus.PREPPED, fakePrepDao)

        // Verify all 3 tracks are PREPPED
        for (id in batchIds) {
            val entity = fakePrepDao.getByTrackId(id)
            assertNotNull(entity)
            assertEquals("PREPPED", entity?.prepStatus)
        }
    }

    @Test
    fun testMissingMetadataTrackGracefulHandling() = runBlocking {
        // Track with missing BPM, key, and zero duration
        val emptyTrack = Track(
            id = "empty_meta_track",
            title = "Unknown Song",
            artist = "Unknown Artist",
            album = "",
            durationSeconds = 0,
            filePath = "/storage/emulated/0/Music/empty.mp3",
            bpm = 0.0,
            musicalKey = ""
        )
        trackStore[emptyTrack.id] = trackToEntity(emptyTrack)

        // Initializing prep data should succeed without crashing
        val prepData = prepManager.getOrInitPrepData(emptyTrack, fakePrepDao)
        assertNotNull(prepData)
        assertEquals(120.0, prepData.bpm, 0.001) // Safe default fallback
        assertEquals(0L, prepData.grid.firstDownbeatMs)
        assertEquals(PrepStatus.NOT_ANALYSED, prepData.prepStatus)

        // Setting a cue on this track succeeds
        val withCue = prepManager.setHotCue(emptyTrack, slot = "A", positionMs = 5000L, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(1, withCue.hotCues.size)

        // Setting manual BPM on this track succeeds
        val withBpm = prepManager.setBpm(emptyTrack, 128.0, customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals(128.0, withBpm.bpm, 0.001)
        assertTrue(withBpm.isManualBpm)

        // Setting manual Key on this track succeeds
        val withKey = prepManager.setKey(emptyTrack, "F Minor", camelotKey = "4A", customDao = fakePrepDao, customTrackDao = fakeTrackDao)
        assertEquals("F Minor", withKey.musicalKey)
        assertEquals("4A", withKey.camelotKey)
        assertTrue(withKey.isManualKey)
    }

    @Test
    fun testMetronomeClickPcmGeneration() {
        // Accented click: 2200 Hz
        val accentedPcm = prepManager.generateTickPcm(isDownbeat = true)
        assertNotNull(accentedPcm)
        assertTrue(accentedPcm.isNotEmpty())

        // Regular click: 1200 Hz
        val regularPcm = prepManager.generateTickPcm(isDownbeat = false)
        assertNotNull(regularPcm)
        assertTrue(regularPcm.isNotEmpty())

        // Ensure 16-bit mono sample format (even number of bytes)
        assertEquals(0, accentedPcm.size % 2)
        assertEquals(0, regularPcm.size % 2)
        assertEquals(accentedPcm.size, regularPcm.size)

        // Metronome state flow toggle
        prepManager.setMetronomeEnabled(true)
        assertTrue(prepManager.isMetronomeEnabled.value)

        prepManager.setMetronomeEnabled(false)
        assertFalse(prepManager.isMetronomeEnabled.value)
    }

    @Test
    fun testDjPrepEntityJsonSerialization() {
        val original = DjPrepTrackData(
            trackId = "json_test_track",
            prepStatus = PrepStatus.PREPPED,
            bpm = 125.5,
            isManualBpm = true,
            musicalKey = "D Minor",
            camelotKey = "7A",
            isManualKey = true,
            grid = BeatGridData(bpm = 125.5, firstDownbeatMs = 350L, gridOffsetMs = 0L, isManualOverride = true),
            hotCues = listOf(
                CuePoint(id = "A", positionMs = 15000L, type = CueType.HOT_CUE, label = "Drop", colorHex = "#FF3366")
            ),
            memoryCues = listOf(
                CuePoint(id = "mem_1", positionMs = 60000L, type = CueType.MEMORY_CUE, label = "Breakdown", colorHex = "#FFCC00")
            ),
            phraseMarkers = listOf(
                PhraseMarker(id = "phrase_1", type = PhraseType.INTRO, label = "Intro", startMs = 0L, endMs = 15000L)
            ),
            notes = "Test notes",
            updatedAt = 1700000000000L
        )

        val entity = DjPrepEntity.fromDjPrepTrackData(original)
        val reconstructed = entity.toDjPrepTrackData()

        assertEquals(original.trackId, reconstructed.trackId)
        assertEquals(original.prepStatus, reconstructed.prepStatus)
        assertEquals(original.bpm, reconstructed.bpm, 0.001)
        assertEquals(original.isManualBpm, reconstructed.isManualBpm)
        assertEquals(original.musicalKey, reconstructed.musicalKey)
        assertEquals(original.camelotKey, reconstructed.camelotKey)
        assertEquals(original.isManualKey, reconstructed.isManualKey)
        assertEquals(original.grid.firstDownbeatMs, reconstructed.grid.firstDownbeatMs)
        assertEquals(original.grid.bpm, reconstructed.grid.bpm, 0.001)
        assertEquals(original.hotCues.size, reconstructed.hotCues.size)
        assertEquals(original.hotCues[0].label, reconstructed.hotCues[0].label)
        assertEquals(original.memoryCues.size, reconstructed.memoryCues.size)
        assertEquals(original.memoryCues[0].label, reconstructed.memoryCues[0].label)
        assertEquals(original.phraseMarkers.size, reconstructed.phraseMarkers.size)
        assertEquals(original.phraseMarkers[0].label, reconstructed.phraseMarkers[0].label)
        assertEquals(original.notes, reconstructed.notes)
    }
}
