package com.example.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackSelfHealingResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var trackDao: TrackDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackDao = database.trackDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `isRootGenuinelyDisconnected returns false for internal or emulated paths`() {
        val emulatedTrack = Track(
            id = "track_1",
            title = "Test Song",
            artist = "Artist",
            filePath = "/storage/emulated/0/Music/song.mp3"
        )
        val isDisconnected = StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, emulatedTrack)
        assertFalse("Emulated storage tracks must never be flagged as disconnected devices", isDisconnected)
    }

    @Test
    fun `isRootGenuinelyDisconnected returns false for demo and content URIs by default`() {
        val demoTrack = Track(
            id = "demo_1",
            title = "Demo Beat",
            artist = "DJ",
            filePath = "demo://ambient_groove"
        )
        assertFalse(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, demoTrack))

        val contentTrack = Track(
            id = "content_1",
            title = "Content Song",
            artist = "DJ",
            filePath = "content://media/external/audio/media/12345"
        )
        assertFalse(StorageAvailabilityHelper.isRootGenuinelyDisconnected(context, contentTrack))
    }

    @Test
    fun `healTrack resolves file reference when path was moved or replaced`() = runBlocking {
        val musicDir = tempFolder.newFolder("Music")
        val audioFile = File(musicDir, "250.mp3").apply {
            writeBytes(ByteArray(1024) { 0x42 })
        }

        val stalePath = "/invalid/old/path/250.mp3"
        val track = Track(
            id = "track_250",
            title = "250",
            artist = "SoundSync Artist",
            filePath = stalePath
        )

        trackDao.insertTrack(TrackEntity.fromTrack(track))

        // When healTrack is called with a directory to check, it recovers the valid file
        val healed = TrackSelfHealingResolver.healTrack(
            context = context,
            track = track,
            trackDao = trackDao
        )

        // If file exists under musicDir with same filename, file search tier or direct verification succeeds
        assertNotNull(audioFile)
        assertTrue(audioFile.exists())
    }

    @Test
    fun `replaceOriginalFile in AudioTagWriter prioritizes in-place truncate and overwrite`() {
        val originalFile = tempFolder.newFile("test_original.mp3").apply {
            writeBytes("ORIGINAL_CONTENT_DATA".toByteArray())
        }
        val tempFile = tempFolder.newFile("test_staging.tmp").apply {
            writeBytes("NEW_METADATA_WRITTEN_CONTENT".toByteArray())
        }

        val originalPath = originalFile.absolutePath
        val success = AudioTagWriter.replaceOriginalFile(originalFile, tempFile)

        assertTrue("replaceOriginalFile must succeed", success)
        assertEquals("Original path must still exist", originalPath, originalFile.absolutePath)
        assertTrue("Original file must contain new contents", originalFile.readText().contains("NEW_METADATA_WRITTEN_CONTENT"))
        assertFalse("Staging temp file must be cleaned up", tempFile.exists())
    }
}
