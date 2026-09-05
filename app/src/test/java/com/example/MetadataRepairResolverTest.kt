package com.example

import com.example.metadata.MetadataConfidenceScorer
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.repair.ArtistStructureAnalyzer
import com.example.metadata.repair.StringNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataRepairResolverTest {

    @Test
    fun `StringNormalizer normalizes diacritics and collaborations accurately`() {
        assertEquals("rufus du sol", StringNormalizer.normalizeArtist("RÜFÜS DU SOL"))
        assertEquals("beyonce", StringNormalizer.normalizeArtist("Beyoncé"))
        assertEquals("motley crue", StringNormalizer.normalizeArtist("Mötley Crüe"))

        // Collaborations & vs and vs x
        val coll1 = StringNormalizer.standardizeCollaborations("Calvin Harris and Dua Lipa")
        val coll2 = StringNormalizer.standardizeCollaborations("Calvin Harris & Dua Lipa")
        val coll3 = StringNormalizer.standardizeCollaborations("Calvin Harris x Dua Lipa")
        assertEquals("Calvin Harris & Dua Lipa", coll1)
        assertEquals("Calvin Harris & Dua Lipa", coll2)
        assertEquals("Calvin Harris & Dua Lipa", coll3)

        // Equivalence check
        assertTrue(StringNormalizer.areArtistsEquivalent("Calvin Harris and Dua Lipa", "Calvin Harris & Dua Lipa"))
        assertTrue(StringNormalizer.areArtistsEquivalent("RÜFÜS DU SOL", "Rufus Du Sol"))
        assertTrue(StringNormalizer.areArtistsEquivalent("Fred again.. & Skrillex", "Skrillex & Fred again.."))

        // Version stripping
        assertEquals("One More Time", StringNormalizer.stripVersionAndExtension("One More Time (Club Mix)"))
        assertEquals("Levels", StringNormalizer.stripVersionAndExtension("Levels [Radio Edit].mp3"))
        assertEquals("Titanium", StringNormalizer.stripVersionAndExtension("01 - Titanium (feat. Sia).flac"))
    }

    @Test
    fun `ArtistStructureAnalyzer extracts artist and title from diverse formats`() {
        // Hyphen format: Artist - Title
        val res1 = ArtistStructureAnalyzer.analyze(
            embeddedTitle = "Strobe",
            embeddedArtist = "",
            filePathOrName = "Deadmau5 - Strobe.mp3"
        )
        assertEquals("Deadmau5", res1.candidateArtist)
        assertEquals("Strobe", res1.title)

        // Numbered prefix: 01 - Eric Prydz - Opus.mp3
        val res2 = ArtistStructureAnalyzer.analyze(
            embeddedTitle = "Opus (Original Mix)",
            embeddedArtist = "",
            filePathOrName = "03 - Eric Prydz - Opus (Original Mix).wav"
        )
        assertEquals("Eric Prydz", res2.candidateArtist)
        assertEquals("Opus", res2.title)

        // "By" format: Animals by Martin Garrix
        val res3 = ArtistStructureAnalyzer.analyze(
            embeddedTitle = "Animals by Martin Garrix",
            embeddedArtist = "",
            filePathOrName = "Animals by Martin Garrix.mp3"
        )
        assertEquals("Martin Garrix", res3.candidateArtist)
        assertEquals("Animals", res3.title)

        // Collaboration extraction
        val res4 = ArtistStructureAnalyzer.analyze(
            embeddedTitle = "I'm Good (Blue)",
            embeddedArtist = "",
            filePathOrName = "David Guetta & Bebe Rexha - I'm Good (Blue).mp3"
        )
        assertTrue(res4.searchQueries.isNotEmpty())
        assertTrue(res4.searchQueries.any { it.contains("David Guetta") })
    }

    @Test
    fun `TrackIdentityParser extracts artist, title, version and strips web garbage`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Strobe (Club Mix) [y2mate.com] [320kbps]",
            existingArtist = "Deadmau5",
            album = "For Lack of a Better Name",
            filename = "/music/Deadmau5 - Strobe (Club Mix) [320kbps].mp3",
            durationSeconds = 637
        )

        assertEquals("Deadmau5", parsed.artist)
        assertEquals("Strobe (Club Mix)", parsed.title)
        assertEquals("Club Mix", parsed.version)
        assertFalse(parsed.isArtistMissing)
        assertTrue(parsed.searchTerms.isNotEmpty())
    }

    @Test
    fun `TrackIdentityParser recovers missing artist from filename`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Opus",
            existingArtist = "Unknown Artist",
            filename = "01 - Eric Prydz - Opus (Extended Mix).flac",
            durationSeconds = 540
        )

        assertEquals("Eric Prydz", parsed.artist)
        assertEquals("Opus", parsed.title)
        assertEquals("Extended Mix", parsed.version)
    }

    @Test
    fun `TrackIdentityParser flags missing artist when filename also has no artist`() {
        val parsed = TrackIdentityParser.parse(
            existingTitle = "Opus",
            existingArtist = "Unknown Artist",
            filename = "01 - Opus (Extended Mix).flac",
            durationSeconds = 540
        )

        assertTrue(parsed.isArtistMissing)
        assertEquals("Opus", parsed.title)
        assertEquals("Extended Mix", parsed.version)
    }

    @Test
    fun `MetadataConfidenceScorer scores matching candidate above commit threshold`() {
        val candidate = AppleTrackResult(
            trackId = 123456L,
            artistId = 789L,
            collectionId = 456L,
            trackName = "Opus (Extended Mix)",
            artistName = "Eric Prydz",
            collectionName = "Opus",
            trackTimeMillis = 540000L,
            releaseDate = "2015-11-30T00:00:00Z"
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = "Opus (Extended Mix)",
            localArtist = "Eric Prydz",
            localAlbum = "Opus",
            localDurationSeconds = 540,
            candidate = candidate
        )

        assertTrue("Exact match should exceed commit threshold of 85.0", scored.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD)
        assertTrue(scored.isVersionMatched)
        assertEquals(0, scored.durationDeltaSeconds)
    }

    @Test
    fun `MetadataConfidenceScorer heavily penalizes duration mismatch`() {
        val candidate = AppleTrackResult(
            trackId = 999L,
            artistId = 789L,
            collectionId = 456L,
            trackName = "Opus (Radio Edit)",
            artistName = "Eric Prydz",
            collectionName = "Opus",
            trackTimeMillis = 200000L, // 200s vs 540s (340s mismatch)
            releaseDate = "2015-11-30T00:00:00Z"
        )

        val scored = MetadataConfidenceScorer.scoreCandidate(
            localTitle = "Opus (Extended Mix)",
            localArtist = "Eric Prydz",
            localAlbum = "Opus",
            localDurationSeconds = 540,
            candidate = candidate
        )

        assertTrue("Duration mismatch should fall well below commit threshold", scored.totalScore < MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD)
    }
}
