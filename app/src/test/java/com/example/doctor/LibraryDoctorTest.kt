package com.example.doctor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrackBrainDao
import com.example.data.TrackBrainStatusEntity
import com.example.data.TrackDao
import com.example.data.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDoctorTest {

    private lateinit var context: Context
    private lateinit var preferences: LibraryDoctorPreferences

    private val trackMap = ConcurrentHashMap<String, TrackEntity>()
    private val brainMap = ConcurrentHashMap<String, TrackBrainStatusEntity>()

    private lateinit var mockTrackDao: TrackDao
    private lateinit var mockBrainDao: TrackBrainDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = LibraryDoctorPreferences(context)
        preferences.clearAllPreferences()

        trackMap.clear()
        brainMap.clear()

        mockTrackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllTracksList" -> trackMap.values.toList()
                "getTrackById" -> trackMap[args[0] as String]
                "updateTrack" -> {
                    val t = args[0] as TrackEntity
                    trackMap[t.id] = t
                    1
                }
                "deleteTrackById" -> {
                    trackMap.remove(args[0] as String)
                    1
                }
                else -> null
            }
        } as TrackDao

        mockBrainDao = Proxy.newProxyInstance(
            TrackBrainDao::class.java.classLoader,
            arrayOf(TrackBrainDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllStatuses", "getAllStatusesList" -> brainMap.values.toList()
                "getStatusForTrack" -> brainMap[args[0] as String]
                "upsert" -> {
                    val s = args[0] as TrackBrainStatusEntity
                    brainMap[s.trackId] = s
                    null
                }
                else -> null
            }
        } as TrackBrainDao
    }

    private fun createAuditor(): LibraryDoctorAuditor {
        return LibraryDoctorAuditor(
            context = context,
            preferences = preferences,
            trackDaoOverride = mockTrackDao,
            brainDaoOverride = mockBrainDao
        )
    }

    private fun createRepairManager(): LibraryDoctorRepairManager {
        return LibraryDoctorRepairManager(
            context = context,
            preferences = preferences,
            trackDaoOverride = mockTrackDao
        )
    }

    @Test
    fun testAllTwelveDoctorCategoriesExist() {
        val categories = DoctorCategory.values()
        assertEquals("Must support exactly 12 diagnostic categories", 12, categories.size)

        val expected = setOf(
            DoctorCategory.MISSING_ARTWORK,
            DoctorCategory.MISSING_ARTIST,
            DoctorCategory.DUPLICATE_TRACKS,
            DoctorCategory.BROKEN_FILE_PATHS,
            DoctorCategory.CORRUPTED_AUDIO,
            DoctorCategory.SUSPICIOUS_BPM,
            DoctorCategory.SUSPICIOUS_KEY,
            DoctorCategory.LOW_QUALITY_AUDIO,
            DoctorCategory.INCONSISTENT_ALBUMS,
            DoctorCategory.INCOMPLETE_ANALYSIS,
            DoctorCategory.MISSING_FILES,
            DoctorCategory.FAILED_BACKGROUND_JOBS
        )

        assertEquals("All expected categories must be present", expected, categories.toSet())
    }

    @Test
    fun testDoctorPreferencesPersistence() {
        val issueId = "art_track_123"

        assertEquals(DoctorReviewStatus.OPEN, preferences.getIssueStatus(issueId))
        assertFalse(preferences.isIgnored(issueId))

        preferences.ignoreIssue(issueId)
        assertTrue(preferences.isIgnored(issueId))
        assertEquals(DoctorReviewStatus.IGNORED, preferences.getIssueStatus(issueId))
        assertEquals(1, preferences.getIgnoredCount())

        preferences.markReviewed(issueId)
        assertFalse(preferences.isIgnored(issueId))
        assertEquals(DoctorReviewStatus.NEEDS_REVIEW, preferences.getIssueStatus(issueId))

        preferences.markFixed(issueId)
        assertEquals(DoctorReviewStatus.FIXED, preferences.getIssueStatus(issueId))

        preferences.unignoreIssue(issueId)
        preferences.clearAllPreferences()
        assertEquals(DoctorReviewStatus.OPEN, preferences.getIssueStatus(issueId))
    }

    @Test
    fun testEmptyLibraryAuditReturns100PercentHealth() = runBlocking {
        val auditor = createAuditor()
        val report = auditor.runAudit()

        assertEquals("Empty library health should be 100%", 100, report.summary.healthScore)
        assertEquals(0, report.summary.totalTracks)
        assertEquals(0, report.issues.size)
        assertFalse(report.isAuditing)
    }

    @Test
    fun testMissingArtworkDetection() = runBlocking {
        val trackWithoutArt = TrackEntity(
            id = "t_no_art",
            title = "Track Without Art",
            artist = "Artist A",
            album = "Album A",
            filePath = "/storage/music/track1.mp3",
            durationSeconds = 180,
            artworkCachePath = null,
            artworkUrl = null
        )
        trackMap[trackWithoutArt.id] = trackWithoutArt

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val artIssue = report.issues.find { it.category == DoctorCategory.MISSING_ARTWORK }
        assertNotNull("Missing artwork issue must be surfaced", artIssue)
        assertEquals("t_no_art", artIssue?.trackId)
        assertTrue("Missing artwork is safe auto-repairable", artIssue?.isSafeAutoRepair == true)
    }

    @Test
    fun testEmbeddedArtistDetectionAndSplitProposal() = runBlocking {
        val embeddedTrack = TrackEntity(
            id = "t_embedded",
            title = "Daft Punk - One More Time",
            artist = "Unknown Artist",
            album = "Discovery",
            filePath = "/storage/music/discovery.mp3",
            durationSeconds = 320
        )
        trackMap[embeddedTrack.id] = embeddedTrack

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val artistIssue = report.issues.find { it.category == DoctorCategory.MISSING_ARTIST }
        assertNotNull("Embedded artist issue must be identified", artistIssue)
        assertTrue(artistIssue?.problem?.contains("embedded", ignoreCase = true) == true)
        assertTrue("Proposed value should split artist and title", artistIssue?.proposedValue?.contains("Artist: 'Daft Punk'") == true)
        assertTrue(artistIssue?.isSafeAutoRepair == true)
    }

    @Test
    fun testSuspiciousBpmDetectionAndHalfDoubleTime() = runBlocking {
        val fastBpmTrack = TrackEntity(
            id = "t_fast_bpm",
            title = "Drum and Bass Rapid",
            artist = "DnB Artist",
            album = "Fast Album",
            filePath = "/storage/music/fast.mp3",
            durationSeconds = 200,
            bpm = 174.0,
            bpmConfidence = 0.25 // Low confidence
        )
        trackMap[fastBpmTrack.id] = fastBpmTrack

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val bpmIssue = report.issues.find { it.category == DoctorCategory.SUSPICIOUS_BPM }
        assertNotNull("Low-confidence BPM must be flagged", bpmIssue)
        assertTrue("Should propose half-time BPM", bpmIssue?.proposedValue?.contains("Half-time") == true)
        assertFalse("Suspicious BPM requires manual confirmation or reanalysis", bpmIssue?.isSafeAutoRepair == true)
    }

    @Test
    fun testConservativeDuplicateDetection() = runBlocking {
        val track1 = TrackEntity(
            id = "t1",
            title = "Around The World",
            artist = "Daft Punk",
            album = "Homework",
            filePath = "/storage/music/track1.mp3",
            durationSeconds = 425,
            contentFingerprint = "FP_12345"
        )
        val track2 = TrackEntity(
            id = "t2",
            title = "Around The World",
            artist = "Daft Punk",
            album = "Homework",
            filePath = "/storage/music/track2.mp3",
            durationSeconds = 425,
            contentFingerprint = "FP_12345"
        )
        trackMap[track1.id] = track1
        trackMap[track2.id] = track2

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val dupIssue = report.issues.find { it.category == DoctorCategory.DUPLICATE_TRACKS }
        assertNotNull("Exact duplicate fingerprint must be flagged", dupIssue)
        assertEquals(0.98f, dupIssue?.confidence ?: 0f, 0.01f)
        assertFalse("Duplicate file deletion must NOT be auto-applied without user confirmation", dupIssue?.isSafeAutoRepair == true)
    }

    @Test
    fun testRemixAndLiveVersionsNotTreatedAsExactDuplicates() = runBlocking {
        val original = TrackEntity(
            id = "t_orig",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            filePath = "/storage/music/orig.mp3",
            durationSeconds = 320
        )
        val remix = TrackEntity(
            id = "t_remix",
            title = "One More Time (Romanthony's Unplugged Remix)",
            artist = "Daft Punk",
            album = "Discovery",
            filePath = "/storage/music/remix.mp3",
            durationSeconds = 321
        )
        trackMap[original.id] = original
        trackMap[remix.id] = remix

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val remixIssue = report.issues.find { it.trackId == "t_remix" && it.category == DoctorCategory.DUPLICATE_TRACKS }
        if (remixIssue != null) {
            assertTrue("Remix issue problem should specify possible alternate version/remix", remixIssue.problem.contains("remix", ignoreCase = true))
            assertEquals(0.50f, remixIssue.confidence, 0.05f)
        }
    }

    @Test
    fun testInconsistentAlbumDetection() = runBlocking {
        val track1 = TrackEntity(
            id = "t_alb1",
            title = "Song 1",
            artist = "Justice",
            album = "Cross",
            filePath = "/storage/music/s1.mp3",
            durationSeconds = 210
        )
        val track2 = TrackEntity(
            id = "t_alb2",
            title = "Song 2",
            artist = "Justice",
            album = "Cross ", // Trailing space
            filePath = "/storage/music/s2.mp3",
            durationSeconds = 220
        )
        trackMap[track1.id] = track1
        trackMap[track2.id] = track2

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val albumIssue = report.issues.find { it.category == DoctorCategory.INCONSISTENT_ALBUMS }
        assertNotNull("Inconsistent album naming must be detected", albumIssue)
        assertEquals("Cross", albumIssue?.proposedValue)
    }

    @Test
    fun testSafeRepairManagerResolvesEmbeddedArtist() = runBlocking {
        val embeddedTrack = TrackEntity(
            id = "t_embed",
            title = "Modjo - Lady (Hear Me Tonight)",
            artist = "Unknown Artist",
            album = "Single",
            filePath = "/storage/music/lady.mp3",
            durationSeconds = 300
        )
        trackMap[embeddedTrack.id] = embeddedTrack

        val auditor = createAuditor()
        val report = auditor.runAudit()

        val repairManager = createRepairManager()
        val result = repairManager.repairSafeIssues(report.issues)

        assertTrue("Should have repaired at least 1 issue", result.repairedCount > 0)
        val updatedTrack = trackMap["t_embed"]
        assertEquals("Modjo", updatedTrack?.artist)
        assertEquals("Lady (Hear Me Tonight)", updatedTrack?.title)
        assertEquals(DoctorReviewStatus.FIXED, preferences.getIssueStatus("missing_artist_t_embed"))
    }
}
