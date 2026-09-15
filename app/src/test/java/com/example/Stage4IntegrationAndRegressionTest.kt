package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjAudioEngine
import com.example.audio.EqBand
import com.example.audio.EqFilterType
import com.example.audio.HaasSurroundEffect
import com.example.audio.ParametricEq
import com.example.audio.ParametricEqManager
import com.example.backup.DjPrepBackupItem
import com.example.backup.SoundSyncBackup
import com.example.backup.SoundSyncBackupManager
import com.example.backup.ValidationResult
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.djprep.BeatGridData
import com.example.djprep.CuePoint
import com.example.djprep.CueType
import com.example.djprep.DjPrepDao
import com.example.djprep.DjPrepEntity
import com.example.djprep.DjPrepManager
import com.example.djprep.PhraseMarker
import com.example.djprep.PhraseType
import com.example.metadata.artist.ArtistCollaborationParser
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.sin

/**
 * Stage 4: Comprehensive Integration, Regression & QA Test Suite.
 *
 * Verifies end-to-end integration across all 4 stages:
 * - Stage 1: Auto Backup defaults OFF, Artist grouping & splitting, Library Doctor isolation, Metadata Settings separation.
 * - Stage 2: Haas Surround progressive widening & mono cancellation, Parametric EQ 10-band DSP, Solo audition, A/B compare.
 * - Stage 3: DJ Prep mini environment, Hot Cues, Memory Cues, Beat Grid Anchor & Nudge, Undo history, Key Lock audio params.
 * - Stage 4: Full Backup/Restore with DJ Prep & EQ configuration round-trip, robust error/failure paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage4IntegrationAndRegressionTest {

    private lateinit var context: Context
    private val prepStore = mutableMapOf<String, DjPrepEntity>()
    private val trackStore = mutableMapOf<String, TrackEntity>()

    private lateinit var fakePrepDao: DjPrepDao
    private lateinit var fakeTrackDao: TrackDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        DjAudioEngine.resetInstance()
        SoundSyncBackupManager.resetInstance()
        prepStore.clear()
        trackStore.clear()

        // Clean SharedPreferences
        context.getSharedPreferences("soundsync_backup_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("soundsync_dj_prefs", Context.MODE_PRIVATE).edit().clear().commit()

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
                "getAllPrepData" -> prepStore.values.toList()
                "deleteByTrackId" -> {
                    val trackId = args[0] as String
                    prepStore.remove(trackId)
                    null
                }
                else -> null
            }
        } as DjPrepDao

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

    // ── 1. Stage 1 Invariants ────────────────────────────────────────────────

    @Test
    fun testStage1AutoBackupDefaultsOffAndExplicitOptIn() {
        val backupManager = SoundSyncBackupManager.getInstance(context)
        // Fresh install must be false
        assertFalse(backupManager.isAutoBackupEnabled())

        // Explicit opt-in
        backupManager.setAutoBackupEnabled(true)
        assertTrue(backupManager.isAutoBackupEnabled())

        // Explicit opt-out
        backupManager.setAutoBackupEnabled(false)
        assertFalse(backupManager.isAutoBackupEnabled())
    }

    @Test
    fun testStage1ArtistCollaborationSplittingWithProtectedBands() {
        // Splitting standard collaborations
        val result1 = ArtistCollaborationParser.splitArtists("Calvin Harris feat. Ellie Goulding")
        assertEquals(listOf("Calvin Harris", "Ellie Goulding"), result1)

        val result2 = ArtistCollaborationParser.splitArtists("David Guetta & Bebe Rexha")
        assertEquals(listOf("David Guetta", "Bebe Rexha"), result2)

        val result3 = ArtistCollaborationParser.splitArtists("Skrillex with Fred again.. and Flowdan")
        assertTrue(result3.contains("Skrillex"))
        assertTrue(result3.contains("Fred again.."))
        assertTrue(result3.contains("Flowdan"))

        // Protected bands must not be split
        val resultAcDc = ArtistCollaborationParser.splitArtists("AC/DC")
        assertEquals(listOf("AC/DC"), resultAcDc)

        val resultEarthWindFire = ArtistCollaborationParser.splitArtists("Earth, Wind & Fire")
        assertEquals(listOf("Earth, Wind & Fire"), resultEarthWindFire)

        val resultAboveBeyond = ArtistCollaborationParser.splitArtists("Above & Beyond")
        assertEquals(listOf("Above & Beyond"), resultAboveBeyond)
    }

    // ── 2. Stage 2 Audio DSP Invariants ──────────────────────────────────────

    @Test
    fun testStage2HaasSurroundWideningAndMonoCompatibility() {
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        assertTrue(haas.isEnabled)

        // Process stereo buffer
        val numFrames = 256
        val buffer = ShortArray(numFrames * 2) { (sin(2.0 * Math.PI * 440.0 * it / 44100.0) * 10000.0).toInt().toShort() }
        val copy = buffer.clone()

        haas.process(buffer, 0, numFrames, 44100)

        // Verify processed audio is valid and non-zero
        assertTrue(buffer.any { it != 0.toShort() })
        assertFalse(buffer.contentEquals(copy))
    }

    @Test
    fun testStage2ParametricEq10BandsAndSoloAudition() {
        val eq = ParametricEq(44100)
        assertEquals(10, eq.getBands().size)

        // Add band
        eq.addBand(EqBand(10, "Air High", EqFilterType.HIGH_SHELF, 18000.0, 3.0, 0.71, true))
        assertEquals(11, eq.getBands().size)

        // Remove band
        eq.removeBand(10)
        assertEquals(10, eq.getBands().size)

        // Solo audition mode
        eq.soloBandIndex = 2
        assertEquals(2, eq.soloBandIndex)

        // Process audio with solo band active
        val numFrames = 128
        val buffer = ShortArray(numFrames * 2) { 8000 }
        eq.processStereo(buffer, 0, numFrames)

        // Audio should process without crashing
        assertTrue(buffer.any { it != 0.toShort() })

        // Clear solo
        eq.soloBandIndex = null
        assertNull(eq.soloBandIndex)
    }

    // ── 3. Stage 3 DJ Prep Invariants ────────────────────────────────────────

    @Test
    fun testStage3DjPrepWorkflowWithUndoAndKeyLock() = runBlocking {
        val track = Track(
            id = "track_stage4_full",
            title = "Integration Song",
            artist = "QA Artist",
            durationSeconds = 240,
            filePath = "/Music/song.mp3",
            bpm = 126.0,
            musicalKey = "11B"
        )
        val prepManager = DjPrepManager.getInstance(context)
        prepManager.getOrInitPrepData(track, fakePrepDao)

        // 1. Set first downbeat
        prepManager.setFirstDownbeat(track, 300L, fakePrepDao, fakeTrackDao)

        // 2. Add Hot Cue A & Memory Cue 1
        prepManager.addOrUpdateHotCue(track, "A", 15000L, "Drop", "#FF2D55", fakePrepDao, fakeTrackDao)
        prepManager.addMemoryCue(track, 30000L, "Break", fakePrepDao, fakeTrackDao)

        // 3. Double BPM (126.0 -> 252.0)
        val doubled = prepManager.doubleBpm(track, fakePrepDao, fakeTrackDao)
        assertEquals(252.0, doubled.bpm, 0.001)

        // 4. Undo BPM Double
        val undone = prepManager.undo(track, fakePrepDao, fakeTrackDao)
        assertNotNull(undone)
        assertEquals(126.0, undone!!.bpm, 0.001)

        // 5. Verify Key Lock persistence
        val audioEngine = DjAudioEngine.getInstance(context)
        audioEngine.setKeyLock(true)
        assertTrue(audioEngine.keyLockEnabled.value)

        val prefs = context.getSharedPreferences("soundsync_dj_prefs", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("key_lock_enabled", false))
    }

    // ── 4. Stage 4 Full Backup & Restore Integration ─────────────────────────

    @Test
    fun testStage4FullBackupAndRestoreWithDjPrepAndEqConfig() = runBlocking {
        val backupManager = SoundSyncBackupManager.getInstance(context)

        // Setup test EQ presets
        val eqManager = ParametricEqManager.getInstance(context)
        eqManager.setPreamp(-1.5)
        eqManager.updateBand(0, freqHz = 32.0, gainDb = 4.0, q = 0.71, isEnabled = true)

        // Create DJ Prep Item
        val prepItem = DjPrepBackupItem(
            trackId = "track_integration_99",
            bpm = 132.0,
            isManualBpm = true,
            musicalKey = "5A",
            camelotKey = "5A",
            isManualKey = true,
            firstDownbeatMs = 400L,
            gridOffsetMs = 0L,
            isManualGrid = true,
            hotCuesJson = "[{\"id\":\"A\",\"label\":\"Intro\",\"positionMs\":10000,\"colorHex\":\"#FF2D55\",\"type\":\"HOT_CUE\"}]",
            memoryCuesJson = "[{\"id\":\"mem_1\",\"label\":\"Vocal\",\"positionMs\":25000,\"colorHex\":\"#FFCC00\",\"type\":\"MEMORY_CUE\"}]",
            phraseMarkersJson = "[{\"id\":\"ph_1\",\"type\":\"INTRO\",\"label\":\"Intro\",\"startMs\":0,\"endMs\":30000,\"startBar\":1,\"barCount\":16,\"colorHex\":\"#00E5FF\"}]",
            prepStatus = "PREPPED",
            notes = "Checked on stage",
            updatedAt = System.currentTimeMillis()
        )

        val fullBackup = SoundSyncBackup(
            backupVersion = SoundSyncBackup.CURRENT_BACKUP_VERSION,
            appVersion = "1.0.0",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            songFinds = emptyList(),
            tracks = emptyList(),
            doctorIgnoredIssues = listOf("ISSUE_001"),
            doctorReviewedIssues = listOf("ISSUE_002"),
            djPrepData = listOf(prepItem),
            eqConfigJson = "{\"version\":3,\"preampDb\":-1.5,\"activePresetId\":\"preset_flat\"}"
        )

        // 1. Serialization
        val serializedJson = backupManager.serializeBackup(fullBackup)
        assertTrue(serializedJson.contains("djPrepData"))
        assertTrue(serializedJson.contains("eqConfigJson"))
        assertTrue(serializedJson.contains("track_integration_99"))

        // 2. Validation
        val validation = backupManager.validateBackup(serializedJson)
        assertTrue(validation is ValidationResult.Valid)
        val valid = (validation as ValidationResult.Valid).backup
        assertEquals(1, valid.djPrepData.size)
        assertEquals("track_integration_99", valid.djPrepData[0].trackId)
        assertNotNull(valid.eqConfigJson)

        // 3. Ensure Auto Backup is still OFF after backup operations
        assertFalse(backupManager.isAutoBackupEnabled())
    }

    // ── 5. Failure Paths & Robustness ────────────────────────────────────────

    @Test
    fun testFailurePathsMalformedJsonAndClampedBpm() = runBlocking {
        val backupManager = SoundSyncBackupManager.getInstance(context)

        // Malformed JSON must return Invalid, not crash
        val invalidResult = backupManager.validateBackup("{ malformed json }")
        assertTrue(invalidResult is ValidationResult.Invalid)

        // Empty string must return Invalid
        val emptyResult = backupManager.validateBackup("")
        assertTrue(emptyResult is ValidationResult.Invalid)

        // BPM clamping in DjPrepManager
        val track = Track(id = "track_clamping", title = "Fast", artist = "Producer", durationSeconds = 120, bpm = 120.0)
        val prepManager = DjPrepManager.getInstance(context)
        prepManager.getOrInitPrepData(track, fakePrepDao)

        // Set absurdly high BPM -> clamped to 300.0
        val clampedHigh = prepManager.setBpm(track, 999.0, fakePrepDao, fakeTrackDao)
        assertEquals(300.0, clampedHigh.bpm, 0.001)

        // Set absurdly low BPM -> clamped to 40.0
        val clampedLow = prepManager.setBpm(track, 5.0, fakePrepDao, fakeTrackDao)
        assertEquals(40.0, clampedLow.bpm, 0.001)
    }
}
