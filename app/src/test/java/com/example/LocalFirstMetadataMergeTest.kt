package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.metadata.merge.CandidateMetadata
import com.example.metadata.merge.LocalFirstMetadataMerger
import com.example.metadata.merge.MetadataSourceProvenance
import com.example.metadata.merge.TrackFieldProvenance
import com.example.metadata.review.MetadataReviewManager
import com.example.model.MetadataScanState
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalFirstMetadataMergeTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var reviewDao: MetadataReviewInboxDao
    private lateinit var reviewManager: MetadataReviewManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = createInMemoryAppDatabase()
        trackDao = database.trackDao()
        reviewDao = database.metadataReviewInboxDao()
        reviewManager = MetadataReviewManager(context, database)
    }

    @After
    fun tearDown() {
        database.close()
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
                "upsertPhysicalTrack" -> {
                    val track = args[0] as TrackEntity
                    val existing = (if (track.physicalMediaKey.isNotBlank()) tracks.values.find { it.physicalMediaKey == track.physicalMediaKey } else null)
                        ?: (if (track.mediaStoreId != null) tracks.values.find { it.mediaStoreId == track.mediaStoreId } else null)
                        ?: (if (track.filePath.isNotBlank()) tracks.values.find { it.filePath == track.filePath } else null)
                        ?: tracks[track.id]
                    if (existing != null) {
                        val merged = existing.copy(
                            title = if (existing.userConfirmedMetadata || (existing.title.isNotBlank() && existing.title != "<unknown>" && !existing.title.startsWith("Track "))) existing.title else track.title,
                            artist = if (existing.userConfirmedMetadata || (existing.artist.isNotBlank() && existing.artist != "<unknown>" && existing.artist != "Unknown Artist")) existing.artist else track.artist,
                            album = if (existing.userConfirmedMetadata || (existing.album.isNotBlank() && existing.album != "<unknown>" && existing.album != "Single")) existing.album else track.album,
                            bpm = if (existing.bpm > 0.0) existing.bpm else track.bpm,
                            bpmConfidence = if (existing.bpm > 0.0) existing.bpmConfidence else track.bpmConfidence,
                            musicalKey = if (existing.musicalKey.isNotBlank()) existing.musicalKey else track.musicalKey,
                            isManualBpm = existing.isManualBpm,
                            isManualKey = existing.isManualKey,
                            userConfirmedMetadata = existing.userConfirmedMetadata || track.userConfirmedMetadata,
                            fieldProvenanceJson = if (existing.fieldProvenanceJson.isNotBlank() && existing.fieldProvenanceJson != "{}") existing.fieldProvenanceJson else track.fieldProvenanceJson
                        )
                        tracks[merged.id] = merged
                        0L
                    } else {
                        tracks[track.id] = track
                        1L
                    }
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

    @Test
    fun `1 - Empty artist with valid internet artist fills artist`() {
        val localTrack = Track(
            id = "t1",
            title = "Get Lucky",
            artist = "",
            filePath = "/storage/emulated/0/Music/get_lucky.mp3"
        )
        val candidate = CandidateMetadata(
            title = "Get Lucky",
            artist = "Daft Punk",
            album = "Random Access Memories",
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 92.0,
            matchState = MetadataScanState.VERIFIED
        )

        assertEquals("Daft Punk", result.mergedTrack.artist)
        assertTrue(result.wasRepaired)
        assertEquals(MetadataSourceProvenance.ONLINE_PROVIDER, result.appliedProvenances["artist"])
        assertFalse(result.conflicts.any { it.fieldName == "artist" })
    }

    @Test
    fun `2 - Valid local artist with different online artist keeps local`() {
        val localTrack = Track(
            id = "t2",
            title = "Get Lucky",
            artist = "Daft Punk",
            filePath = "/storage/emulated/0/Music/get_lucky.mp3"
        )
        val candidate = CandidateMetadata(
            title = "Get Lucky",
            artist = "Thomas Bangalter",
            album = "Random Access Memories",
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 88.0,
            matchState = MetadataScanState.REVIEW_REQUIRED
        )

        // Local artist is preserved!
        assertEquals("Daft Punk", result.mergedTrack.artist)
        // Conflict is recorded for user review
        val artistConflict = result.conflicts.find { it.fieldName == "artist" }
        assertNotNull(artistConflict)
        assertEquals("Daft Punk", artistConflict!!.localValue)
        assertEquals("Thomas Bangalter", artistConflict.proposedValue)
    }

    @Test
    fun `3 - User-edited title with conflicting online title keeps user edit`() {
        val localTrack = Track(
            id = "t3",
            title = "One More Time (Club VIP Edit)",
            artist = "Daft Punk",
            userConfirmedMetadata = true,
            filePath = "/storage/emulated/0/Music/one_more_time.mp3"
        )
        val candidate = CandidateMetadata(
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 95.0,
            matchState = MetadataScanState.VERIFIED
        )

        // User edit is strictly immutable
        assertEquals("One More Time (Club VIP Edit)", result.mergedTrack.title)
        assertEquals("Daft Punk", result.mergedTrack.artist)
        val titleConflict = result.conflicts.find { it.fieldName == "title" }
        assertNotNull(titleConflict)
        assertEquals(MetadataSourceProvenance.USER_EDIT, titleConflict!!.localProvenance)
    }

    @Test
    fun `4 - Local artwork present with online artwork present keeps local artwork`() {
        val localTrack = Track(
            id = "t4",
            title = "Strobe",
            artist = "Deadmau5",
            artworkUrl = "/local/custom_art.jpg",
            artworkCachePath = "/local/custom_art.jpg",
            filePath = "/storage/emulated/0/Music/strobe.mp3"
        )
        val candidate = CandidateMetadata(
            title = "Strobe",
            artist = "Deadmau5",
            album = "For Lack of a Better Name",
            artworkUrl = "https://apple.com/1200x1200bb.jpg",
            artworkCachePath = "/cache/apple_art.jpg",
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 95.0,
            matchState = MetadataScanState.VERIFIED,
            replaceExistingArtworkSetting = false
        )

        // Local artwork is preserved
        assertEquals("/local/custom_art.jpg", result.mergedTrack.artworkUrl)
        assertEquals("/local/custom_art.jpg", result.mergedTrack.artworkCachePath)
        val artConflict = result.conflicts.find { it.fieldName == "artwork" }
        assertNotNull(artConflict)
    }

    @Test
    fun `5 - Missing artwork is filled by online artwork after confident match`() {
        val localTrack = Track(
            id = "t5",
            title = "Levels",
            artist = "Avicii",
            artworkUrl = null,
            artworkCachePath = null,
            filePath = "/storage/emulated/0/Music/levels.mp3"
        )
        val candidate = CandidateMetadata(
            title = "Levels",
            artist = "Avicii",
            album = "Levels - EP",
            artworkUrl = "https://apple.com/1200x1200bb.jpg",
            artworkCachePath = "/cache/levels_art.jpg",
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 95.0,
            matchState = MetadataScanState.VERIFIED
        )

        assertEquals("https://apple.com/1200x1200bb.jpg", result.mergedTrack.artworkUrl)
        assertEquals("/cache/levels_art.jpg", result.mergedTrack.artworkCachePath)
        assertEquals(MetadataSourceProvenance.ONLINE_PROVIDER, result.appliedProvenances["artwork"])
    }

    @Test
    fun `6 - Placeholder Unknown Artist is replaced by confident match`() {
        val localTrack = Track(
            id = "t6",
            title = "Levels",
            artist = "Unknown Artist",
            filePath = "/storage/emulated/0/Music/levels.mp3"
        )
        val candidate = CandidateMetadata(
            title = "Levels",
            artist = "Avicii",
            album = "Levels - EP",
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 90.0,
            matchState = MetadataScanState.VERIFIED
        )

        assertEquals("Avicii", result.mergedTrack.artist)
        assertTrue(result.wasRepaired)
        assertEquals(MetadataSourceProvenance.ONLINE_PROVIDER, result.appliedProvenances["artist"])
    }

    @Test
    fun `7 - Null online field never erases local value`() {
        val localTrack = Track(
            id = "t7",
            title = "Titanium",
            artist = "David Guetta",
            album = "Nothing but the Beat",
            genre = "Dance",
            releaseYear = 2011,
            filePath = "/storage/emulated/0/Music/titanium.mp3"
        )
        // Candidate has null genre and year
        val candidate = CandidateMetadata(
            title = "Titanium",
            artist = "David Guetta",
            album = null,
            genre = null,
            releaseYear = null,
            provider = "Apple iTunes Search API"
        )

        val result = LocalFirstMetadataMerger.merge(
            localTrack = localTrack,
            candidate = candidate,
            candidateScore = 90.0,
            matchState = MetadataScanState.VERIFIED
        )

        assertEquals("Nothing but the Beat", result.mergedTrack.album)
        assertEquals("Dance", result.mergedTrack.genre)
        assertEquals(2011, result.mergedTrack.releaseYear)
    }

    @Test
    fun `8 - Re-scan does not revert user edits`() = runBlocking {
        // Track initially saved with user confirmed metadata
        val userEditedTrack = TrackEntity(
            id = "t8",
            title = "Custom User Title",
            artist = "Custom User Artist",
            album = "Custom Album",
            bpm = 126.0,
            isManualBpm = true,
            musicalKey = "8A",
            isManualKey = true,
            filePath = "/storage/emulated/0/Music/track8.mp3",
            userConfirmedMetadata = true,
            metadataScanState = "APPROVED"
        )
        trackDao.insertTrack(userEditedTrack)

        // Rescan reads raw tags from file
        val rescannedFileTrack = TrackEntity(
            id = "t8",
            title = "Raw Container Title",
            artist = "Raw Container Artist",
            album = "Raw Container Album",
            bpm = 120.0,
            musicalKey = "1A",
            filePath = "/storage/emulated/0/Music/track8.mp3"
        )

        trackDao.upsertPhysicalTrack(rescannedFileTrack)

        val persisted = trackDao.getTrackById("t8")
        assertNotNull(persisted)
        // User edits MUST be intact
        assertEquals("Custom User Title", persisted!!.title)
        assertEquals("Custom User Artist", persisted.artist)
        assertEquals("Custom Album", persisted.album)
        assertEquals(126.0, persisted.bpm, 0.001)
        assertTrue(persisted.isManualBpm)
        assertEquals("8A", persisted.musicalKey)
        assertTrue(persisted.isManualKey)
        assertTrue(persisted.userConfirmedMetadata)
    }

    @Test
    fun `9 - Metadata conflict review applies only selected fields`() = runBlocking {
        val initialTrack = TrackEntity(
            id = "t9",
            title = "Original Title",
            artist = "Original Artist",
            album = "Original Album",
            genre = "Original Genre",
            filePath = "/storage/emulated/0/Music/track9.mp3"
        )
        trackDao.insertTrack(initialTrack)

        val reviewId = reviewManager.submitForReview(
            track = initialTrack,
            proposedArtist = "Proposed Artist",
            proposedTitle = "Proposed Title",
            proposedAlbum = "Proposed Album",
            proposedGenre = "Proposed Genre",
            provider = "Apple iTunes Search API",
            confidenceScore = 85.0,
            evidenceSummary = "Conflict detected"
        )

        // User applies ONLY the title field
        val applied = reviewManager.acceptSelectedFields(reviewId, setOf("title"))
        assertTrue(applied)

        val updated = trackDao.getTrackById("t9")
        assertNotNull(updated)
        // Title was updated
        assertEquals("Proposed Title", updated!!.title)
        // Artist, Album, Genre were NOT touched
        assertEquals("Original Artist", updated.artist)
        assertEquals("Original Album", updated.album)
        assertEquals("Original Genre", updated.genre)

        // Provenance of title is now USER_EDIT
        val provMap = TrackFieldProvenance.parse(updated.fieldProvenanceJson)
        assertEquals(MetadataSourceProvenance.USER_EDIT, provMap["title"])
    }

    @Test
    fun `10 - Metadata conflict review keepLocal dismisses proposal and protects local tags`() = runBlocking {
        val initialTrack = TrackEntity(
            id = "t10",
            title = "My Local Song",
            artist = "My Local Artist",
            album = "My Local Album",
            filePath = "/storage/emulated/0/Music/track10.mp3"
        )
        trackDao.insertTrack(initialTrack)

        val reviewId = reviewManager.submitForReview(
            track = initialTrack,
            proposedArtist = "Wrong Online Artist",
            proposedTitle = "Wrong Online Title",
            proposedAlbum = "Wrong Online Album",
            provider = "Apple iTunes Search API",
            confidenceScore = 75.0,
            evidenceSummary = "Potential match"
        )

        val dismissed = reviewManager.keepLocal(reviewId)
        assertTrue(dismissed)

        val updated = trackDao.getTrackById("t10")
        assertNotNull(updated)
        // Local tags preserved
        assertEquals("My Local Song", updated!!.title)
        assertEquals("My Local Artist", updated.artist)
        assertEquals("My Local Album", updated.album)
        assertTrue(updated.userConfirmedMetadata)
        assertEquals(MetadataScanState.APPROVED.name, updated.metadataScanState)

        val item = reviewDao.getItemById(reviewId)
        assertNotNull(item)
        assertEquals("KEPT_LOCAL", item!!.status)
    }
}
