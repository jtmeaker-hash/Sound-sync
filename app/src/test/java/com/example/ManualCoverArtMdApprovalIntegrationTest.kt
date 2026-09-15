package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.MetadataFileWriter
import com.example.metadata.MetadataFileWriteQueue
import com.example.metadata.MetadataWriteResult
import com.example.metadata.backup.MetadataBackupManager
import com.example.metadata.history.MetadataHistoryManager
import com.example.metadata.review.MetadataReviewManager
import com.example.model.MetadataWriteState
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.AudioValidationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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
class ManualCoverArtMdApprovalIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var reviewDao: MetadataReviewInboxDao
    private lateinit var reviewManager: MetadataReviewManager
    private lateinit var writeQueue: MetadataFileWriteQueue
    private lateinit var fileWriter: MetadataFileWriter

    private val sampleArtworkBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
        0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x48.toByte(),
        0x00.toByte(), 0x48.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(),
        0x00.toByte(), 0x08.toByte(), 0x06.toByte(), 0x06.toByte(),
        0x07.toByte(), 0x06.toByte(), 0x05.toByte(), 0x08.toByte(),
        0x07.toByte(), 0x07.toByte(), 0x07.toByte(), 0x09.toByte(),
        0x09.toByte(), 0x08.toByte(), 0x0A.toByte(), 0x0C.toByte(),
        0x14.toByte(), 0x0D.toByte(), 0x0C.toByte(), 0x0B.toByte(),
        0x0B.toByte(), 0x0C.toByte(), 0x19.toByte(), 0x12.toByte(),
        0x13.toByte(), 0x0F.toByte(), 0x14.toByte(), 0x1D.toByte(),
        0x1A.toByte(), 0x1F.toByte(), 0x1E.toByte(), 0x1D.toByte(),
        0x1A.toByte(), 0x1C.toByte(), 0x1C.toByte(), 0x20.toByte(),
        0x24.toByte(), 0x2E.toByte(), 0x27.toByte(), 0x20.toByte(),
        0x22.toByte(), 0x2C.toByte(), 0x23.toByte(), 0x1C.toByte(),
        0x1C.toByte(), 0x28.toByte(), 0x37.toByte(), 0x29.toByte(),
        0x2C.toByte(), 0x30.toByte(), 0x31.toByte(), 0x34.toByte(),
        0x34.toByte(), 0x34.toByte(), 0x1F.toByte(), 0x27.toByte(),
        0x39.toByte(), 0x3D.toByte(), 0x38.toByte(), 0x32.toByte(),
        0x3C.toByte(), 0x2E.toByte(), 0x33.toByte(), 0x34.toByte(),
        0x32.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00.toByte(),
        0x0B.toByte(), 0x08.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x11.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xC4.toByte(),
        0x00.toByte(), 0x1F.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x05.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(),
        0x05.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte(),
        0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0xFF.toByte(),
        0xDA.toByte(), 0x00.toByte(), 0x08.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3F.toByte(),
        0x00.toByte(), 0x7F.toByte(), 0x00.toByte(), 0xFF.toByte(),
        0xD9.toByte()
    )

    private val alternateArtworkBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
        0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x48.toByte(),
        0x00.toByte(), 0x48.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(),
        0x00.toByte(), 0x09.toByte(), 0x07.toByte(), 0x07.toByte(),
        0x08.toByte(), 0x07.toByte(), 0x06.toByte(), 0x09.toByte(),
        0x08.toByte(), 0x08.toByte(), 0x08.toByte(), 0x0A.toByte(),
        0xFF.toByte(), 0xD9.toByte()
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = createInMemoryAppDatabase()
        trackDao = database.trackDao()
        reviewDao = database.metadataReviewInboxDao()
        val historyManager = MetadataHistoryManager(context, database)
        val backupManager = MetadataBackupManager(context, database, historyManager)
        fileWriter = MetadataFileWriter(context, trackDao)
        writeQueue = MetadataFileWriteQueue.createForTesting(context, trackDao, fileWriter)
        MetadataFileWriteQueue.setInstanceForTesting(writeQueue)
        reviewManager = MetadataReviewManager(context, database, historyManager, backupManager)
    }

    @After
    fun tearDown() {
        MetadataFileWriteQueue.setInstanceForTesting(null)
        database.close()
    }

    private fun createSampleMp3File(file: File, frameCount: Int = 10): File {
        val frameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val frameSize = 417
        FileOutputStream(file).use { fos ->
            for (i in 0 until frameCount) {
                val frame = ByteArray(frameSize) { 0xAA.toByte() }
                frameHeader.copyInto(frame, 0)
                fos.write(frame)
            }
        }
        return file
    }

    private fun createSampleFlacFile(file: File, pcmData: ByteArray): File {
        val magic = "fLaC".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        val streaminfoHdr = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22)
        val streaminfo = ByteArray(34)
        streaminfo[0] = 0x10; streaminfo[1] = 0x00
        streaminfo[2] = 0x10; streaminfo[3] = 0x00
        streaminfo[10] = 0x0A.toByte(); streaminfo[11] = 0xC4.toByte(); streaminfo[12] = 0x42.toByte(); streaminfo[13] = 0xF0.toByte()
        val totalSamples = 44100
        streaminfo[14] = 0; streaminfo[15] = 0
        streaminfo[16] = ((totalSamples shr 8) and 0xFF).toByte()
        streaminfo[17] = (totalSamples and 0xFF).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(magic)
            fos.write(streaminfoHdr)
            fos.write(streaminfo)
            fos.write(pcmData)
        }
        return file
    }

    @Test
    fun `detects when track has no embedded artwork and queues manual cover`() = runBlocking {
        val mp3File = File(tempFolder.root, "no_art.mp3")
        createSampleMp3File(mp3File)

        val embeddedBefore = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath, includeArtworkBytes = true)
        assertFalse("Track initially must not have embedded artwork", embeddedBefore.hasEmbeddedArtwork)
        assertNull("Embedded artwork bytes must be null", embeddedBefore.embeddedArtworkBytes)

        val track = Track(
            id = "test-no-art-1",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            filePath = mp3File.absolutePath
        )
        val trackEntity = TrackEntity.fromTrack(track)
        trackDao.insertTrack(trackEntity)

        // Save selected artwork to cache
        val cacheFile = File(tempFolder.root, "cached_cover.jpg")
        cacheFile.writeBytes(sampleArtworkBytes)

        val itemId = reviewManager.submitManualArtworkChange(
            track = track,
            newArtworkCachePath = cacheFile.absolutePath,
            newArtworkUrl = "file://${cacheFile.absolutePath}",
            oldArtworkPathOrUrl = null,
            artworkMimeType = "image/jpeg"
        )

        assertNotNull(itemId)
        val pendingItem = reviewDao.getItemById(itemId)
        assertNotNull(pendingItem)
        assertEquals("Manual Cover", pendingItem?.provider)
        assertEquals(cacheFile.absolutePath, pendingItem?.artworkCachePath)
        assertEquals(100.0, pendingItem?.confidenceScore ?: 0.0, 0.01)
        assertEquals("PENDING", pendingItem?.status)

        // Track DB write state must transition to PENDING_APPROVAL
        val updatedTrack = trackDao.getTrackById(track.id)
        assertEquals(MetadataWriteState.PENDING_APPROVAL.name, updatedTrack?.metadataWriteState)
    }

    @Test
    fun `detects when selected cover differs from existing embedded artwork`() = runBlocking {
        val mp3File = File(tempFolder.root, "existing_art.mp3")
        createSampleMp3File(mp3File)

        // Pre-embed initial artwork
        val initialTrack = Track(
            id = "test-diff-art-1",
            title = "Different Art Song",
            artist = "Different Art Artist",
            album = "Different Art Album",
            filePath = mp3File.absolutePath
        )
        trackDao.insertTrack(TrackEntity.fromTrack(initialTrack))
        val initialWrite = fileWriter.writeAsync(initialTrack, artworkBytes = sampleArtworkBytes)
        assertTrue(initialWrite is MetadataWriteResult.Written || initialWrite is MetadataWriteResult.Partial)

        val embeddedBefore = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath, includeArtworkBytes = true)
        assertTrue(embeddedBefore.hasEmbeddedArtwork)
        assertNotNull(embeddedBefore.embeddedArtworkBytes)
        assertTrue(sampleArtworkBytes.contentEquals(embeddedBefore.embeddedArtworkBytes!!))

        // New candidate has different bytes
        val candidateDifferent = !alternateArtworkBytes.contentEquals(embeddedBefore.embeddedArtworkBytes)
        assertTrue("Candidate must be detected as different from embedded art", candidateDifferent)

        // Cache alternate cover
        val cacheFile = File(tempFolder.root, "alternate_cover.jpg")
        cacheFile.writeBytes(alternateArtworkBytes)

        val itemId = reviewManager.submitManualArtworkChange(
            track = initialTrack,
            newArtworkCachePath = cacheFile.absolutePath,
            newArtworkUrl = "file://${cacheFile.absolutePath}",
            oldArtworkPathOrUrl = "embedded",
            artworkMimeType = "image/jpeg"
        )

        val item = reviewDao.getItemById(itemId)
        assertNotNull(item)
        assertEquals("Manual Cover", item?.provider)
        assertEquals(cacheFile.absolutePath, item?.artworkCachePath)
    }

    @Test
    fun `merges manual cover into existing pending metadata approval without duplicates`() = runBlocking {
        val mp3File = File(tempFolder.root, "merge_test.mp3")
        createSampleMp3File(mp3File)

        val track = Track(
            id = "merge-track-1",
            title = "Original Title",
            artist = "Original Artist",
            album = "Original Album",
            filePath = mp3File.absolutePath
        )
        val entity = TrackEntity.fromTrack(track)
        trackDao.insertTrack(entity)

        // Simulate an automated scan already proposing a title & artist change
        val existingItemId = reviewManager.submitForReview(
            track = entity,
            proposedArtist = "Corrected Artist",
            proposedTitle = "Corrected Title",
            proposedAlbum = "Original Album",
            provider = "MusicBrainz",
            confidenceScore = 92.0,
            evidenceSummary = "Acoustic fingerprint match"
        )

        val pendingCountBefore = reviewDao.getPendingCount()
        assertEquals(1, pendingCountBefore)

        // Now user selects a manual cover in Track Inspector for the same track
        val cacheFile = File(tempFolder.root, "manual_art.jpg")
        cacheFile.writeBytes(sampleArtworkBytes)

        val mergedItemId = reviewManager.submitManualArtworkChange(
            track = track,
            newArtworkCachePath = cacheFile.absolutePath,
            newArtworkUrl = "file://${cacheFile.absolutePath}",
            oldArtworkPathOrUrl = null
        )

        // Must reuse existing item ID and NOT create duplicate
        assertEquals(existingItemId, mergedItemId)
        val pendingCountAfter = reviewDao.getPendingCount()
        assertEquals("Queue must still contain exactly 1 item (merged)", 1, pendingCountAfter)

        val mergedItem = reviewDao.getItemById(mergedItemId)
        assertNotNull(mergedItem)
        assertEquals("Corrected Artist", mergedItem?.proposedArtist)
        assertEquals("Corrected Title", mergedItem?.proposedTitle)
        assertEquals(cacheFile.absolutePath, mergedItem?.artworkCachePath)
        assertTrue(mergedItem?.evidenceSummary?.contains("Manual Cover") == true)
        assertTrue(mergedItem?.evidenceSummary?.contains("Acoustic fingerprint") == true)
    }

    @Test
    fun `standalone artwork mutation approves and writes physical tags to MP3 file`() = runBlocking {
        val mp3File = File(tempFolder.root, "standalone_art.mp3")
        createSampleMp3File(mp3File)

        val track = Track(
            id = "standalone-art-mp3",
            title = "Strobe",
            artist = "deadmau5",
            album = "Random Album Title",
            filePath = mp3File.absolutePath
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val cacheFile = File(tempFolder.root, "deadmau5_cover.jpg")
        cacheFile.writeBytes(sampleArtworkBytes)

        val itemId = reviewManager.submitManualArtworkChange(
            track = track,
            newArtworkCachePath = cacheFile.absolutePath,
            newArtworkUrl = "file://${cacheFile.absolutePath}",
            oldArtworkPathOrUrl = null
        )

        // Approve all proposed changes (which writes physical tags)
        val approved = reviewManager.acceptAllProposed(itemId)
        assertTrue("Approval and physical file write must succeed", approved)

        // Review inbox item must be marked ACCEPTED
        val item = reviewDao.getItemById(itemId)
        assertEquals("ACCEPTED", item?.status)

        // Verify physical file on disk has embedded artwork tag
        val validation = AudioTagWriter.validateRewrittenAudio(mp3File, "mp3", null)
        assertTrue("MP3 file after artwork write must remain valid: $validation", validation is AudioValidationResult.Valid)

        val readBack = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath, includeArtworkBytes = true)
        assertTrue("MP3 file must now contain embedded artwork", readBack.hasEmbeddedArtwork)
        assertNotNull("Embedded artwork bytes must not be null", readBack.embeddedArtworkBytes)
        assertTrue("Embedded artwork bytes must match original sample bytes", sampleArtworkBytes.contentEquals(readBack.embeddedArtworkBytes!!))

        // DB track must have ARTWORK_SAVED state
        val updatedTrack = trackDao.getTrackById(track.id)
        assertEquals(MetadataWriteState.ARTWORK_SAVED.name, updatedTrack?.metadataWriteState)
    }

    @Test
    fun `standalone artwork mutation approves and writes physical PICTURE block to FLAC file`() = runBlocking {
        val flacFile = File(tempFolder.root, "standalone_art.flac")
        createSampleFlacFile(flacFile, ByteArray(1200) { 0x44 })

        val track = Track(
            id = "standalone-art-flac",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            filePath = flacFile.absolutePath
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val cacheFile = File(tempFolder.root, "m83_cover.jpg")
        cacheFile.writeBytes(sampleArtworkBytes)

        val itemId = reviewManager.submitManualArtworkChange(
            track = track,
            newArtworkCachePath = cacheFile.absolutePath,
            newArtworkUrl = "file://${cacheFile.absolutePath}",
            oldArtworkPathOrUrl = null
        )

        val approved = reviewManager.acceptAllProposed(itemId)
        assertTrue("FLAC approval and write must succeed", approved)

        val item = reviewDao.getItemById(itemId)
        assertEquals("ACCEPTED", item?.status)

        val validation = AudioTagWriter.validateRewrittenAudio(flacFile, "flac", null)
        assertTrue("FLAC file after artwork write must be valid: $validation", validation is AudioValidationResult.Valid)

        val readBack = AudioEmbeddedMetadataReader.read(context, flacFile.absolutePath, includeArtworkBytes = true)
        assertTrue("FLAC file must contain embedded artwork", readBack.hasEmbeddedArtwork)
        assertNotNull("FLAC embedded artwork bytes must not be null", readBack.embeddedArtworkBytes)
        assertTrue(sampleArtworkBytes.contentEquals(readBack.embeddedArtworkBytes!!))
    }

    @Test
    fun `acceptSelectedFields with artwork writes artwork to file and updates track`() = runBlocking {
        val mp3File = File(tempFolder.root, "selected_fields.mp3")
        createSampleMp3File(mp3File)

        val track = Track(
            id = "selected-fields-track",
            title = "Original Title",
            artist = "Original Artist",
            album = "Original Album",
            filePath = mp3File.absolutePath
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val cacheFile = File(tempFolder.root, "selected_cover.jpg")
        cacheFile.writeBytes(sampleArtworkBytes)

        // Queue item with proposed title change AND artwork
        val itemId = reviewManager.submitManualArtworkChange(
            track = track,
            newArtworkCachePath = cacheFile.absolutePath,
            newArtworkUrl = "file://${cacheFile.absolutePath}",
            oldArtworkPathOrUrl = null
        )

        // Accept ONLY artwork, not other fields
        val accepted = reviewManager.acceptSelectedFields(itemId, setOf("artwork"))
        assertTrue("Accepting artwork field must succeed", accepted)

        val item = reviewDao.getItemById(itemId)
        assertEquals("ACCEPTED", item?.status)

        val readBack = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath, includeArtworkBytes = true)
        assertTrue("MP3 file must have embedded artwork", readBack.hasEmbeddedArtwork)
        assertTrue(sampleArtworkBytes.contentEquals(readBack.embeddedArtworkBytes!!))

        val updatedTrack = trackDao.getTrackById(track.id)
        assertEquals("Manual Cover", updatedTrack?.artworkSource)
        assertEquals(MetadataWriteState.ARTWORK_SAVED.name, updatedTrack?.metadataWriteState)
    }

    @Test
    fun `metadata write state transitions and display names are consistent`() {
        assertEquals("Pending approval", MetadataWriteState.PENDING_APPROVAL.displayName)
        assertEquals("Writing artwork...", MetadataWriteState.WRITING_ARTWORK.displayName)
        assertEquals("Artwork saved", MetadataWriteState.ARTWORK_SAVED.displayName)
        assertEquals("Permission required", MetadataWriteState.PERMISSION_REQUIRED.displayName)
        assertEquals("Write failed", MetadataWriteState.FILE_WRITE_FAILED.displayName)
        assertEquals("Unsupported format", MetadataWriteState.FORMAT_WRITE_UNSUPPORTED.displayName)
    }

    private fun createInMemoryAppDatabase(): AppDatabase {
        val tracks = mutableMapOf<String, TrackEntity>()
        val reviewEntries = mutableListOf<MetadataReviewItemEntity>()
        val backupEntries = mutableListOf<MetadataBackupEntity>()
        val historyEntries = mutableListOf<MetadataHistoryEntity>()

        val fakeTrackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertTrack" -> {
                    val t = args[0] as TrackEntity
                    tracks[t.id] = t
                    null
                }
                "insertTracks" -> {
                    val list = args[0] as List<TrackEntity>
                    list.forEach { tracks[it.id] = it }
                    null
                }
                "getTrackById" -> {
                    tracks[args[0] as String]
                }
                "getAllTracksSync", "getAllTracksList" -> {
                    tracks.values.toList()
                }
                "updateTrack" -> {
                    val t = args[0] as TrackEntity
                    tracks[t.id] = t
                    null
                }
                "updateMetadataWriteState" -> {
                    val id = args[0] as String
                    val state = args[1] as String
                    tracks[id]?.let { tracks[id] = it.copy(metadataWriteState = state) }
                    null
                }
                "deleteTrack", "deleteTrackById" -> {
                    tracks.remove(args[0] as String)
                    null
                }
                else -> null
            }
        } as TrackDao

        val fakeReviewDao = Proxy.newProxyInstance(
            MetadataReviewInboxDao::class.java.classLoader,
            arrayOf(MetadataReviewInboxDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertItem" -> {
                    val item = args[0] as MetadataReviewItemEntity
                    reviewEntries.removeIf { it.id == item.id }
                    reviewEntries.add(0, item)
                    null
                }
                "getItemById" -> {
                    val id = args[0] as String
                    reviewEntries.find { it.id == id }
                }
                "getPendingItemForTrack" -> {
                    val trackId = args[0] as String
                    reviewEntries.find { it.trackId == trackId && it.status == "PENDING" }
                }
                "updateStatus" -> {
                    val id = args[0] as String
                    val status = args[1] as String
                    val idx = reviewEntries.indexOfFirst { it.id == id }
                    if (idx >= 0) reviewEntries[idx] = reviewEntries[idx].copy(status = status)
                    null
                }
                "observePendingItems" -> {
                    kotlinx.coroutines.flow.flowOf(reviewEntries.filter { it.status == "PENDING" })
                }
                "observePendingCount" -> {
                    kotlinx.coroutines.flow.flowOf(reviewEntries.count { it.status == "PENDING" })
                }
                "getPendingCount" -> {
                    reviewEntries.count { it.status == "PENDING" }
                }
                "getPendingItems" -> {
                    reviewEntries.filter { it.status == "PENDING" }
                }
                else -> null
            }
        } as MetadataReviewInboxDao

        val fakeBackupDao = Proxy.newProxyInstance(
            MetadataBackupDao::class.java.classLoader,
            arrayOf(MetadataBackupDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertBackup" -> {
                    val b = args[0] as MetadataBackupEntity
                    backupEntries.removeIf { it.id == b.id }
                    backupEntries.add(0, b)
                    null
                }
                "getOriginalBackupForTrack" -> {
                    val trackId = args[0] as String
                    backupEntries.firstOrNull { it.trackId == trackId && it.isOriginalScanBackup }
                }
                "observeModifiedTracksCount" -> {
                    kotlinx.coroutines.flow.flowOf(backupEntries.map { it.trackId }.distinct().size)
                }
                else -> null
            }
        } as MetadataBackupDao

        val fakeHistoryDao = Proxy.newProxyInstance(
            MetadataHistoryDao::class.java.classLoader,
            arrayOf(MetadataHistoryDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertHistory" -> {
                    historyEntries.add(0, args[0] as MetadataHistoryEntity)
                    null
                }
                else -> null
            }
        } as MetadataHistoryDao

        return object : AppDatabase() {
            override fun trackDao(): TrackDao = fakeTrackDao
            override fun metadataReviewInboxDao(): MetadataReviewInboxDao = fakeReviewDao
            override fun metadataBackupDao(): MetadataBackupDao = fakeBackupDao
            override fun metadataHistoryDao(): MetadataHistoryDao = fakeHistoryDao
            override fun playlistDao(): PlaylistDao = Proxy.newProxyInstance(PlaylistDao::class.java.classLoader, arrayOf(PlaylistDao::class.java)) { _, _, _ -> null } as PlaylistDao
            override fun watchedFolderDao(): WatchedFolderDao = Proxy.newProxyInstance(WatchedFolderDao::class.java.classLoader, arrayOf(WatchedFolderDao::class.java)) { _, _, _ -> null } as WatchedFolderDao
            override fun sourceFolderDao(): SourceFolderDao = Proxy.newProxyInstance(SourceFolderDao::class.java.classLoader, arrayOf(SourceFolderDao::class.java)) { _, _, _ -> null } as SourceFolderDao
            override fun songFindDao(): SongFindDao = Proxy.newProxyInstance(SongFindDao::class.java.classLoader, arrayOf(SongFindDao::class.java)) { _, _, _ -> null } as SongFindDao
            override fun playbackSessionDao(): PlaybackSessionDao = Proxy.newProxyInstance(PlaybackSessionDao::class.java.classLoader, arrayOf(PlaybackSessionDao::class.java)) { _, _, _ -> null } as PlaybackSessionDao
            override fun bulkOperationHistoryDao(): BulkOperationHistoryDao = Proxy.newProxyInstance(BulkOperationHistoryDao::class.java.classLoader, arrayOf(BulkOperationHistoryDao::class.java)) { _, _, _ -> null } as BulkOperationHistoryDao
            override fun lyricsDao(): LyricsDao = Proxy.newProxyInstance(LyricsDao::class.java.classLoader, arrayOf(LyricsDao::class.java)) { _, _, _ -> null } as LyricsDao
            override fun trackBrainDao(): TrackBrainDao = Proxy.newProxyInstance(TrackBrainDao::class.java.classLoader, arrayOf(TrackBrainDao::class.java)) { _, _, _ -> null } as TrackBrainDao
            override fun djPrepDao(): com.example.djprep.DjPrepDao = Proxy.newProxyInstance(com.example.djprep.DjPrepDao::class.java.classLoader, arrayOf(com.example.djprep.DjPrepDao::class.java)) { _, _, _ -> null } as com.example.djprep.DjPrepDao
            override fun artistDao(): com.example.data.ArtistDao = Proxy.newProxyInstance(com.example.data.ArtistDao::class.java.classLoader, arrayOf(com.example.data.ArtistDao::class.java)) { _, _, _ -> null } as com.example.data.ArtistDao
            override fun clearAllTables() {
                tracks.clear()
                reviewEntries.clear()
                backupEntries.clear()
                historyEntries.clear()
            }
            override fun createInvalidationTracker(): androidx.room.InvalidationTracker = androidx.room.InvalidationTracker(this, "tracks")
            override fun close() {}
        }
    }
}
