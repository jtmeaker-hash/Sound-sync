package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.storage.LocalFileSystemScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WavMetadataDurationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createWavFile(
        file: File,
        sampleRate: Int = 44100,
        channels: Short = 2,
        bitsPerSample: Short = 16,
        durationSeconds: Int = 215, // 3 minutes 35 seconds
        title: String? = null,
        artist: String? = null
    ) {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val dataSize = byteRate * durationSeconds

        // Optional LIST INFO chunk
        val listInfoBytes = if (title != null || artist != null) {
            val listBody = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
            listBody.put("INFO".toByteArray(StandardCharsets.US_ASCII))
            if (title != null) {
                val tBytes = (title + "\u0000").toByteArray(StandardCharsets.US_ASCII)
                listBody.put("INAM".toByteArray(StandardCharsets.US_ASCII))
                listBody.putInt(tBytes.size)
                listBody.put(tBytes)
                if (tBytes.size % 2 != 0) listBody.put(0.toByte())
            }
            if (artist != null) {
                val aBytes = (artist + "\u0000").toByteArray(StandardCharsets.US_ASCII)
                listBody.put("IART".toByteArray(StandardCharsets.US_ASCII))
                listBody.putInt(aBytes.size)
                listBody.put(aBytes)
                if (aBytes.size % 2 != 0) listBody.put(0.toByte())
            }
            val bodySize = listBody.position()
            val listChunk = ByteBuffer.allocate(8 + bodySize).order(ByteOrder.LITTLE_ENDIAN)
            listChunk.put("LIST".toByteArray(StandardCharsets.US_ASCII))
            listChunk.putInt(bodySize)
            listChunk.put(listBody.array(), 0, bodySize)
            listChunk.array()
        } else {
            ByteArray(0)
        }

        val totalRiffSize = 36 + listInfoBytes.size + dataSize
        val headerBuf = ByteBuffer.allocate(44 + listInfoBytes.size).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        headerBuf.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        headerBuf.putInt(totalRiffSize)
        headerBuf.put("WAVE".toByteArray(StandardCharsets.US_ASCII))

        // fmt chunk
        headerBuf.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        headerBuf.putInt(16)
        headerBuf.putShort(1) // PCM
        headerBuf.putShort(channels)
        headerBuf.putInt(sampleRate)
        headerBuf.putInt(byteRate)
        headerBuf.putShort(blockAlign)
        headerBuf.putShort(bitsPerSample)

        // LIST chunk if present
        if (listInfoBytes.isNotEmpty()) {
            headerBuf.put(listInfoBytes)
        }

        // data chunk
        headerBuf.put("data".toByteArray(StandardCharsets.US_ASCII))
        headerBuf.putInt(dataSize)

        FileOutputStream(file).use { fos ->
            fos.write(headerBuf.array(), 0, headerBuf.position())
            // write dummy audio data (4096 bytes)
            fos.write(ByteArray(4096))
        }
    }

    @Test
    fun testWavDurationExtractionMatchesSongLength() {
        val wavFile = tempFolder.newFile("song_215s.wav")
        createWavFile(wavFile, durationSeconds = 215, title = "Full Track", artist = "Producer")

        val metadata = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)

        // Symptom check: must NOT be 1 second!
        assertNotEquals(1, metadata.durationSeconds)
        assertEquals(215, metadata.durationSeconds)
        assertEquals(1411, metadata.bitrateKbps)
        assertEquals(44100, metadata.sampleRate)
        assertEquals(16, metadata.bitDepth)
        assertEquals("Full Track", metadata.title)
        assertEquals("Producer", metadata.artist)
    }

    @Test
    fun testHighResolutionWavDurationAndBitrate() {
        val wavFile = tempFolder.newFile("hires_96k_24b.wav")
        // 96000Hz, 24-bit stereo = 576,000 bytes/sec = 4608 kbps
        createWavFile(wavFile, sampleRate = 96000, channels = 2, bitsPerSample = 24, durationSeconds = 180)

        val metadata = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)

        assertEquals(180, metadata.durationSeconds)
        assertEquals(4608, metadata.bitrateKbps)
        assertEquals(96000, metadata.sampleRate)
        assertEquals(24, metadata.bitDepth)
    }

    @Test
    fun testWavFailureDoesNotSilentlyBecomeOneSecond() {
        val invalidFile = tempFolder.newFile("corrupt.wav")
        // Write invalid header
        FileOutputStream(invalidFile).use { it.write("NOT_A_WAV_FILE".toByteArray()) }

        val metadata = AudioEmbeddedMetadataReader.read(context, invalidFile.absolutePath)

        // Metadata extraction failure MUST NOT silently become a fake duration of 1000 milliseconds (1 second)
        assertEquals(0, metadata.durationSeconds)
    }

    @Test
    fun testLocalFileSystemScannerExtractsFullWavDuration() {
        val usbFolder = tempFolder.newFolder("usb_drive")
        val trackFile = File(usbFolder, "dj_set_track.wav")
        createWavFile(trackFile, durationSeconds = 340, title = "Club Anthem", artist = "DJ SoundSync")

        val track = LocalFileSystemScanner.extractTrackFromFile(context, trackFile, "usb_test")
        org.junit.Assert.assertNotNull(track)
        assertEquals("Club Anthem", track!!.title)
        assertEquals("DJ SoundSync", track.artist)
        assertNotEquals("Duration must not be 1 second", 1, track.durationSeconds)
        assertEquals(340, track.durationSeconds)
        assertEquals(1411, track.bitrateKbps)
    }
}
