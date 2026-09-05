package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.DuplicateDetector
import com.example.data.*
import com.example.data.integrity.DatabaseIntegrityChecker
import com.example.data.integrity.IntegrityIssueType
import com.example.metadata.history.MetadataHistoryManager
import com.example.metadata.provider.*
import com.example.metadata.review.MetadataReviewManager
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import com.example.storage.AudioFingerprintUtil
import com.example.storage.SafeFileManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SoundSyncStep1FoundationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var testAudioFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = createInMemoryAppDatabase()

        testAudioFile = File(context.filesDir, "test_track_1.mp3").apply {
            writeBytes(ByteArray(1024) { 0x42.toByte() })
        }
    }

    @After
    fun tearDown() {
        db.close()
        testAudioFile.delete()
    }

    private fun createInMemoryAppDatabase(): AppDatabase {
        val tracks = mutableMapOf<String, TrackEntity>()
        val playlists = mutableMapOf<String, PlaylistEntity>()
        val playlistTracks = mutableListOf<PlaylistTrackEntity>()
        val historyEntries = mutableListOf<MetadataHistoryEntity>()
        val reviewEntries = mutableListOf<MetadataReviewItemEntity>()
        val watchedFolders = mutableListOf<WatchedFolderEntity>()

        val fakeTrackDao = java.lang.reflect.Proxy.newProxyInstance(
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
                "deleteTrack", "deleteTrackById" -> {
                    tracks.remove(args[0] as String)
                    null
                }
                "updateTrackPath" -> {
                    val id = args[0] as String
                    val path = args[1] as String
                    tracks[id]?.let { tracks[id] = it.copy(filePath = path) }
                    null
                }
                "findByFingerprint" -> {
                    val fp = args[0] as String
                    tracks.values.find { it.contentFingerprint == fp }
                }
                else -> null
            }
        } as TrackDao

        val fakePlaylistDao = java.lang.reflect.Proxy.newProxyInstance(
            PlaylistDao::class.java.classLoader,
            arrayOf(PlaylistDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertPlaylist" -> {
                    val p = args[0] as PlaylistEntity
                    playlists[p.id] = p
                    null
                }
                "getAllPlaylistsSync" -> {
                    playlists.values.toList()
                }
                "insertPlaylistTracks" -> {
                    val list = args[0] as List<PlaylistTrackEntity>
                    playlistTracks.addAll(list)
                    null
                }
                "getTracksForPlaylistSync" -> {
                    val pid = args[0] as String
                    playlistTracks.filter { it.playlistId == pid }
                }
                "removeTrackByEntryId", "deletePlaylistTrackById" -> {
                    val id = args[0] as Long
                    playlistTracks.removeIf { it.id == id }
                    null
                }
                else -> null
            }
        } as PlaylistDao

        val fakeHistoryDao = java.lang.reflect.Proxy.newProxyInstance(
            MetadataHistoryDao::class.java.classLoader,
            arrayOf(MetadataHistoryDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertHistory" -> {
                    historyEntries.add(0, args[0] as MetadataHistoryEntity)
                    null
                }
                "insertHistories" -> {
                    val list = args[0] as List<MetadataHistoryEntity>
                    historyEntries.addAll(0, list)
                    null
                }
                "getHistoryForTrack" -> {
                    val tid = args[0] as String
                    historyEntries.filter { it.trackId == tid }
                }
                "getRecentHistory" -> {
                    historyEntries.toList()
                }
                "pruneOldEntries" -> null
                else -> null
            }
        } as MetadataHistoryDao

        val fakeReviewDao = java.lang.reflect.Proxy.newProxyInstance(
            MetadataReviewInboxDao::class.java.classLoader,
            arrayOf(MetadataReviewInboxDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertItem" -> {
                    val item = args[0] as MetadataReviewItemEntity
                    reviewEntries.add(item)
                    null
                }
                "getPendingItems" -> {
                    reviewEntries.filter { it.status == "PENDING" }
                }
                "getItemById" -> {
                    val id = args[0] as String
                    reviewEntries.find { it.id == id }
                }
                "updateStatus" -> {
                    val id = args[0] as String
                    val status = args[1] as String
                    val idx = reviewEntries.indexOfFirst { it.id == id }
                    if (idx >= 0) reviewEntries[idx] = reviewEntries[idx].copy(status = status)
                    null
                }
                else -> null
            }
        } as MetadataReviewInboxDao

        val fakeWatchedDao = java.lang.reflect.Proxy.newProxyInstance(
            WatchedFolderDao::class.java.classLoader,
            arrayOf(WatchedFolderDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllWatchedFoldersSync" -> watchedFolders.toList()
                "insertWatchedFolder" -> {
                    watchedFolders.add(args[0] as WatchedFolderEntity)
                    null
                }
                "updateLastScanned" -> null
                else -> null
            }
        } as WatchedFolderDao

        return object : AppDatabase() {
            override fun trackDao(): TrackDao = fakeTrackDao
            override fun playlistDao(): PlaylistDao = fakePlaylistDao
            override fun metadataHistoryDao(): MetadataHistoryDao = fakeHistoryDao
            override fun metadataReviewInboxDao(): MetadataReviewInboxDao = fakeReviewDao
            override fun watchedFolderDao(): WatchedFolderDao = fakeWatchedDao
            override fun sourceFolderDao(): SourceFolderDao = java.lang.reflect.Proxy.newProxyInstance(SourceFolderDao::class.java.classLoader, arrayOf(SourceFolderDao::class.java)) { _, _, _ -> null } as SourceFolderDao
            override fun songFindDao(): SongFindDao = java.lang.reflect.Proxy.newProxyInstance(SongFindDao::class.java.classLoader, arrayOf(SongFindDao::class.java)) { _, _, _ -> null } as SongFindDao
            override fun playbackSessionDao(): PlaybackSessionDao = java.lang.reflect.Proxy.newProxyInstance(PlaybackSessionDao::class.java.classLoader, arrayOf(PlaybackSessionDao::class.java)) { _, _, _ -> null } as PlaybackSessionDao
            override fun bulkOperationHistoryDao(): BulkOperationHistoryDao = java.lang.reflect.Proxy.newProxyInstance(BulkOperationHistoryDao::class.java.classLoader, arrayOf(BulkOperationHistoryDao::class.java)) { _, _, _ -> null } as BulkOperationHistoryDao
            override fun clearAllTables() {
                tracks.clear()
                playlists.clear()
                playlistTracks.clear()
                historyEntries.clear()
                reviewEntries.clear()
            }
            override fun createInvalidationTracker(): androidx.room.InvalidationTracker = androidx.room.InvalidationTracker(this, "tracks")
            override fun close() {}
        }
    }

    private fun createSampleTrack(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Song",
        artist: String = "Test Artist",
        filePath: String = testAudioFile.absolutePath,
        bpm: Double = 124.0,
        contentFingerprint: String = "fp_test_12345"
    ): TrackEntity {
        return TrackEntity(
            id = id,
            title = title,
            artist = artist,
            album = "Test Album",
            genre = "Tech House",
            bpm = bpm,
            bpmConfidence = 0.95,
            musicalKey = "8A",
            camelotKey = "8A",
            durationSeconds = 180,
            bitrateKbps = 320,
            format = "MP3",
            filePath = filePath,
            contentFingerprint = contentFingerprint,
            fileModifiedTimestamp = testAudioFile.lastModified()
        )
    }

    // -------------------------------------------------------------
    // PART A: Database Integrity Checker Tests
    // -------------------------------------------------------------
    @Test
    fun `integrity checker detects missing physical files`() = runBlocking {
        val nonExistentPath = "/storage/emulated/0/Music/does_not_exist_99.mp3"
        val missingTrack = createSampleTrack(id = "missing_1", filePath = nonExistentPath)
        db.trackDao().insertTrack(missingTrack)

        val checker = DatabaseIntegrityChecker(context, db)
        val report = checker.scanIntegrity()

        assertEquals(1, report.missingFilesCount)
        assertTrue(report.issues.any { it.type == IntegrityIssueType.ORPHANED_TRACK && it.trackId == "missing_1" })
    }

    @Test
    fun `integrity checker detects and repairs broken playlist references`() = runBlocking {
        val playlist = PlaylistEntity(
            id = "pl_1",
            name = "Peak Hour Set",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isRockboxCompatible = false,
            isImported = false
        )
        db.playlistDao().insertPlaylist(playlist)

        // Add a playlist track pointing to non-existent track
        db.playlistDao().insertPlaylistTracks(
            listOf(PlaylistTrackEntity(id = 1, playlistId = "pl_1", trackId = "ghost_track", position = 0, dateAdded = 100L))
        )

        val checker = DatabaseIntegrityChecker(context, db)
        val report = checker.scanIntegrity()

        assertEquals(1, report.brokenPlaylistCount)
        val repaired = checker.repairSafeIssues()
        assertTrue(repaired >= 1)

        // After repair, broken playlist entry should be cleaned
        val remainingTracks = db.playlistDao().getTracksForPlaylistSync("pl_1")
        assertTrue(remainingTracks.isEmpty())
    }

    // -------------------------------------------------------------
    // PART B: Metadata History + Undo Tests
    // -------------------------------------------------------------
    @Test
    fun `metadata history records and undo restores previous value`() = runBlocking {
        val track = createSampleTrack(id = "track_undo", artist = "Original Artist")
        db.trackDao().insertTrack(track)

        val historyManager = MetadataHistoryManager(context, db)
        historyManager.recordChange(
            trackId = track.id,
            filePath = track.filePath,
            fieldChanged = "artist",
            previousValue = "Original Artist",
            newValue = "Changed Artist",
            source = "MANUAL",
            isAutomatic = false
        )

        // Apply change to trackDao
        db.trackDao().updateTrack(track.copy(artist = "Changed Artist"))
        assertEquals("Changed Artist", db.trackDao().getTrackById("track_undo")?.artist)

        // Perform undo
        val success = historyManager.undoLastChange("track_undo")
        assertTrue(success)

        val restored = db.trackDao().getTrackById("track_undo")
        assertEquals("Original Artist", restored?.artist)
    }

    // -------------------------------------------------------------
    // PART C & D: Metadata Confidence & Review Inbox Tests
    // -------------------------------------------------------------
    @Test
    fun `metadata review inbox submits item and handles user approval`() = runBlocking {
        val track = createSampleTrack(id = "track_inbox", title = "Old Title", artist = "Old Artist")
        db.trackDao().insertTrack(track)

        val reviewManager = MetadataReviewManager(context, db)
        val itemId = reviewManager.submitForReview(
            track = track,
            proposedArtist = "New Resolved Artist",
            proposedTitle = "New Resolved Title",
            proposedAlbum = "Remastered Album",
            provider = "Apple Music",
            confidenceScore = 75.0,
            evidenceSummary = "Duration matched, version matched"
        )

        val pending = reviewManager.getPendingItems()
        assertEquals(1, pending.size)
        assertEquals(75.0, pending[0].confidenceScore, 0.01)

        // User approves proposal
        val accepted = reviewManager.acceptAllProposed(itemId)
        assertTrue(accepted)

        val updated = db.trackDao().getTrackById("track_inbox")
        assertEquals("New Resolved Title", updated?.title)
        assertEquals("New Resolved Artist", updated?.artist)
        assertEquals("Remastered Album", updated?.album)
    }

    // -------------------------------------------------------------
    // PART E & F: Pluggable Metadata Provider Registry Tests
    // -------------------------------------------------------------
    @Test
    fun `metadata provider registry falls through to secondary provider`() = runBlocking {
        val mockProvider1 = object : MetadataProvider {
            override val name: String = "Failing Provider"
            override val supportedFields = setOf(MetadataField.TITLE, MetadataField.ARTIST)
            override var isEnabled: Boolean = true
            override suspend fun searchTrack(artist: String, title: String, durationSeconds: Int) = emptyList<CandidateMetadata>()
            override suspend fun fetchArtwork(album: String, artist: String): String? = null
        }

        val mockProvider2 = object : MetadataProvider {
            override val name: String = "Successful Fallback"
            override val supportedFields = setOf(MetadataField.TITLE, MetadataField.ARTIST)
            override var isEnabled: Boolean = true
            override suspend fun searchTrack(artist: String, title: String, durationSeconds: Int) = listOf(
                CandidateMetadata(
                    provider = name,
                    title = "Found Title",
                    artist = "Found Artist",
                    album = "Found Album",
                    confidence = 90.0
                )
            )
            override suspend fun fetchArtwork(album: String, artist: String): String? = "https://example.com/art.jpg"
        }

        val registry = MetadataProviderRegistry(listOf(mockProvider1, mockProvider2))
        registry.globalOrder = listOf("Failing Provider", "Successful Fallback")

        val results = registry.resolveTrackWithFallback("Artist", "Title", 180)
        assertEquals(1, results.size)
        assertEquals("Found Title", results[0].title)
        assertEquals("Successful Fallback", results[0].provider)
    }

    // -------------------------------------------------------------
    // PART G: Audio Fingerprint Tests
    // -------------------------------------------------------------
    @Test
    fun `audio fingerprint produces stable deterministic hash from audio file`() {
        val fp1 = AudioFingerprintUtil.generateFingerprint(context, testAudioFile.absolutePath, testAudioFile.length(), 180)
        val fp2 = AudioFingerprintUtil.generateFingerprint(context, testAudioFile.absolutePath, testAudioFile.length(), 180)

        assertNotNull(fp1)
        assertTrue(fp1.startsWith("fp_"))
        assertEquals(fp1, fp2)
    }

    // -------------------------------------------------------------
    // PART H: Duplicate Detector Version Discrimination Tests
    // -------------------------------------------------------------
    @Test
    fun `duplicate detector distinguishes different versions and remixes`() {
        val original = createSampleTrack(
            id = "t_orig",
            title = "Midnight Sun (Original Mix)",
            artist = "Solarstone",
            bpm = 138.0,
            contentFingerprint = "fp_orig_mix"
        ).toTrack()

        val remix = createSampleTrack(
            id = "t_remix",
            title = "Midnight Sun (Acoustic Version)",
            artist = "Solarstone",
            bpm = 138.0,
            contentFingerprint = "fp_acoustic_ver"
        ).toTrack()

        val matches = DuplicateDetector.findDuplicates(listOf(original, remix))
        // Should NOT be flagged as duplicate because versions are conflicting!
        assertTrue(matches.isEmpty())
    }

    // -------------------------------------------------------------
    // PART L: Safe File Operations Tests
    // -------------------------------------------------------------
    @Test
    fun `safe file rename updates database path while preserving track id`(): Unit = runBlocking {
        val track = createSampleTrack(id = "safe_rename_track", filePath = testAudioFile.absolutePath)
        db.trackDao().insertTrack(track)

        val result = SafeFileManager.renameTrackFile(context, db, track, "test_track_renamed")
        assertTrue(result is com.example.storage.FileOperationResult.Success)

        val updated = db.trackDao().getTrackById("safe_rename_track")
        assertNotNull(updated)
        assertTrue(updated!!.filePath.endsWith("test_track_renamed.mp3"))
        assertEquals("safe_rename_track", updated.id)
        assertEquals("Test Song", updated.title)

        // Restore file
        File(updated.filePath).renameTo(testAudioFile)
        Unit
    }
}
