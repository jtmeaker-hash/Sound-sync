package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression & Contract Tests for SoundSync File Deletion Guard,
 * Transaction-Safe Replacement, and Forensic Logging.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileDeletionGuardAndSafetyTest {

    private lateinit var context: Context
    private lateinit var testFolder: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testFolder = File(context.cacheDir, "test_safety_${System.currentTimeMillis()}").apply { mkdirs() }
        ForensicFileLogger.clear()
    }

    @After
    fun tearDown() {
        testFolder.deleteRecursively()
        ForensicFileLogger.clear()
    }

    @Test
    fun testAutomatedDeletionIsBlockedAndLogged() = runBlocking {
        val audioFile = File(testFolder, "my_track.mp3").apply {
            writeBytes(ByteArray(1024) { 0x41.toByte() })
        }
        assertTrue("Audio file must exist before test", audioFile.exists())

        val result = FileDeletionGuard.deleteAudioFile(
            file = audioFile,
            reason = DeletionReason.UNAUTHORIZED_OR_AUTOMATED,
            caller = "BackgroundAudioScanner"
        )

        assertTrue("Result must be Blocked", result is FileDeletionResult.Blocked)
        assertTrue("CRITICAL: Audio file must NEVER be deleted by automated caller!", audioFile.exists())
        assertEquals("File size must remain untouched", 1024L, audioFile.length())

        val recentLogs = ForensicFileLogger.getRecentLogs()
        assertTrue("Forensic logs must contain the blocked event", recentLogs.any {
            it.caller == "BackgroundAudioScanner" && it.outcome == "BLOCKED_UNAUTHORIZED"
        })
    }

    @Test
    fun testTemporaryStagingCleanupBlocksRealAudioFiles() {
        val audioFile = File(testFolder, "saved_track.flac").apply {
            writeBytes(ByteArray(2048) { 0x42.toByte() })
        }
        assertTrue(audioFile.exists())

        // Caller attempts to masquerade an audio file as temporary
        val deleted = FileDeletionGuard.deleteTempFile(audioFile, caller = "SuspectCleanupWorker")

        assertFalse("deleteTempFile must refuse to delete audio files", deleted)
        assertTrue("Real audio file must remain untouched", audioFile.exists())
        assertEquals(2048L, audioFile.length())

        val logs = ForensicFileLogger.getRecentLogs()
        assertTrue("Violation must be recorded in forensic logs", logs.any {
            it.outcome == "BLOCKED_NOT_A_TEMP_FILE"
        })
    }

    @Test
    fun testTemporaryStagingCleanupAllowsValidTmpFiles() {
        val tmpFile = File(testFolder, "staging_123.tmp").apply {
            writeBytes(ByteArray(512) { 0x00.toByte() })
        }
        assertTrue(tmpFile.exists())

        val deleted = FileDeletionGuard.deleteTempFile(tmpFile, caller = "AudioTagWriter")
        assertTrue("Valid temp file should be deleted", deleted)
        assertFalse("Temp file should no longer exist", tmpFile.exists())

        val logs = ForensicFileLogger.getRecentLogs()
        assertTrue("Cleanup should be recorded in forensic logs", logs.any {
            it.path == tmpFile.absolutePath && it.outcome == "SUCCESS"
        })
    }

    @Test
    fun testExplicitUserActionDeletesAudioFile() = runBlocking {
        val userAudioFile = File(testFolder, "user_track.mp3").apply {
            writeBytes(ByteArray(4096) { 0x55.toByte() })
        }
        assertTrue(userAudioFile.exists())

        val result = FileDeletionGuard.deleteAudioFile(
            file = userAudioFile,
            reason = DeletionReason.EXPLICIT_USER_ACTION,
            caller = "BulkTrackEditorDialog"
        )

        assertTrue("Deletion with explicit user action must succeed", result is FileDeletionResult.Success)
        assertFalse("Audio file should be physically removed after explicit confirmation", userAudioFile.exists())
    }

    @Test
    fun testProtectedDemoTrackCannotBeDeleted() = runBlocking {
        val demoDir = File(testFolder, "Music/Tech House").apply { mkdirs() }
        val demoFile = File(demoDir, "Demo_Club_Mix.mp3").apply {
            writeBytes(ByteArray(1024))
        }

        val result = FileDeletionGuard.deleteAudioFile(
            file = demoFile,
            reason = DeletionReason.EXPLICIT_USER_ACTION,
            caller = "TestCaller"
        )

        assertTrue("Protected demo track deletion must be blocked", result is FileDeletionResult.Blocked)
        assertTrue("Demo file must remain intact", demoFile.exists())
    }

    @Test
    fun testReplaceOriginalFileNeverDeletesOriginalOnEmptyTempFile() {
        val original = File(testFolder, "song.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
        val emptyTemp = File(testFolder, "song.mp3.tmp").apply {
            createNewFile()
        }
        assertEquals(0L, emptyTemp.length())

        val success = AudioTagWriter.replaceOriginalFile(original, emptyTemp)
        assertFalse("replaceOriginalFile must return false for empty temp file", success)
        assertTrue("Original file must NEVER be deleted!", original.exists())
        assertEquals(8L, original.length())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), original.readBytes())
    }

    @Test
    fun testReplaceOriginalFileNeverDeletesOriginalOnNonexistentTempFile() {
        val original = File(testFolder, "song2.mp3").apply {
            writeBytes(byteArrayOf(10, 20, 30))
        }
        val missingTemp = File(testFolder, "song2_missing.tmp")

        val success = AudioTagWriter.replaceOriginalFile(original, missingTemp)
        assertFalse(success)
        assertTrue("Original file must remain intact", original.exists())
        assertEquals(3L, original.length())
    }

    @Test
    fun testReplaceOriginalFileSucceedsWithValidTempFile() {
        val original = File(testFolder, "song3.mp3").apply {
            writeBytes(byteArrayOf(1, 1, 1, 1))
        }
        val validTemp = File(testFolder, ".song3.mp3.staging.tmp").apply {
            writeBytes(byteArrayOf(2, 2, 2, 2, 2, 2))
        }

        val success = AudioTagWriter.replaceOriginalFile(original, validTemp)
        assertTrue("Replacement must succeed with valid staging file", success)
        assertTrue("Original file must exist", original.exists())
        assertEquals("File size must match updated temp file", 6L, original.length())
        assertArrayEquals(byteArrayOf(2, 2, 2, 2, 2, 2), original.readBytes())
    }

    @Test
    fun testFileLockingPreventsConcurrentMutations() = runBlocking {
        val path = File(testFolder, "concurrent_track.mp3").apply { writeBytes(ByteArray(100)) }.absolutePath

        val job1 = async(Dispatchers.IO) {
            FileLockManager.withFileLock(path) {
                assertTrue("File should report locked while in lock block", FileLockManager.isFileLocked(path))
                delay(100)
                "done1"
            }
        }

        val job2 = async(Dispatchers.IO) {
            delay(20) // Ensure job1 acquired lock
            assertTrue("File must be locked by job1", FileLockManager.isFileLocked(path))
            FileLockManager.withFileLock(path) {
                "done2"
            }
        }

        val r1 = job1.await()
        val r2 = job2.await()
        assertEquals("done1", r1)
        assertEquals("done2", r2)
        assertFalse("Lock must be released after completion", FileLockManager.isFileLocked(path))
    }
}
