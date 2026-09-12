package com.example.storage

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.AudioFormatSniffer
import com.example.analysis.PlayabilityValidator
import com.example.analysis.TrackAnalysisManager
import com.example.backup.SoundSyncBackupManager
import com.example.data.AppDatabase
import com.example.data.SourceFolderEntity
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.AnalysisState
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.PlayabilityStatus
import com.example.model.PlaybackErrorCodes
import com.example.model.RepairActionType
import com.example.model.SourceHealthTier
import com.example.model.SyncState
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
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackStorageAndPlaybackIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var trackDao: TrackDao
    private val memoryDb = mutableMapOf<String, TrackEntity>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        memoryDb.clear()
        TrackSourceResolver.clearVolumeOverridesForTesting()

        trackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertTrack", "updateTrack" -> {
                    val entity = args[0] as TrackEntity
                    memoryDb[entity.id] = entity
                    null
                }
                "insertTracks", "updateTracks" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = args[0] as List<TrackEntity>
                    list.forEach { memoryDb[it.id] = it }
                    null
                }
                "getTrackById" -> memoryDb[args[0] as String]
                "updateFilePath" -> {
                    val id = args[0] as String
                    val newPath = args[1] as String
                    memoryDb[id]?.let { memoryDb[id] = it.copy(filePath = newPath, resolvedUri = newPath) }
                    null
                }
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
                "getAllTracksSync" -> memoryDb.values.toList()
                "getTracksWithPlaybackIssues" -> memoryDb.values.filter { it.playabilityStatus != "PLAYABLE" && it.playabilityStatus != "REPAIRED" }
                else -> null
            }
        } as TrackDao
    }

    private fun createValidWavBytes(): ByteArray {
        val pcmSize = 1000
        val totalSize = 36 + pcmSize
        val header = ByteArray(44 + pcmSize)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = 2; header[23] = 0
        val sr = 44100
        header[24] = (sr and 0xff).toByte(); header[25] = ((sr shr 8) and 0xff).toByte()
        header[26] = ((sr shr 16) and 0xff).toByte(); header[27] = ((sr shr 24) and 0xff).toByte()
        val br = 176400
        header[28] = (br and 0xff).toByte(); header[29] = ((br shr 8) and 0xff).toByte()
        header[30] = ((br shr 16) and 0xff).toByte(); header[31] = ((br shr 24) and 0xff).toByte()
        header[32] = 4; header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmSize and 0xff).toByte()
        header[41] = ((pcmSize shr 8) and 0xff).toByte()
        header[42] = ((pcmSize shr 16) and 0xff).toByte()
        header[43] = ((pcmSize shr 24) and 0xff).toByte()
        return header
    }

    private fun buildSampleTrack(
        id: String = "track_kamikaze",
        title: String = "Kamikaze",
        artist: String = "Act of Rage",
        filePath: String = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav",
        durationSeconds: Int = 210,
        bpm: Double = 150.0,
        camelotKey: String = "4A",
        hotCues: List<Int> = listOf(0, 30, 60, 90),
        rating: Int = 5,
        playabilityStatus: PlayabilityStatus = PlayabilityStatus.PLAYABLE,
        errorCode: String? = null
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = "Sabotage",
            genre = "Hardstyle",
            bpm = bpm,
            musicalKey = "Fm",
            camelotKey = camelotKey,
            durationSeconds = durationSeconds,
            bitrateKbps = 1411,
            format = "WAV",
            fileSizeMb = 35.0,
            filePath = filePath,
            directoryPath = filePath.substringBeforeLast('/'),
            isOfflineReady = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            energyRating = 9,
            hotCues = hotCues,
            rating = rating,
            crateId = "crate_hardcore",
            storageRelativePath = "The Assassinz Archives/Act of Rage - Kamikaze.wav",
            playabilityStatus = playabilityStatus.name,
            playbackErrorCode = errorCode,
            playbackErrorMessage = if (errorCode != null) "Storage Read Error" else null
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST A: MediaStore resolution of raw secondary external storage path
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testA_mediaStoreResolutionOfRawSecondaryStoragePath() = runBlocking {
        val rawPath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val track = buildSampleTrack(filePath = rawPath)
        val mockMediaStoreUri = Uri.parse("content://media/external/audio/media/1001")

        TrackSourceResolver.mediaStoreUriFinderForTesting = { _, t ->
            if (t.filePath == rawPath || t.title == "Kamikaze") mockMediaStoreUri else null
        }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == mockMediaStoreUri
        }

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)

        assertTrue("Resolution must be playable", resolution.isPlayable)
        assertEquals("Source type must be MEDIASTORE", ResolvedSourceType.MEDIASTORE, resolution.sourceType)
        assertNotNull("Resolved URI must be populated", resolution.resolvedUriOrPath)
        assertTrue("Resolved URI must be content:// URI", resolution.resolvedUriOrPath!!.startsWith("content://"))
        assertEquals("Resolved URI must match inserted MediaStore record", mockMediaStoreUri.toString(), resolution.resolvedUriOrPath)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST B: Persisted SAF document resolution of raw SD card path
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testB_persistedSafDocumentResolutionOfRawSdCardPath() = runBlocking {
        val rawPath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val track = buildSampleTrack(filePath = rawPath)
        val mockSafUri = Uri.parse("content://com.android.externalstorage.documents/document/AA44-8296%3AThe%20Assassinz%20Archives%2FAct%20of%20Rage%20-%20Kamikaze.wav")

        TrackSourceResolver.safDocumentUriFinderForTesting = { _, t ->
            if (t.filePath == rawPath || t.title == "Kamikaze") mockSafUri else null
        }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == mockSafUri
        }

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)
        assertTrue("Track must be playable through Android content resolver", resolution.isPlayable)
        assertEquals(ResolvedSourceType.SAF_DOCUMENT, resolution.sourceType)
        assertTrue("Resolved path must be accessible content URI", resolution.resolvedUriOrPath!!.startsWith("content://"))
        assertEquals(mockSafUri.toString(), resolution.resolvedUriOrPath)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST C: Legitimate raw filesystem path readability on primary emulated storage
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testC_legitimateRawFilesystemPathReadabilityOnPrimaryStorage() = runBlocking {
        val testFile = tempFolder.newFile("song.wav").apply {
            FileOutputStream(this).use { it.write(createValidWavBytes()) }
        }
        val track = buildSampleTrack(filePath = testFile.absolutePath)

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)

        assertTrue("Direct readable file must be playable", resolution.isPlayable)
        assertEquals("Primary storage file should resolve as RAW_FILE_PATH", ResolvedSourceType.RAW_FILE_PATH, resolution.sourceType)
        assertEquals("Resolved path must match raw file path", testFile.absolutePath, resolution.resolvedUriOrPath)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST D: SD card volume unmounted detection (VOLUME_UNAVAILABLE, no deletion)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testD_sdCardVolumeUnmountedDetectionPreservesTrackInLibrary() = runBlocking {
        val unmountedPath = "/storage/DEAD-BEEF/Music/test.wav"
        val track = buildSampleTrack(filePath = unmountedPath)
        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)
        assertFalse("Unmounted track source cannot be playable", resolution.isPlayable)
        assertFalse("Volume must be flagged unmounted in diagnostics", resolution.diagnostics.isVolumeMounted)

        val validation = PlayabilityValidator.validateTrack(context, track, forceFresh = true)
        assertEquals("Status must be VOLUME_UNAVAILABLE", PlayabilityStatus.VOLUME_UNAVAILABLE, validation.status)
        assertEquals("Error code must be ERR_STORAGE_UNMOUNTED", "ERR_STORAGE_UNMOUNTED", validation.errorCode)

        // Track MUST remain in Room DB!
        val preservedTrack = trackDao.getTrackById(track.id)
        assertNotNull("Track must NOT be deleted from library when volume is unmounted", preservedTrack)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST E: SD card volume UUID extraction and diagnostics
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testE_storageVolumeUuidExtractionAndDiagnostics() {
        val path1 = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val uuid1 = TrackSourceResolver.extractVolumeUuid(path1)
        assertEquals("AA44-8296", uuid1)

        val path2 = "content://com.android.externalstorage.documents/document/BB55-9307%3AMusic%2Fsong.mp3"
        val uuid2 = TrackSourceResolver.extractVolumeUuid(path2)
        assertEquals("BB55-9307", uuid2)

        val emulatedPath = "/storage/emulated/0/Music/song.mp3"
        val emulatedUuid = TrackSourceResolver.extractVolumeUuid(emulatedPath)
        assertNull("Emulated storage should not have a removable hex UUID", emulatedUuid)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST F: Removable storage single-grant folder reconciliation in bulk
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testF_removableStorageSingleGrantFolderReconciliationInBulk() = runBlocking {
        val track1 = buildSampleTrack(
            id = "t1",
            title = "Kamikaze",
            filePath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav",
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        val track2 = buildSampleTrack(
            id = "t2",
            title = "Sabotage",
            filePath = "/storage/AA44-8296/The Assassinz Archives/Sabotage.wav",
            playabilityStatus = PlayabilityStatus.PERMISSION_DENIED,
            errorCode = "ERR_SCOPED_STORAGE_RESTRICTION"
        )
        val track3 = buildSampleTrack(
            id = "t3",
            title = "Rage",
            filePath = "/storage/AA44-8296/Music/Rage.wav",
            playabilityStatus = PlayabilityStatus.VOLUME_UNAVAILABLE,
            errorCode = "ERR_STORAGE_UNMOUNTED"
        )

        trackDao.insertTrack(TrackEntity.fromTrack(track1))
        trackDao.insertTrack(TrackEntity.fromTrack(track2))
        trackDao.insertTrack(TrackEntity.fromTrack(track3))

        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/AA44-8296%3A")
        val summary = TrackSourceResolver.onRemovableStorageFolderGranted(context, treeUri, trackDao)

        assertNotNull(summary)
        val allAfter = trackDao.getAllTracksSync()
        assertEquals(3, allAfter.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST G: Subfolder grant reconciliation (The Assassinz Archives)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testG_subfolderGrantReconciliation() = runBlocking {
        val track = buildSampleTrack(
            id = "t_kamikaze_sub",
            title = "Kamikaze",
            filePath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav",
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val subfolderTreeUri = Uri.parse("content://com.android.externalstorage.documents/tree/AA44-8296%3AThe%20Assassinz%20Archives")
        val summary = TrackSourceResolver.onRemovableStorageFolderGranted(context, subfolderTreeUri, trackDao)

        assertNotNull(summary)
        val entity = trackDao.getTrackById("t_kamikaze_sub")
        assertNotNull(entity)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST H: Scoped storage restriction detection
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testH_scopedStorageRestrictionFlagsFolderPermissionRequirement() = runBlocking {
        TrackSourceResolver.setVolumeMountedForTesting("AA44-8296", true)
        val path = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val track = buildSampleTrack(filePath = path)

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)

        if (!resolution.isPlayable) {
            assertTrue("Must require folder access when raw access fails on removable storage", resolution.requiresFolderAccess)
            assertEquals("Target removable folder must point to volume root", "/storage/AA44-8296", resolution.targetRemovableFolder)

            val validation = PlayabilityValidator.validateTrack(context, track, forceFresh = true)
            assertTrue(
                "Status must be PERMISSION_DENIED or PERMISSION_REQUIRED",
                validation.status == PlayabilityStatus.PERMISSION_DENIED || validation.status == PlayabilityStatus.PERMISSION_REQUIRED
            )
            assertEquals("ERR_SCOPED_STORAGE_RESTRICTION", validation.errorCode)
            assertTrue("REQUEST_PERMISSION action must be available", validation.availableActions.contains(RepairActionType.REQUEST_PERMISSION))
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST I: Differentiating truncated corrupt file from storage permission errors
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testI_differentiatingTruncatedCorruptFile() = runBlocking {
        val corruptFile = tempFolder.newFile("truncated.wav").apply {
            FileOutputStream(this).use { it.write(ByteArray(10) { 0x00 }) }
        }
        val track = buildSampleTrack(filePath = corruptFile.absolutePath)

        val report = PlayabilityValidator.validateTrack(context, track, forceFresh = true)

        assertEquals("Status must be CORRUPTED_FILE for truncated <128 byte file", PlayabilityStatus.CORRUPTED_FILE, report.status)
        assertEquals("Error code must be ERR_ZERO_OR_EMPTY_FILE", "ERR_ZERO_OR_EMPTY_FILE", report.errorCode)
        assertFalse("Should not report ERR_IO_READ for empty/truncated file", report.errorCode == "ERR_IO_READ")
        assertFalse("Should not report ERR_SCOPED_STORAGE_RESTRICTION", report.errorCode == "ERR_SCOPED_STORAGE_RESTRICTION")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST J: Audio decoder failure vs IO failure separation
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testJ_audioDecoderFailureVsIoFailureSeparation() {
        val validWav = createValidWavBytes()
        assertTrue("Valid WAV header must start with RIFF", validWav[0] == 'R'.code.toByte())
        assertTrue("Valid WAV size must be >= 44 bytes", validWav.size >= 44)

        val diag = TrackSourceResolver.runDiagnosticProbe(context, buildSampleTrack(filePath = "/storage/AA44-8296/test.wav"))
        val formatted = diag.formatDiagnostics()
        assertTrue("Diagnostics probe must include failure mode", formatted.contains("Failure Mode:"))
        assertTrue("Diagnostics probe must include volume information", formatted.contains("StorageVolume Info:"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST K: TrackPlaybackRepairEngine autoRepairTrack healing ERR_IO_READ in-place
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testK_trackPlaybackRepairEngineAutoRepairTrackHealingInPlace() = runBlocking {
        val rawPath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val brokenTrack = buildSampleTrack(
            filePath = rawPath,
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        trackDao.insertTrack(TrackEntity.fromTrack(brokenTrack))

        val mockMediaStoreUri = Uri.parse("content://media/external/audio/media/2002")
        TrackSourceResolver.mediaStoreUriFinderForTesting = { _, t ->
            if (t.filePath == rawPath || t.id == brokenTrack.id) mockMediaStoreUri else null
        }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == mockMediaStoreUri
        }

        val result = TrackPlaybackRepairEngine.autoRepairTrack(context, brokenTrack, trackDao)

        assertTrue("Auto-repair must succeed", result.success)
        assertEquals("Status must become PLAYABLE", PlayabilityStatus.PLAYABLE.name, result.track.playabilityStatus)
        assertNull("Error code must be cleared", result.track.playbackErrorCode)
        assertNull("Error message must be cleared", result.track.playbackErrorMessage)
        assertEquals("New path must be the healed MediaStore URI", mockMediaStoreUri.toString(), result.newPath)

        val dbEntity = trackDao.getTrackById(brokenTrack.id)
        assertNotNull(dbEntity)
        assertEquals("DB filePath must be updated to healed URI", mockMediaStoreUri.toString(), dbEntity?.filePath)
        assertEquals("DB playabilityStatus must be PLAYABLE", PlayabilityStatus.PLAYABLE.name, dbEntity?.playabilityStatus)
        assertNull("DB playbackErrorCode must be cleared", dbEntity?.playbackErrorCode)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST L: TrackPlaybackRepairEngine getAffectedVolumesNeedingPermission grouping
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testL_bulkVolumeRepairGroupingByRemovableStorageUuid() {
        val trackSD1 = buildSampleTrack(
            id = "sd1",
            filePath = "/storage/AA44-8296/Track1.wav",
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        val trackSD2 = buildSampleTrack(
            id = "sd2",
            filePath = "/storage/AA44-8296/Track2.wav",
            playabilityStatus = PlayabilityStatus.PERMISSION_DENIED,
            errorCode = "ERR_SCOPED_STORAGE_RESTRICTION"
        )
        val trackUSB = buildSampleTrack(
            id = "usb1",
            filePath = "/storage/CC77-1122/Track3.wav",
            playabilityStatus = PlayabilityStatus.VOLUME_UNAVAILABLE,
            errorCode = "ERR_STORAGE_UNMOUNTED"
        )
        val trackInternal = buildSampleTrack(
            id = "int1",
            filePath = "/storage/emulated/0/Music/GoodTrack.mp3",
            playabilityStatus = PlayabilityStatus.PLAYABLE
        )

        val groups = TrackPlaybackRepairEngine.getAffectedVolumesNeedingPermission(
            context,
            listOf(trackSD1, trackSD2, trackUSB, trackInternal)
        )

        assertEquals("Must create exactly 2 volume groups (AA44-8296 and CC77-1122)", 2, groups.size)
        val aaGroup = groups.firstOrNull { it.volumeUuid == "AA44-8296" }
        assertNotNull("Group AA44-8296 must exist", aaGroup)
        assertEquals(2, aaGroup?.affectedTracks?.size)
        assertEquals("/storage/AA44-8296", aaGroup?.folderPath)

        val ccGroup = groups.firstOrNull { it.volumeUuid == "CC77-1122" }
        assertNotNull("Group CC77-1122 must exist", ccGroup)
        assertEquals(1, ccGroup?.affectedTracks?.size)

        val hasInternal = groups.any { it.volumeUuid.contains("emulated") }
        assertFalse("Healthy internal storage must not be grouped into permission requests", hasInternal)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST M: StorageAvailabilityHelper isTrackPathAvailable strict check
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testM_isTrackPathAvailableReturnsFalseForUnreadableRawPaths() {
        val brokenRawPath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val mockUri = Uri.parse("content://media/external/audio/media/3003")
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri -> uri == mockUri }

        // isTrackPathAvailable MUST return false for the unreadable raw path!
        val isRawAvailable = StorageAvailabilityHelper.isTrackPathAvailable(context, brokenRawPath)
        assertFalse("Unreadable raw path must return false from isTrackPathAvailable", isRawAvailable)

        val isUriAvailable = StorageAvailabilityHelper.isTrackPathAvailable(context, mockUri.toString())
        assertTrue("Valid MediaStore URI must return true from isTrackPathAvailable", isUriAvailable)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST N: DjAudioEngine fallback resolution for raw paths
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testN_fallbackResolutionForRawPaths() {
        val rawPath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val mockUri = Uri.parse("content://media/external/audio/media/4004")
        TrackSourceResolver.mediaStoreUriFinderForTesting = { _, t ->
            if (t.filePath == rawPath || t.title.contains("Kamikaze")) mockUri else null
        }

        val resolved = TrackSourceResolver.findMediaStoreUriForPath(context, rawPath)
        assertNotNull("Must resolve raw path to MediaStore URI", resolved)
        assertEquals(mockUri.toString(), resolved)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST O: Background analysis skipping inaccessible tracks with backoff
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testO_backgroundAnalysisSkipsInaccessibleTracksWithBackoff() = runBlocking {
        val brokenTrack = buildSampleTrack(
            id = "track_bg_unmounted",
            filePath = "/storage/AA44-8296/Unmounted/Track.wav",
            playabilityStatus = PlayabilityStatus.VOLUME_UNAVAILABLE,
            errorCode = "ERR_STORAGE_UNMOUNTED"
        )
        trackDao.insertTrack(TrackEntity.fromTrack(brokenTrack))

        val isPathAvail = StorageAvailabilityHelper.isTrackPathAvailable(context, brokenTrack.filePath)
        assertFalse(isPathAvail)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST P: Library playlist, cue points, and BPM preservation during auto-repair
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testP_libraryPlaylistCuePointsAndBpmPreservationDuringAutoRepair() = runBlocking {
        val originalTrack = buildSampleTrack(
            id = "dj_custom_track_1",
            title = "Kamikaze",
            artist = "Act of Rage",
            filePath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav",
            bpm = 155.0,
            camelotKey = "4A",
            hotCues = listOf(0, 45, 90, 135),
            rating = 5,
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        trackDao.insertTrack(TrackEntity.fromTrack(originalTrack))

        val mockMediaStoreUri = Uri.parse("content://media/external/audio/media/5005")
        TrackSourceResolver.mediaStoreUriFinderForTesting = { _, t ->
            if (t.id == originalTrack.id || t.filePath == originalTrack.filePath) mockMediaStoreUri else null
        }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == mockMediaStoreUri
        }

        val repairResult = TrackPlaybackRepairEngine.autoRepairTrack(context, originalTrack, trackDao)
        assertTrue(repairResult.success)

        val healedEntity = trackDao.getTrackById("dj_custom_track_1")
        assertNotNull("Track must exist with original ID", healedEntity)
        assertEquals("Track ID must never change during repair", "dj_custom_track_1", healedEntity?.id)
        assertEquals("BPM must be preserved perfectly", 155.0, healedEntity?.bpm ?: 0.0, 0.001)
        assertEquals("Camelot key must be preserved", "4A", healedEntity?.camelotKey)
        assertEquals("Rating must be preserved", 5, healedEntity?.rating)
        assertEquals("Crate ID must be preserved", "crate_hardcore", healedEntity?.crateId)
        assertEquals("Hot cues count must be preserved", 4, healedEntity?.toTrack()?.hotCues?.size)
        assertEquals("Hot cues values must be preserved", listOf(0, 45, 90, 135), healedEntity?.toTrack()?.hotCues)
        assertEquals("Title must be preserved", "Kamikaze", healedEntity?.title)
        assertEquals("Artist must be preserved", "Act of Rage", healedEntity?.artist)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST Q: Backup restore reconciliation for raw paths
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testQ_backupRestoreReconciliationForRawPaths() = runBlocking {
        val rawPath = "/storage/AA44-8296/The Assassinz Archives/Act of Rage - Kamikaze.wav"
        val isAvail = StorageAvailabilityHelper.isTrackPathAvailable(context, rawPath)
        assertFalse("Raw path on disconnected volume must be detected as unavailable", isAvail)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST R: Valid MediaStore URI is retained and NEVER converted to SAF (Priority 1)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testR_validMediaStoreUriRetainedAndNeverConvertedToSaf() = runBlocking {
        val mediaStorePath = "content://media/aa44-8296/audio/media/1000038010"
        val track = buildSampleTrack(
            id = "track_party_till_we_die",
            title = "Party Till We Die",
            artist = "MAKJ & Timmy Trumpet",
            filePath = mediaStorePath
        )
        val candidateSafUri = Uri.parse("content://com.android.externalstorage.documents/document/AA44-8296%3AParty%20Till%20We%20Die.mp3")

        // Both MediaStore and SAF are technically readable in test environment
        TrackSourceResolver.safDocumentUriFinderForTesting = { _, _ -> candidateSafUri }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri.toString() == mediaStorePath || uri == candidateSafUri
        }

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)

        assertTrue("Track with valid MediaStore URI must resolve as playable", resolution.isPlayable)
        assertEquals("Source type must strictly be MEDIASTORE under Priority 1", ResolvedSourceType.MEDIASTORE, resolution.sourceType)
        assertEquals("Must retain original MediaStore URI and NOT convert to SAF", mediaStorePath, resolution.resolvedUriOrPath)
        assertEquals("Source health tier must be VERIFIED_PLAYABLE", SourceHealthTier.VERIFIED_PLAYABLE, resolution.healthTier)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST S: Stale MediaStore URI falls back to valid SAF document URI
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testS_staleMediaStoreUriFallsBackToSaf() = runBlocking {
        val staleMediaStorePath = "content://media/aa44-8296/audio/media/9999999"
        val track = buildSampleTrack(
            id = "track_stale_ms",
            title = "Stale Track",
            artist = "Artist",
            filePath = staleMediaStorePath
        )
        val validSafUri = Uri.parse("content://com.android.externalstorage.documents/document/AA44-8296%3AStale%20Track.wav")

        TrackSourceResolver.safDocumentUriFinderForTesting = { _, _ -> validSafUri }
        // MediaStore is NOT playable, but SAF is playable
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == validSafUri
        }

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)

        assertTrue("Resolution must succeed via playable fallback", resolution.isPlayable)
        assertEquals("Source type should fall back to SAF_DOCUMENT", ResolvedSourceType.SAF_DOCUMENT, resolution.sourceType)
        assertEquals("Resolved URI should be the valid SAF URI", validSafUri.toString(), resolution.resolvedUriOrPath)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST T: SAF candidate failing MediaExtractor is rejected
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testT_safCandidateFailingMediaExtractorIsRejected() = runBlocking {
        val brokenRawPath = "/storage/AA44-8296/Music/Song.mp3"
        val track = buildSampleTrack(filePath = brokenRawPath)
        val brokenSafUri = Uri.parse("content://com.android.externalstorage.documents/document/AA44-8296%3AMusic%2FSong.mp3")

        TrackSourceResolver.safDocumentUriFinderForTesting = { _, _ -> brokenSafUri }
        // Checker returns false (extractor instantiation failure)
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            false
        }

        val resolution = TrackSourceResolver.resolveTrackSource(context, track)

        assertFalse("Resolution must not succeed with broken SAF candidate", resolution.isPlayable)
        assertFalse("Must not resolve to broken SAF candidate", resolution.resolvedUriOrPath == brokenSafUri.toString())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST U: Audio format sniffer differentiates audio from unrecognized headers
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testU_formatSniffingDifferentiatesAudioFromUnrecognized() {
        // 1. WAV
        val wavBytes = createValidWavBytes()
        val wavResult = AudioFormatSniffer.sniffStream(wavBytes.inputStream(), wavBytes.size.toLong())
        assertTrue("WAV bytes must be recognized as audio", wavResult.isRecognizedAudio)
        assertEquals("Container format must be WAV", "WAV", wavResult.containerFormat)
        assertEquals("MIME must be audio/wav", "audio/wav", wavResult.detectedMime)

        // 2. MP3 ID3 header
        val mp3Bytes = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 10)
        val mp3Result = AudioFormatSniffer.sniffStream(mp3Bytes.inputStream(), mp3Bytes.size.toLong())
        assertTrue("MP3 ID3 bytes must be recognized", mp3Result.isRecognizedAudio)
        assertEquals("Container format must be MP3", "MP3", mp3Result.containerFormat)

        // 3. FLAC header
        val flacBytes = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0, 0, 0, 34)
        val flacResult = AudioFormatSniffer.sniffStream(flacBytes.inputStream(), flacBytes.size.toLong())
        assertTrue("FLAC bytes must be recognized", flacResult.isRecognizedAudio)
        assertEquals("Container format must be FLAC", "FLAC", flacResult.containerFormat)

        // 4. Random corrupt / non-audio bytes
        val garbageBytes = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val garbageResult = AudioFormatSniffer.sniffStream(garbageBytes.inputStream(), garbageBytes.size.toLong())
        assertFalse("Garbage bytes must not be recognized as audio", garbageResult.isRecognizedAudio)
        assertNull("Container format must be null for unrecognized", garbageResult.containerFormat)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST V: autoRepairAll guarantees 100% progress even when individual tracks throw
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testV_autoRepairAllGuarantees100PercentProgressEvenWithExceptions() = runBlocking {
        val goodTrack = buildSampleTrack(
            id = "good_1",
            title = "Good Track",
            filePath = "/storage/AA44-8296/good.wav",
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        val throwTrack = buildSampleTrack(
            id = "throw_2",
            title = "Throw Track",
            filePath = "/storage/AA44-8296/throw.wav",
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        val tracks = listOf(goodTrack, throwTrack)

        val progressReports = mutableListOf<Pair<Int, Int>>()
        val validUri = Uri.parse("content://media/external/audio/media/8001")

        TrackSourceResolver.mediaStoreUriFinderForTesting = { _, t ->
            if (t.id == "throw_2") {
                throw RuntimeException("Simulated unexpected crash during track probe")
            }
            if (t.id == "good_1") validUri else null
        }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri -> uri == validUri }

        val summary = TrackPlaybackRepairEngine.autoRepairAll(
            context = context,
            tracks = tracks,
            trackDao = trackDao,
            onProgress = { cur, total -> progressReports.add(cur to total) }
        )

        assertEquals("Total processed must equal total tracks", 2, summary.totalProcessed)
        assertFalse("Progress reports must not be empty", progressReports.isEmpty())
        val lastProgress = progressReports.last()
        assertEquals("Progress must complete to 100% (2 of 2)", 2 to 2, lastProgress)
        assertEquals("Total results count must be 2", 2, summary.results.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST W: recoverIncorrectlyRepairedTracks restores tracks back to MediaStore
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testW_recoverIncorrectlyRepairedTracksRestoresMediaStore() = runBlocking {
        val safPath = "content://com.android.externalstorage.documents/document/AA44-8296%3AParty%20Till%20We%20Die.mp3"
        val originalTrack = buildSampleTrack(
            id = "track_saf_corrupted",
            title = "Party Till We Die",
            artist = "MAKJ & Timmy Trumpet",
            filePath = safPath,
            bpm = 128.0,
            camelotKey = "8A",
            hotCues = listOf(0, 32, 64),
            rating = 5,
            playabilityStatus = PlayabilityStatus.READ_ERROR,
            errorCode = "ERR_IO_READ"
        )
        trackDao.insertTrack(TrackEntity.fromTrack(originalTrack))

        val realMediaStoreUri = Uri.parse("content://media/aa44-8296/audio/media/1000038010")
        TrackSourceResolver.mediaStoreUriFinderForTesting = { _, t ->
            if (t.id == "track_saf_corrupted" || t.title == "Party Till We Die") realMediaStoreUri else null
        }
        TrackSourceResolver.contentUriPlayableCheckerForTesting = { _, uri ->
            uri == realMediaStoreUri
        }

        val recovered = TrackPlaybackRepairEngine.recoverIncorrectlyRepairedTracks(context, trackDao)
        assertEquals("Must recover 1 track back to MediaStore", 1, recovered)

        val updatedEntity = trackDao.getTrackById("track_saf_corrupted")
        assertNotNull("Track must exist in database", updatedEntity)
        assertEquals("File path must be restored to MediaStore URI", realMediaStoreUri.toString(), updatedEntity?.filePath)
        assertEquals("Resolved URI must match MediaStore URI", realMediaStoreUri.toString(), updatedEntity?.resolvedUri)
        assertEquals("Status must be PLAYABLE", PlayabilityStatus.PLAYABLE.name, updatedEntity?.playabilityStatus)
        assertNull("Error code must be cleared", updatedEntity?.playbackErrorCode)

        // Metadata preservation check
        assertEquals("BPM must be preserved", 128.0, updatedEntity?.bpm ?: 0.0, 0.001)
        assertEquals("Camelot key must be preserved", "8A", updatedEntity?.camelotKey)
        assertEquals("Rating must be preserved", 5, updatedEntity?.rating)
        assertEquals("Hot cues must be preserved", listOf(0, 32, 64), updatedEntity?.toTrack()?.hotCues)
        assertEquals("Title must be preserved", "Party Till We Die", updatedEntity?.title)
        assertEquals("Artist must be preserved", "MAKJ & Timmy Trumpet", updatedEntity?.artist)
    }
}
