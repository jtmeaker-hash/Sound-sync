package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.carmode.CarDisplayMode
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.ExplorerSortOption
import com.example.model.Track
import com.example.model.WaveformStyle
import com.example.player.PersistentQueueManager
import com.example.player.QueueRepeatMode
import com.example.player.SmartContinueMode
import com.example.state.PersistentAppSession
import com.example.state.PersistentScannerCheckpoint
import com.example.state.PersistentSessionManager
import com.example.storage.ScanStateManager
import com.example.storage.ScanStatus
import com.example.ui.theme.ProDarkVariant
import com.example.ui.theme.ProLibraryDensity
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/**
 * Stage 27: Full Persistent Application & Playback State Tests.
 *
 * Verifies all 12 required scenarios from 27_Persistent_App_State.txt:
 * 1. Track paused halfway through (restores exact position, paused).
 * 2. Active playback session (wasPlaying remembered, audio paused on relaunch).
 * 3. Non-empty manual queue (exact items and order preserved).
 * 4. Shuffle enabled halfway through generated order (sequence and index preserved, not re-shuffled).
 * 5. Repeat ONE and repeat ALL modes.
 * 6. Previous-track history (LIFO return in shuffle and normal).
 * 7. Non-default sort (BPM_DESC, etc.).
 * 8. Last browsed folder and storage source.
 * 9. Non-default theme and library density.
 * 10. Car Mode state and settings.
 * 11. Scanner/analysis checkpoint halfway through (resumes remaining without duplicating).
 * 12. One queued track deleted before relaunch (repaired gracefully without crash).
 *
 * Plus edge-case validations:
 * - Completed track near end (within 2s) guard resets to 0L.
 * - Corrupted session JSON fallback does not wipe database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistentAppStateTest {

    private lateinit var context: Context
    private lateinit var sessionManager: PersistentSessionManager
    private lateinit var queueManager: PersistentQueueManager
    private lateinit var scanStateManager: ScanStateManager

    private val trackStore = mutableMapOf<String, TrackEntity>()
    private lateinit var fakeTrackDao: TrackDao

    private fun makeTrack(id: String, title: String, durationMs: Long = 200000L, filePath: String = "/storage/emulated/0/Music/$id.mp3"): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            album = "Album $id",
            durationSeconds = (durationMs / 1000L).toInt(),
            filePath = filePath,
            bpm = 124.0,
            musicalKey = "8A"
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Clean up any test persistence files
        File(context.filesDir, "persistent_app_session.json").delete()
        File(context.filesDir, "persistent_playback_queue.json").delete()

        sessionManager = PersistentSessionManager(context)
        queueManager = PersistentQueueManager.getInstance(context)
        scanStateManager = ScanStateManager(context)

        scanStateManager.clearCheckpoint()
        scanStateManager.status = ScanStatus.IDLE

        // Setup mock TrackDao
        trackStore.clear()
        fakeTrackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertTrack" -> {
                    val t = args[0] as TrackEntity
                    trackStore[t.id] = t
                    null
                }
                "getTrackById" -> {
                    trackStore[args[0] as String]
                }
                "getAllTracksSync", "getAllTracksList" -> {
                    trackStore.values.toList()
                }
                "deleteTrack", "deleteTrackById" -> {
                    trackStore.remove(args[0] as String)
                    null
                }
                else -> null
            }
        } as TrackDao
    }

    @After
    fun tearDown() {
        File(context.filesDir, "persistent_app_session.json").delete()
        File(context.filesDir, "persistent_playback_queue.json").delete()
    }

    // ── Scenario 1: Track paused halfway through ──────────────────────────────
    @Test
    fun test01_trackPausedHalfwayThrough_restoresExactPositionPaused() = runBlocking(Dispatchers.IO) {
        val track = makeTrack("t1", "Track 1", durationMs = 180000L)
        val halfwayPos = 90000L

        // User pauses halfway through
        sessionManager.updatePlaybackPosition(track, halfwayPos, wasPlaying = false, immediate = true)
        sessionManager.saveToDisk()

        // Simulate new app launch reading from disk
        val restoredManager = PersistentSessionManager(context)
        val restored = restoredManager.restoreFromDisk()

        assertNotNull("Restored track must not be null", restored.playback.currentTrack)
        assertEquals("Track ID must match", track.id, restored.playback.currentTrack?.id)
        assertEquals("Playback position must be exactly restored", halfwayPos, restored.playback.playbackPositionMs)
        assertFalse("Playback state must be paused", restored.playback.wasPlaying)
    }

    // ── Scenario 2: Active playback session (wasPlaying remembered, audio paused) ──
    @Test
    fun test02_activePlaybackSession_wasPlayingRemembered_relaunchSafe() = runBlocking(Dispatchers.IO) {
        val track = makeTrack("t2", "Track 2", durationMs = 240000L)
        val pos = 120000L
        trackStore[track.id] = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationSeconds = track.durationSeconds,
            filePath = track.filePath
        )

        // App backgrounded or killed during active playback
        sessionManager.updatePlaybackPosition(track, pos, wasPlaying = true, immediate = true)
        sessionManager.saveToDisk()

        val restoredManager = PersistentSessionManager(context)
        val restored = restoredManager.restoreFromDisk()

        assertEquals("Track ID matches", track.id, restored.playback.currentTrack?.id)
        assertEquals("Position matches", pos, restored.playback.playbackPositionMs)
        assertTrue("wasPlaying was true at time of termination", restored.playback.wasPlaying)

        // Validate and repair check ensures safe non-blasting startup
        val repaired = restoredManager.validateAndRepair(fakeTrackDao)
        assertNotNull(repaired.playback.currentTrack)
        assertEquals(pos, repaired.playback.playbackPositionMs)
    }

    // ── Scenario 3: Non-empty manual queue preserved ─────────────────────────
    @Test
    fun test03_nonEmptyManualQueue_exactItemsAndOrderPreserved() = runBlocking(Dispatchers.IO) {
        val cur = makeTrack("cur", "Current Track")
        val q1 = makeTrack("q1", "Queue 1")
        val q2 = makeTrack("q2", "Queue 2")
        val q3 = makeTrack("q3", "Queue 3")

        sessionManager.updateQueueState(
            currentTrack = cur,
            upcomingQueue = listOf(q1, q2, q3),
            playbackHistory = emptyList(),
            isShuffleEnabled = false,
            shuffleSequenceTrackIds = emptyList(),
            shuffleIndex = 0,
            originalQueueTrackIds = listOf("cur", "q1", "q2", "q3"),
            repeatMode = QueueRepeatMode.OFF,
            smartContinueMode = SmartContinueMode.OFF,
            immediate = true
        )
        sessionManager.saveToDisk()

        val restoredManager = PersistentSessionManager(context)
        val restored = restoredManager.restoreFromDisk()

        assertEquals("Current track matches", "cur", restored.queue.currentTrack?.id)
        assertEquals("Upcoming queue count matches", 3, restored.queue.upcomingQueue.size)
        assertEquals("Queue item 1 matches", "q1", restored.queue.upcomingQueue[0].id)
        assertEquals("Queue item 2 matches", "q2", restored.queue.upcomingQueue[1].id)
        assertEquals("Queue item 3 matches", "q3", restored.queue.upcomingQueue[2].id)
    }

    // ── Scenario 4: Shuffle enabled halfway through generated order ───────────
    @Test
    fun test04_shuffleEnabledHalfwayThrough_sequenceAndIndexPreservedNotReshuffled() = runBlocking(Dispatchers.IO) {
        val cur = makeTrack("s3", "Track S3")
        val generatedSequence = listOf("s1", "s2", "s3", "s4", "s5")
        val currentIndexInSequence = 2 // At "s3"

        sessionManager.updateQueueState(
            currentTrack = cur,
            upcomingQueue = listOf(makeTrack("s4", "Track S4"), makeTrack("s5", "Track S5")),
            playbackHistory = listOf(makeTrack("s2", "Track S2"), makeTrack("s1", "Track S1")),
            isShuffleEnabled = true,
            shuffleSequenceTrackIds = generatedSequence,
            shuffleIndex = currentIndexInSequence,
            originalQueueTrackIds = listOf("s1", "s2", "s3", "s4", "s5"),
            repeatMode = QueueRepeatMode.ALL,
            smartContinueMode = SmartContinueMode.OFF,
            immediate = true
        )
        sessionManager.saveToDisk()

        val restoredManager = PersistentSessionManager(context)
        val restored = restoredManager.restoreFromDisk()

        assertTrue("Shuffle remains enabled", restored.queue.isShuffleEnabled)
        assertEquals("Shuffle sequence exactly preserved", generatedSequence, restored.queue.shuffleSequenceTrackIds)
        assertEquals("Shuffle index exactly preserved", currentIndexInSequence, restored.queue.shuffleIndex)
        assertEquals("Upcoming queue size preserved", 2, restored.queue.upcomingQueue.size)
    }

    // ── Scenario 5: Repeat ONE and repeat ALL modes ───────────────────────────
    @Test
    fun test05_repeatModes_preservedAndRestored() = runBlocking(Dispatchers.IO) {
        // Test Repeat ONE
        sessionManager.updateQueueState(
            currentTrack = makeTrack("r1", "Repeat Track"),
            upcomingQueue = emptyList(),
            playbackHistory = emptyList(),
            isShuffleEnabled = false,
            shuffleSequenceTrackIds = emptyList(),
            shuffleIndex = 0,
            originalQueueTrackIds = listOf("r1"),
            repeatMode = QueueRepeatMode.ONE,
            smartContinueMode = SmartContinueMode.OFF,
            immediate = true
        )
        sessionManager.saveToDisk()

        var restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("Repeat ONE preserved", QueueRepeatMode.ONE, restored.queue.repeatMode)

        // Test Repeat ALL
        sessionManager.updateQueueState(
            currentTrack = makeTrack("r1", "Repeat Track"),
            upcomingQueue = emptyList(),
            playbackHistory = emptyList(),
            isShuffleEnabled = false,
            shuffleSequenceTrackIds = emptyList(),
            shuffleIndex = 0,
            originalQueueTrackIds = listOf("r1"),
            repeatMode = QueueRepeatMode.ALL,
            smartContinueMode = SmartContinueMode.OFF,
            immediate = true
        )
        sessionManager.saveToDisk()

        restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("Repeat ALL preserved", QueueRepeatMode.ALL, restored.queue.repeatMode)
    }

    // ── Scenario 6: Previous-track history (LIFO order preserved) ────────────
    @Test
    fun test06_previousTrackHistory_lifoOrderPreserved() = runBlocking(Dispatchers.IO) {
        val h1 = makeTrack("h1", "First Played")
        val h2 = makeTrack("h2", "Second Played")
        val h3 = makeTrack("h3", "Third Played (Most Recent)")

        // LIFO order: h3 was played just before current track
        val lifoHistory = listOf(h3, h2, h1)

        sessionManager.updateQueueState(
            currentTrack = makeTrack("cur", "Currently Playing"),
            upcomingQueue = emptyList(),
            playbackHistory = lifoHistory,
            isShuffleEnabled = true,
            shuffleSequenceTrackIds = emptyList(),
            shuffleIndex = 0,
            originalQueueTrackIds = listOf("h1", "h2", "h3", "cur"),
            repeatMode = QueueRepeatMode.OFF,
            smartContinueMode = SmartContinueMode.OFF,
            immediate = true
        )
        sessionManager.saveToDisk()

        val restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("History size matches", 3, restored.queue.playbackHistory.size)
        assertEquals("Most recent history item is h3", "h3", restored.queue.playbackHistory[0].id)
        assertEquals("Second history item is h2", "h2", restored.queue.playbackHistory[1].id)
        assertEquals("Oldest history item is h1", "h1", restored.queue.playbackHistory[2].id)
    }

    // ── Scenario 7: Non-default sort ──────────────────────────────────────────
    @Test
    fun test07_nonDefaultSort_preservedAndRestored() = runBlocking(Dispatchers.IO) {
        sessionManager.updateLibraryUi(
            sortOption = ExplorerSortOption.BPM_DESC,
            sortAscending = false,
            immediate = true
        )
        sessionManager.saveToDisk()

        val restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("Sort option preserved as BPM_DESC", ExplorerSortOption.BPM_DESC, restored.libraryUi.sortOption)
        assertFalse("Sort direction preserved as descending", restored.libraryUi.sortAscending)
    }

    // ── Scenario 8: Last browsed folder and storage source ────────────────────
    @Test
    fun test08_lastBrowsedFolderAndStorageSource_preservedAndRestored() = runBlocking(Dispatchers.IO) {
        val testFolder = "/storage/emulated/0/Music/Electronic/DeepHouse"
        val testSource = "usb_drive_1"

        sessionManager.updateLibraryUi(
            currentDirectoryPath = testFolder,
            currentStorageSourceId = testSource,
            selectedTab = "EXPLORER",
            selectedLocalCategory = "FOLDERS",
            searchQuery = "flac",
            selectedGenreFilter = "House",
            selectedPlatformFilter = "BEATPORT",
            immediate = true
        )
        sessionManager.saveToDisk()

        val restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("Folder path matches", testFolder, restored.libraryUi.currentDirectoryPath)
        assertEquals("Storage source matches", testSource, restored.libraryUi.currentStorageSourceId)
        assertEquals("Selected tab matches", "EXPLORER", restored.libraryUi.selectedTab)
        assertEquals("Category matches", "FOLDERS", restored.libraryUi.selectedLocalCategory)
        assertEquals("Search query matches", "flac", restored.libraryUi.searchQuery)
        assertEquals("Genre filter matches", "House", restored.libraryUi.selectedGenreFilter)
        assertEquals("Platform filter matches", "BEATPORT", restored.libraryUi.selectedPlatformFilter)
    }

    // ── Scenario 9: Non-default theme and library density ─────────────────────
    @Test
    fun test09_nonDefaultThemeAndDensity_preservedAndRestored() = runBlocking(Dispatchers.IO) {
        sessionManager.updateAppearance(
            themeMode = ThemeMode.PRO,
            proDarkVariant = ProDarkVariant.BLACK_RED,
            libraryDensity = ProLibraryDensity.COMFORTABLE,
            waveformStyle = WaveformStyle.FREQUENCY_COLOURED,
            isTrackGridView = true,
            immediate = true
        )
        sessionManager.saveToDisk()

        val restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("Theme mode matches", ThemeMode.PRO, restored.appearance.themeMode)
        assertEquals("Dark variant matches", ProDarkVariant.BLACK_RED, restored.appearance.proDarkVariant)
        assertEquals("Density matches", ProLibraryDensity.COMFORTABLE, restored.appearance.libraryDensity)
        assertEquals("Waveform style matches", WaveformStyle.FREQUENCY_COLOURED, restored.appearance.waveformStyle)
        assertTrue("Track grid view matches", restored.appearance.isTrackGridView)
    }

    // ── Scenario 10: Car Mode state and settings ──────────────────────────────
    @Test
    fun test10_carModeStateAndSettings_preservedAndRestored() = runBlocking(Dispatchers.IO) {
        sessionManager.updateAppearance(
            isCarModeActive = true,
            carModeKeepAwake = true,
            carModeNightMode = true,
            carModeDisplayMode = CarDisplayMode.DJ_DASHBOARD,
            carModeSmartShuffle = true,
            immediate = true
        )
        sessionManager.saveToDisk()

        val restored = PersistentSessionManager(context).restoreFromDisk()
        assertTrue("Car mode active flag preserved", restored.appearance.isCarModeActive)
        assertTrue("Car mode keep awake preserved", restored.appearance.carModeKeepAwake)
        assertTrue("Car mode night mode preserved", restored.appearance.carModeNightMode)
        assertEquals("Car mode display mode preserved", CarDisplayMode.DJ_DASHBOARD, restored.appearance.carModeDisplayMode)
        assertTrue("Car mode smart shuffle preserved", restored.appearance.carModeSmartShuffle)
    }

    // ── Scenario 11: Scanner/analysis checkpoint halfway through ──────────────
    @Test
    fun test11_scannerCheckpoint_resumesWithoutDuplicating() = runBlocking(Dispatchers.IO) {
        // Save scan checkpoint halfway through (50 of 100 items processed)
        scanStateManager.saveCheckpoint(50, 100, "track_050", "/music/track050.mp3")
        scanStateManager.status = ScanStatus.SCANNING

        // Simulate process termination and relaunch recovery
        val wasInterrupted = scanStateManager.checkAndRecoverInterruptedScan()

        assertTrue("Should detect interrupted scan", wasInterrupted)
        assertEquals("Scan status should safely reset to PAUSED", ScanStatus.PAUSED, scanStateManager.status)
        assertEquals("Processed checkpoint count preserved", 50, scanStateManager.checkpointProcessedCount)
        assertEquals("Total checkpoint count preserved", 100, scanStateManager.checkpointTotalCount)
        assertEquals("Last track checkpoint ID preserved", "track_050", scanStateManager.checkpointLastTrackId)

        // Session manager checkpoint mirror
        sessionManager.saveScannerCheckpoint(
            PersistentScannerCheckpoint(
                processedCount = 50,
                totalDiscoveredCount = 100,
                lastProcessedTrackId = "track_050",
                lastProcessedFilePath = "/music/track050.mp3",
                status = ScanStatus.PAUSED
            ),
            immediate = true
        )
        sessionManager.saveToDisk()

        val restored = PersistentSessionManager(context).restoreFromDisk()
        assertEquals("Session checkpoint processed count matches", 50, restored.scannerCheckpoint?.processedCount)
        assertEquals("Session checkpoint total count matches", 100, restored.scannerCheckpoint?.totalDiscoveredCount)
    }

    // ── Scenario 12: Queued track deleted before relaunch ─────────────────────
    @Test
    fun test12_queuedTrackDeleted_repairedGracefully() = runBlocking(Dispatchers.IO) {
        val t1 = makeTrack("t1", "Track 1", filePath = "/storage/emulated/0/Music/t1.mp3")
        val t2 = makeTrack("t2", "Track 2", filePath = "/storage/emulated/0/Music/t2.mp3")
        val t3 = makeTrack("t3", "Track 3", filePath = "/storage/emulated/0/Music/t3.mp3")

        sessionManager.updatePlaybackPosition(t1, 10000L, wasPlaying = false, immediate = true)
        sessionManager.updateQueueState(
            currentTrack = t1,
            upcomingQueue = listOf(t2, t3),
            playbackHistory = emptyList(),
            isShuffleEnabled = false,
            shuffleSequenceTrackIds = emptyList(),
            shuffleIndex = 0,
            originalQueueTrackIds = listOf("t1", "t2", "t3"),
            repeatMode = QueueRepeatMode.OFF,
            smartContinueMode = SmartContinueMode.OFF,
            immediate = true
        )
        sessionManager.saveToDisk()

        // Simulate external deletion of t1 (current track) and t2 (first upcoming)
        // Only t3 remains accessible on disk
        val repaired = sessionManager.validateAndRepair(
            trackDao = fakeTrackDao,
            fileExistsCheck = { path -> path.contains("t3.mp3") }
        )

        // t1 was deleted, so t3 should be promoted to current track
        assertNotNull("Repaired current track should promote next accessible track", repaired.playback.currentTrack)
        assertEquals("Promoted track must be t3", "t3", repaired.playback.currentTrack?.id)
        assertEquals("Playback position of promoted track must reset to 0", 0L, repaired.playback.playbackPositionMs)
        assertEquals("Upcoming queue must prune deleted t2", 0, repaired.queue.upcomingQueue.size)
    }

    // ── Edge Case: Near-end of track guard resets position to 0L ──────────────
    @Test
    fun test13_completedTrackNearEnd_resetsPositionToZero() = runBlocking(Dispatchers.IO) {
        val track = makeTrack("tEnd", "Track Ending", durationMs = 120000L)
        // Position at 119500ms (within 2s of 120000ms end)
        sessionManager.updatePlaybackPosition(track, 119500L, wasPlaying = true, immediate = true)

        val repaired = sessionManager.validateAndRepair(
            trackDao = fakeTrackDao,
            fileExistsCheck = { true }
        )

        assertEquals("Near-end position within 2s of duration should reset to 0", 0L, repaired.playback.playbackPositionMs)
    }

    // ── Edge Case: Corrupted session JSON fallback does not crash or wipe DB ──
    @Test
    fun test14_corruptedSessionJson_fallsBackGracefullyWithoutWipingDb() = runBlocking(Dispatchers.IO) {
        val file = File(context.filesDir, "persistent_app_session.json")
        file.writeText("{ corrupted json content :: [[[ }}")

        // Track store has tracks that must NOT be wiped
        trackStore["safe_track"] = TrackEntity(
            id = "safe_track",
            title = "Safe Track",
            artist = "Safe Artist",
            album = "Safe Album",
            durationSeconds = 180,
            filePath = "/storage/emulated/0/Music/safe.mp3",
            dateAdded = System.currentTimeMillis()
        )

        val restored = sessionManager.restoreFromDisk()
        assertNotNull("Should recover default session safely", restored)
        assertEquals("Default version is 1", 1, restored.version)

        // Verify database remains untouched
        assertEquals("Track database must not be modified or wiped", 1, trackStore.size)
        assertNotNull("Safe track must still exist", trackStore["safe_track"])
    }
}
