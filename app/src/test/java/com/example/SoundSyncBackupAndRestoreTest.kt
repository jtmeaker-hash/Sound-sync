package com.example

import com.example.backup.MatchConfidenceLevel
import com.example.backup.SongFindBackupItem
import com.example.backup.SoundSyncBackup
import com.example.backup.SoundSyncBackupManager
import com.example.backup.TrackBackupItem
import com.example.backup.TrackMatcher
import com.example.backup.ValidationResult
import com.example.data.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundSyncBackupAndRestoreTest {

    @Test
    fun `SoundSyncBackupModels serialization round-trip preserves all fields`() {
        val originalFind = SongFindBackupItem(
            id = "find_001",
            url = "https://open.spotify.com/track/12345",
            title = "Glue - Bicep",
            sourceAppName = "Spotify",
            notes = "Heard at Printworks",
            createdAt = 1700000000L,
            isCompleted = true
        )

        val jsonFind = originalFind.toJson()
        val parsedFind = SongFindBackupItem.fromJson(jsonFind)
        assertEquals(originalFind.id, parsedFind.id)
        assertEquals(originalFind.url, parsedFind.url)
        assertEquals(originalFind.title, parsedFind.title)
        assertEquals(originalFind.sourceAppName, parsedFind.sourceAppName)
        assertEquals(originalFind.notes, parsedFind.notes)
        assertEquals(originalFind.createdAt, parsedFind.createdAt)
        assertEquals(originalFind.isCompleted, parsedFind.isCompleted)

        val originalTrack = TrackBackupItem(
            id = "track_001",
            title = "Strobe",
            artist = "Deadmau5",
            originalArtist = "Unknown",
            resolvedArtist = "Deadmau5",
            metadataSource = "Apple + TheAudioDB",
            metadataConfidence = 95.0,
            bpm = 128.0,
            bpmConfidence = 0.99,
            musicalKey = "A# Minor",
            camelotKey = "3A",
            durationSeconds = 637,
            filePath = "/storage/emulated/0/Music/Deadmau5 - Strobe.mp3",
            storageRelativePath = "Music/Deadmau5 - Strobe.mp3",
            contentFingerprint = "fp_deadmau5_strobe_xyz",
            appleTrackId = 12345L,
            analysisState = "COMPLETE",
            analysisVersion = 2,
            hotCuesString = "0,32,64,128",
            energyRating = 8,
            rating = 5,
            notes = "Peak-time progressive house masterpiece",
            fileModifiedTimestamp = 1705000000L
        )

        val jsonTrack = originalTrack.toJson()
        val parsedTrack = TrackBackupItem.fromJson(jsonTrack)
        assertEquals(originalTrack.id, parsedTrack.id)
        assertEquals(originalTrack.title, parsedTrack.title)
        assertEquals(originalTrack.artist, parsedTrack.artist)
        assertEquals(originalTrack.originalArtist, parsedTrack.originalArtist)
        assertEquals(originalTrack.resolvedArtist, parsedTrack.resolvedArtist)
        assertEquals(originalTrack.metadataSource, parsedTrack.metadataSource)
        assertEquals(originalTrack.metadataConfidence, parsedTrack.metadataConfidence, 0.01)
        assertEquals(originalTrack.bpm, parsedTrack.bpm, 0.01)
        assertEquals(originalTrack.camelotKey, parsedTrack.camelotKey)
        assertEquals(originalTrack.contentFingerprint, parsedTrack.contentFingerprint)
        assertEquals(originalTrack.appleTrackId, parsedTrack.appleTrackId)
        assertEquals(originalTrack.analysisState, parsedTrack.analysisState)
        assertEquals(originalTrack.hotCuesString, parsedTrack.hotCuesString)
        assertEquals(originalTrack.rating, parsedTrack.rating)
    }

    @Test
    fun `TrackMatcher prioritizes acoustic fingerprint then Apple Track ID`() {
        val backupTrack = TrackBackupItem(
            id = "bk_1",
            title = "Opus",
            artist = "Eric Prydz",
            contentFingerprint = "fp_unique_123",
            appleTrackId = 456L,
            storageRelativePath = "DJ/Opus.mp3",
            filePath = "/old/path/Opus.mp3",
            durationSeconds = 540,
            fileSizeMb = 20.0
        )

        val fpCandidate = TrackEntity(
            id = "entity_fp",
            title = "Opus (Club Mix)",
            artist = "Eric Prydz",
            contentFingerprint = "fp_unique_123",
            appleTrackId = 999L,
            storageRelativePath = "NewFolder/Opus.mp3",
            filePath = "/new/path/Opus.mp3",
            durationSeconds = 540,
            fileSizeMb = 20.0
        )

        val match = TrackMatcher.matchTrack(backupTrack, listOf(fpCandidate))
        assertEquals(MatchConfidenceLevel.FINGERPRINT, match.confidenceLevel)
        assertEquals("entity_fp", match.matchedEntity?.id)
        assertFalse("Audio file should not be flagged as modified", match.isFileModified)
    }

    @Test
    fun `TrackMatcher matches by relative path and detects modified or replaced files`() {
        val backupTrack = TrackBackupItem(
            id = "bk_2",
            title = "Satisfaction",
            artist = "Benny Benassi",
            storageRelativePath = "House/Satisfaction.mp3",
            durationSeconds = 285,
            fileSizeMb = 10.5,
            fileModifiedTimestamp = 1000L
        )

        // Exact match
        val exactCandidate = TrackEntity(
            id = "entity_exact",
            title = "Satisfaction",
            artist = "Benny Benassi",
            storageRelativePath = "House/Satisfaction.mp3",
            durationSeconds = 285,
            fileSizeMb = 10.5,
            fileModifiedTimestamp = 1000L
        )
        val matchExact = TrackMatcher.matchTrack(backupTrack, listOf(exactCandidate))
        assertEquals(MatchConfidenceLevel.RELATIVE_PATH_EXACT, matchExact.confidenceLevel)
        assertFalse(matchExact.isFileModified)

        // Modified candidate: duration differs significantly (e.g. replaced with short radio edit)
        val modifiedCandidate = TrackEntity(
            id = "entity_modified",
            title = "Satisfaction",
            artist = "Benny Benassi",
            storageRelativePath = "House/Satisfaction.mp3",
            durationSeconds = 140, // 285 vs 140 seconds
            fileSizeMb = 5.2,
            fileModifiedTimestamp = 2000L
        )
        val matchModified = TrackMatcher.matchTrack(backupTrack, listOf(modifiedCandidate))
        assertEquals(MatchConfidenceLevel.RELATIVE_PATH_EXACT, matchModified.confidenceLevel)
        assertTrue("Replaced track with duration mismatch must be flagged as modified", matchModified.isFileModified)
        assertNotNull(matchModified.modificationReason)
    }

    @Test
    fun `TrackMatcher performs fuzzy metadata matching when paths differ`() {
        val backupTrack = TrackBackupItem(
            id = "bk_3",
            title = "Language (Original Mix)",
            artist = "Porter Robinson",
            durationSeconds = 368,
            fileSizeMb = 12.0
        )

        val candidate = TrackEntity(
            id = "entity_lang",
            title = "Language",
            artist = "Porter Robinson",
            filePath = "/different/device/folder/Language.wav",
            durationSeconds = 368,
            fileSizeMb = 60.0
        )

        val match = TrackMatcher.matchTrack(backupTrack, listOf(candidate))
        assertTrue(
            "Fuzzy match should succeed with high confidence",
            match.confidenceLevel == MatchConfidenceLevel.METADATA_HIGH
        )
        assertEquals("entity_lang", match.matchedEntity?.id)
    }

    @Test
    fun `TrackMatcher mergeTrack preserves local identity and selectively merges analysis`() {
        val backup = TrackBackupItem(
            id = "bk_merge",
            title = "Animals",
            artist = "Martin Garrix",
            originalArtist = "Unknown",
            resolvedArtist = "Martin Garrix",
            metadataSource = "Apple Search",
            metadataConfidence = 96.0,
            bpm = 128.0,
            bpmConfidence = 0.98,
            camelotKey = "4A",
            musicalKey = "F Minor",
            hotCuesString = "0,16,32,64",
            analysisState = "COMPLETE",
            rating = 5,
            notes = "Festival banger"
        )

        // Case 1: Unmodified audio file -> applies completed analysis & beatgrids
        val unanalyzedEntity = TrackEntity(
            id = "local_unanalysed",
            title = "Animals",
            artist = "Unknown",
            filePath = "/storage/Animals.mp3",
            bpm = 0.0,
            analysisState = "NOT_ANALYSED"
        )

        val merged = TrackMatcher.mergeTrack(backup, unanalyzedEntity, isFileModified = false)
        assertEquals("Martin Garrix", merged.artist)
        assertEquals("Unknown", merged.originalArtist)
        assertEquals("Martin Garrix", merged.resolvedArtist)
        assertEquals(128.0, merged.bpm, 0.01)
        assertEquals("4A", merged.camelotKey)
        assertEquals("0,16,32,64", merged.hotCuesString)
        assertEquals("COMPLETE", merged.analysisState)
        assertEquals(5, merged.rating)
        assertEquals("Festival banger", merged.notes)

        // Case 2: Modified audio file -> metadata tags updated, but analysisState kept NOT_ANALYSED
        val modifiedEntity = TrackEntity(
            id = "local_modified",
            title = "Animals",
            artist = "Unknown",
            filePath = "/storage/Animals.mp3",
            bpm = 0.0,
            analysisState = "NOT_ANALYSED"
        )

        val mergedModified = TrackMatcher.mergeTrack(backup, modifiedEntity, isFileModified = true)
        assertEquals("Martin Garrix", mergedModified.artist)
        assertEquals("NOT_ANALYSED", mergedModified.analysisState)
        assertEquals(0.0, mergedModified.bpm, 0.01)
    }

    @Test
    fun `SoundSyncBackup validation detects valid, empty, malformed, and future versions`() {
        // Valid JSON
        val validJson = """
            {
                "backupVersion": 1,
                "appVersion": "1.0.0",
                "createdAt": 1700000000,
                "updatedAt": 1700000000,
                "songFinds": [],
                "tracks": []
            }
        """.trimIndent()

        val mockContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val backupManager = SoundSyncBackupManager(mockContext)

        val res1 = backupManager.validateBackup(validJson)
        assertTrue(res1 is ValidationResult.Valid)

        // Empty string
        val res2 = backupManager.validateBackup("")
        assertTrue(res2 is ValidationResult.Invalid)

        // Malformed JSON
        val res3 = backupManager.validateBackup("{ malformed json }")
        assertTrue(res3 is ValidationResult.Invalid)

        // Future version
        val futureJson = """
            {
                "backupVersion": 999,
                "appVersion": "99.0.0",
                "createdAt": 1700000000,
                "updatedAt": 1700000000
            }
        """.trimIndent()
        val res4 = backupManager.validateBackup(futureJson)
        assertTrue(res4 is ValidationResult.Invalid)
        assertTrue((res4 as ValidationResult.Invalid).reason.contains("newer version"))
    }
}
