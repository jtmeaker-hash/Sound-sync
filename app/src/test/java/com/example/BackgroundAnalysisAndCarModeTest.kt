package com.example

import com.example.carmode.CarAudioProfile
import com.example.carmode.CarDisplayMode
import com.example.carmode.DrivingSession
import com.example.carmode.PlaySomethingSource
import com.example.data.TrackEntity
import com.example.model.AnalysisState
import com.example.model.AudioQualityRating
import com.example.model.BitrateMode
import com.example.model.Track
import com.example.ui.components.splitArtistNames
import com.example.ui.components.splitArtistSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAnalysisAndCarModeTest {

    // ========================================================================
    // 1. Artist Name Splitting Tests
    // ========================================================================

    @Test
    fun testSplitArtistNamesSingleArtist() {
        val names = splitArtistNames("Daft Punk")
        assertEquals(listOf("Daft Punk"), names)

        val segments = splitArtistSegments("Daft Punk")
        assertEquals(1, segments.size)
        assertTrue(segments[0].isArtist)
        assertEquals("Daft Punk", segments[0].text)
    }

    @Test
    fun testSplitArtistNamesFeaturingVariants() {
        // "feat."
        val featDot = splitArtistNames("Calvin Harris feat. Dua Lipa")
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), featDot)

        // "ft."
        val ftDot = splitArtistNames("Major Lazer ft. Justin Bieber")
        assertEquals(listOf("Major Lazer", "Justin Bieber"), ftDot)

        // "featuring"
        val featuring = splitArtistNames("Drake featuring Wizkid")
        assertEquals(listOf("Drake", "Wizkid"), featuring)

        // "vs."
        val vsDot = splitArtistNames("Armin van Buuren vs. Sophie Ellis-Bextor")
        assertEquals(listOf("Armin van Buuren", "Sophie Ellis-Bextor"), vsDot)

        // "with"
        val with = splitArtistNames("Disclosure with Sam Smith")
        assertEquals(listOf("Disclosure", "Sam Smith"), with)
    }

    @Test
    fun testSplitArtistNamesDelimiters() {
        // Ampersand
        val ampersand = splitArtistNames("Skrillex & Diplo")
        assertEquals(listOf("Skrillex", "Diplo"), ampersand)

        // Comma
        val comma = splitArtistNames("Tiësto, Martin Garrix, Hardwell")
        assertEquals(listOf("Tiësto", "Martin Garrix", "Hardwell"), comma)

        // Slash
        val slash = splitArtistNames("Swedish House Mafia / Axwell")
        assertEquals(listOf("Swedish House Mafia", "Axwell"), slash)

        // Semicolon
        val semicolon = splitArtistNames("Deadmau5; Kaskade")
        assertEquals(listOf("Deadmau5", "Kaskade"), semicolon)

        // "x" collaboration token
        val xCollab = splitArtistNames("Marshmello x Khalid")
        assertEquals(listOf("Marshmello", "Khalid"), xCollab)
    }

    @Test
    fun testSplitArtistSegmentsPreservesDelimitersForUi() {
        val segments = splitArtistSegments("Daft Punk feat. Pharrell Williams & Nile Rodgers")
        assertEquals(5, segments.size)

        assertEquals("Daft Punk", segments[0].text)
        assertTrue(segments[0].isArtist)

        assertEquals(" feat. ", segments[1].text)
        assertFalse(segments[1].isArtist)

        assertEquals("Pharrell Williams", segments[2].text)
        assertTrue(segments[2].isArtist)

        assertEquals(" & ", segments[3].text)
        assertFalse(segments[3].isArtist)

        assertEquals("Nile Rodgers", segments[4].text)
        assertTrue(segments[4].isArtist)
    }

    @Test
    fun testSplitArtistNamesEmptyAndUnknown() {
        assertEquals(emptyList<String>(), splitArtistNames(""))
        assertEquals(emptyList<String>(), splitArtistNames("   "))
        assertEquals(emptyList<String>(), splitArtistNames("Unknown Artist"))

        val unknownSegments = splitArtistSegments("Unknown Artist")
        assertEquals(1, unknownSegments.size)
        assertFalse(unknownSegments[0].isArtist)
    }

    // ========================================================================
    // 2. Car Mode Models & Display Modes
    // ========================================================================

    @Test
    fun testCarAudioProfileDefaults() {
        val profile = CarAudioProfile(deviceAddress = "00:11:22:33:44:55", deviceName = "My Car")
        assertEquals("00:11:22:33:44:55", profile.deviceAddress)
        assertEquals("My Car", profile.deviceName)
        assertEquals("Car Flat", profile.eqPreset)
        assertEquals(1.0f, profile.customEqLow)
        assertEquals(1.0f, profile.customEqMid)
        assertEquals(1.0f, profile.customEqHigh)
        assertFalse(profile.haasEnabled)
        assertEquals(4, profile.crossfadeDurationSec)
        assertTrue(profile.replayGainEnabled)
        assertTrue(profile.autoLaunch)
        assertTrue(profile.resumeOnConnect)
        assertTrue(profile.pauseOnDisconnect)
        assertEquals(CarDisplayMode.ARTWORK, profile.preferredDisplayMode)
    }

    @Test
    fun testCarDisplayModeCycling() {
        val mode1 = CarDisplayMode.ARTWORK
        val mode2 = when (mode1) {
            CarDisplayMode.ARTWORK -> CarDisplayMode.WAVEFORM
            CarDisplayMode.WAVEFORM -> CarDisplayMode.DJ_DASHBOARD
            CarDisplayMode.DJ_DASHBOARD -> CarDisplayMode.ARTWORK
        }
        assertEquals(CarDisplayMode.WAVEFORM, mode2)

        val mode3 = when (mode2) {
            CarDisplayMode.ARTWORK -> CarDisplayMode.WAVEFORM
            CarDisplayMode.WAVEFORM -> CarDisplayMode.DJ_DASHBOARD
            CarDisplayMode.DJ_DASHBOARD -> CarDisplayMode.ARTWORK
        }
        assertEquals(CarDisplayMode.DJ_DASHBOARD, mode3)

        val mode4 = when (mode3) {
            CarDisplayMode.ARTWORK -> CarDisplayMode.WAVEFORM
            CarDisplayMode.WAVEFORM -> CarDisplayMode.DJ_DASHBOARD
            CarDisplayMode.DJ_DASHBOARD -> CarDisplayMode.ARTWORK
        }
        assertEquals(CarDisplayMode.ARTWORK, mode4)
    }

    @Test
    fun testDrivingSessionTracking() {
        val start = System.currentTimeMillis()
        val session = DrivingSession(
            id = "test_session_1",
            carName = "BMW 330i",
            startedAt = start,
            endedAt = start + 30 * 60 * 1000L,
            totalDurationMs = 30 * 60 * 1000L,
            tracksPlayedCount = 3,
            tracksSkippedCount = 1,
            trackTitles = listOf("One More Time", "Aerodynamic", "Digital Love"),
            artistNames = listOf("Daft Punk", "Daft Punk", "Daft Punk")
        )

        assertEquals("BMW 330i", session.carName)
        assertEquals(3, session.tracksPlayedCount)
        assertEquals(1, session.tracksSkippedCount)
        assertEquals(30 * 60 * 1000L, session.totalDurationMs)
        assertEquals(3, session.trackTitles.size)
    }

    @Test
    fun testPlaySomethingSources() {
        assertEquals("Favourites", PlaySomethingSource.FAVORITES.label)
        assertEquals("Recently Added", PlaySomethingSource.RECENTLY_ADDED.label)
        assertEquals("Driving Playlist", PlaySomethingSource.DRIVING_PLAYLIST.label)
        assertEquals("Unplayed Music", PlaySomethingSource.UNPLAYED.label)
        assertEquals("Entire Library", PlaySomethingSource.LIBRARY.label)
    }

    // ========================================================================
    // 3. Analysis State & Entity Mapping
    // ========================================================================

    @Test
    fun testAnalysisStateEnum() {
        val states = AnalysisState.values()
        assertTrue(states.contains(AnalysisState.NOT_ANALYSED))
        assertTrue(states.contains(AnalysisState.QUEUED))
        assertTrue(states.contains(AnalysisState.ANALYSING))
        assertTrue(states.contains(AnalysisState.PARTIAL))
        assertTrue(states.contains(AnalysisState.COMPLETE))
        assertTrue(states.contains(AnalysisState.FAILED))
    }

    @Test
    fun testTrackEntityAnalysisStateMapping() {
        val track = Track(
            id = "track_123",
            title = "Harder, Better, Faster, Stronger",
            artist = "Daft Punk",
            album = "Discovery",
            durationSeconds = 224,
            filePath = "/storage/emulated/0/Music/Discovery/04.flac",
            bpm = 123.0,
            musicalKey = "F#m",
            camelotKey = "11A",
            analysisState = AnalysisState.COMPLETE,
            analysisVersion = 1,
            lastAnalysedAt = 1700000000000L,
            analysisFailureReason = null,
            analysisRetryCount = 0,
            fileModifiedTimestamp = 1690000000000L
        )

        val entity = TrackEntity.fromTrack(track)
        assertEquals("COMPLETE", entity.analysisState)
        assertEquals(1, entity.analysisVersion)
        assertEquals(1700000000000L, entity.lastAnalysedAt)
        assertEquals(0, entity.analysisRetryCount)
        assertEquals(1690000000000L, entity.fileModifiedTimestamp)

        val converted = entity.toTrack()
        assertEquals(AnalysisState.COMPLETE, converted.analysisState)
        assertEquals(1, converted.analysisVersion)
        assertEquals(1700000000000L, converted.lastAnalysedAt)
        assertEquals(0, converted.analysisRetryCount)
        assertEquals(1690000000000L, converted.fileModifiedTimestamp)
        assertTrue(converted.hasValidBpm)
        assertTrue(converted.hasValidKey)
    }

    @Test
    fun testTrackEntityAnalysisFailureMapping() {
        val track = Track(
            id = "track_corrupted",
            title = "Corrupted Audio",
            artist = "Unknown",
            album = "",
            durationSeconds = 0,
            filePath = "/storage/emulated/0/Music/corrupted.mp3",
            analysisState = AnalysisState.FAILED,
            analysisVersion = 1,
            lastAnalysedAt = 1700000050000L,
            analysisFailureReason = "Unrecognized audio codec format",
            analysisRetryCount = 2,
            fileModifiedTimestamp = 1690000050000L
        )

        val entity = TrackEntity.fromTrack(track)
        assertEquals("FAILED", entity.analysisState)
        assertEquals("Unrecognized audio codec format", entity.analysisFailureReason)
        assertEquals(2, entity.analysisRetryCount)

        val converted = entity.toTrack()
        assertEquals(AnalysisState.FAILED, converted.analysisState)
        assertEquals("Unrecognized audio codec format", converted.analysisFailureReason)
        assertEquals(2, converted.analysisRetryCount)
        assertFalse(converted.hasValidBpm)
        assertFalse(converted.hasValidKey)
    }
}
