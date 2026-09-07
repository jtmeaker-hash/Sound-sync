package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.backup.TrackBackupItem
import com.example.backup.TrackMatcher
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import com.example.metadata.ArtworkCache
import com.example.metadata.CandidateEvaluationResult
import com.example.metadata.MetadataConfidenceScorer
import com.example.metadata.MetadataResolver
import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.repair.StringNormalizer
import com.example.model.MetadataScanState
import com.example.model.MetadataWriteState
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundSyncMetadataIntelligenceTest {

    private lateinit var context: Context
    private lateinit var artworkCache: ArtworkCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        artworkCache = ArtworkCache(context)
    }

    // =========================================================================
    // SECTION 22: ORIGINAL SCREENSHOT TEST CASES
    // =========================================================================

    @Test
    fun `test Section 22 - Coone - Savages wav parses artist and title correctly`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Savages",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/Coone - Savages.wav"
        )
        assertEquals("Coone", parsed.artist)
        assertEquals("Savages", parsed.title)
        assertEquals("Savages", parsed.cleanSearchTitle)
        assertFalse(parsed.isRecordingPlaceholder)
    }

    @Test
    fun `test Section 22 - Coone_Savages parses underscore artist and title`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Coone_Savages",
            existingArtist = "<unknown>",
            filename = "/storage/emulated/0/Music/Coone_Savages.mp3"
        )
        assertEquals("Coone", parsed.artist)
        assertEquals("Savages", parsed.title)
    }

    @Test
    fun `test Section 22 - REC001_202601072125024 mp3 identified as recording placeholder`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "REC001_202601072125024",
            existingArtist = null,
            filename = "/storage/emulated/0/Recordings/REC001_202601072125024.mp3"
        )
        assertTrue("Expected isRecordingPlaceholder to be true", parsed.isRecordingPlaceholder)
        assertEquals("REC001", parsed.cleanSearchTitle)
        assertTrue(TrackIdentityParser.isRecordingPlaceholder("REC001_202601072125024"))
    }

    @Test
    fun `test Section 22 - Seventeen in the Rearview clip mp3 strips clip suffix`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Seventeen in the Rearview clip",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/Seventeen in the Rearview clip.mp3"
        )
        assertEquals("Seventeen in the Rearview", parsed.cleanSearchTitle)
        assertEquals("Seventeen in the Rearview", parsed.title)
    }

    @Test
    fun `test Section 22 - Ghosts In The Little Things_202605281217014 mp3 removes timestamp suffix`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Ghosts In The Little Things_202605281217014",
            existingArtist = "<unknown>",
            filename = "/storage/emulated/0/Download/Ghosts In The Little Things_202605281217014.mp3"
        )
        assertEquals("Ghosts In The Little Things", parsed.cleanSearchTitle)
        assertEquals("Ghosts In The Little Things", parsed.title)
    }

    @Test
    fun `test Section 22 - In My Heart_202603151616007 mp3 removes timestamp suffix`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "In My Heart_202603151616007",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/In My Heart_202603151616007.mp3"
        )
        assertEquals("In My Heart", parsed.cleanSearchTitle)
        assertEquals("In My Heart", parsed.title)
    }

    @Test
    fun `test Section 22 - Ghosts In The Little Things Tiktok mp3 removes TikTok noise token`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Ghosts In The Little Things Tiktok",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/Ghosts In The Little Things Tiktok.mp3"
        )
        assertEquals("Ghosts In The Little Things", parsed.cleanSearchTitle)
        assertEquals("Ghosts In The Little Things", parsed.title)
    }

    @Test
    fun `test Section 22 - Front Porch Confession_202603161348019 mp3 removes timestamp suffix`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Front Porch Confession_202603161348019",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/Front Porch Confession_202603161348019.mp3"
        )
        assertEquals("Front Porch Confession", parsed.cleanSearchTitle)
        assertEquals("Front Porch Confession", parsed.title)
    }

    @Test
    fun `test Section 22 - Primeshock - Lose Control (CTRL) wav parses correctly`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Primeshock - Lose Control (CTRL)",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/Primeshock - Lose Control (CTRL).wav"
        )
        assertEquals("Primeshock", parsed.artist)
        assertEquals("Lose Control (CTRL)", parsed.title)
    }

    @Test
    fun `test Section 22 - Alyssa - Keep Me High wav parses correctly`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Alyssa - Keep Me High",
            existingArtist = null,
            filename = "/storage/emulated/0/Download/Alyssa - Keep Me High.wav"
        )
        assertEquals("Alyssa", parsed.artist)
        assertEquals("Keep Me High", parsed.title)
    }

    // =========================================================================
    // SECTION 23: PARSING, CLEANUP & RECOGNITION TESTS
    // =========================================================================

    @Test
    fun `test TrackIdentityParser handles track number prefixes and delimiters`() {
        // Track number prefix with dash
        val p1 = TrackIdentityParser.parse(
            existingTitle = "01 - Coone - Savages",
            existingArtist = null,
            filename = "01 - Coone - Savages.flac"
        )
        assertEquals("Coone", p1.artist)
        assertEquals("Savages", p1.title)

        // Track number prefix without dash
        val p2 = TrackIdentityParser.parse(
            existingTitle = "01 Coone - Savages",
            existingArtist = null,
            filename = "01 Coone - Savages.mp3"
        )
        assertEquals("Coone", p2.artist)
        assertEquals("Savages", p2.title)

        // Title by Artist syntax
        val p3 = TrackIdentityParser.parse(
            existingTitle = "Savages by Coone",
            existingArtist = null,
            filename = "Savages by Coone.mp3"
        )
        assertEquals("Coone", p3.artist)
        assertEquals("Savages", p3.title)
    }

    @Test
    fun `test timestamp removal handles diverse date time pattern formats`() {
        val clean1 = TrackIdentityParser.cleanGarbage("TrackName_202605281217014")
        assertEquals("TrackName", clean1)

        val clean2 = TrackIdentityParser.cleanGarbage("TrackName-202605281217014")
        assertEquals("TrackName", clean2)

        val clean3 = TrackIdentityParser.cleanGarbage("TrackName 202605281217014")
        assertEquals("TrackName", clean3)

        val clean4 = TrackIdentityParser.cleanGarbage("TrackName_20260528_121701")
        assertEquals("TrackName", clean4)
    }

    @Test
    fun `test TikTok and clip noise token handling`() {
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track tiktok"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track TikTok"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track Tik Tok"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track clip"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track [Clip]"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track (TikTok)"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track Official Video"))
        assertEquals("Track", TrackIdentityParser.cleanGarbage("Track (Lyric Video)"))
    }

    @Test
    fun `test recording filenames identification`() {
        assertTrue(TrackIdentityParser.isRecordingPlaceholder("REC001"))
        assertTrue(TrackIdentityParser.isRecordingPlaceholder("REC_20260101"))
        assertTrue(TrackIdentityParser.isRecordingPlaceholder("Voice 001"))
        assertTrue(TrackIdentityParser.isRecordingPlaceholder("Recording (12)"))
        assertFalse(TrackIdentityParser.isRecordingPlaceholder("Coone - Savages"))
        assertFalse(TrackIdentityParser.isRecordingPlaceholder("Ghosts In The Little Things"))
    }

    @Test
    fun `test unknown artists validation`() {
        assertFalse(TrackIdentityParser.isArtistValid(null))
        assertFalse(TrackIdentityParser.isArtistValid(""))
        assertFalse(TrackIdentityParser.isArtistValid(" "))
        assertFalse(TrackIdentityParser.isArtistValid("<unknown>"))
        assertFalse(TrackIdentityParser.isArtistValid("Unknown"))
        assertFalse(TrackIdentityParser.isArtistValid("Unknown Artist"))
        assertFalse(TrackIdentityParser.isArtistValid("Various Artists"))
        assertTrue(TrackIdentityParser.isArtistValid("Coone"))
        assertTrue(TrackIdentityParser.isArtistValid("Armin van Buuren"))
    }

    @Test
    fun `test album folder-name rejection`() {
        assertTrue(TrackIdentityParser.isGenericAlbumName(null))
        assertTrue(TrackIdentityParser.isGenericAlbumName(""))
        assertTrue(TrackIdentityParser.isGenericAlbumName("Download"))
        assertTrue(TrackIdentityParser.isGenericAlbumName("download"))
        assertTrue(TrackIdentityParser.isGenericAlbumName("Recordings"))
        assertTrue(TrackIdentityParser.isGenericAlbumName("Music"))
        assertTrue(TrackIdentityParser.isGenericAlbumName("Audio"))
        assertTrue(TrackIdentityParser.isGenericAlbumName("/storage/emulated/0/Download"))
        assertFalse(TrackIdentityParser.isGenericAlbumName("Global Dedication"))
        assertFalse(TrackIdentityParser.isGenericAlbumName("Abbey Road"))
    }

    @Test
    fun `test existing good tags preserved`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Blah Blah Blah",
            existingArtist = "Armin van Buuren",
            album = "Balance",
            filename = "/storage/emulated/0/Music/track01.mp3"
        )
        assertEquals("Armin van Buuren", parsed.artist)
        assertEquals("Blah Blah Blah", parsed.title)
        assertEquals("Balance", parsed.album)
    }

    // =========================================================================
    // SECTION 23: SCORING, RANKING & SAFETY TESTS
    // =========================================================================

    @Test
    fun `test duration comparison within tolerance gives high score while large difference penalizes`() {
        val candidateGoodDuration = AppleTrackResult(
            trackId = 101,
            trackName = "Savages",
            artistName = "Coone",
            collectionName = "Global Dedication",
            trackTimeMillis = 200_000,
            artworkUrl100 = "https://example.com/art.jpg"
        )
        val scoreGood = MetadataConfidenceScorer.scoreCandidate(
            localTitle = "Savages",
            localArtist = "Coone",
            localAlbum = null,
            localDurationSeconds = 202,
            candidate = candidateGoodDuration
        )
        assertTrue("Close duration score should exceed 85.0, was ${scoreGood.totalScore}", scoreGood.totalScore >= 85.0)

        val candidateBadDuration = candidateGoodDuration.copy(
            trackTimeMillis = 400_000
        )
        val scoreBad = MetadataConfidenceScorer.scoreCandidate(
            localTitle = "Savages",
            localArtist = "Coone",
            localAlbum = null,
            localDurationSeconds = 202,
            candidate = candidateBadDuration
        )
        assertTrue("Large duration difference should heavily penalize, score was ${scoreBad.totalScore}", scoreBad.totalScore < 60.0)
    }

    @Test
    fun `test API result ranking selects candidate with highest artist and duration match`() {
        val candidateA = AppleTrackResult(
            trackId = 1,
            trackName = "Lose Control",
            artistName = "Completely Different Artist",
            collectionName = "Some Album",
            trackTimeMillis = 180_000
        )
        val candidateB = AppleTrackResult(
            trackId = 2,
            trackName = "Lose Control (CTRL)",
            artistName = "Primeshock",
            collectionName = "Lose Control",
            trackTimeMillis = 182_000
        )

        val evaluation = MetadataConfidenceScorer.evaluateCandidates(
            localTitle = "Lose Control (CTRL)",
            localArtist = "Primeshock",
            localAlbum = null,
            localDurationSeconds = 181,
            candidates = listOf(candidateA, candidateB)
        )

        assertNotNull(evaluation.bestCandidate)
        assertEquals(2L, evaluation.bestCandidate?.candidate?.trackId)
        assertEquals("Primeshock", evaluation.bestCandidate?.candidate?.artistName)
    }

    @Test
    fun `test manual metadata protection prevents resolver overwriting`() = runBlocking {
        val resolver = MetadataResolver(context)
        val userConfirmedTrack = Track(
            id = "user_edit_1",
            title = "My Custom Title",
            artist = "My Custom Artist",
            userConfirmedMetadata = true,
            metadataScanState = MetadataScanState.USER_CONFIRMED.name
        )

        val result = resolver.resolveTrackMetadata(userConfirmedTrack, forceRefresh = false)
        assertEquals(MetadataScanState.USER_CONFIRMED, result.scanState)
        assertEquals("My Custom Title", result.updatedTrack.title)
        assertEquals("My Custom Artist", result.updatedTrack.artist)
        assertFalse(result.wasRepaired)
    }

    // =========================================================================
    // SECTION 23: PERSISTENCE, CACHE, FAILS & BACKUP TESTS
    // =========================================================================

    @Test
    fun `test artwork cache persistence and retrieval`() {
        val downloaded = com.example.metadata.theaudiodb.DownloadedArtwork(
            bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            mimeType = "image/jpeg",
            width = 500,
            height = 500,
            sourceUrl = "https://example.com/art.jpg"
        )
        val file = artworkCache.saveArtwork("Coone", "Savages", downloaded, sourceProvider = "Apple iTunes")
        assertNotNull("Artwork file should not be null", file)
        assertTrue("Artwork file should exist", file.exists())
        assertEquals(4, file.length())

        val retrievedFile = artworkCache.getCachedArtworkFile("Coone", "Savages")
        assertNotNull(retrievedFile)
        assertTrue(retrievedFile!!.exists())

        // Cleanup
        artworkCache.clear()
        assertFalse(file.exists())
    }

    @Test
    fun `test failed internet requests do not crash resolver and fall back to parsed metadata`() = runBlocking {
        val failingAppleProvider = object : AppleMetadataProvider() {
            override suspend fun searchTracks(query: String, country: String, limit: Int): List<AppleTrackResult> {
                throw IOException("Simulated network timeout")
            }
            override suspend fun downloadArtwork(artworkUrl: String): com.example.metadata.theaudiodb.DownloadedArtwork? = null
        }

        val resolver = MetadataResolver(
            context = context,
            appleProvider = failingAppleProvider,
            artworkProvider = null
        )

        val track = Track(
            id = "net_fail_1",
            title = "Ghosts In The Little Things_202605281217014",
            artist = "<unknown>",
            filePath = "/storage/emulated/0/Download/Ghosts In The Little Things_202605281217014.mp3"
        )

        // Should NOT throw exception
        val result = resolver.resolveTrackMetadata(track, forceRefresh = true)
        assertNotNull(result)
        assertEquals("Ghosts In The Little Things", result.updatedTrack.title)
    }

    @Test
    fun `test TrackBackupItem preserves metadataWriteState and fields on serialization roundtrip`() {
        val original = TrackBackupItem(
            id = "backup_1",
            title = "Savages",
            artist = "Coone",
            album = "Global Dedication",
            durationSeconds = 200,
            metadataScanState = MetadataScanState.COMPLETE.name,
            metadataWriteState = MetadataWriteState.FILE_WRITE_SUCCESS.name,
            appleTrackId = 99999L
        )

        val json = original.toJson()
        val restored = TrackBackupItem.fromJson(json)

        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.album, restored.album)
        assertEquals(original.metadataScanState, restored.metadataScanState)
        assertEquals(original.metadataWriteState, restored.metadataWriteState)
        assertEquals(original.appleTrackId, restored.appleTrackId)
    }

    @Test
    fun `test TrackMatcher merges updated metadata into existing track`() {
        val existingTrack = TrackEntity(
            id = "t_merge_1",
            title = "Savages_20260101",
            artist = "<unknown>",
            album = "Download",
            filePath = "/music/coone_savages.mp3",
            durationSeconds = 200
        )

        val backupTrack = TrackBackupItem(
            id = "t_merge_1",
            title = "Savages",
            artist = "Coone",
            album = "Global Dedication",
            genre = "Hardstyle",
            releaseYear = 2016,
            metadataScanState = MetadataScanState.COMPLETE.name,
            metadataWriteState = MetadataWriteState.FILE_WRITE_SUCCESS.name
        )

        val merged = TrackMatcher.mergeTrack(
            backupTrack = backupTrack,
            existingEntity = existingTrack,
            isFileModified = false
        )
        assertEquals("Savages", merged.title)
        assertEquals("Coone", merged.artist)
        assertEquals("Global Dedication", merged.album)
        assertEquals("Hardstyle", merged.genre)
        assertEquals(2016, merged.releaseYear)
        assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS.name, merged.metadataWriteState)
    }
}
