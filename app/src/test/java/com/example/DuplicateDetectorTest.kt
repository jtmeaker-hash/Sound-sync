package com.example

import com.example.analysis.DuplicateDetector
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {

    private fun createTrack(
        id: String,
        title: String,
        artist: String,
        durationSeconds: Int = 200,
        bpm: Double = 128.0,
        fingerprint: String = ""
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = "Test Album",
            durationSeconds = durationSeconds,
            bpm = bpm,
            musicalKey = "8A",
            bitrateKbps = 320,
            format = "MP3",
            fileSizeMb = 8.0,
            filePath = "/storage/emulated/0/Music/$title.mp3",
            directoryPath = "/storage/emulated/0/Music",
            isOfflineReady = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            energyRating = 7,
            hotCues = listOf(0, 30),
            isAiTagged = false,
            qualityRating = AudioQualityRating.TRUE_320,
            contentFingerprint = fingerprint
        )
    }

    @Test
    fun `finds exact duplicate by audio fingerprint`() {
        val t1 = createTrack("1", "Track One", "Artist A", durationSeconds = 180, fingerprint = "hash_abc_123")
        val t2 = createTrack("2", "Different Title", "Different Artist", durationSeconds = 180, fingerprint = "hash_abc_123")

        val matches = DuplicateDetector.findDuplicates(listOf(t1, t2))
        assertEquals(1, matches.size)
        assertEquals(100, matches[0].similarityScore)
    }

    @Test
    fun `finds fuzzy duplicates with similar title and artist`() {
        val t1 = createTrack("1", "Atmospheric Echoes (Extended Club Mix)", "Nexus & Solis", durationSeconds = 384, bpm = 126.0)
        val t2 = createTrack("2", "01. Nexus - Atmospheric Echoes [12'' Master Rip]", "Nexus", durationSeconds = 382, bpm = 126.0)

        val matches = DuplicateDetector.findDuplicates(listOf(t1, t2))
        assertTrue(matches.isNotEmpty())
        assertTrue(matches[0].similarityScore >= 68)
    }

    @Test
    fun `ignores tracks with disparate duration`() {
        val t1 = createTrack("1", "Midnight Session", "DJ Nova", durationSeconds = 120)
        val t2 = createTrack("2", "Midnight Session", "DJ Nova", durationSeconds = 480)

        val matches = DuplicateDetector.findDuplicates(listOf(t1, t2))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `empty and single track list returns empty`() {
        assertTrue(DuplicateDetector.findDuplicates(emptyList()).isEmpty())
        assertTrue(DuplicateDetector.findDuplicates(listOf(createTrack("1", "Solo", "Artist"))).isEmpty())
    }
}
