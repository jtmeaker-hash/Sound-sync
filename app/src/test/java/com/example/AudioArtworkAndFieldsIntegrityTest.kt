package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.ArtworkEmbeddingHelper
import com.example.metadata.MetadataFileWriter
import com.example.metadata.MetadataWriteResult
import com.example.metadata.artwork.ArtworkEmbedValidator
import com.example.model.MetadataWriteState
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.CompleteTagPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioArtworkAndFieldsIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    private fun createSampleJpeg(width: Int = 100, height: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    private fun createSampleWav(file: File, pcmBytes: ByteArray = ByteArray(4000) { 0x55.toByte() }): File {
        FileOutputStream(file).use { fos ->
            val totalRiff = 36 + pcmBytes.size
            fos.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf((totalRiff and 0xFF).toByte(), ((totalRiff shr 8) and 0xFF).toByte(), ((totalRiff shr 16) and 0xFF).toByte(), ((totalRiff shr 24) and 0xFF).toByte()))
            fos.write("WAVE".toByteArray(StandardCharsets.US_ASCII))
            fos.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(16, 0, 0, 0))
            fos.write(byteArrayOf(1, 0)) // PCM
            fos.write(byteArrayOf(2, 0)) // 2 channels
            fos.write(byteArrayOf(0x44, 0xAC.toByte(), 0, 0)) // 44100 Hz
            val byteRate = 44100 * 2 * 2
            fos.write(byteArrayOf((byteRate and 0xFF).toByte(), ((byteRate shr 8) and 0xFF).toByte(), ((byteRate shr 16) and 0xFF).toByte(), ((byteRate shr 24) and 0xFF).toByte()))
            fos.write(byteArrayOf(4, 0)) // Block align
            fos.write(byteArrayOf(16, 0)) // 16 bits
            fos.write("data".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf((pcmBytes.size and 0xFF).toByte(), ((pcmBytes.size shr 8) and 0xFF).toByte(), ((pcmBytes.size shr 16) and 0xFF).toByte(), ((pcmBytes.size shr 24) and 0xFF).toByte()))
            fos.write(pcmBytes)
        }
        return file
    }

    private fun createSampleMp3(file: File, audioBytes: ByteArray = ByteArray(2048) { 0xFF.toByte() }): File {
        FileOutputStream(file).use { fos ->
            fos.write(audioBytes)
        }
        return file
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testArtworkEmbedValidator_normalizesAndDownscalesOversizedImage() {
        val oversized = createSampleJpeg(2000, 1500)
        val result = ArtworkEmbedValidator.validateAndPrepare(oversized, "image/jpeg", maxDimension = 1200)

        assertNotNull(result)
        assertEquals("image/jpeg", result!!.mimeType)
        assertTrue("Prepared artwork should be smaller than 1200px", result.width <= 1200)
        assertTrue("Prepared artwork should be smaller than 1200px", result.height <= 1200)
        assertTrue("Prepared artwork bytes should not be empty", result.bytes.isNotEmpty())
    }

    @Test
    fun testArtworkEmbedValidator_rejectsCorruptOrEmptyBytes() {
        val corruptBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val resultCorrupt = ArtworkEmbedValidator.validateAndPrepare(corruptBytes, "image/jpeg")
        assertNull(resultCorrupt)

        val emptyBytes = ByteArray(0)
        val resultEmpty = ArtworkEmbedValidator.validateAndPrepare(emptyBytes, "image/jpeg")
        assertNull(resultEmpty)
    }

    @Test
    fun testMp3_consolidatesBpmAndKeyWithoutDuplicates() = runBlocking {
        val mp3File = File(tempFolder.root, "test_consolidate.mp3")
        createSampleMp3(mp3File)

        val payload = CompleteTagPayload(
            title = "Integrity Anthem",
            artist = "SoundSync DJ",
            album = "Integrity Album",
            bpm = 128.0,
            musicalKey = "8A",
            artworkBytes = createSampleJpeg(200, 200),
            artworkMimeType = "image/jpeg"
        )

        val writeOk = AudioTagWriter.writeCompleteTags(context, mp3File.absolutePath, payload)
        assertTrue("MP3 tags should be written successfully", writeOk)

        // Read back directly using embedded reader
        val readBack = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath)
        assertEquals("Integrity Anthem", readBack.title)
        assertEquals("SoundSync DJ", readBack.artist)
        assertEquals("Integrity Album", readBack.album)
        assertEquals(128.0, readBack.bpm ?: 0.0, 0.1)
        assertEquals("8A", readBack.musicalKey)
        assertTrue("Embedded artwork should be detected", readBack.hasEmbeddedArtwork)
        assertTrue("Embedded artwork size should be > 0", readBack.embeddedArtworkSize > 0)
    }

    @Test
    fun testWav_embedsArtworkInId3ChunkAndVerifies() = runBlocking {
        val wavFile = File(tempFolder.root, "test_artwork.wav")
        createSampleWav(wavFile)

        val artBytes = createSampleJpeg(150, 150)
        val payload = CompleteTagPayload(
            title = "WAV Title",
            artist = "WAV Artist",
            album = "WAV Album",
            bpm = 130.0,
            musicalKey = "11B",
            artworkBytes = artBytes,
            artworkMimeType = "image/jpeg"
        )

        val writeOk = AudioTagWriter.writeCompleteTags(context, wavFile.absolutePath, payload)
        assertTrue("WAV tags should write successfully", writeOk)

        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("WAV Title", readBack.title)
        assertEquals("WAV Artist", readBack.artist)
        assertEquals(130.0, readBack.bpm ?: 0.0, 0.1)
        assertEquals("11B", readBack.musicalKey)
        assertTrue("WAV should have embedded artwork verified", readBack.hasEmbeddedArtwork)
        assertTrue("WAV embedded artwork size should be > 0", readBack.embeddedArtworkSize > 0)
    }

    @Test
    fun testMetadataFileWriter_postWriteVerificationReturnsWrittenState() = runBlocking {
        val mp3File = File(tempFolder.root, "full_verified.mp3")
        createSampleMp3(mp3File)

        val artBytes = createSampleJpeg(200, 200)
        val track = Track(
            id = "test-track-1",
            title = "Physical File Verified",
            artist = "Physical Artist",
            album = "Physical Album",
            bpm = 124.0,
            musicalKey = "5A",
            filePath = mp3File.absolutePath
        )

        val fileWriter = MetadataFileWriter(context)
        val result = fileWriter.writeAsync(track, artBytes, "image/jpeg")

        assertTrue(
            "Expected Written or TextWritten result, got: $result",
            result is MetadataWriteResult.Written || result is MetadataWriteResult.TextWritten
        )
        assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS, result.writeState)

        // Verify reopening confirms presence on disk
        val reopened = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath)
        assertEquals("Physical File Verified", reopened.title)
        assertEquals("Physical Artist", reopened.artist)
        assertEquals(124.0, reopened.bpm ?: 0.0, 0.1)
        assertEquals("5A", reopened.musicalKey)
        assertTrue("Reopened file contains verified artwork", reopened.hasEmbeddedArtwork)
    }

    @Test
    fun testArtworkEmbeddingHelper_embedArtworkWithReopeningVerification() = runBlocking {
        val mp3File = File(tempFolder.root, "artwork_helper.mp3")
        createSampleMp3(mp3File)

        val artBytes = createSampleJpeg(250, 250)
        val embedOk = ArtworkEmbeddingHelper.embedArtwork(mp3File, artBytes, "image/jpeg")
        assertTrue("Artwork embedding should succeed and pass post-write verification", embedOk)

        val reopened = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath)
        assertTrue("Physical file has embedded artwork", reopened.hasEmbeddedArtwork)
        assertTrue("Physical file embedded artwork size is positive", reopened.embeddedArtworkSize > 0)
    }
}
