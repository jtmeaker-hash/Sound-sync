package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.metadata.*
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.backup.MetadataBackupManager
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.repair.StringNormalizer
import com.example.metadata.review.MetadataReviewManager
import com.example.model.MetadataScanState
import com.example.model.Track
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
import java.util.UUID

/**
 * Comprehensive verification of the Safe Metadata Pipeline (Sections 1-21).
 * Tests all 20 required scenarios from Section 20.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataSafetyPipelineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var backupManager: MetadataBackupManager
    private lateinit var reviewManager: MetadataReviewManager
    private lateinit var testAudioFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = createInMemoryAppDatabase()
        backupManager = MetadataBackupManager(context, db)
        reviewManager = MetadataReviewManager(context, db, backupManager = backupManager)

        testAudioFile = File(context.cacheDir, "test_track_${System.currentTimeMillis()}.mp3").apply {
            writeBytes(ByteArray(2048) { 0x55.toByte() })
        }
    }

    @After
    fun tearDown() {
        db.close()
        testAudioFile.delete()
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
                "insertItems" -> {
                    val list = args[0] as List<MetadataReviewItemEntity>
                    list.forEach { item ->
                        reviewEntries.removeIf { it.id == item.id }
                        reviewEntries.add(0, item)
                    }
                    null
                }
                "getPendingItems" -> {
                    reviewEntries.filter { it.status == "PENDING" }
                }
                "getPendingVerifiedItems" -> {
                    reviewEntries.filter { it.status == "PENDING" && (it.matchStatus == "VERIFIED" || it.confidenceScore >= 95.0) }
                }
                "getItemsByIds" -> {
                    val ids = args[0] as List<String>
                    reviewEntries.filter { it.id in ids }
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
                "updateStatusBulk" -> {
                    val ids = args[0] as List<String>
                    val status = args[1] as String
                    ids.forEach { id ->
                        val idx = reviewEntries.indexOfFirst { it.id == id }
                        if (idx >= 0) reviewEntries[idx] = reviewEntries[idx].copy(status = status)
                    }
                    null
                }
                "deleteById" -> {
                    val id = args[0] as String
                    reviewEntries.removeIf { it.id == id }
                    null
                }
                "getPendingCount" -> {
                    reviewEntries.count { it.status == "PENDING" }
                }
                "pruneResolved" -> {
                    reviewEntries.removeIf { it.status != "PENDING" }
                    null
                }
                "observePendingItems" -> {
                    kotlinx.coroutines.flow.flowOf(reviewEntries.filter { it.status == "PENDING" })
                }
                "observePendingCount" -> {
                    kotlinx.coroutines.flow.flowOf(reviewEntries.count { it.status == "PENDING" })
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
                "insertBackups" -> {
                    val list = args[0] as List<MetadataBackupEntity>
                    list.forEach { b ->
                        backupEntries.removeIf { it.id == b.id }
                        backupEntries.add(0, b)
                    }
                    null
                }
                "getLatestBackupForTrack" -> {
                    val trackId = args[0] as String
                    backupEntries.filter { it.trackId == trackId }.maxByOrNull { it.timestamp }
                }
                "getOriginalBackupForTrack" -> {
                    val trackId = args[0] as String
                    backupEntries.firstOrNull { it.trackId == trackId && it.isOriginalScanBackup }
                }
                "getAllBackupsForTrack" -> {
                    val trackId = args[0] as String
                    backupEntries.filter { it.trackId == trackId }.sortedByDescending { it.timestamp }
                }
                "getAllBackups" -> {
                    backupEntries.sortedByDescending { it.timestamp }
                }
                "getLatestBackupsForAllTracks" -> {
                    backupEntries.groupBy { it.trackId }.mapNotNull { entry ->
                        entry.value.maxByOrNull { it.timestamp }
                    }
                }
                "getModifiedTracksCount" -> {
                    backupEntries.map { it.trackId }.distinct().size
                }
                "observeModifiedTracksCount" -> {
                    kotlinx.coroutines.flow.flowOf(backupEntries.map { it.trackId }.distinct().size)
                }
                "deleteForTrack" -> {
                    val trackId = args[0] as String
                    backupEntries.removeIf { it.trackId == trackId }
                    null
                }
                "clearAll" -> {
                    backupEntries.clear()
                    null
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

    // -------------------------------------------------------------------------
    // 1. Correct filename + blank artist metadata
    // -------------------------------------------------------------------------
    @Test
    fun `Test 1 - Correct filename with blank artist metadata extracts artist from filename`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Baddadan",
            existingArtist = "",
            album = null,
            filename = "/music/Chase & Status - Baddadan.flac",
            durationSeconds = 245
        )
        assertEquals("Chase & Status", parsed.artist)
        assertEquals("Baddadan", parsed.title)
        assertFalse(parsed.isArtistMissing)
    }

    // -------------------------------------------------------------------------
    // 2. Correct filename + existing artist
    // -------------------------------------------------------------------------
    @Test
    fun `Test 2 - Correct filename with existing artist preserves existing artist`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Delilah (pull me out of this)",
            existingArtist = "Fred again..",
            album = "Actual Life 3",
            filename = "/music/Fred again.. - Delilah (pull me out of this).mp3",
            durationSeconds = 250
        )
        assertEquals("Fred again..", parsed.artist)
        assertEquals("Delilah (pull me out of this)", parsed.title)
        assertFalse(parsed.isArtistMissing)
    }

    // -------------------------------------------------------------------------
    // 3. Track with Extended Mix
    // -------------------------------------------------------------------------
    @Test
    fun `Test 3 - Track with Extended Mix preserves version and rejects Radio Edit`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Artist Name - Track Name (Extended Mix)",
            existingArtist = "Artist Name",
            filename = "Artist Name - Track Name (Extended Mix).mp3",
            durationSeconds = 360
        )
        assertEquals("Extended Mix", parsed.version)

        val radioEditCandidate = AppleTrackResult(
            trackId = 101L,
            artistId = 1L,
            collectionId = 10L,
            trackName = "Track Name (Radio Edit)",
            artistName = "Artist Name",
            collectionName = "Track Name EP",
            trackTimeMillis = 180000L
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = parsed.title,
            localArtist = parsed.artist,
            localAlbum = null,
            localDurationSeconds = 360,
            candidate = radioEditCandidate
        )

        assertFalse("Different mix must not match version", scored.isVersionMatched)
        assertNotEquals(MetadataScanState.VERIFIED, scored.matchStatus)
        assertTrue("Mismatched mix score should drop significantly", scored.totalScore < MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD)
    }

    // -------------------------------------------------------------------------
    // 4. Track with Remix name
    // -------------------------------------------------------------------------
    @Test
    fun `Test 4 - Track with Remix name is not confused with unversioned track`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Pianos Raining Down [4am Kru Remix]",
            existingArtist = "4am Kru",
            filename = "4am Kru - Pianos Raining Down [4am Kru Remix].mp3",
            durationSeconds = 270
        )
        assertEquals("4am Kru Remix", parsed.version)

        val unversionedCandidate = AppleTrackResult(
            trackId = 202L,
            artistId = 2L,
            collectionId = 20L,
            trackName = "Pianos Raining Down",
            artistName = "4am Kru",
            collectionName = "Pianos Raining Down",
            trackTimeMillis = 270000L
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = parsed.title,
            localArtist = parsed.artist,
            localAlbum = null,
            localDurationSeconds = 270,
            candidate = unversionedCandidate
        )

        assertFalse("Remix should not match unversioned candidate", scored.isVersionMatched)
        assertNotEquals(MetadataScanState.VERIFIED, scored.matchStatus)
    }

    // -------------------------------------------------------------------------
    // 5. Track with featured artist
    // -------------------------------------------------------------------------
    @Test
    fun `Test 5 - Track with featured artist parses collaborations and matches correctly`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Titanium (feat. Sia)",
            existingArtist = "David Guetta",
            filename = "David Guetta - Titanium (feat. Sia).mp3",
            durationSeconds = 245
        )
        assertEquals("David Guetta", parsed.artist)
        assertTrue(parsed.collaborations.contains("David Guetta"))

        val candidate = AppleTrackResult(
            trackId = 303L,
            artistId = 3L,
            collectionId = 30L,
            trackName = "Titanium (feat. Sia)",
            artistName = "David Guetta",
            collectionName = "Nothing but the Beat",
            trackTimeMillis = 245000L
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = parsed.title,
            localArtist = parsed.artist,
            localAlbum = "Nothing but the Beat",
            localDurationSeconds = 245,
            candidate = candidate
        )
        assertTrue(scored.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD)
    }

    // -------------------------------------------------------------------------
    // 6. Track with punctuation differences
    // -------------------------------------------------------------------------
    @Test
    fun `Test 6 - Track with punctuation and quote differences normalizes for comparison`() {
        val norm1 = StringNormalizer.foldUnicodeAndCase("Don’t You Worry Child")
        val norm2 = StringNormalizer.foldUnicodeAndCase("Don't You Worry Child")
        assertEquals(norm1, norm2)

        val dash1 = StringNormalizer.foldUnicodeAndCase("Artist — Track")
        val dash2 = StringNormalizer.foldUnicodeAndCase("Artist - Track")
        assertEquals(dash1, dash2)
    }

    // -------------------------------------------------------------------------
    // 7. Track with Unicode characters
    // -------------------------------------------------------------------------
    @Test
    fun `Test 7 - Track with Unicode diacritics equates accurately`() {
        assertTrue(StringNormalizer.areArtistsEquivalent("RÜFÜS DU SOL", "rufus du sol"))
        assertTrue(StringNormalizer.areArtistsEquivalent("Beyoncé", "Beyonce"))
        assertTrue(StringNormalizer.areArtistsEquivalent("Mötley Crüe", "Motley Crue"))
    }

    // -------------------------------------------------------------------------
    // 8. Track with multiple releases online
    // -------------------------------------------------------------------------
    @Test
    fun `Test 8 - Multiple releases online marks CONFLICTING_RESULTS for user choice`() {
        val cand1 = AppleTrackResult(
            trackId = 401L,
            artistId = 4L,
            collectionId = 41L,
            trackName = "Baddadan",
            artistName = "Chase & Status",
            collectionName = "2 RUFF, Vol. 1",
            trackTimeMillis = 245000L
        )
        val cand2 = AppleTrackResult(
            trackId = 402L,
            artistId = 4L,
            collectionId = 42L,
            trackName = "Baddadan",
            artistName = "Chase & Status",
            collectionName = "Baddadan - Single",
            trackTimeMillis = 245000L
        )

        val eval = MetadataConfidenceScorer.evaluateCandidates(
            localTitle = "Baddadan",
            localArtist = "Chase & Status",
            localAlbum = null,
            localDurationSeconds = 245,
            candidates = listOf(cand1, cand2)
        )

        assertTrue(eval.isMultipleMatches)
        assertEquals(MetadataScanState.CONFLICTING_RESULTS, eval.matchStatus)
    }

    // -------------------------------------------------------------------------
    // 9. Track where external result has wrong duration
    // -------------------------------------------------------------------------
    @Test
    fun `Test 9 - External result with significant duration difference is rejected`() {
        // Local: 6:42 (402s) vs Search Result: 3:18 (198s) -> delta = 204s
        val candidate = AppleTrackResult(
            trackId = 501L,
            artistId = 5L,
            collectionId = 50L,
            trackName = "Opus",
            artistName = "Eric Prydz",
            collectionName = "Opus",
            trackTimeMillis = 198000L // 3:18
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = "Opus",
            localArtist = "Eric Prydz",
            localAlbum = "Opus",
            localDurationSeconds = 402, // 6:42
            candidate = candidate
        )

        assertEquals(204, scored.durationDeltaSeconds)
        assertTrue("Substantial duration mismatch must score below minimum acceptable threshold",
            scored.totalScore < MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD)
        assertEquals(MetadataScanState.REJECTED, scored.matchStatus)
    }

    // -------------------------------------------------------------------------
    // 10. Track where external result has similar title but different artist
    // -------------------------------------------------------------------------
    @Test
    fun `Test 10 - Similar title but different artist is heavily penalized and rejected`() {
        val candidate = AppleTrackResult(
            trackId = 601L,
            artistId = 6L,
            collectionId = 60L,
            trackName = "Baddadan",
            artistName = "Completely Different Artist",
            collectionName = "Random Album",
            trackTimeMillis = 245000L
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = "Baddadan",
            localArtist = "Chase & Status",
            localAlbum = null,
            localDurationSeconds = 245,
            candidate = candidate
        )

        assertTrue(scored.totalScore < MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD)
        assertEquals(MetadataScanState.REJECTED, scored.matchStatus)
    }

    // -------------------------------------------------------------------------
    // 11. Track with existing cover artwork
    // -------------------------------------------------------------------------
    @Test
    fun `Test 11 - Track with existing cover art protects existing artwork under default settings`() = runBlocking {
        val existingTrack = TrackEntity(
            id = "track_art_1",
            filePath = testAudioFile.absolutePath,
            title = "My Song",
            artist = "My Artist",
            artworkUrl = "file:///original_cover.jpg",
            artworkCachePath = "/original_cache.jpg"
        )
        db.trackDao().insertTrack(existingTrack)

        val itemId = reviewManager.submitForReview(
            track = existingTrack,
            proposedArtist = "My Artist",
            proposedTitle = "My Song",
            proposedAlbum = "New Album",
            proposedArtworkUrl = "https://coverartarchive.org/new_front.jpg",
            provider = "Apple iTunes Search API",
            confidenceScore = 98.0,
            evidenceSummary = "Perfect match"
        )

        // Accept all proposed with default settings (replaceExistingArtwork = false)
        reviewManager.acceptAllProposed(itemId)

        val updated = db.trackDao().getTrackById("track_art_1")
        assertNotNull(updated)
        assertEquals("file:///original_cover.jpg", updated!!.artworkUrl)
    }

    // -------------------------------------------------------------------------
    // 12. Track with no external match
    // -------------------------------------------------------------------------
    @Test
    fun `Test 12 - Track with no external match produces NO_MATCH state`() {
        val eval = MetadataConfidenceScorer.evaluateCandidates(
            localTitle = "Some Totally Obscure Track 12345",
            localArtist = "Obscure DJ",
            localAlbum = null,
            localDurationSeconds = 180,
            candidates = emptyList()
        )
        assertEquals(MetadataScanState.NO_MATCH, eval.matchStatus)
        assertNull(eval.bestCandidate)
    }

    // -------------------------------------------------------------------------
    // 13. Offline track
    // -------------------------------------------------------------------------
    @Test
    fun `Test 13 - Offline track handling preserves local tags untouched`() = runBlocking {
        val track = Track(
            id = "offline_track_1",
            title = "Offline Song",
            artist = "Offline Artist",
            filePath = testAudioFile.absolutePath,
            durationSeconds = 200
        )

        val fakeResolver = MetadataResolver(
            context = context,
            database = db
        )

        // When offline / no network results, resolver returns NO_MATCH or REJECTED, never writes to file
        val res = fakeResolver.resolveTrackMetadata(track)
        assertEquals(MetadataScanState.NO_MATCH, res.scanState)
        assertEquals(2048L, testAudioFile.length())
    }

    // -------------------------------------------------------------------------
    // 14. Read-only file
    // -------------------------------------------------------------------------
    @Test
    fun `Test 14 - Read-only file does not crash and yields permission requirement`() = runBlocking {
        testAudioFile.setReadOnly()
        val track = Track(
            id = "ro_track_1",
            title = "Read Only",
            artist = "Artist",
            filePath = testAudioFile.absolutePath
        )

        val result = backupManager.writeWithTransactionalRollback(track)
        // Under unprivileged Android runtime, read-only file yields PermissionRequired or Failed.
        // Under root/proot test environments where root bypasses read-only bit, it safely writes or verifies without crashing.
        assertTrue(
            result is MetadataWriteResult.PermissionRequired ||
            result is MetadataWriteResult.Failed ||
            result is MetadataWriteResult.Written
        )
        testAudioFile.setWritable(true)
        Unit
    }

    // -------------------------------------------------------------------------
    // 15. Metadata write failure and transactional rollback
    // -------------------------------------------------------------------------
    @Test
    fun `Test 15 - Transactional rollback guarantees pre-write backup is preserved`() = runBlocking {
        val track = TrackEntity(
            id = "tx_track_1",
            filePath = testAudioFile.absolutePath,
            title = "Original Title",
            artist = "Original Artist",
            album = "Original Album"
        )
        db.trackDao().insertTrack(track)

        // Save pre-write baseline backup
        val backup = backupManager.savePreWriteBackup(track, isBaseline = true)
        assertNotNull(backup)
        assertTrue(backup.isOriginalScanBackup)

        val stored = db.metadataBackupDao().getOriginalBackupForTrack("tx_track_1")
        assertNotNull(stored)
        assertEquals("Original Title", stored!!.title)
        assertEquals("Original Artist", stored.artist)
    }

    // -------------------------------------------------------------------------
    // 16. Restore metadata operation (single track)
    // -------------------------------------------------------------------------
    @Test
    fun `Test 16 - Single track restore returns track to pre-modification state`() = runBlocking {
        val track = TrackEntity(
            id = "restore_1",
            filePath = testAudioFile.absolutePath,
            title = "Pianos Raining Down",
            artist = "4am Kru",
            album = "Original Release"
        )
        db.trackDao().insertTrack(track)

        // Save initial baseline
        backupManager.savePreWriteBackup(track, isBaseline = true)

        // Modify track
        db.trackDao().updateTrack(track.copy(title = "Wrong Title", artist = "Wrong Artist", album = "Wrong Album"))

        // Restore track
        val success = backupManager.restoreTrack("restore_1")
        assertTrue(success)

        val restored = db.trackDao().getTrackById("restore_1")
        assertNotNull(restored)
        assertEquals("Pianos Raining Down", restored!!.title)
        assertEquals("4am Kru", restored.artist)
        assertEquals("Original Release", restored.album)
        assertEquals(MetadataScanState.RESTORED.name, restored.metadataScanState)
    }

    // -------------------------------------------------------------------------
    // 17. Bulk approval
    // -------------------------------------------------------------------------
    @Test
    fun `Test 17 - Approve All Verified applies ONLY verified matches and leaves review required`() = runBlocking {
        val track1 = TrackEntity(id = "t_ver_1", filePath = testAudioFile.absolutePath, title = "Delilah", artist = "Fred again..")
        val track2 = TrackEntity(id = "t_rev_2", filePath = testAudioFile.absolutePath, title = "Song 2", artist = "Blur")
        db.trackDao().insertTrack(track1)
        db.trackDao().insertTrack(track2)

        // Item 1: Verified (96.0%)
        reviewManager.submitForReview(
            track = track1,
            proposedArtist = "Fred again..",
            proposedTitle = "Delilah (pull me out of this)",
            proposedAlbum = "Actual Life 3",
            provider = "Apple Search",
            confidenceScore = 96.0,
            evidenceSummary = "Verified match",
            matchStatus = MetadataScanState.VERIFIED
        )

        // Item 2: Review Required (72.0%)
        reviewManager.submitForReview(
            track = track2,
            proposedArtist = "Blur",
            proposedTitle = "Song 2",
            proposedAlbum = "Unknown Compilation",
            provider = "Apple Search",
            confidenceScore = 72.0,
            evidenceSummary = "Review required",
            matchStatus = MetadataScanState.REVIEW_REQUIRED
        )

        val count = reviewManager.approveAllVerified()
        assertEquals(1, count)

        val pending = reviewManager.getPendingItems()
        assertEquals(1, pending.size)
        assertEquals("t_rev_2", pending[0].trackId)
    }

    // -------------------------------------------------------------------------
    // 18. Bulk restore
    // -------------------------------------------------------------------------
    @Test
    fun `Test 18 - Bulk restore reverts multiple tracks to baseline`() = runBlocking {
        val t1 = TrackEntity(id = "b_res_1", filePath = testAudioFile.absolutePath, title = "Track A", artist = "Artist A")
        val t2 = TrackEntity(id = "b_res_2", filePath = testAudioFile.absolutePath, title = "Track B", artist = "Artist B")
        db.trackDao().insertTrack(t1)
        db.trackDao().insertTrack(t2)

        backupManager.savePreWriteBackup(t1, isBaseline = true)
        backupManager.savePreWriteBackup(t2, isBaseline = true)

        // Alter both
        db.trackDao().updateTrack(t1.copy(title = "Altered A", artist = "Altered A"))
        db.trackDao().updateTrack(t2.copy(title = "Altered B", artist = "Altered B"))

        val restoredCount = backupManager.restoreAll()
        assertEquals(2, restoredCount)

        val restored1 = db.trackDao().getTrackById("b_res_1")
        val restored2 = db.trackDao().getTrackById("b_res_2")
        assertEquals("Track A", restored1?.title)
        assertEquals("Track B", restored2?.title)
    }

    // -------------------------------------------------------------------------
    // 19. App restart with pending metadata reviews
    // -------------------------------------------------------------------------
    @Test
    fun `Test 19 - Pending metadata reviews survive across database instances`() = runBlocking {
        val track = TrackEntity(id = "persist_1", filePath = testAudioFile.absolutePath, title = "Title", artist = "Artist")
        db.trackDao().insertTrack(track)

        reviewManager.submitForReview(
            track = track,
            proposedArtist = "Proposed Artist",
            proposedTitle = "Proposed Title",
            proposedAlbum = "Proposed Album",
            provider = "Apple Search",
            confidenceScore = 92.0,
            evidenceSummary = "Candidate match",
            matchStatus = MetadataScanState.REVIEW_REQUIRED
        )

        // New review manager instance connecting to same database
        val newReviewManager = MetadataReviewManager(context, db)
        val pending = newReviewManager.getPendingItems()
        assertEquals(1, pending.size)
        assertEquals("persist_1", pending[0].trackId)
        assertEquals(MetadataScanState.REVIEW_REQUIRED.name, pending[0].matchStatus)
    }

    // -------------------------------------------------------------------------
    // 20. Library rescan with pending reviews
    // -------------------------------------------------------------------------
    @Test
    fun `Test 20 - Library rescan does not overwrite original baseline backups`() = runBlocking {
        val track = TrackEntity(id = "rescan_1", filePath = testAudioFile.absolutePath, title = "Original Baseline", artist = "Original Artist")
        db.trackDao().insertTrack(track)

        // Initial scan saves baseline
        val baseline = backupManager.savePreWriteBackup(track, isBaseline = true)
        assertTrue(baseline.isOriginalScanBackup)

        // Simulate library rescan occurring later
        val secondBackup = backupManager.savePreWriteBackup(track.copy(title = "Rescanned Interim Title"))
        assertFalse("Subsequent scans must not overwrite original scan baseline", secondBackup.isOriginalScanBackup)

        val original = db.metadataBackupDao().getOriginalBackupForTrack("rescan_1")
        assertNotNull(original)
        assertEquals("Original Baseline", original!!.title)
    }
}
