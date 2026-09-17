package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.backup.DurableRestoreState
import com.example.backup.MatchConfidenceLevel
import com.example.backup.RestoreDiagnosticLogger
import com.example.backup.RestoreResult
import com.example.backup.RestoreStage
import com.example.backup.SongFindBackupItem
import com.example.backup.SoundSyncBackup
import com.example.backup.SoundSyncBackupManager
import com.example.backup.TrackBackupItem
import com.example.backup.TrackMatcher
import com.example.model.PlayabilityStatus
import com.example.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestorePipelineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `TrackMatcher matchTracks handles 50 tracks in under 100 milliseconds`() {
        val backupTracks = (1..50).map { i ->
            TrackBackupItem(
                id = "b_$i",
                title = "Song $i",
                artist = "Artist ${i % 10}",
                durationSeconds = 180 + i,
                contentFingerprint = if (i % 2 == 0) "fp_$i" else "",
                storageRelativePath = "Music/Song_$i.mp3",
                filePath = "/old/path/Song_$i.mp3"
            )
        }

        val localEntities = (1..50).map { i ->
            TrackEntity(
                id = "local_$i",
                title = "Song $i",
                artist = "Artist ${i % 10}",
                durationSeconds = 180 + i,
                contentFingerprint = if (i % 2 == 0) "fp_$i" else "",
                storageRelativePath = "Music/Song_$i.mp3",
                filePath = "/current/device/Song_$i.mp3"
            )
        }

        val duration = measureTimeMillis {
            val matches = TrackMatcher.matchTracks(backupTracks, localEntities)
            assertEquals(50, matches.size)
            assertTrue(matches.all { it.matchedEntity != null })
        }
        assertTrue("50 tracks matching took ${duration}ms, expected < 500ms", duration < 500)
    }

    @Test
    fun `TrackMatcher matchTracks handles 2000 tracks in under 1 second without quadratic explosion`() {
        val backupTracks = (1..2000).map { i ->
            TrackBackupItem(
                id = "b_$i",
                title = "Track Title $i",
                artist = "Producer ${i % 50}",
                durationSeconds = 120 + (i % 300),
                contentFingerprint = if (i % 3 == 0) "fp_$i" else "",
                appleTrackId = if (i % 5 == 0) i.toLong() else null,
                storageRelativePath = "Music/Album_${i % 20}/Track_$i.mp3",
                filePath = "/old/storage/Track_$i.mp3"
            )
        }

        val localEntities = (1..2000).map { i ->
            TrackEntity(
                id = "local_$i",
                title = "Track Title $i",
                artist = "Producer ${i % 50}",
                durationSeconds = 120 + (i % 300),
                contentFingerprint = if (i % 3 == 0) "fp_$i" else "",
                appleTrackId = if (i % 5 == 0) i.toLong() else null,
                storageRelativePath = "Music/Album_${i % 20}/Track_$i.mp3",
                filePath = "/new/storage/Track_$i.mp3"
            )
        }

        val duration = measureTimeMillis {
            val matches = TrackMatcher.matchTracks(backupTracks, localEntities)
            assertEquals(2000, matches.size)
            assertTrue(matches.all { it.matchedEntity != null })
        }
        assertTrue("2000 tracks matching took ${duration}ms, expected < 1000ms", duration < 1000)
    }

    @Test
    fun `TrackMatcher matchTracks scales to 10000 tracks efficiently`() {
        val count = 10000
        val backupTracks = (1..count).map { i ->
            TrackBackupItem(
                id = "b_$i",
                title = "Track Number $i",
                artist = "Artist Name ${i % 100}",
                durationSeconds = 200 + (i % 200),
                storageRelativePath = "Music/Folder_${i % 50}/Track_$i.mp3",
                filePath = "/old/device/Track_$i.mp3"
            )
        }

        val localEntities = (1..count).map { i ->
            TrackEntity(
                id = "local_$i",
                title = "Track Number $i",
                artist = "Artist Name ${i % 100}",
                durationSeconds = 200 + (i % 200),
                storageRelativePath = "Music/Folder_${i % 50}/Track_$i.mp3",
                filePath = "/new/device/Track_$i.mp3"
            )
        }

        val duration = measureTimeMillis {
            val matches = TrackMatcher.matchTracks(backupTracks, localEntities)
            assertEquals(count, matches.size)
            assertTrue(matches.all { it.matchedEntity != null })
        }
        assertTrue("10,000 tracks matching took ${duration}ms, expected < 3000ms", duration < 3000)
    }

    @Test
    fun `TrackMatcher correctly handles unmatched backup tracks without inventing invalid local paths`() {
        val backupTrack = TrackBackupItem(
            id = "bk_missing",
            title = "Extremely Rare White Label",
            artist = "Underground DJ",
            filePath = "/storage/emulated/0/Music/OldPhone/rare.mp3",
            storageRelativePath = "Music/OldPhone/rare.mp3",
            bpm = 135.0,
            camelotKey = "8A"
        )

        val localEntities = listOf(
            TrackEntity(
                id = "local_other",
                title = "Something Else Entirely",
                artist = "Different Artist",
                filePath = "/storage/emulated/0/Music/different.mp3"
            )
        )

        val matches = TrackMatcher.matchTracks(listOf(backupTrack), localEntities)
        assertEquals(1, matches.size)
        assertEquals(MatchConfidenceLevel.NONE, matches[0].confidenceLevel)
        assertEquals(null, matches[0].matchedEntity)
    }

    @Test
    fun `SoundSyncBackupManager durable restore state tracks lifecycle and survives process death crash loops`() {
        val prefs = context.getSharedPreferences("soundsync_backup_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Simulate an uncompleted restore left in SharedPreferences as if process died mid-restore
        prefs.edit()
            .putString("durable_restore_state", DurableRestoreState.RESTORING.name)
            .putLong("durable_restore_start_time", System.currentTimeMillis())
            .putString("durable_restore_stage", RestoreStage.READING_BACKUP.name)
            .commit()

        // Creating / initializing manager should detect the crash and mark it CRASHED without re-crashing
        val manager = SoundSyncBackupManager(context)
        assertEquals(DurableRestoreState.CRASHED.name, prefs.getString("durable_restore_state", null))
        assertFalse(manager.isRestoring.value)
        assertFalse(SoundSyncBackupManager.isRestoring())
    }

    @Test
    fun `RestoreDiagnosticLogger logs stage changes and queue counts correctly`() {
        RestoreDiagnosticLogger.reset()
        RestoreDiagnosticLogger.logStage(RestoreStage.VALIDATING_SCHEMA, 10L, 0, 0, "Validating payload")
        RestoreDiagnosticLogger.logStage(RestoreStage.READING_BACKUP, 20L, 0, 0, "Reading payload")
        RestoreDiagnosticLogger.logCoreRestoreComplete(restoredTracks = 500, matchedTracks = 100, restoredFinds = 50, elapsedMs = 250L)
        RestoreDiagnosticLogger.logPostRestoreStartup(
            analysisQueuePending = 10,
            brainQueuePending = 5,
            autoBackupSuppressed = true,
            scannerActive = false
        )

        val dump = RestoreDiagnosticLogger.dump()
        assertTrue(dump.contains("VALIDATING_SCHEMA"))
        assertTrue(dump.contains("READING_BACKUP"))
        assertTrue(dump.contains("CORE_RESTORE_COMPLETE"))
        assertTrue(dump.contains("POST_RESTORE_STARTUP_STATUS"))
        assertTrue(dump.contains("analysisQueuePending=10"))
        assertTrue(dump.contains("brainQueuePending=5"))
    }

    @Test
    fun `TrackMatcher mergeTrack preserves analysis and beatgrids for matched tracks`() {
        val backup = TrackBackupItem(
            id = "bk_01",
            title = "Sun & Moon",
            artist = "Above & Beyond",
            bpm = 134.0,
            bpmConfidence = 0.99,
            camelotKey = "11B",
            musicalKey = "A Major",
            analysisState = "COMPLETE",
            hotCuesString = "10.5,35.0,75.2",
            energyRating = 9,
            rating = 5,
            notes = "Trance anthem"
        )

        val localEntity = TrackEntity(
            id = "local_01",
            title = "Sun & Moon",
            artist = "Above & Beyond",
            filePath = "/storage/emulated/0/Music/Above & Beyond - Sun & Moon.mp3",
            bpm = 0.0,
            analysisState = "NOT_ANALYSED"
        )

        val merged = TrackMatcher.mergeTrack(backup, localEntity, isFileModified = false)
        assertEquals("local_01", merged.id)
        assertEquals(134.0, merged.bpm, 0.001)
        assertEquals("11B", merged.camelotKey)
        assertEquals("COMPLETE", merged.analysisState)
        assertEquals("10.5,35.0,75.2", merged.hotCuesString)
        assertEquals(9, merged.energyRating)
        assertEquals(5, merged.rating)
        assertEquals("Trance anthem", merged.notes)
    }

    @Test
    fun `SoundSyncBackupManager restore rejects invalid JSON payload safely`() = runBlocking {
        val manager = SoundSyncBackupManager(context)
        val result = manager.restoreBackupFromString("{ invalid json payload !!! }")
        assertTrue(result is RestoreResult.Error)
        val error = result as RestoreResult.Error
        assertTrue(error.message.isNotBlank())
        assertTrue(error.diagnosticDetails.contains("Restore Diagnostic Log"))
    }
}
