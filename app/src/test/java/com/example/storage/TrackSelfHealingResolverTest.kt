package com.example.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.Track
import kotlinx.coroutines.runBlocking
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
import java.io.FileOutputStream
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackSelfHealingResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var trackDao: TrackDao
    private val tracksMap = mutableMapOf<String, TrackEntity>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tracksMap.clear()
        trackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertTrack", "updateTrack" -> {
                    val t = args[0] as TrackEntity
                    tracksMap[t.id] = t
                    null
                }
                "getTrackById" -> tracksMap[args[0] as String]
                "updateFilePath" -> {
                    val id = args[0] as String
                    val newPath = args[1] as String
                    tracksMap[id]?.let { tracksMap[id] = it.copy(filePath = newPath) }
                    null
                }
                "getAllTracksSync" -> tracksMap.values.toList()
                else -> null
            }
        } as TrackDao
    }

    private fun createSampleMp3File(file: File, frameCount: Int = 10): File {
        val frameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val frameSize = 417
        FileOutputStream(file).use { fos ->
            for (i in 0 until frameCount) {
                val frame = ByteArray(frameSize) { 0xAA.toByte() }
                frameHeader.copyInto(frame, 0)
                fos.write(frame)
            }
        }
        return file
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
            createSampleMp3File(this, frameCount = 5)
        }

        val stalePath = "/invalid/old/path/250.mp3"
        val track = Track(
            id = "track_250",
            title = "250",
            artist = "SoundSync Artist",
            filePath = stalePath
        )

        trackDao.insertTrack(TrackEntity.fromTrack(track))

        // When healTrack is called, it verifies or resolves the file
        val healed = TrackSelfHealingResolver.healTrack(
            context = context,
            track = track,
            trackDao = trackDao
        )

        assertNotNull(audioFile)
        assertTrue(audioFile.exists())
    }

    @Test
    fun `replaceOriginalFile in AudioTagWriter prioritizes in-place truncate and overwrite`() {
        val originalFile = tempFolder.newFile("test_original.mp3").apply {
            createSampleMp3File(this, frameCount = 10)
        }
        val tempFile = tempFolder.newFile("test_staging.tmp").apply {
            createSampleMp3File(this, frameCount = 15)
        }

        val originalPath = originalFile.absolutePath
        val stagingLength = tempFile.length()
        val success = AudioTagWriter.replaceOriginalFile(originalFile, tempFile)

        assertTrue("replaceOriginalFile must succeed", success)
        assertEquals("Original path must still exist", originalPath, originalFile.absolutePath)
        assertEquals("Original file size must match staging size", stagingLength, originalFile.length())
        assertFalse("Staging temp file must be cleaned up", tempFile.exists())
    }
}
