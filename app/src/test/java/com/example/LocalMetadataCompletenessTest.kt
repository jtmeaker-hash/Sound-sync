package com.example

import com.example.metadata.LocalMetadataCompletenessChecker
import com.example.model.AnalysisState
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataCompletenessTest {

    private fun createSampleTrack(
        title: String = "Strobe",
        artist: String = "deadmau5",
        album: String = "For Lack of a Better Name",
        genre: String = "Progressive House",
        releaseYear: Int? = 2009,
        releaseDate: String? = "2009-09-22",
        trackNumber: Int = 1,
        discNumber: Int = 1,
        bpm: Double = 128.0,
        bpmConfidence: Double = 1.0,
        musicalKey: String = "8A",
        camelotKey: String = "8A",
        hasArtwork: Boolean = true,
        artworkSource: String? = "Embedded Tag"
    ): Track {
        return Track(
            id = "track_${System.nanoTime()}",
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            releaseYear = releaseYear,
            releaseDate = releaseDate,
            trackNumber = trackNumber,
            discNumber = discNumber,
            bpm = bpm,
            bpmConfidence = bpmConfidence,
            musicalKey = musicalKey,
            camelotKey = camelotKey,
            artworkSource = artworkSource,
            filePath = "/storage/emulated/0/Music/test.mp3",
            directoryPath = "/storage/emulated/0/Music",
            durationSeconds = 637,
            bitrateKbps = 320,
            format = "MP3",
            fileSizeMb = 24.5,
            isOfflineReady = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            qualityRating = AudioQualityRating.TRUE_320,
            dateAdded = System.currentTimeMillis()
        )
    }

    @Test
    fun testCompleteTrackEvaluatesToComplete() {
        val track = createSampleTrack()
        val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
        assertTrue("Track with all required fields must be complete", result.isComplete)
        assertTrue("Missing fields list should be empty", result.missingFields.isEmpty())
    }

    @Test
    fun testOptionalFieldsDoNotBlockCompleteness() {
        val track = createSampleTrack().copy(
            albumArtist = "",
            isrc = null,
            barcode = null,
            recordLabel = null,
            rating = 0,
            notes = "",
            composer = ""
        )
        val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
        assertTrue("Missing optional fields must NOT cause track to be incomplete", result.isComplete)
    }

    @Test
    fun testPlaceholderTitlesAreIncomplete() {
        val placeholders = listOf(
            "Track 01", "track 1", "Track_05", "Audio Track 12", "Untitled",
            "Unknown", "Unknown Title", "N/A", "None", "Undefined", ""
        )
        for (p in placeholders) {
            val track = createSampleTrack(title = p)
            val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
            assertFalse("Title '$p' must be identified as incomplete", result.isComplete)
            assertTrue("Missing fields must contain Title for '$p'", result.missingFields.contains("Title"))
        }
    }

    @Test
    fun testPlaceholderArtistsAreIncomplete() {
        val placeholders = listOf(
            "Unknown", "Unknown Artist", "<unknown>", "N/A", "None", "Undefined", "Various Artists", ""
        )
        for (p in placeholders) {
            val track = createSampleTrack(artist = p)
            val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
            assertFalse("Artist '$p' must be identified as incomplete", result.isComplete)
            assertTrue("Missing fields must contain Artist for '$p'", result.missingFields.contains("Artist"))
        }
    }

    @Test
    fun testPlaceholderAlbumsAreIncomplete() {
        val placeholders = listOf(
            "Unknown", "Unknown Album", "<unknown>", "N/A", "None", "Undefined", "Album", "Generic Album", ""
        )
        for (p in placeholders) {
            val track = createSampleTrack(album = p)
            val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
            assertFalse("Album '$p' must be identified as incomplete", result.isComplete)
            assertTrue("Missing fields must contain Album for '$p'", result.missingFields.contains("Album"))
        }
    }

    @Test
    fun testPlaceholderGenresAreIncomplete() {
        val placeholders = listOf(
            "Unknown", "Unknown Genre", "<unknown>", "N/A", "None", "Undefined", ""
        )
        for (p in placeholders) {
            val track = createSampleTrack(genre = p)
            val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
            assertFalse("Genre '$p' must be identified as incomplete", result.isComplete)
            assertTrue("Missing fields must contain Genre for '$p'", result.missingFields.contains("Genre"))
        }
    }

    @Test
    fun testMissingYearIsIncomplete() {
        val track = createSampleTrack(releaseYear = null, releaseDate = null)
        val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
        assertFalse("Missing year must be incomplete", result.isComplete)
        assertTrue("Missing fields must contain Year", result.missingFields.contains("Year"))

        val trackWithValidYear = createSampleTrack(releaseYear = 2018, releaseDate = null)
        assertTrue("Valid releaseYear satisfies year requirement", LocalMetadataCompletenessChecker.evaluateTrack(null, trackWithValidYear).isComplete)

        val trackWithValidDate = createSampleTrack(releaseYear = null, releaseDate = "2020-05-14")
        assertTrue("Valid releaseDate string satisfies year requirement", LocalMetadataCompletenessChecker.evaluateTrack(null, trackWithValidDate).isComplete)
    }

    @Test
    fun testMissingTrackNumberIsIncomplete() {
        val track = createSampleTrack(trackNumber = 0)
        val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
        assertFalse("Track number 0 must be incomplete", result.isComplete)
        assertTrue("Missing fields must contain TrackNumber", result.missingFields.contains("TrackNumber"))
    }

    @Test
    fun testBpmValidation() {
        val invalidBpms = listOf(0.0, -10.0, 15.0, 350.0)
        for (b in invalidBpms) {
            val track = createSampleTrack(bpm = b)
            val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
            assertFalse("BPM $b must be incomplete", result.isComplete)
            assertTrue("Missing fields must contain BPM for $b", result.missingFields.contains("BPM"))
        }

        // Default 120.0 with 0 confidence is a placeholder
        val defaultBpmTrack = createSampleTrack(bpm = 120.0, bpmConfidence = 0.0)
        assertFalse("120.0 BPM with 0 confidence must be incomplete", LocalMetadataCompletenessChecker.evaluateTrack(null, defaultBpmTrack).isComplete)

        // Valid 120.0 with positive confidence
        val valid120Track = createSampleTrack(bpm = 120.0, bpmConfidence = 0.85)
        assertTrue("120.0 BPM with positive confidence is valid", LocalMetadataCompletenessChecker.evaluateTrack(null, valid120Track).isComplete)
    }

    @Test
    fun testMusicalKeyValidation() {
        val invalidKeys = listOf("", "—", "-", "Unknown", "None", "N/A", "null", "undefined")
        for (k in invalidKeys) {
            val track = createSampleTrack(musicalKey = k, camelotKey = k)
            val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
            assertFalse("Key '$k' must be incomplete", result.isComplete)
            assertTrue("Missing fields must contain MusicalKey for '$k'", result.missingFields.contains("MusicalKey"))
        }

        val validKeys = listOf("8A", "11B", "Am", "C#m", "D min", "F# Maj")
        for (k in validKeys) {
            val track = createSampleTrack(musicalKey = k, camelotKey = k)
            assertTrue("Key '$k' must be valid", LocalMetadataCompletenessChecker.evaluateTrack(null, track).isComplete)
        }
    }

    @Test
    fun testMissingArtworkIsIncomplete() {
        val track = createSampleTrack(hasArtwork = false, artworkSource = null)
        val result = LocalMetadataCompletenessChecker.evaluateTrack(null, track)
        assertFalse("Missing artwork must be incomplete", result.isComplete)
        assertTrue("Missing fields must contain CoverArt", result.missingFields.contains("CoverArt"))
    }

    @Test
    fun testScanQueueSizingSimulation() {
        // Simulation from prompt:
        // Total library: 4,000 tracks
        // Already complete in file tags: 3,650 tracks
        // Needing analysis: 350 tracks
        // Queue total must be 350, not 4,000.
        val totalTracks = 4000
        val completeCount = 3650
        val incompleteCount = totalTracks - completeCount // 350

        val library = mutableListOf<Track>()
        // 3650 complete tracks
        for (i in 1..completeCount) {
            library.add(createSampleTrack(title = "Original Mix $i", trackNumber = i))
        }
        // 350 incomplete tracks (e.g. missing BPM or missing artwork or missing key)
        for (i in 1..incompleteCount) {
            val track = if (i % 3 == 0) {
                createSampleTrack(title = "Song $i", bpm = 0.0) // Missing BPM
            } else if (i % 3 == 1) {
                createSampleTrack(title = "Song $i", hasArtwork = false, artworkSource = null) // Missing Artwork
            } else {
                createSampleTrack(title = "Song $i", musicalKey = "—", camelotKey = "") // Missing Key
            }
            library.add(track)
        }

        val eligibleForAnalysis = library.filter { track ->
            !LocalMetadataCompletenessChecker.evaluateTrack(null, track).isComplete
        }

        assertEquals("Completed tracks must be completely excluded from queue", incompleteCount, eligibleForAnalysis.size)
        assertEquals("Exactly 350 tracks should need analysis", 350, eligibleForAnalysis.size)
    }
}
