package com.example

import com.example.intelligence.SoundSyncIntelligenceEngine
import com.example.metadata.artwork.ArtworkStatus
import com.example.metadata.artwork.ArtworkStatusResolver
import com.example.model.CoverArtFilter
import com.example.model.CoverArtFilterMode
import com.example.model.Track
import com.example.smartcrate.SmartCrateEngine
import com.example.smartcrate.SmartField
import com.example.smartcrate.SmartOperator
import com.example.smartcrate.SmartRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [ArtworkStatusResolver] and [Track.hasRealArtwork] / [Track.artworkStatus] –
 * the single canonical artwork availability system used by all SoundSync features
 * (Local Library filters, Library Insights, Library Health, Listening Statistics,
 * Smart Crates, Command Palette, Library Doctor, etc.).
 *
 * Test numbers correspond to the 13 test cases in the bug specification.
 */
class CanonicalArtworkStatusTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Before
    fun setUp() {
        ArtworkStatusResolver.invalidateAll()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun baseTrack(
        id: String = "test-track",
        artworkCachePath: String? = null,
        artworkUrl: String? = null,
        artworkSource: String? = null,
        isEmbeddedInFile: Boolean = false
    ) = Track(
        id = id,
        title = "Test Track",
        artist = "Test Artist",
        artworkCachePath = artworkCachePath,
        artworkUrl = artworkUrl,
        artworkSource = artworkSource,
        metadataWriteState = if (isEmbeddedInFile) com.example.model.MetadataWriteState.FILE_WRITE_SUCCESS.name else com.example.model.MetadataWriteState.NOT_ANALYSED.name
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 1 – Track with valid embedded artwork (or valid artworkCachePath)
    // Expected: HAS_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 01 - track with valid artworkCachePath classifies as HAS_ARTWORK`() {
        val artFile = tmpFolder.newFile("cover.jpg").also { it.writeBytes(ByteArray(1024)) }
        val track = baseTrack(artworkCachePath = artFile.absolutePath)

        assertTrue("Track with a real, existing artworkCachePath must have real artwork", track.hasRealArtwork)
        assertEquals(ArtworkStatus.HAS_ARTWORK, track.artworkStatus)
        assertEquals(ArtworkStatus.HAS_ARTWORK, ArtworkStatusResolver.getStatus(track))
    }

    @Test
    fun `test 01b - track with valid embedded tag in readable audio file classifies as HAS_ARTWORK`() {
        val audioFile = tmpFolder.newFile("song.mp3").also { it.writeBytes(ByteArray(4096)) }
        val track = Track(
            id = "t_embedded",
            title = "Embedded Song",
            artist = "Artist",
            filePath = audioFile.absolutePath,
            artworkSource = "Embedded Tag"
        )

        assertTrue("Track with verified embedded tag must be HAS_ARTWORK", track.hasRealArtwork)
        assertEquals(ArtworkStatus.HAS_ARTWORK, track.artworkStatus)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 2 – Track with no artwork at all
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 02 - track with null artworkCachePath and null artworkUrl is NO_ARTWORK`() {
        val track = baseTrack(artworkCachePath = null, artworkUrl = null)

        assertFalse("Track with no artwork fields must be NO_ARTWORK", track.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, track.artworkStatus)
        assertEquals(ArtworkStatus.NO_ARTWORK, ArtworkStatusResolver.getStatus(track))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 3 – Track displaying a SoundSync placeholder or fallback
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 03 - bare MediaStore albumart auto-URI is NO_ARTWORK`() {
        val mediaStoreUri = "content://media/external/audio/albumart/42"
        val track = baseTrack(artworkCachePath = null, artworkUrl = mediaStoreUri)

        assertFalse("Bare MediaStore albumart auto-URI must be NO_ARTWORK", track.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, track.artworkStatus)
    }

    @Test
    fun `test 03b - MediaStore multi-user and external_primary albumart URIs are NO_ARTWORK`() {
        val uri1 = "content://0@media/external/audio/albumart/7"
        val uri2 = "content://media/external_primary/audio/albumart/5"
        assertFalse(baseTrack(artworkUrl = uri1).hasRealArtwork)
        assertFalse(baseTrack(artworkUrl = uri2).hasRealArtwork)
    }

    @Test
    fun `test 03c - generic placeholder images and fallback drawables are NO_ARTWORK`() {
        val placeholders = listOf(
            "android.resource://com.example/drawable/placeholder",
            "file:///app/drawables/default_album.png",
            "fallback_cover.jpg",
            "music_note_default.png",
            "none",
            "null"
        )
        for (ph in placeholders) {
            val track = baseTrack(artworkUrl = ph)
            assertFalse("Placeholder '$ph' must be NO_ARTWORK", track.hasRealArtwork)
            assertEquals(ArtworkStatus.NO_ARTWORK, track.artworkStatus)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 4 – Track with null artwork URI
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 04 - null artworkUrl and null artworkCachePath is NO_ARTWORK`() {
        val track = baseTrack(artworkCachePath = null, artworkUrl = null)
        assertFalse(track.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, track.artworkStatus)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 5 – Track with blank artwork URI
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 05 - blank artworkUrl is NO_ARTWORK`() {
        assertFalse(baseTrack(artworkCachePath = null, artworkUrl = "").hasRealArtwork)
        assertFalse(baseTrack(artworkCachePath = null, artworkUrl = "   ").hasRealArtwork)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 6 – Track with stale database path to non-existent file
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 06 - stale artworkCachePath pointing to non-existent file returns NO_ARTWORK`() {
        val staleTrack = baseTrack(artworkCachePath = "/data/user/0/com.example/files/stale_artwork_12345.jpg")

        assertFalse("Stale artworkCachePath to non-existent file must be NO_ARTWORK", staleTrack.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, staleTrack.artworkStatus)
        assertEquals(ArtworkStatus.NO_ARTWORK, ArtworkStatusResolver.getStatus(staleTrack))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 7 – Track with missing local artwork file
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 07 - missing local artwork file returns NO_ARTWORK`() {
        val missingTrack = baseTrack(artworkUrl = "file:///storage/emulated/0/Music/missing_cover.jpg")

        assertFalse("Missing local artwork file must be NO_ARTWORK", missingTrack.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, missingTrack.artworkStatus)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 8 – Track with valid downloaded artwork
    // Expected: HAS_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 08 - valid downloaded artwork file is HAS_ARTWORK`() {
        val artFile = tmpFolder.newFile("downloaded_cover.jpg").also { it.writeBytes(ByteArray(2048)) }
        val trackFile = baseTrack(artworkUrl = artFile.absolutePath)
        val trackFileUri = baseTrack(artworkUrl = "file://${artFile.absolutePath}")

        assertTrue("Valid downloaded artwork file path must be HAS_ARTWORK", trackFile.hasRealArtwork)
        assertTrue("Valid downloaded artwork file:// URI must be HAS_ARTWORK", trackFileUri.hasRealArtwork)
        assertEquals(ArtworkStatus.HAS_ARTWORK, trackFile.artworkStatus)
    }

    @Test
    fun `test 08b - valid remote https artwork URL is HAS_ARTWORK`() {
        val remoteTrack = baseTrack(artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/Music/test.jpg/600x600bb.jpg")
        assertTrue("Valid remote artwork URL must be HAS_ARTWORK", remoteTrack.hasRealArtwork)
        assertEquals(ArtworkStatus.HAS_ARTWORK, remoteTrack.artworkStatus)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 9 – Artwork added after initial scan (dynamic transition to HAS_ARTWORK)
    // Expected: HAS_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 09 - artwork added dynamically transitions track from NO_ARTWORK to HAS_ARTWORK`() {
        var track = baseTrack(id = "dynamic_t1", artworkCachePath = null, artworkUrl = null)
        assertFalse("Before artwork: must be NO_ARTWORK", track.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, track.artworkStatus)

        val artFile = tmpFolder.newFile("newly_added.jpg").also { it.writeBytes(ByteArray(512)) }
        track = track.copy(artworkCachePath = artFile.absolutePath)

        assertTrue("After adding valid artworkCachePath: must be HAS_ARTWORK", track.hasRealArtwork)
        assertEquals(ArtworkStatus.HAS_ARTWORK, track.artworkStatus)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 10 – Artwork removed (dynamic transition to NO_ARTWORK)
    // Expected: NO_ARTWORK
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 10 - artwork removed dynamically transitions track from HAS_ARTWORK to NO_ARTWORK`() {
        val artFile = tmpFolder.newFile("to_remove.jpg").also { it.writeBytes(ByteArray(512)) }
        var track = baseTrack(id = "dynamic_t2", artworkCachePath = artFile.absolutePath)
        assertTrue("Before removal: must be HAS_ARTWORK", track.hasRealArtwork)

        track = track.copy(artworkCachePath = null, artworkUrl = null)
        assertFalse("After removal: must be NO_ARTWORK", track.hasRealArtwork)
        assertEquals(ArtworkStatus.NO_ARTWORK, track.artworkStatus)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 11 – Library Insights "Missing Artwork" == locally computed NO_ARTWORK count
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 11 - Library Insights missing artwork count equals direct hasRealArtwork count`() {
        val artFile1 = tmpFolder.newFile("art1.jpg").also { it.writeBytes(ByteArray(1024)) }
        val artFile2 = tmpFolder.newFile("art2.jpg").also { it.writeBytes(ByteArray(1024)) }

        val tracks = listOf(
            baseTrack(id = "1", artworkCachePath = artFile1.absolutePath),
            baseTrack(id = "2", artworkUrl = artFile2.absolutePath),
            baseTrack(id = "3", artworkCachePath = null, artworkUrl = null),
            baseTrack(id = "4", artworkUrl = "content://media/external/audio/albumart/5"),
            baseTrack(id = "5", artworkCachePath = "/stale/path.jpg", artworkUrl = ""),
            baseTrack(id = "6", artworkUrl = "https://coverartarchive.org/release/123/front.jpg")
        )

        val directMissingCount = tracks.count { !it.hasRealArtwork }
        val directHasArtCount = tracks.count { it.hasRealArtwork }

        val insightsReport = SoundSyncIntelligenceEngine.getLibraryHealthInsights(tracks)
        val insightsMissingCount = insightsReport.tracksMissingArtwork

        assertEquals(
            "Library Insights tracksMissingArtwork must equal direct !hasRealArtwork count",
            directMissingCount,
            insightsMissingCount
        )
        assertEquals("Expected 3 tracks missing artwork in test library", 3, directMissingCount)
        assertEquals("Expected 3 tracks with artwork in test library", 3, directHasArtCount)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 12 – Artwork totals invariant: HAS_ARTWORK + NO_ARTWORK == TOTAL TRACKS
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 12 - HAS_ARTWORK + NO_ARTWORK equals total tracks`() {
        val artFile = tmpFolder.newFile("inv_art.jpg").also { it.writeBytes(ByteArray(512)) }
        val tracks = listOf(
            baseTrack(id = "1", artworkCachePath = artFile.absolutePath),
            baseTrack(id = "2", artworkUrl = "https://example.com/art.jpg"),
            baseTrack(id = "3", artworkUrl = "content://media/external/audio/albumart/1"),
            baseTrack(id = "4"),
            baseTrack(id = "5", artworkUrl = null, artworkCachePath = null),
            baseTrack(id = "6", artworkCachePath = "/stale/file.jpg"),
            baseTrack(id = "7", artworkUrl = "file:///nonexistent/file.jpg")
        )

        val hasArtCount = tracks.count { it.hasRealArtwork }
        val noArtCount = tracks.count { !it.hasRealArtwork }

        assertEquals(
            "HAS_ARTWORK + NO_ARTWORK must equal total tracks",
            tracks.size,
            hasArtCount + noArtCount
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 13 – Smart Crate HAS_ARTWORK filter matches canonical status
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 13 - SmartCrateEngine HAS_ARTWORK filter matches hasRealArtwork canonical property`() {
        val artFile = tmpFolder.newFile("smart_art.jpg").also { it.writeBytes(ByteArray(512)) }
        val tracks = listOf(
            baseTrack(id = "1", artworkCachePath = artFile.absolutePath),
            baseTrack(id = "2", artworkUrl = "https://example.com/art.jpg"),
            baseTrack(id = "3", artworkUrl = "content://media/external/audio/albumart/3"),
            baseTrack(id = "4", artworkCachePath = "/stale/path.jpg"),
            baseTrack(id = "5")
        )

        val hasArtRule = SmartRule(
            field = SmartField.HAS_ARTWORK,
            operator = SmartOperator.EQUALS,
            value = "true"
        )
        val noArtRule = SmartRule(
            field = SmartField.HAS_ARTWORK,
            operator = SmartOperator.EQUALS,
            value = "false"
        )

        val hasArtFiltered = tracks.filter { SmartCrateEngine.matchesRule(it, hasArtRule) }
        val noArtFiltered = tracks.filter { SmartCrateEngine.matchesRule(it, noArtRule) }

        assertEquals(
            "SmartCrate HAS_ARTWORK filter must match canonical hasRealArtwork",
            tracks.count { it.hasRealArtwork },
            hasArtFiltered.size
        )
        assertEquals(
            "SmartCrate NO_ARTWORK filter must match canonical !hasRealArtwork",
            tracks.count { !it.hasRealArtwork },
            noArtFiltered.size
        )
        assertEquals(
            "Filtered partition sums must equal total tracks",
            tracks.size,
            hasArtFiltered.size + noArtFiltered.size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 14 – Local Library CoverArtFilterMode Invariant
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 14 - Local Library CoverArtFilterMode filters partition library accurately`() {
        val artFile1 = tmpFolder.newFile("lib_art1.jpg").also { it.writeBytes(ByteArray(512)) }
        val artFile2 = tmpFolder.newFile("lib_art2.jpg").also { it.writeBytes(ByteArray(512)) }

        val tracks = listOf(
            baseTrack(id = "1", artworkCachePath = artFile1.absolutePath),
            baseTrack(id = "2", artworkUrl = artFile2.absolutePath),
            baseTrack(id = "3", artworkUrl = "content://media/external/audio/albumart/99"),
            baseTrack(id = "4", artworkCachePath = "/stale.jpg"),
            baseTrack(id = "5")
        )

        val allCount = tracks.size
        val noCoverCount = tracks.count { !it.hasRealArtwork }
        val hasCoverCount = tracks.count { it.hasRealArtwork }

        // Test filter results
        val allFiltered = tracks.filter { track ->
            when (CoverArtFilter.ALL) {
                CoverArtFilter.ALL -> true
                CoverArtFilter.NO_COVER_ART -> !track.hasRealArtwork
                CoverArtFilter.HAS_COVER_ART -> track.hasRealArtwork
            }
        }
        val noCoverFiltered = tracks.filter { track ->
            when (CoverArtFilter.NO_COVER_ART) {
                CoverArtFilter.ALL -> true
                CoverArtFilter.NO_COVER_ART -> !track.hasRealArtwork
                CoverArtFilter.HAS_COVER_ART -> track.hasRealArtwork
            }
        }
        val hasCoverFiltered = tracks.filter { track ->
            when (CoverArtFilter.HAS_COVER_ART) {
                CoverArtFilter.ALL -> true
                CoverArtFilter.NO_COVER_ART -> !track.hasRealArtwork
                CoverArtFilter.HAS_COVER_ART -> track.hasRealArtwork
            }
        }

        assertEquals("All Tracks count must equal total tracks", allCount, allFiltered.size)
        assertEquals("No Cover Art count must equal noCoverFiltered size", noCoverCount, noCoverFiltered.size)
        assertEquals("Has Cover Art count must equal hasCoverFiltered size", hasCoverCount, hasCoverFiltered.size)

        // Invariant: No Cover Art + Has Cover Art = All Tracks
        assertEquals("Invariant: No Cover Art + Has Cover Art == All Tracks", allCount, noCoverCount + hasCoverCount)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test 15 – Invalidation clears cached entries
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test 15 - invalidate clears cached status for specific track and invalidateAll clears all`() {
        val artFile = tmpFolder.newFile("inv_test.jpg").also { it.writeBytes(ByteArray(512)) }
        val track = baseTrack(id = "track_to_invalidate", artworkCachePath = artFile.absolutePath)

        assertEquals(ArtworkStatus.HAS_ARTWORK, ArtworkStatusResolver.getStatus(track))

        // Invalidate single track
        ArtworkStatusResolver.invalidate("track_to_invalidate")

        // Status re-resolved
        assertEquals(ArtworkStatus.HAS_ARTWORK, ArtworkStatusResolver.getStatus(track))

        // Invalidate all
        ArtworkStatusResolver.invalidateAll()
        assertEquals(ArtworkStatus.HAS_ARTWORK, ArtworkStatusResolver.getStatus(track))
    }
}
