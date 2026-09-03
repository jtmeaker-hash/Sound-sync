package com.example

import com.example.data.BulkOperationHistoryEntity
import com.example.data.ListeningOverviewStats
import com.example.data.PlaybackSessionEntity
import com.example.data.TrackEntity
import com.example.data.TrackPlaybackStats
import com.example.metadata.CamelotKey
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TrackInspectorAndStatsTest {

    @Test
    fun testTrackPlaybackStatsCompletionRate() {
        val stats = TrackPlaybackStats(
            playCount = 10,
            completedCount = 8,
            skippedCount = 2,
            totalListeningMs = 1800000L
        )
        assertEquals(80.0f, stats.completionRate, 0.01f)
    }

    @Test
    fun testTrackPlaybackStatsZeroPlays() {
        val stats = TrackPlaybackStats(
            playCount = 0,
            completedCount = 0,
            skippedCount = 0,
            totalListeningMs = 0L
        )
        assertEquals(0.0f, stats.completionRate, 0.01f)
    }

    @Test
    fun testListeningOverviewStatsRates() {
        val overview = ListeningOverviewStats(
            totalPlays = 50,
            totalListeningMs = 9000000L,
            uniqueTracksPlayed = 30,
            totalCompleted = 40,
            totalSkipped = 5
        )
        assertEquals(80.0f, overview.completionRate, 0.01f)
        assertEquals(10.0f, overview.skipRate, 0.01f)
    }

    @Test
    fun testBpmHalvingAndDoubling() {
        val track = Track(
            id = "test-1",
            title = "Test BPM",
            artist = "Artist",
            filePath = "/music/test.mp3",
            bpm = 140.0
        )
        val halved = track.copy(bpm = track.bpm / 2.0, isManualBpm = true)
        assertEquals(70.0, halved.bpm, 0.001)
        assertTrue(halved.isManualBpm)

        val doubled = track.copy(bpm = track.bpm * 2.0, isManualBpm = true)
        assertEquals(280.0, doubled.bpm, 0.001)
        assertTrue(doubled.isManualBpm)
    }

    @Test
    fun testCamelotKeyDerivation() {
        assertEquals("8A", CamelotKey.fromMusicalKey("Am"))
        assertEquals("8B", CamelotKey.fromMusicalKey("C"))
        assertEquals("11B", CamelotKey.fromMusicalKey("A"))
        assertEquals("4A", CamelotKey.fromMusicalKey("Fm"))
        assertEquals("12B", CamelotKey.fromMusicalKey("E"))
    }

    @Test
    fun testTrackEntityMappingWithNewFields() {
        val original = Track(
            id = "t100",
            title = "Deep Sound",
            artist = "DJ Horizon",
            album = "Summer Waves",
            filePath = "/storage/emulated/0/Music/deep.mp3",
            rating = 5,
            customTags = "Peak Time, Vocal, Weapon",
            notes = "Start fade at 3:30. Drop at 1:15.",
            composer = "Horizon Composer",
            isManualBpm = true,
            isManualKey = true,
            durationSeconds = 240
        )

        val entity = TrackEntity.fromTrack(original)
        assertEquals(5, entity.rating)
        assertEquals("Peak Time, Vocal, Weapon", entity.customTags)
        assertEquals("Start fade at 3:30. Drop at 1:15.", entity.notes)
        assertEquals("Horizon Composer", entity.composer)
        assertTrue(entity.isManualBpm)
        assertTrue(entity.isManualKey)

        val restored = entity.toTrack()
        assertEquals(original.id, restored.id)
        assertEquals(original.rating, restored.rating)
        assertEquals(original.customTags, restored.customTags)
        assertEquals(original.notes, restored.notes)
        assertEquals(original.composer, restored.composer)
        assertEquals(listOf("Peak Time", "Vocal", "Weapon"), restored.tagsList)
        assertTrue(restored.isManualBpm)
        assertTrue(restored.isManualKey)
    }

    @Test
    fun testBulkMetadataSelectiveApplication() {
        val tracks = listOf(
            Track(id = "1", title = "Track A", artist = "Old Artist A", album = "Old Album 1", genre = "Rock", filePath = "/1.mp3"),
            Track(id = "2", title = "Track B", artist = "Old Artist B", album = "Old Album 2", genre = "Pop", filePath = "/2.mp3")
        )

        // Simulate bulk editor with ONLY genre and rating checked
        val applyGenre = true
        val targetGenre = "Techno"
        val targetRating = 4

        val updated = tracks.map { t ->
            var updatedTrack = t
            if (applyGenre) updatedTrack = updatedTrack.copy(genre = targetGenre)
            updatedTrack = updatedTrack.copy(rating = targetRating)
            updatedTrack
        }

        // Artist and Album must remain untouched!
        assertEquals("Old Artist A", updated[0].artist)
        assertEquals("Old Album 1", updated[0].album)
        assertEquals("Techno", updated[0].genre)
        assertEquals(4, updated[0].rating)

        assertEquals("Old Artist B", updated[1].artist)
        assertEquals("Old Album 2", updated[1].album)
        assertEquals("Techno", updated[1].genre)
        assertEquals(4, updated[1].rating)
    }

    @Test
    fun testCustomTagAdditionAndRemoval() {
        val track = Track(id = "1", title = "T", artist = "A", filePath = "/1.mp3", customTags = "Peak Time, Vocal")
        
        // Add tag
        val tagToAdd = "Heavy Bass"
        val addedTags = (track.tagsList + tagToAdd).distinct().joinToString(",")
        val withAdded = track.copy(customTags = addedTags)
        assertEquals(listOf("Peak Time", "Vocal", "Heavy Bass"), withAdded.tagsList)

        // Remove tag
        val removedTags = withAdded.tagsList.filter { it != "Vocal" }.joinToString(",")
        val withRemoved = withAdded.copy(customTags = removedTags)
        assertEquals(listOf("Peak Time", "Heavy Bass"), withRemoved.tagsList)
    }

    @Test
    fun testSmartFileRenamerFormatting() {
        val track = Track(
            id = "1",
            title = "Strobe",
            artist = "deadmau5",
            album = "For Lack of a Better Name",
            trackNumber = 5,
            filePath = "/music/original.flac"
        )

        val ext = track.filePath.substringAfterLast(".", "mp3")

        val format1 = "${track.artist} - ${track.title}.$ext"
        assertEquals("deadmau5 - Strobe.flac", format1)

        val format2 = "${track.artist} - ${track.album} - ${track.title}.$ext"
        assertEquals("deadmau5 - For Lack of a Better Name - Strobe.flac", format2)

        val format3 = "${String.format(Locale.US, "%02d", track.trackNumber)} - ${track.title}.$ext"
        assertEquals("05 - Strobe.flac", format3)
    }

    @Test
    fun testPlaybackSessionEntityDefaults() {
        val session = PlaybackSessionEntity(
            id = 1L,
            trackId = "track-123",
            startedAt = 1000L,
            endedAt = 2000L,
            listenedDurationMs = 35000L,
            trackDurationMs = 180000L,
            completed = false,
            skipped = true,
            playbackContext = "LIBRARY"
        )
        assertEquals("track-123", session.trackId)
        assertEquals(35000L, session.listenedDurationMs)
        assertTrue(session.skipped)
        assertFalse(session.completed)
    }
}
