package com.example.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.PlayabilityValidator
import com.example.analysis.ProcessOutcome
import com.example.analysis.ScanLifecycleState
import com.example.analysis.TrackAnalysisManager
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.AnalysisState
import com.example.model.PlayabilityStatus
import com.example.model.PlaybackErrorCodes
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class StorageDiagnosticsAndScanLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TrackSourceResolver.clearVolumeOverridesForTesting()
        TrackSourceResolver.audioReadPermissionOverrideForTesting = true
        TrackAnalysisManager.getInstance(context).trackDaoOverrideForTesting = null
    }

    // ── PART A: STORAGE AVAILABILITY & DIAGNOSTICS ────────────────────────────

    @Test
    fun testUsbTrackWithReadableContentUriReportsConnectedReadable() = runBlocking {
        // USB removable volume track with readable content URI
        val usbContentUri = Uri.parse("content://media/1234-ABCD/audio/media/42")
        val track = Track(
            id = "usb_track_1",
            title = "USB Festival Track",
            artist = "DJ Removable",
            filePath = usbContentUri.toString(),
            sourceId = "usb_drive"
        )

        // Mock readable content URI
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == usbContentUri
        }

        val report = StorageAvailabilityHelper.evaluateStorageAvailability(context, track)
        assertEquals(StorageAvailabilityState.CONNECTED_READABLE, report.state)
        assertTrue(report.isReadable)
        assertTrue(report.hasPermission)
        assertFalse(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track))

        // Playability probe must never mark a readable USB track as VOLUME_UNAVAILABLE
        val diag = PlayabilityValidator.validateTrack(context, track, quickCheckOnly = true)
        assertEquals(PlayabilityStatus.PLAYABLE, diag.status)
        assertTrue(diag.isFileAccessible)
    }

    @Test
    fun testRemovedVolumeReportsUnmounted() = runBlocking {
        val removedUuid = "ABCD-1234"
        val path = "/storage/$removedUuid/Music/offline_track.mp3"
        val track = Track(
            id = "offline_track_1",
            title = "Missing USB Song",
            artist = "Unplugged",
            filePath = path,
            sourceId = "usb_$removedUuid"
        )

        TrackSourceResolver.setVolumeMountedForTesting(removedUuid, false)

        val report = StorageAvailabilityHelper.evaluateStorageAvailability(context, track)
        assertEquals(StorageAvailabilityState.VOLUME_UNMOUNTED, report.state)
        assertFalse(report.isReadable)
        assertEquals(removedUuid, report.volumeIdentity)
        assertTrue(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track))

        val diag = PlayabilityValidator.validateTrack(context, track, quickCheckOnly = true)
        assertEquals(PlayabilityStatus.VOLUME_UNAVAILABLE, diag.status)
        assertEquals(PlaybackErrorCodes.ERR_STORAGE_UNMOUNTED, diag.errorCode)
    }

    @Test
    fun testPermissionRevokedReportsPermissionProblemNotGenericDisconnected() = runBlocking {
        val volumeUuid = "5555-6666"
        val path = "/storage/$volumeUuid/Music/protected.mp3"
        val track = Track(
            id = "perm_track_1",
            title = "Protected Removable Track",
            artist = "Scoped Storage",
            filePath = path,
            sourceId = "sdcard_$volumeUuid"
        )

        // Volume is physically mounted
        TrackSourceResolver.setVolumeMountedForTesting(volumeUuid, true)

        val report = StorageAvailabilityHelper.evaluateStorageAvailability(context, track)
        assertEquals(StorageAvailabilityState.PERMISSION_LOST, report.state)
        assertFalse(report.isReadable)
        // Must NOT be classified as disconnected
        assertFalse(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track))

        val diag = PlayabilityValidator.validateTrack(context, track, quickCheckOnly = true)
        assertEquals(PlayabilityStatus.PERMISSION_DENIED, diag.status)
        assertEquals(PlaybackErrorCodes.ERR_SCOPED_STORAGE_RESTRICTION, diag.errorCode)
    }

    @Test
    fun testStaleMediaStoreUriReportsStaleMissing() = runBlocking {
        val staleUri = "content://media/external/audio/media/999999"
        val track = Track(
            id = "stale_track_1",
            title = "Ghost Track",
            artist = "Deleted Artist",
            filePath = staleUri
        )

        val report = StorageAvailabilityHelper.evaluateStorageAvailability(context, track)
        // Primary media volume is mounted, so non-existent URI is STALE_SOURCE, never VOLUME_UNMOUNTED
        assertEquals(StorageAvailabilityState.STALE_SOURCE, report.state)
        assertFalse(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track))

        val diag = PlayabilityValidator.validateTrack(context, track, quickCheckOnly = true)
        assertEquals(PlayabilityStatus.STALE_URI, diag.status)
        assertEquals(PlaybackErrorCodes.ERR_MEDIASTORE_STALE, diag.errorCode)
    }

    @Test
    fun testInternalStorageSourceRemainsCorrect() = runBlocking {
        val internalPath = "/storage/emulated/0/Music/nonexistent.mp3"
        val track = Track(
            id = "internal_missing",
            title = "Missing Internal Track",
            artist = "Local",
            filePath = internalPath
        )

        val report = StorageAvailabilityHelper.evaluateStorageAvailability(context, track)
        assertEquals(StorageAvailabilityState.SOURCE_MISSING, report.state)
        assertFalse(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, track))

        val diag = PlayabilityValidator.validateTrack(context, track, quickCheckOnly = true)
        assertEquals(PlayabilityStatus.MISSING_FILE, diag.status)
        assertEquals(PlaybackErrorCodes.ERR_SOURCE_MISSING, diag.errorCode)
    }

    // ── PART B: METADATA SCAN LIFECYCLE & WORKER ACCOUNTING ───────────────────

    @Test
    fun testFullMetadataScanCompletesAndLeavesRunningState() = runBlocking {
        val memoryDb = mutableMapOf<String, TrackEntity>()

        // Create mock tracks that are completed
        val t1 = TrackEntity(id = "trk_1", title = "Track 1", artist = "Artist", filePath = "demo://track1", analysisState = "COMPLETE")
        val t2 = TrackEntity(id = "trk_2", title = "Track 2", artist = "Artist", filePath = "demo://track2", analysisState = "COMPLETE")
        memoryDb[t1.id] = t1
        memoryDb[t2.id] = t2

        val trackDao = createMockTrackDao(memoryDb)
        val pending = trackDao.getPendingAnalysisCount()
        assertEquals(0, pending)

        // Queue progress starts IDLE and leaves RUNNING upon completion
        val stateManager = ScanStateManager(context)
        stateManager.status = ScanStatus.COMPLETE
        assertFalse(stateManager.status.isActive)
        assertTrue(stateManager.status.isTerminal)
    }

    @Test
    fun testOneFailedTrackDoesNotKeepEntireScanAliveForever() = runBlocking {
        val memoryDb = mutableMapOf<String, TrackEntity>()
        val brokenFile = File(tempFolder.root, "broken.mp3") // file does not exist

        val entity = TrackEntity(
            id = "failed_trk_1",
            title = "Broken Track",
            artist = "Unknown",
            filePath = brokenFile.absolutePath,
            analysisState = "QUEUED",
            analysisRetryCount = 2
        )
        memoryDb[entity.id] = entity

        val trackDao = createMockTrackDao(memoryDb)
        val track = entity.toTrack()

        val manager = TrackAnalysisManager.getInstance(context)
        manager.trackDaoOverrideForTesting = trackDao
        val outcome = manager.processSingleTrackWithOutcome(track)

        // With retryCount initially 2, failure increments to 3 and reaches FAILED_TERMINAL
        assertEquals(ProcessOutcome.FAILED_TERMINAL, outcome)
        val updated = memoryDb[entity.id]
        assertNotNull(updated)
        assertEquals("FAILED", updated!!.analysisState)
        assertEquals(3, updated.analysisRetryCount)

        // Querying pending count must now exclude this terminal failed track
        val pendingCount = trackDao.getPendingAnalysisCount()
        assertEquals(0, pendingCount)
    }

    @Test
    fun testRetryableErrorRetriesWithinBoundThenTerminatesCorrectly() = runBlocking {
        val memoryDb = mutableMapOf<String, TrackEntity>()
        val brokenFile = File(tempFolder.root, "transient_broken.mp3")

        val entity = TrackEntity(
            id = "retry_trk",
            title = "Transient File",
            artist = "Artist",
            filePath = brokenFile.absolutePath,
            analysisState = "QUEUED",
            analysisRetryCount = 0
        )
        memoryDb[entity.id] = entity

        val trackDao = createMockTrackDao(memoryDb)
        val manager = TrackAnalysisManager.getInstance(context)
        manager.trackDaoOverrideForTesting = trackDao

        // Run 1: attempt 1 -> is terminal because file is completely missing on storage
        val outcome1 = manager.processSingleTrackWithOutcome(entity.toTrack())
        assertEquals(ProcessOutcome.FAILED_TERMINAL, outcome1)
        val updated = memoryDb[entity.id]
        assertEquals(3, updated?.analysisRetryCount)
        assertEquals("FAILED", updated?.analysisState)
    }

    @Test
    fun testAppKilledReopenedDuringScan() {
        val scanStateManager = ScanStateManager(context)
        scanStateManager.status = ScanStatus.RUNNING
        scanStateManager.activeRunId = "scan_interrupted_123"

        // On app restart, checkAndRecoverInterruptedScan recovers state
        val wasRecovered = scanStateManager.checkAndRecoverInterruptedScan()
        assertTrue(wasRecovered)
        assertEquals(ScanStatus.FAILED, scanStateManager.status)
        assertNotNull(scanStateManager.lastErrorMessage)
        assertNull(scanStateManager.activeRunId)
    }

    @Test
    fun testAppReopenedAfterScanAlreadyCompleted() {
        val scanStateManager = ScanStateManager(context)
        scanStateManager.status = ScanStatus.COMPLETE
        scanStateManager.lastScanTime = System.currentTimeMillis()
        scanStateManager.lastScannedCount = 100

        val wasRecovered = scanStateManager.checkAndRecoverInterruptedScan()
        assertFalse(wasRecovered)
        assertEquals(ScanStatus.COMPLETE, scanStateManager.status)
        assertEquals(100, scanStateManager.lastScannedCount)
    }

    @Test
    fun testScanLifecycleStateTransitions() {
        val idle = ScanLifecycleState.IDLE
        assertFalse(idle.isActive)
        assertFalse(idle.isTerminal)

        val running = ScanLifecycleState.RUNNING
        assertTrue(running.isActive)
        assertFalse(running.isTerminal)

        val paused = ScanLifecycleState.PAUSED
        assertTrue(paused.isActive)
        assertFalse(paused.isTerminal)

        val complete = ScanLifecycleState.COMPLETE
        assertFalse(complete.isActive)
        assertTrue(complete.isTerminal)

        val errors = ScanLifecycleState.COMPLETE_WITH_ERRORS
        assertFalse(errors.isActive)
        assertTrue(errors.isTerminal)

        val cancelled = ScanLifecycleState.CANCELLED
        assertFalse(cancelled.isActive)
        assertTrue(cancelled.isTerminal)
    }

    private fun createMockTrackDao(memoryDb: MutableMap<String, TrackEntity>): TrackDao {
        return Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertTrack", "updateTrack" -> {
                    val entity = args[0] as TrackEntity
                    memoryDb[entity.id] = entity
                    null
                }
                "getTrackById" -> memoryDb[args[0] as String]
                "updatePlayabilityStatus" -> {
                    val id = args[0] as String
                    val status = args[1] as String
                    val code = args[2] as? String
                    val msg = args[3] as? String
                    memoryDb[id]?.let {
                        memoryDb[id] = it.copy(
                            playabilityStatus = status,
                            playbackErrorCode = code,
                            playbackErrorMessage = msg
                        )
                    }
                    null
                }
                "updateTrackAnalysisStatus" -> {
                    val id = args[0] as String
                    val state = args[1] as String
                    val reason = args[3] as? String
                    val retry = (args[4] as? Int) ?: 0
                    memoryDb[id]?.let {
                        memoryDb[id] = it.copy(
                            analysisState = state,
                            analysisFailureReason = reason,
                            analysisRetryCount = retry
                        )
                    }
                    null
                }
                "getPendingAnalysisCount" -> {
                    memoryDb.values.count { entity ->
                        (entity.analysisState in listOf("NOT_ANALYSED", "QUEUED") || (entity.analysisState == "PARTIAL" && entity.analysisRetryCount < 3)) &&
                        entity.analysisState != "COMPLETE" &&
                        !(entity.analysisState == "FAILED" && entity.analysisRetryCount >= 3)
                    }
                }
                "getTracksNeedingAnalysis" -> {
                    val limit = (args[0] as? Int) ?: 10
                    memoryDb.values.filter { entity ->
                        (entity.analysisState in listOf("NOT_ANALYSED", "QUEUED") || (entity.analysisState == "PARTIAL" && entity.analysisRetryCount < 3)) &&
                        entity.analysisState != "COMPLETE" &&
                        !(entity.analysisState == "FAILED" && entity.analysisRetryCount >= 3)
                    }.take(limit)
                }
                else -> null
            }
        } as TrackDao
    }
}
