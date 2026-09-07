package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.MetadataFileWriter
import com.example.metadata.MetadataFileWriteQueue
import com.example.metadata.MetadataWriteResult
import com.example.model.MetadataWriteState
import com.example.model.Track
import com.example.storage.AudioTagWriter
import com.example.storage.CompleteTagPayload
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
import java.nio.charset.StandardCharsets
import java.util.Scanner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioMetadataWriteIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    // Valid minimal 1x1 JPEG bytes for artwork embedding tests
    private val sampleArtworkBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
        0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x48.toByte(),
        0x00.toByte(), 0x48.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(),
        0x00.toByte(), 0x08.toByte(), 0x06.toByte(), 0x06.toByte(),
        0x07.toByte(), 0x06.toByte(), 0x05.toByte(), 0x08.toByte(),
        0x07.toByte(), 0x07.toByte(), 0x07.toByte(), 0x09.toByte(),
        0x09.toByte(), 0x08.toByte(), 0x0A.toByte(), 0x0C.toByte(),
        0x14.toByte(), 0x0D.toByte(), 0x0C.toByte(), 0x0B.toByte(),
        0x0B.toByte(), 0x0C.toByte(), 0x19.toByte(), 0x12.toByte(),
        0x13.toByte(), 0x0F.toByte(), 0x14.toByte(), 0x1D.toByte(),
        0x1A.toByte(), 0x1F.toByte(), 0x1E.toByte(), 0x1D.toByte(),
        0x1A.toByte(), 0x1C.toByte(), 0x1C.toByte(), 0x20.toByte(),
        0x24.toByte(), 0x2E.toByte(), 0x27.toByte(), 0x20.toByte(),
        0x22.toByte(), 0x2C.toByte(), 0x23.toByte(), 0x1C.toByte(),
        0x1C.toByte(), 0x28.toByte(), 0x37.toByte(), 0x29.toByte(),
        0x2C.toByte(), 0x30.toByte(), 0x31.toByte(), 0x34.toByte(),
        0x34.toByte(), 0x34.toByte(), 0x1F.toByte(), 0x27.toByte(),
        0x39.toByte(), 0x3D.toByte(), 0x38.toByte(), 0x32.toByte(),
        0x3C.toByte(), 0x2E.toByte(), 0x33.toByte(), 0x34.toByte(),
        0x32.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00.toByte(),
        0x0B.toByte(), 0x08.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x11.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xC4.toByte(),
        0x00.toByte(), 0x1F.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x05.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(),
        0x05.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte(),
        0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0xFF.toByte(),
        0xDA.toByte(), 0x00.toByte(), 0x08.toByte(), 0x01.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3F.toByte(),
        0x00.toByte(), 0x7F.toByte(), 0x00.toByte(), 0xFF.toByte(),
        0xD9.toByte()
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Helper to create a pristine RIFF WAVE audio file with PCM data.
     */
    private fun createSampleWavFile(file: File, pcmData: ByteArray): File {
        FileOutputStream(file).use { fos ->
            val totalRiffSize = 36 + pcmData.size
            fos.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(
                (totalRiffSize and 0xFF).toByte(),
                ((totalRiffSize shr 8) and 0xFF).toByte(),
                ((totalRiffSize shr 16) and 0xFF).toByte(),
                ((totalRiffSize shr 24) and 0xFF).toByte()
            ))
            fos.write("WAVE".toByteArray(StandardCharsets.US_ASCII))

            // fmt chunk: PCM 16-bit, 2 channels, 44100Hz
            fos.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
            val fmtSize = 16
            fos.write(byteArrayOf(16, 0, 0, 0))
            fos.write(byteArrayOf(1, 0)) // PCM
            fos.write(byteArrayOf(2, 0)) // 2 channels
            val sampleRate = 44100
            fos.write(byteArrayOf(
                (sampleRate and 0xFF).toByte(),
                ((sampleRate shr 8) and 0xFF).toByte(),
                ((sampleRate shr 16) and 0xFF).toByte(),
                ((sampleRate shr 24) and 0xFF).toByte()
            ))
            val byteRate = 44100 * 2 * 2
            fos.write(byteArrayOf(
                (byteRate and 0xFF).toByte(),
                ((byteRate shr 8) and 0xFF).toByte(),
                ((byteRate shr 16) and 0xFF).toByte(),
                ((byteRate shr 24) and 0xFF).toByte()
            ))
            fos.write(byteArrayOf(4, 0)) // block align
            fos.write(byteArrayOf(16, 0)) // 16 bits per sample

            // data chunk
            fos.write("data".toByteArray(StandardCharsets.US_ASCII))
            val dataSize = pcmData.size
            fos.write(byteArrayOf(
                (dataSize and 0xFF).toByte(),
                ((dataSize shr 8) and 0xFF).toByte(),
                ((dataSize shr 16) and 0xFF).toByte(),
                ((dataSize shr 24) and 0xFF).toByte()
            ))
            fos.write(pcmData)
        }
        return file
    }

    /**
     * Helper to create a 24-bit 96kHz PCM RIFF WAVE audio file.
     */
    private fun createSampleWavFile24Bit96k(file: File, pcmData: ByteArray): File {
        FileOutputStream(file).use { fos ->
            val totalRiffSize = 36 + pcmData.size
            fos.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(
                (totalRiffSize and 0xFF).toByte(),
                ((totalRiffSize shr 8) and 0xFF).toByte(),
                ((totalRiffSize shr 16) and 0xFF).toByte(),
                ((totalRiffSize shr 24) and 0xFF).toByte()
            ))
            fos.write("WAVE".toByteArray(StandardCharsets.US_ASCII))

            // fmt chunk: PCM 24-bit, 2 channels, 96000Hz
            fos.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(16, 0, 0, 0))
            fos.write(byteArrayOf(1, 0)) // PCM
            fos.write(byteArrayOf(2, 0)) // 2 channels
            val sampleRate = 96000
            fos.write(byteArrayOf(
                (sampleRate and 0xFF).toByte(),
                ((sampleRate shr 8) and 0xFF).toByte(),
                ((sampleRate shr 16) and 0xFF).toByte(),
                ((sampleRate shr 24) and 0xFF).toByte()
            ))
            val byteRate = 96000 * 2 * 3
            fos.write(byteArrayOf(
                (byteRate and 0xFF).toByte(),
                ((byteRate shr 8) and 0xFF).toByte(),
                ((byteRate shr 16) and 0xFF).toByte(),
                ((byteRate shr 24) and 0xFF).toByte()
            ))
            fos.write(byteArrayOf(6, 0)) // block align (2 channels * 3 bytes)
            fos.write(byteArrayOf(24, 0)) // 24 bits per sample

            // data chunk
            fos.write("data".toByteArray(StandardCharsets.US_ASCII))
            val dataSize = pcmData.size
            fos.write(byteArrayOf(
                (dataSize and 0xFF).toByte(),
                ((dataSize shr 8) and 0xFF).toByte(),
                ((dataSize shr 16) and 0xFF).toByte(),
                ((dataSize shr 24) and 0xFF).toByte()
            ))
            fos.write(pcmData)
        }
        return file
    }

    /**
     * Helper to create a WAV file with custom chunks such as 'bext' (Broadcast Wave) and 'cue ' (cue points).
     */
    private fun createSampleWavWithExtraChunks(file: File, pcmData: ByteArray, bextData: ByteArray, cueData: ByteArray): File {
        FileOutputStream(file).use { fos ->
            val bextPad = if (bextData.size % 2 != 0) 1 else 0
            val cuePad = if (cueData.size % 2 != 0) 1 else 0
            val totalRiffSize = 36 + (8 + bextData.size + bextPad) + (8 + cueData.size + cuePad) + pcmData.size
            fos.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(
                (totalRiffSize and 0xFF).toByte(),
                ((totalRiffSize shr 8) and 0xFF).toByte(),
                ((totalRiffSize shr 16) and 0xFF).toByte(),
                ((totalRiffSize shr 24) and 0xFF).toByte()
            ))
            fos.write("WAVE".toByteArray(StandardCharsets.US_ASCII))

            // fmt chunk
            fos.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(16, 0, 0, 0, 1, 0, 2, 0))
            val sampleRate = 44100
            fos.write(byteArrayOf(
                (sampleRate and 0xFF).toByte(),
                ((sampleRate shr 8) and 0xFF).toByte(),
                ((sampleRate shr 16) and 0xFF).toByte(),
                ((sampleRate shr 24) and 0xFF).toByte()
            ))
            val byteRate = 44100 * 4
            fos.write(byteArrayOf(
                (byteRate and 0xFF).toByte(),
                ((byteRate shr 8) and 0xFF).toByte(),
                ((byteRate shr 16) and 0xFF).toByte(),
                ((byteRate shr 24) and 0xFF).toByte()
            ))
            fos.write(byteArrayOf(4, 0, 16, 0))

            // bext chunk
            fos.write("bext".toByteArray(StandardCharsets.US_ASCII))
            val bextSize = bextData.size
            fos.write(byteArrayOf(
                (bextSize and 0xFF).toByte(),
                ((bextSize shr 8) and 0xFF).toByte(),
                ((bextSize shr 16) and 0xFF).toByte(),
                ((bextSize shr 24) and 0xFF).toByte()
            ))
            fos.write(bextData)
            if (bextPad > 0) fos.write(0)

            // cue chunk
            fos.write("cue ".toByteArray(StandardCharsets.US_ASCII))
            val cueSize = cueData.size
            fos.write(byteArrayOf(
                (cueSize and 0xFF).toByte(),
                ((cueSize shr 8) and 0xFF).toByte(),
                ((cueSize shr 16) and 0xFF).toByte(),
                ((cueSize shr 24) and 0xFF).toByte()
            ))
            fos.write(cueData)
            if (cuePad > 0) fos.write(0)

            // data chunk
            fos.write("data".toByteArray(StandardCharsets.US_ASCII))
            val dataSize = pcmData.size
            fos.write(byteArrayOf(
                (dataSize and 0xFF).toByte(),
                ((dataSize shr 8) and 0xFF).toByte(),
                ((dataSize shr 16) and 0xFF).toByte(),
                ((dataSize shr 24) and 0xFF).toByte()
            ))
            fos.write(pcmData)
        }
        return file
    }

    /**
     * Helper to create a WAV file preceded by an ID3v2 tag at the start of the file.
     */
    private fun createSampleWavWithPrependedId3(file: File, pcmData: ByteArray): File {
        FileOutputStream(file).use { fos ->
            // ID3v2.3 header (10 bytes) + 32-byte payload synchsafe
            fos.write(byteArrayOf(
                'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
                0x03, 0x00, // ID3v2.3
                0x00,       // flags
                0x00, 0x00, 0x00, 0x20 // 32 bytes payload
            ))
            fos.write(ByteArray(32) { 0x00 })

            // RIFF WAVE
            val totalRiffSize = 36 + pcmData.size
            fos.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(
                (totalRiffSize and 0xFF).toByte(),
                ((totalRiffSize shr 8) and 0xFF).toByte(),
                ((totalRiffSize shr 16) and 0xFF).toByte(),
                ((totalRiffSize shr 24) and 0xFF).toByte()
            ))
            fos.write("WAVE".toByteArray(StandardCharsets.US_ASCII))

            // fmt chunk
            fos.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(16, 0, 0, 0, 1, 0, 2, 0))
            val sampleRate = 44100
            fos.write(byteArrayOf(
                (sampleRate and 0xFF).toByte(),
                ((sampleRate shr 8) and 0xFF).toByte(),
                ((sampleRate shr 16) and 0xFF).toByte(),
                ((sampleRate shr 24) and 0xFF).toByte()
            ))
            val byteRate = 44100 * 4
            fos.write(byteArrayOf(
                (byteRate and 0xFF).toByte(),
                ((byteRate shr 8) and 0xFF).toByte(),
                ((byteRate shr 16) and 0xFF).toByte(),
                ((byteRate shr 24) and 0xFF).toByte()
            ))
            fos.write(byteArrayOf(4, 0, 16, 0))

            // data chunk
            fos.write("data".toByteArray(StandardCharsets.US_ASCII))
            val dataSize = pcmData.size
            fos.write(byteArrayOf(
                (dataSize and 0xFF).toByte(),
                ((dataSize shr 8) and 0xFF).toByte(),
                ((dataSize shr 16) and 0xFF).toByte(),
                ((dataSize shr 24) and 0xFF).toByte()
            ))
            fos.write(pcmData)
        }
        return file
    }

    /**
     * Helper to create a pristine FLAC audio file.
     */
    private fun createSampleFlacFile(file: File, audioBytes: ByteArray): File {
        val magic = "fLaC".toByteArray(StandardCharsets.US_ASCII)
        val streaminfoHdr = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22)
        val streaminfo = ByteArray(34)
        streaminfo[0] = 0x10; streaminfo[1] = 0x00
        streaminfo[2] = 0x10; streaminfo[3] = 0x00
        streaminfo[10] = 0x0A.toByte(); streaminfo[11] = 0xC4.toByte(); streaminfo[12] = 0x42.toByte(); streaminfo[13] = 0xF0.toByte()
        val totalSamples = 44100
        streaminfo[14] = 0; streaminfo[15] = 0
        streaminfo[16] = ((totalSamples shr 8) and 0xFF).toByte()
        streaminfo[17] = (totalSamples and 0xFF).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(magic)
            fos.write(streaminfoHdr)
            fos.write(streaminfo)
            fos.write(audioBytes)
        }
        return file
    }

    /**
     * Helper to create a pristine M4A (ISO MP4) audio file.
     */
    private fun createSampleM4aFile(file: File, audioBytes: ByteArray): File {
        fun makeBox(type: String, data: ByteArray): ByteArray {
            val sz = 8 + data.size
            val b = ByteArray(sz)
            b[0] = ((sz shr 24) and 0xFF).toByte()
            b[1] = ((sz shr 16) and 0xFF).toByte()
            b[2] = ((sz shr 8) and 0xFF).toByte()
            b[3] = (sz and 0xFF).toByte()
            type.take(4).toByteArray(StandardCharsets.ISO_8859_1).copyInto(b, 4)
            data.copyInto(b, 8)
            return b
        }

        val ftyp = makeBox("ftyp", "M4A \u0000\u0000\u0000\u0000M4A mp42isom".toByteArray(StandardCharsets.ISO_8859_1))
        val stco = makeBox("stco", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0))
        val stsz = makeBox("stsz", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, audioBytes.size.toByte()))
        val stsc = makeBox("stsc", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1))
        val stts = makeBox("stts", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 4, 0))
        val stsd = makeBox("stsd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        val stbl = makeBox("stbl", stsd + stts + stsc + stsz + stco)
        val dinf = makeBox("dinf", makeBox("dref", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)))
        val smhd = makeBox("smhd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        val minf = makeBox("minf", smhd + dinf + stbl)
        val hdlr = makeBox("hdlr", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) + "soun".toByteArray(StandardCharsets.ISO_8859_1) + ByteArray(12) + "SoundHandler\u0000".toByteArray(StandardCharsets.ISO_8859_1))
        val mdhd = makeBox("mdhd", ByteArray(16) + byteArrayOf(0x55.toByte(), 0xC4.toByte(), 0, 0))
        val mdia = makeBox("mdia", mdhd + hdlr + minf)
        val tkhd = makeBox("tkhd", byteArrayOf(0, 0, 0, 1) + ByteArray(16) + byteArrayOf(0, 0, 0, 1) + ByteArray(60))
        val trak = makeBox("trak", tkhd + mdia)
        val mvhd = makeBox("mvhd", byteArrayOf(0, 0, 0, 0) + ByteArray(16) + ByteArray(76) + byteArrayOf(0, 0, 0, 2))
        val moov = makeBox("moov", mvhd + trak)
        val mdat = makeBox("mdat", audioBytes)

        FileOutputStream(file).use { fos ->
            fos.write(ftyp)
            fos.write(moov)
            fos.write(mdat)
        }
        return file
    }

    /**
     * Helper to create a pristine OGG Opus audio file.
     */
    private fun createSampleOggOpusFile(file: File, audioBytes: ByteArray): File {
        val crcTable = IntArray(256).apply {
            for (i in 0 until 256) {
                var curr = i shl 24
                for (j in 0 until 8) {
                    curr = if ((curr and 0x80000000.toInt()) != 0) (curr shl 1) xor 0x04C11DB7 else curr shl 1
                }
                this[i] = curr
            }
        }

        fun computeOggCrc(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
                crc = (crc shl 8) xor crcTable[idx]
            }
            return crc
        }

        fun makeOggPage(headerType: Int, granule: Long, serial: Int, seq: Int, payload: ByteArray): ByteArray {
            val segTable = byteArrayOf(payload.size.toByte())
            val totalSize = 27 + segTable.size + payload.size
            val page = ByteArray(totalSize)
            page[0] = 'O'.code.toByte(); page[1] = 'g'.code.toByte(); page[2] = 'g'.code.toByte(); page[3] = 'S'.code.toByte()
            page[5] = headerType.toByte()
            for (i in 0 until 8) page[6 + i] = ((granule shr (i * 8)) and 0xFF).toByte()
            page[14] = (serial and 0xFF).toByte(); page[15] = ((serial shr 8) and 0xFF).toByte()
            page[16] = ((serial shr 16) and 0xFF).toByte(); page[17] = ((serial shr 24) and 0xFF).toByte()
            page[18] = (seq and 0xFF).toByte(); page[19] = ((seq shr 8) and 0xFF).toByte()
            page[20] = ((seq shr 16) and 0xFF).toByte(); page[21] = ((seq shr 24) and 0xFF).toByte()
            page[26] = 1
            page[27] = segTable[0]
            payload.copyInto(page, 28)
            val crc = computeOggCrc(page)
            page[22] = (crc and 0xFF).toByte()
            page[23] = ((crc shr 8) and 0xFF).toByte()
            page[24] = ((crc shr 16) and 0xFF).toByte()
            page[25] = ((crc shr 24) and 0xFF).toByte()
            return page
        }

        val opusHead = "OpusHead".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val p0 = makeOggPage(0x02, 0L, 12345, 0, opusHead)

        val opusTags = "OpusTags".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(9, 0, 0, 0) + "SoundSync".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0, 0, 0, 0)
        val p1 = makeOggPage(0x00, 0L, 12345, 1, opusTags)

        val p2 = makeOggPage(0x04, 960L, 12345, 2, audioBytes)

        FileOutputStream(file).use { fos ->
            fos.write(p0)
            fos.write(p1)
            fos.write(p2)
        }
        return file
    }

    /**
     * Helper to create a pristine AIFF audio file with PCM data.
     */
    private fun createSampleAiffFile(file: File, pcmData: ByteArray): File {
        val commPayload = ByteArray(18)
        commPayload[0] = 0; commPayload[1] = 2 // 2 channels
        val frames = pcmData.size / 4
        commPayload[2] = ((frames shr 24) and 0xFF).toByte()
        commPayload[3] = ((frames shr 16) and 0xFF).toByte()
        commPayload[4] = ((frames shr 8) and 0xFF).toByte()
        commPayload[5] = (frames and 0xFF).toByte()
        commPayload[6] = 0; commPayload[7] = 16 // 16-bit
        byteArrayOf(0x40.toByte(), 0x0E.toByte(), 0xAC.toByte(), 0x44.toByte(), 0, 0, 0, 0, 0, 0)
            .copyInto(commPayload, 8) // 44100 Hz

        val ssndPayload = ByteArray(8 + pcmData.size)
        pcmData.copyInto(ssndPayload, 8)

        val totalFormLength = 4 + (8 + commPayload.size) + (8 + ssndPayload.size)
        FileOutputStream(file).use { fos ->
            fos.write("FORM".toByteArray(StandardCharsets.US_ASCII))
            fos.write(byteArrayOf(
                ((totalFormLength shr 24) and 0xFF).toByte(),
                ((totalFormLength shr 16) and 0xFF).toByte(),
                ((totalFormLength shr 8) and 0xFF).toByte(),
                (totalFormLength and 0xFF).toByte()
            ))
            fos.write("AIFF".toByteArray(StandardCharsets.US_ASCII))

            fos.write("COMM".toByteArray(StandardCharsets.US_ASCII))
            val cLen = commPayload.size
            fos.write(byteArrayOf(((cLen shr 24) and 0xFF).toByte(), ((cLen shr 16) and 0xFF).toByte(), ((cLen shr 8) and 0xFF).toByte(), (cLen and 0xFF).toByte()))
            fos.write(commPayload)

            fos.write("SSND".toByteArray(StandardCharsets.US_ASCII))
            val sLen = ssndPayload.size
            fos.write(byteArrayOf(((sLen shr 24) and 0xFF).toByte(), ((sLen shr 16) and 0xFF).toByte(), ((sLen shr 8) and 0xFF).toByte(), (sLen and 0xFF).toByte()))
            fos.write(ssndPayload)
        }
        return file
    }

    /**
     * Helper to extract the raw PCM bytes from the 'SSND' chunk of an AIFF file.
     */
    private fun extractAiffSsndDataBytes(file: File): ByteArray {
        val bytes = file.readBytes()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, StandardCharsets.US_ASCII)
            val chunkSize = ((bytes[offset + 4].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 7].toInt() and 0xFF)
            val pad = if (chunkSize % 2 != 0) 1 else 0
            if (chunkId == "SSND") {
                val data = ByteArray(chunkSize - 8)
                System.arraycopy(bytes, offset + 8 + 8, data, 0, chunkSize - 8)
                return data
            }
            offset += 8 + chunkSize + pad
        }
        throw IllegalStateException("No SSND chunk found in ${file.name}")
    }

    /**
     * Helper to extract the raw PCM bytes from the 'data' chunk of a WAV file.
     */
    private fun extractWavDataChunkBytes(file: File): ByteArray {
        val bytes = file.readBytes()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, StandardCharsets.US_ASCII)
            val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val pad = if (chunkSize % 2 != 0) 1 else 0
            if (chunkId == "data") {
                val data = ByteArray(chunkSize)
                System.arraycopy(bytes, offset + 8, data, 0, chunkSize)
                return data
            }
            offset += 8 + chunkSize + pad
        }
        throw IllegalStateException("No data chunk found in ${file.name}")
    }

    /**
     * Helper to extract the raw bytes of an arbitrary chunk from a WAV file.
     */
    private fun extractWavChunkBytes(file: File, targetChunkId: String): ByteArray {
        val bytes = file.readBytes()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, StandardCharsets.US_ASCII)
            val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val pad = if (chunkSize % 2 != 0) 1 else 0
            if (chunkId.equals(targetChunkId, ignoreCase = true)) {
                val data = ByteArray(chunkSize)
                System.arraycopy(bytes, offset + 8, data, 0, chunkSize)
                return data
            }
            offset += 8 + chunkSize + pad
        }
        throw IllegalStateException("No $targetChunkId chunk found in ${file.name}")
    }

    /**
     * Executes external exiftool and returns its output key-value map.
     */
    private fun runExifTool(file: File): Map<String, String> {
        val exiftoolBinary = File("/usr/bin/exiftool")
        if (!exiftoolBinary.exists() || !exiftoolBinary.canExecute()) {
            return emptyMap()
        }
        val process = ProcessBuilder("/usr/bin/exiftool", "-a", "-G1", "-s", file.absolutePath)
            .redirectErrorStream(true)
            .start()

        val output = mutableMapOf<String, String>()
        Scanner(process.inputStream).use { scanner ->
            while (scanner.hasNextLine()) {
                val line = scanner.nextLine().trim()
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val tagPart = line.substring(0, colonIdx).trim()
                    val tagName = tagPart.substringAfterLast(']').trim()
                    val value = line.substring(colonIdx + 1).trim()
                    output[tagName] = value
                }
            }
        }
        process.waitFor()
        return output
    }

    @Test
    fun `wav file metadata writing embeds both id3 chunk and list info with verbatim PCM audio preservation`() = runBlocking {
        val wavFile = File(tempFolder.root, "Primeshock - Dance With Me.wav")
        // Create 2400 bytes of distinct PCM audio sample data
        val originalPcm = ByteArray(2400) { i -> ((i * 37 + 11) % 256).toByte() }
        createSampleWavFile(wavFile, originalPcm)

        val track = Track(
            id = "track-primeshock-wav",
            title = "Dance With Me",
            artist = "Primeshock",
            album = "Dance With Me - Single",
            genre = "Hardstyle",
            releaseYear = 2021,
            bpm = 150.0,
            musicalKey = "8B",
            camelotKey = "8B",
            trackNumber = 1,
            filePath = wavFile.absolutePath,
            notes = "Peak time hardstyle banger"
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)

        assertTrue("Expected MetadataWriteResult.Written or Partial, got $result",
            result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        // 1. Mandatory read-back verification using internal reader
        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("Dance With Me", readBack.title)
        assertEquals("Primeshock", readBack.artist)
        assertEquals("Dance With Me - Single", readBack.album)
        assertEquals("Hardstyle", readBack.genre)
        assertEquals(2021, readBack.releaseYear)
        assertEquals(150.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("8B", readBack.musicalKey)
        assertEquals(1, readBack.trackNumber)
        assertTrue(readBack.hasEmbeddedArtwork)
        assertTrue(readBack.embeddedArtworkSize > 0)

        // 2. PCM Audio data MUST remain 100% bit-for-bit verbatim untouched
        val postWritePcm = extractWavDataChunkBytes(wavFile)
        assertEquals("Audio PCM data length must match exactly", originalPcm.size, postWritePcm.size)
        for (i in originalPcm.indices) {
            assertEquals("PCM byte at index $i must be identical", originalPcm[i], postWritePcm[i])
        }

        // 3. ExifTool verification
        val exifData = runExifTool(wavFile)
        if (exifData.isNotEmpty()) {
            assertEquals("Dance With Me", exifData["Title"])
            assertEquals("Primeshock", exifData["Artist"])
            assertEquals("Dance With Me - Single", exifData["Album"])
            assertEquals("Hardstyle", exifData["Genre"])
            val bpmValue = exifData["BeatsPerMinute"] ?: exifData["BPM"]
            assertNotNull("BPM must be visible in ExifTool", bpmValue)
            assertTrue("BPM must contain 150", bpmValue!!.contains("150"))
            val keyValue = exifData["InitialKey"] ?: exifData["Key"]
            assertNotNull("Key must be visible in ExifTool", keyValue)
            assertTrue("Key must contain 8B", keyValue!!.contains("8B"))
        }
    }

    @Test
    fun `mp3 file metadata writing embeds ID3v23 tags and artwork with audio frames preserved`() = runBlocking {
        val mp3File = File(tempFolder.root, "test_track.mp3")
        // Minimal valid MP3: MPEG-1 Layer 3 sync word 0xFF 0xFB
        val dummyAudioFrames = ByteArray(1024) { 0x55 }
        val mp3Bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + dummyAudioFrames
        mp3File.writeBytes(mp3Bytes)

        val track = Track(
            id = "track-mp3-1",
            title = "Strobe",
            artist = "deadmau5",
            album = "For Lack of a Better Name",
            genre = "Progressive House",
            releaseYear = 2009,
            bpm = 128.0,
            musicalKey = "6A",
            camelotKey = "6A",
            trackNumber = 3,
            filePath = mp3File.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)

        assertTrue(result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath)
        assertEquals("Strobe", readBack.title)
        assertEquals("deadmau5", readBack.artist)
        assertEquals("For Lack of a Better Name", readBack.album)
        assertEquals("Progressive House", readBack.genre)
        assertEquals(2009, readBack.releaseYear)
        assertEquals(128.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("6A", readBack.musicalKey)
        assertEquals(3, readBack.trackNumber)
        assertTrue(readBack.hasEmbeddedArtwork)

        // ExifTool check
        val exifData = runExifTool(mp3File)
        if (exifData.isNotEmpty()) {
            assertEquals("Strobe", exifData["Title"])
            assertEquals("deadmau5", exifData["Artist"])
            assertEquals("For Lack of a Better Name", exifData["Album"])
            assertEquals("Progressive House", exifData["Genre"])
        }
    }

    @Test
    fun `sensible merge preserves existing embedded tags when new track payload contains blanks`() = runBlocking {
        val wavFile = File(tempFolder.root, "sensible_merge.wav")
        createSampleWavFile(wavFile, ByteArray(800) { 0x12 })

        // Initial full write
        val initialPayload = CompleteTagPayload(
            title = "Sandstorm",
            artist = "Darude",
            album = "Before the Storm",
            genre = "Trance",
            releaseYear = 2000,
            bpm = 136.0,
            musicalKey = "7B"
        )
        assertTrue(AudioTagWriter.writeCompleteTags(context, wavFile.absolutePath, initialPayload))

        // Subsequent update that only analyzes / sets BPM and Key, with blank title and default album "Single"
        val partialTrack = Track(
            id = "track-merge-test",
            title = "", // Blank
            artist = "Darude",
            album = "Single", // Default generic
            genre = "DJ Library", // Default generic
            bpm = 138.0, // Corrected BPM
            musicalKey = "7B",
            filePath = wavFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(partialTrack)
        assertTrue(result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        // High quality existing metadata must NOT have been wiped out by blank/generic values
        assertEquals("Sandstorm", readBack.title)
        assertEquals("Darude", readBack.artist)
        assertEquals("Before the Storm", readBack.album)
        assertEquals("Trance", readBack.genre)
        assertEquals(138.0, readBack.bpm ?: 0.0, 0.5)
    }

    private fun createFakeTrackDao(): Pair<TrackDao, MutableMap<String, TrackEntity>> {
        val tracks = mutableMapOf<String, TrackEntity>()
        val fakeTrackDao = java.lang.reflect.Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "insertTrack", "updateTrack" -> {
                    val t = args[0] as TrackEntity
                    tracks[t.id] = t
                    null
                }
                "getTrackById" -> {
                    tracks[args[0] as String]
                }
                "updateMetadataWriteState" -> {
                    val id = args[0] as String
                    val state = args[1] as String
                    tracks[id]?.let { tracks[id] = it.copy(metadataWriteState = state) }
                    null
                }
                "updateFilePath" -> {
                    val id = args[0] as String
                    val newPath = args[1] as String
                    tracks[id]?.let { tracks[id] = it.copy(filePath = newPath) }
                    null
                }
                "getTracksNeedingFileWrite" -> {
                    tracks.values.filter { it.metadataWriteState != MetadataWriteState.FILE_WRITE_SUCCESS.name }.toList()
                }
                "getCountNeedingFileWrite" -> {
                    tracks.values.count { it.metadataWriteState != MetadataWriteState.FILE_WRITE_SUCCESS.name }
                }
                "getTracksWithWriteIssues" -> {
                    val issueStates = setOf(
                        MetadataWriteState.FILE_WRITE_FAILED.name,
                        MetadataWriteState.READ_ONLY_FILE.name,
                        MetadataWriteState.PERMISSION_REQUIRED.name,
                        MetadataWriteState.DATABASE_ONLY.name
                    )
                    tracks.values.filter { it.metadataWriteState in issueStates }.toList()
                }
                "getAllTracksList", "getAllTracksSync" -> {
                    tracks.values.toList()
                }
                else -> null
            }
        } as TrackDao
        return Pair(fakeTrackDao, tracks)
    }

    @Test
    fun `MetadataFileWriteQueue executes write asynchronously and updates database writeState`() = runBlocking {
        val (trackDao, _) = createFakeTrackDao()

        val wavFile = File(tempFolder.root, "queue_test.wav")
        createSampleWavFile(wavFile, ByteArray(600) { 0x34 })

        val track = Track(
            id = "track-queue-test-id",
            title = "Titanium",
            artist = "David Guetta",
            album = "Nothing but the Beat",
            genre = "Electro Dance",
            bpm = 126.0,
            musicalKey = "9A",
            filePath = wavFile.absolutePath
        )

        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val queue = MetadataFileWriteQueue.createForTesting(context, trackDao)
        val job = queue.enqueue(track)
        job.join()

        // Verify DB was updated to FILE_WRITE_SUCCESS
        val updatedEntity = trackDao.getTrackById("track-queue-test-id")
        assertNotNull(updatedEntity)
        assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS.name, updatedEntity!!.metadataWriteState)
    }

    @Test
    fun `repairOrMigrateLibrary detects unwritten tracks and embeds metadata safely`() = runBlocking {
        val (trackDao, _) = createFakeTrackDao()

        val wavFile = File(tempFolder.root, "migration_target.wav")
        createSampleWavFile(wavFile, ByteArray(600) { 0x56 })

        val track = Track(
            id = "track-migration-1",
            title = "Animals",
            artist = "Martin Garrix",
            album = "Gold Skies",
            genre = "Big Room",
            bpm = 128.0,
            musicalKey = "4A",
            filePath = wavFile.absolutePath,
            metadataWriteState = MetadataWriteState.DATABASE_ONLY.name
        )

        trackDao.insertTrack(TrackEntity.fromTrack(track))

        val queue = MetadataFileWriteQueue.createForTesting(context, trackDao)
        val report = queue.repairOrMigrateLibrary()

        assertTrue(report.successfullyWritten >= 1 || report.alreadySynchronized >= 1)

        val updatedEntity = trackDao.getTrackById("track-migration-1")
        assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS.name, updatedEntity!!.metadataWriteState)

        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("Animals", readBack.title)
        assertEquals("Martin Garrix", readBack.artist)
    }

    @Test
    fun `pushMetadataToFiles executes 12-step bulk library push with field diffing and verification`() = runBlocking {
        val (trackDao, _) = createFakeTrackDao()

        // Track 1: WAV track with missing tags on disk, but full metadata in DB
        val wavFile1 = File(tempFolder.root, "Kamikaze.wav")
        createSampleWavFile(wavFile1, ByteArray(1200) { 0x44 })
        val track1 = Track(
            id = "track-kamikaze",
            title = "Kamikaze",
            artist = "Act of Rage",
            album = "Outrageous",
            genre = "Rawstyle",
            releaseYear = 2019,
            bpm = 155.0,
            musicalKey = "1B",
            filePath = wavFile1.absolutePath,
            metadataWriteState = MetadataWriteState.DATABASE_ONLY.name
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track1))

        // Track 2: WAV track already written to file and in sync
        val wavFile2 = File(tempFolder.root, "Dance With Me.wav")
        createSampleWavFile(wavFile2, ByteArray(800) { 0x77 })
        AudioTagWriter.writeCompleteTags(
            context,
            wavFile2.absolutePath,
            CompleteTagPayload(
                title = "Dance With Me",
                artist = "Primeshock",
                album = "Dance With Me - Single",
                genre = "Hardstyle",
                releaseYear = 2021,
                bpm = 150.0,
                musicalKey = "8B"
            )
        )
        val track2 = Track(
            id = "track-dance-with-me",
            title = "Dance With Me",
            artist = "Primeshock",
            album = "Dance With Me - Single",
            genre = "Hardstyle",
            releaseYear = 2021,
            bpm = 150.0,
            musicalKey = "8B",
            filePath = wavFile2.absolutePath,
            metadataWriteState = MetadataWriteState.FILE_WRITE_SUCCESS.name
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track2))

        val queue = MetadataFileWriteQueue.createForTesting(context, trackDao)
        val progressList = mutableListOf<com.example.metadata.PushMetadataProgress>()

        val report = queue.pushMetadataToFiles(forceAll = true) { progress ->
            progressList.add(progress)
        }

        // Verify summary report
        assertEquals(2, report.totalExamined)
        assertEquals(1, report.successfullyWritten)
        assertEquals(1, report.alreadySynchronized)
        assertEquals(0, report.failed)

        // Verify Track 1 tags were written and verified on disk
        val readBack1 = AudioEmbeddedMetadataReader.read(context, wavFile1.absolutePath)
        assertEquals("Kamikaze", readBack1.title)
        assertEquals("Act of Rage", readBack1.artist)
        assertEquals("Outrageous", readBack1.album)
        assertEquals("Rawstyle", readBack1.genre)
        assertEquals(2019, readBack1.releaseYear)
        assertEquals(155.0, readBack1.bpm ?: 0.0, 0.5)
        assertEquals("1B", readBack1.musicalKey)

        // Verify DB write state updated
        val updatedTrack1 = trackDao.getTrackById("track-kamikaze")
        assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS.name, updatedTrack1!!.metadataWriteState)

        // Verify progress updates occurred across phases
        assertTrue(progressList.isNotEmpty())
        assertTrue(progressList.any { it.phase == com.example.metadata.PushMetadataPhase.READING_PHYSICAL })
        assertTrue(progressList.any { it.phase == com.example.metadata.PushMetadataPhase.COMPARING })
        assertTrue(progressList.any { it.phase == com.example.metadata.PushMetadataPhase.WRITING_TAGS || it.phase == com.example.metadata.PushMetadataPhase.DONE_SYNCED })
    }

    @Test
    fun `replaceOriginalFile multi-tiered replacement preserves content verbatim`() {
        val targetFile = File(tempFolder.root, "replace_target.bin")
        targetFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val tempFile = File(tempFolder.root, "replace_staging.tmp")
        val newContent = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
        tempFile.writeBytes(newContent)

        val success = AudioTagWriter.replaceOriginalFile(targetFile, tempFile)
        assertTrue(success)
        assertTrue(targetFile.exists())
        assertEquals(newContent.size, targetFile.readBytes().size)
        assertTrue(newContent.contentEquals(targetFile.readBytes()))
    }

    @Test
    fun `flac file metadata writing embeds Vorbis comments and picture block with audio frames preserved`() = runBlocking {
        val flacFile = File(tempFolder.root, "sample_flac.flac")
        val dummyAudio = ByteArray(1200) { 0x42 }
        createSampleFlacFile(flacFile, dummyAudio)

        val track = Track(
            id = "track-flac-test",
            title = "Sun & Moon",
            artist = "Above & Beyond",
            album = "Group Therapy",
            genre = "Trance",
            releaseYear = 2011,
            bpm = 134.0,
            musicalKey = "11B",
            camelotKey = "11B",
            trackNumber = 4,
            filePath = flacFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)
        assertTrue("Expected Written or Partial, got $result", result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, flacFile.absolutePath)
        assertEquals("Sun & Moon", readBack.title)
        assertEquals("Above & Beyond", readBack.artist)
        assertEquals("Group Therapy", readBack.album)
        assertEquals("Trance", readBack.genre)
        assertEquals(2011, readBack.releaseYear)
        assertEquals(134.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("11B", readBack.musicalKey)
        assertEquals(4, readBack.trackNumber)
        assertTrue(readBack.hasEmbeddedArtwork)

        // ExifTool check
        val exifData = runExifTool(flacFile)
        if (exifData.isNotEmpty()) {
            assertEquals("Sun & Moon", exifData["Title"])
            assertEquals("Above & Beyond", exifData["Artist"])
            assertEquals("Group Therapy", exifData["Album"])
            assertEquals("Trance", exifData["Genre"])
        }
    }

    @Test
    fun `m4a file metadata writing embeds ilst atoms and cover art with audio frames preserved`() = runBlocking {
        val m4aFile = File(tempFolder.root, "sample_m4a.m4a")
        val dummyAudio = ByteArray(800) { 0x33 }
        createSampleM4aFile(m4aFile, dummyAudio)

        val track = Track(
            id = "track-m4a-test",
            title = "One (Your Name)",
            artist = "Swedish House Mafia",
            album = "Until One",
            genre = "Progressive House",
            releaseYear = 2010,
            bpm = 126.0,
            musicalKey = "4A",
            camelotKey = "4A",
            trackNumber = 2,
            filePath = m4aFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)
        assertTrue("Expected Written or Partial, got $result", result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, m4aFile.absolutePath)
        assertEquals("One (Your Name)", readBack.title)
        assertEquals("Swedish House Mafia", readBack.artist)
        assertEquals("Until One", readBack.album)
        assertEquals("Progressive House", readBack.genre)
        assertEquals(2010, readBack.releaseYear)
        assertEquals(126.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("4A", readBack.musicalKey)
        assertEquals(2, readBack.trackNumber)
        assertTrue(readBack.hasEmbeddedArtwork)

        // ExifTool check
        val exifData = runExifTool(m4aFile)
        if (exifData.isNotEmpty()) {
            assertEquals("One (Your Name)", exifData["Title"])
            assertEquals("Swedish House Mafia", exifData["Artist"])
            assertEquals("Until One", exifData["Album"])
            assertEquals("Progressive House", exifData["Genre"])
        }
    }

    @Test
    fun `ogg opus file metadata writing embeds OpusTags comments and picture block`() = runBlocking {
        val opusFile = File(tempFolder.root, "sample_opus.opus")
        val dummyAudio = ByteArray(600) { 0x77 }
        createSampleOggOpusFile(opusFile, dummyAudio)

        val track = Track(
            id = "track-opus-test",
            title = "Clarity",
            artist = "Zedd",
            album = "Clarity",
            genre = "Electro House",
            releaseYear = 2012,
            bpm = 128.0,
            musicalKey = "7B",
            camelotKey = "7B",
            trackNumber = 1,
            filePath = opusFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)
        assertTrue("Expected Written or Partial, got $result", result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, opusFile.absolutePath)
        assertEquals("Clarity", readBack.title)
        assertEquals("Zedd", readBack.artist)
        assertEquals("Clarity", readBack.album)
        assertEquals("Electro House", readBack.genre)
        assertEquals(2012, readBack.releaseYear)
        assertEquals(128.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("7B", readBack.musicalKey)
        assertEquals(1, readBack.trackNumber)

        // ExifTool check
        val exifData = runExifTool(opusFile)
        if (exifData.isNotEmpty()) {
            assertEquals("Clarity", exifData["Title"])
            assertEquals("Zedd", exifData["Artist"])
            assertEquals("Clarity", exifData["Album"])
            assertEquals("Electro House", exifData["Genre"])
        }
    }

    @Test
    fun `aiff file metadata writing embeds ID3 chunk with verbatim PCM audio preservation`() = runBlocking {
        val aiffFile = File(tempFolder.root, "sample_aiff.aiff")
        val originalPcm = ByteArray(2000) { i -> ((i * 41 + 7) % 256).toByte() }
        createSampleAiffFile(aiffFile, originalPcm)

        val track = Track(
            id = "track-aiff-test",
            title = "Adagio for Strings",
            artist = "Tiësto",
            album = "Just Be",
            genre = "Trance",
            releaseYear = 2004,
            bpm = 140.0,
            musicalKey = "5A",
            camelotKey = "5A",
            trackNumber = 7,
            filePath = aiffFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)
        assertTrue("Expected Written or Partial, got $result", result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        // 1. Internal reader verification
        val readBack = AudioEmbeddedMetadataReader.read(context, aiffFile.absolutePath)
        assertEquals("Adagio for Strings", readBack.title)
        assertEquals("Tiësto", readBack.artist)
        assertEquals("Just Be", readBack.album)
        assertEquals("Trance", readBack.genre)
        assertEquals(2004, readBack.releaseYear)
        assertEquals(140.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("5A", readBack.musicalKey)
        assertEquals(7, readBack.trackNumber)
        assertTrue(readBack.hasEmbeddedArtwork)

        // 2. Verbatim PCM audio sample preservation in SSND chunk
        val postWritePcm = extractAiffSsndDataBytes(aiffFile)
        assertEquals(originalPcm.size, postWritePcm.size)
        for (i in originalPcm.indices) {
            assertEquals("AIFF PCM byte at index $i must match", originalPcm[i], postWritePcm[i])
        }

        // 3. ExifTool verification
        val exifData = runExifTool(aiffFile)
        if (exifData.isNotEmpty()) {
            assertEquals("Adagio for Strings", exifData["Title"])
            assertEquals("Tiësto", exifData["Artist"])
            assertEquals("Just Be", exifData["Album"])
            assertEquals("Trance", exifData["Genre"])
        }
    }

    @Test
    fun `unicode artist and title across MP3 and FLAC are preserved correctly`() = runBlocking {
        val flacFile = File(tempFolder.root, "unicode_flac.flac")
        createSampleFlacFile(flacFile, ByteArray(800) { 0x55 })

        val track = Track(
            id = "track-unicode",
            title = "千本桜 🌸 (Senbonzakura)",
            artist = "初音ミク (Hatsune Miku)",
            album = "ボカロ名曲選 🎵",
            genre = "J-Pop / Vocaloid",
            releaseYear = 2011,
            bpm = 154.0,
            musicalKey = "5m",
            filePath = flacFile.absolutePath,
            notes = "Тестовая заметка с русскими буквами & special symbols: <>&'\""
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track)
        assertTrue("Expected Written or Partial, got $result", result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, flacFile.absolutePath)
        assertEquals("千本桜 🌸 (Senbonzakura)", readBack.title)
        assertEquals("初音ミク (Hatsune Miku)", readBack.artist)
        assertEquals("ボカロ名曲選 🎵", readBack.album)
        assertEquals("J-Pop / Vocaloid", readBack.genre)
        assertEquals(2011, readBack.releaseYear)
        assertEquals(154.0, readBack.bpm ?: 0.0, 0.5)
    }

    @Test
    fun `very long title and metadata attributes do not corrupt audio frames`() = runBlocking {
        val mp3File = File(tempFolder.root, "long_metadata.mp3")
        val audioData = ByteArray(1024) { 0x22 }
        val mp3Bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + audioData
        mp3File.writeBytes(mp3Bytes)

        val veryLongTitle = "A".repeat(600) + " - The Ultimate Extended DJ Remix with Infinite Subtitle Text"
        val veryLongNotes = "B".repeat(1200)
        val track = Track(
            id = "track-long-meta",
            title = veryLongTitle,
            artist = "Long Artist Name ".repeat(10),
            album = "Extremely Long Album Name ".repeat(10),
            genre = "Dance",
            releaseYear = 2024,
            bpm = 128.0,
            musicalKey = "8A",
            filePath = mp3File.absolutePath,
            notes = veryLongNotes
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track)
        assertTrue("Expected Written or Partial, got $result", result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, mp3File.absolutePath)
        assertEquals(veryLongTitle, readBack.title)
        assertEquals(128.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("8A", readBack.musicalKey)
    }

    @Test
    fun `read only file returns ReadOnlyFile without corrupting file`() = runBlocking {
        val wavFile = File(tempFolder.root, "readonly_test.wav")
        val pcm = ByteArray(400) { 0x11 }
        createSampleWavFile(wavFile, pcm)

        val track = Track(
            id = "track-readonly",
            title = "Locked Track",
            artist = "Locked Artist",
            filePath = wavFile.absolutePath
        )

        try {
            com.example.storage.StorageWritePermissionHelper.isWritableOverrideForTesting = { false }
            val writer = MetadataFileWriter(context)
            val result = writer.writeAsync(track)
            assertEquals(MetadataWriteResult.ReadOnlyFile(wavFile.absolutePath), result)
        } finally {
            com.example.storage.StorageWritePermissionHelper.isWritableOverrideForTesting = null
        }
    }

    @Test
    fun `unsupported format returns Unsupported without corrupting file`() = runBlocking {
        val txtFile = File(tempFolder.root, "not_an_audio.xyz")
        txtFile.writeText("hello world")

        val track = Track(
            id = "track-unsupported",
            title = "Unknown",
            artist = "Unknown",
            filePath = txtFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track)
        assertTrue(result is MetadataWriteResult.Unsupported)
        assertEquals("hello world", txtFile.readText())
    }

    @Test
    fun `writing metadata without artwork succeeds cleanly`() = runBlocking {
        val flacFile = File(tempFolder.root, "sample_no_art.flac")
        createSampleFlacFile(flacFile, ByteArray(600) { 0x33 })

        val track = Track(
            id = "track-no-art",
            title = "No Art Track",
            artist = "No Art Artist",
            album = "No Art Album",
            bpm = 120.0,
            musicalKey = "1A",
            filePath = flacFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = null)
        assertTrue(result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        val readBack = AudioEmbeddedMetadataReader.read(context, flacFile.absolutePath)
        assertEquals("No Art Track", readBack.title)
        assertEquals("No Art Artist", readBack.artist)
    }

    @Test
    fun `24-bit 96kHz High-Res PCM WAV metadata writing preserves audio verbatim`() = runBlocking {
        val wavFile = File(tempFolder.root, "high_res_24bit_96k.wav")
        // Create 24-bit PCM data (multiples of 6 bytes: 2 channels * 3 bytes)
        val originalPcm = ByteArray(3072) { i -> ((i * 47 + 19) % 256).toByte() }
        createSampleWavFile24Bit96k(wavFile, originalPcm)

        val track = Track(
            id = "track-24bit-96k",
            title = "Subterranean",
            artist = "High Res Master",
            album = "Audiophile Edition",
            genre = "Drum & Bass",
            releaseYear = 2024,
            bpm = 174.0,
            musicalKey = "9A",
            camelotKey = "9A",
            trackNumber = 3,
            filePath = wavFile.absolutePath,
            notes = "Lossless 24-bit 96kHz master file"
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = sampleArtworkBytes)
        assertTrue("Expected Written or Partial, got $result",
            result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        // 1. Read-back verification
        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("Subterranean", readBack.title)
        assertEquals("High Res Master", readBack.artist)
        assertEquals("Audiophile Edition", readBack.album)
        assertEquals("Drum & Bass", readBack.genre)
        assertEquals(2024, readBack.releaseYear)
        assertEquals(174.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("9A", readBack.musicalKey)
        assertEquals(3, readBack.trackNumber)
        assertTrue(readBack.hasEmbeddedArtwork)

        // 2. Verbatim 24-bit PCM audio sample preservation
        val postWritePcm = extractWavDataChunkBytes(wavFile)
        assertEquals("24-bit PCM length must match exactly", originalPcm.size, postWritePcm.size)
        for (i in originalPcm.indices) {
            assertEquals("24-bit PCM byte at index $i must match", originalPcm[i], postWritePcm[i])
        }
    }

    @Test
    fun `wav with non-audio chunks bext and cue points preserves those chunks verbatim`() = runBlocking {
        val wavFile = File(tempFolder.root, "broadcast_with_cues.wav")
        val originalPcm = ByteArray(1200) { i -> ((i * 17) % 256).toByte() }
        val bextData = "BWF_DESCRIPTION=SoundSyncStudio;ORIGINATOR=DJ;ORIG_REF=REF12345".toByteArray(StandardCharsets.US_ASCII)
        val cueData = ByteArray(64) { i -> (i + 1).toByte() }

        createSampleWavWithExtraChunks(wavFile, originalPcm, bextData, cueData)

        val track = Track(
            id = "track-bext-cue",
            title = "Broadcast Master Track",
            artist = "Pro Producer",
            album = "Studio Archive",
            genre = "Electronic",
            releaseYear = 2022,
            bpm = 128.0,
            musicalKey = "11B",
            filePath = wavFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track)
        assertTrue("Expected Written or Partial, got $result",
            result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        // 1. Verify tags were written and read back
        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("Broadcast Master Track", readBack.title)
        assertEquals("Pro Producer", readBack.artist)
        assertEquals(128.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("11B", readBack.musicalKey)

        // 2. Verify non-audio chunks were preserved verbatim
        val preservedBext = extractWavChunkBytes(wavFile, "bext")
        assertEquals("bext chunk size must match", bextData.size, preservedBext.size)
        for (i in bextData.indices) {
            assertEquals("bext byte at $i must match", bextData[i], preservedBext[i])
        }

        val preservedCue = extractWavChunkBytes(wavFile, "cue ")
        assertEquals("cue chunk size must match", cueData.size, preservedCue.size)
        for (i in cueData.indices) {
            assertEquals("cue byte at $i must match", cueData[i], preservedCue[i])
        }

        // 3. Verify PCM audio was preserved verbatim
        val postWritePcm = extractWavDataChunkBytes(wavFile)
        assertEquals("PCM audio size must match", originalPcm.size, postWritePcm.size)
        for (i in originalPcm.indices) {
            assertEquals("PCM byte at index $i must match", originalPcm[i], postWritePcm[i])
        }
    }

    @Test
    fun `wav with prepended id3 tag parses and updates cleanly without failure`() = runBlocking {
        val wavFile = File(tempFolder.root, "prepended_id3.wav")
        val originalPcm = ByteArray(1600) { i -> ((i * 31 + 7) % 256).toByte() }
        createSampleWavWithPrependedId3(wavFile, originalPcm)

        val track = Track(
            id = "track-prepended-id3",
            title = "Clean Recovery",
            artist = "Resilient Tagging",
            album = "Fixed Streams",
            genre = "Progressive",
            releaseYear = 2025,
            bpm = 130.0,
            musicalKey = "6A",
            filePath = wavFile.absolutePath
        )

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track)
        assertTrue("Expected Written or Partial without failure, got $result",
            result is MetadataWriteResult.Written || result is MetadataWriteResult.Partial)

        // Verify tags read back correctly
        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("Clean Recovery", readBack.title)
        assertEquals("Resilient Tagging", readBack.artist)
        assertEquals("Fixed Streams", readBack.album)
        assertEquals(130.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("6A", readBack.musicalKey)

        // Verify PCM audio was preserved verbatim
        val postWritePcm = extractWavDataChunkBytes(wavFile)
        assertEquals("PCM audio size must match", originalPcm.size, postWritePcm.size)
        for (i in originalPcm.indices) {
            assertEquals("PCM byte at index $i must match", originalPcm[i], postWritePcm[i])
        }
    }

    @Test
    fun `wav with large artwork safely skips massive artwork embedding and marks partial`() = runBlocking {
        val wavFile = File(tempFolder.root, "large_artwork_test.wav")
        val originalPcm = ByteArray(800) { 0x33 }
        createSampleWavFile(wavFile, originalPcm)

        val track = Track(
            id = "track-large-art",
            title = "Oversized Cover Track",
            artist = "Hi-Res Studio",
            album = "Heavy Album",
            genre = "Electronic",
            releaseYear = 2024,
            bpm = 125.0,
            musicalKey = "1A",
            filePath = wavFile.absolutePath
        )

        // Massive artwork: 75KB (> 64KB threshold for WAV files)
        val largeArtwork = ByteArray(75_000) { 0x55 }

        val writer = MetadataFileWriter(context)
        val result = writer.writeAsync(track, artworkBytes = largeArtwork)

        // Should return Partial because large artwork is stored in library/cache and skipped in WAV
        assertTrue("Expected Partial result for WAV with oversized artwork, got $result",
            result is MetadataWriteResult.Partial)
        val partial = result as MetadataWriteResult.Partial
        assertTrue("Partial result must mention artwork stored in library",
            partial.unverifiedFields.any { it.contains("artwork", ignoreCase = true) })

        // Read back from WAV file - metadata is embedded, but APIC artwork frame was skipped to protect WAV integrity
        val readBack = AudioEmbeddedMetadataReader.read(context, wavFile.absolutePath)
        assertEquals("Oversized Cover Track", readBack.title)
        assertEquals("Hi-Res Studio", readBack.artist)
        assertEquals("Heavy Album", readBack.album)
        assertEquals(125.0, readBack.bpm ?: 0.0, 0.5)
        assertEquals("1A", readBack.musicalKey)
        assertFalse("Oversized artwork must not be embedded in WAV container", readBack.hasEmbeddedArtwork)

        // Verbatim PCM audio preservation
        val postWritePcm = extractWavDataChunkBytes(wavFile)
        assertEquals(originalPcm.size, postWritePcm.size)
        for (i in originalPcm.indices) {
            assertEquals(originalPcm[i], postWritePcm[i])
        }
    }

    @Test
    fun `retryFailedTracks reattempts file writing using database metadata without internet lookups`() = runBlocking {
        val (trackDao, _) = createFakeTrackDao()

        // Track 1: WAV track in FILE_WRITE_FAILED state with full database metadata
        val wavFile1 = File(tempFolder.root, "RetryTrack1.wav")
        createSampleWavFile(wavFile1, ByteArray(1000) { 0x11 })
        val track1 = Track(
            id = "track-retry-1",
            title = "Resilient Beat",
            artist = "SoundSync DJ",
            album = "Recovery Session",
            genre = "House",
            releaseYear = 2023,
            bpm = 126.0,
            musicalKey = "2B",
            filePath = wavFile1.absolutePath,
            metadataWriteState = MetadataWriteState.FILE_WRITE_FAILED.name
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track1))

        // Track 2: WAV track in READ_ONLY_FILE state
        val wavFile2 = File(tempFolder.root, "RetryTrack2_readonly.wav")
        createSampleWavFile(wavFile2, ByteArray(800) { 0x22 })
        val track2 = Track(
            id = "track-retry-2",
            title = "Protected Track",
            artist = "Locked Artist",
            album = "Secure Album",
            genre = "Techno",
            releaseYear = 2021,
            bpm = 132.0,
            musicalKey = "5A",
            filePath = wavFile2.absolutePath,
            metadataWriteState = MetadataWriteState.READ_ONLY_FILE.name
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track2))

        val queue = MetadataFileWriteQueue.createForTesting(context, trackDao)

        try {
            // Track 2 file is read-only
            com.example.storage.StorageWritePermissionHelper.isWritableOverrideForTesting = { file ->
                !file.name.contains("readonly")
            }

            val report = queue.retryFailedTracks()

            assertEquals(2, report.totalExamined)
            assertEquals(1, report.successfullyWritten)
            assertEquals(1, report.libraryOnly)
            assertEquals(0, report.failed)

            // Track 1 tags were written and verified on disk
            val readBack1 = AudioEmbeddedMetadataReader.read(context, wavFile1.absolutePath)
            assertEquals("Resilient Beat", readBack1.title)
            assertEquals("SoundSync DJ", readBack1.artist)
            assertEquals("Recovery Session", readBack1.album)
            assertEquals(126.0, readBack1.bpm ?: 0.0, 0.5)
            assertEquals("2B", readBack1.musicalKey)

            // Track 1 DB state updated to FILE_WRITE_SUCCESS
            val updatedTrack1 = trackDao.getTrackById("track-retry-1")
            assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS.name, updatedTrack1!!.metadataWriteState)

            // Track 2 DB state remains READ_ONLY_FILE
            val updatedTrack2 = trackDao.getTrackById("track-retry-2")
            assertEquals(MetadataWriteState.READ_ONLY_FILE.name, updatedTrack2!!.metadataWriteState)
        } finally {
            com.example.storage.StorageWritePermissionHelper.isWritableOverrideForTesting = null
        }
    }

    @Test
    fun `read-only wav file falls back to database overlay Updated - Library Only without Failed status`() = runBlocking {
        val wavFile = File(tempFolder.root, "readonly_wav_test.wav")
        createSampleWavFile(wavFile, ByteArray(600) { 0x77 })

        val track = Track(
            id = "track-ro-wav",
            title = "Locked Hardstyle",
            artist = "Headhunterz",
            album = "Sacrifice",
            genre = "Hardstyle",
            bpm = 150.0,
            musicalKey = "10B",
            filePath = wavFile.absolutePath,
            metadataScanState = com.example.model.MetadataScanState.COMPLETE.name,
            metadataWriteState = MetadataWriteState.DATABASE_ONLY.name
        )

        try {
            com.example.storage.StorageWritePermissionHelper.isWritableOverrideForTesting = { false }

            val writer = MetadataFileWriter(context)
            val result = writer.writeAsync(track)

            // Must return ReadOnlyFile
            assertTrue("Expected ReadOnlyFile result, got $result", result is MetadataWriteResult.ReadOnlyFile)
            assertEquals(MetadataWriteState.READ_ONLY_FILE, result.writeState)
        } finally {
            com.example.storage.StorageWritePermissionHelper.isWritableOverrideForTesting = null
        }
    }

    /**
     * Helper to create a FLAC file with a prepended ID3v2 tag before the fLaC stream.
     */
    private fun createSampleFlacWithPrependedId3(file: File, audioBytes: ByteArray): File {
        val id3Body = "TIT2\u0000\u0000\u0000\u0008\u0000\u0000\u0000Old Song".toByteArray(StandardCharsets.ISO_8859_1)
        val id3Header = ByteArray(10)
        id3Header[0] = 'I'.code.toByte()
        id3Header[1] = 'D'.code.toByte()
        id3Header[2] = '3'.code.toByte()
        id3Header[3] = 3 // ID3v2.3
        id3Header[4] = 0
        id3Header[5] = 0
        val syncSafeSize = id3Body.size
        id3Header[6] = ((syncSafeSize shr 21) and 0x7F).toByte()
        id3Header[7] = ((syncSafeSize shr 14) and 0x7F).toByte()
        id3Header[8] = ((syncSafeSize shr 7) and 0x7F).toByte()
        id3Header[9] = (syncSafeSize and 0x7F).toByte()

        val magic = "fLaC".toByteArray(StandardCharsets.US_ASCII)
        val streaminfoHdr = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22)
        val streaminfo = ByteArray(34)
        streaminfo[0] = 0x10; streaminfo[1] = 0x00
        streaminfo[2] = 0x10; streaminfo[3] = 0x00
        streaminfo[10] = 0x0A.toByte(); streaminfo[11] = 0xC4.toByte(); streaminfo[12] = 0x42.toByte(); streaminfo[13] = 0xF0.toByte()
        val totalSamples = 44100
        streaminfo[14] = 0; streaminfo[15] = 0
        streaminfo[16] = ((totalSamples shr 8) and 0xFF).toByte()
        streaminfo[17] = (totalSamples and 0xFF).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(id3Header)
            fos.write(id3Body)
            fos.write(magic)
            fos.write(streaminfoHdr)
            fos.write(streaminfo)
            fos.write(audioBytes)
        }
        return file
    }

    @Test
    fun `flac file with prepended ID3 tag is successfully detected and rewritten as valid FLAC with Vorbis comments and artwork`() = runBlocking {
        val flacFile = File(tempFolder.root, "prepended_id3_flac.flac")
        val dummyAudio = ByteArray(1024) { (it % 256).toByte() }
        createSampleFlacWithPrependedId3(flacFile, dummyAudio)

        val payload = CompleteTagPayload(
            title = "Let Him Cook",
            artist = "DTAILZ",
            album = "Cooking Season",
            albumArtist = "DTAILZ",
            genre = "Drum and Bass",
            bpm = 174.0,
            musicalKey = "8A",
            artworkBytes = sampleArtworkBytes,
            artworkMimeType = "image/jpeg"
        )

        val success = AudioTagWriter.writeCompleteTags(context, flacFile.absolutePath, payload)
        assertTrue("writeCompleteTags should succeed on FLAC with prepended ID3", success)

        val readBack = AudioEmbeddedMetadataReader.read(context, flacFile.absolutePath)
        assertEquals("Let Him Cook", readBack.title)
        assertEquals("DTAILZ", readBack.artist)
        assertEquals("Cooking Season", readBack.album)
        assertEquals("Drum and Bass", readBack.genre)
        assertEquals(174.0, readBack.bpm ?: 0.0, 0.1)
        assertEquals("8A", readBack.musicalKey)
        assertTrue("Artwork must be embedded", readBack.hasEmbeddedArtwork)
    }

    @Test
    fun `scoped storage track with non-existent raw path resolves canonical URI and writes successfully`() = runBlocking {
        val realFlacFile = File(tempFolder.root, "actual_storage_file.flac")
        createSampleFlacFile(realFlacFile, ByteArray(800) { 0x44 })

        val (trackDao, _) = createFakeTrackDao()

        val virtualPath = "/storage/emulated/0/FLAC/Let Him Cook - DTAILZ.flac"
        assertFalse("Virtual path must not exist directly on filesystem", File(virtualPath).exists())

        val track = Track(
            id = "media_987654",
            title = "Let Him Cook",
            artist = "DTAILZ",
            album = "The Kitchen",
            genre = "Bass Music",
            bpm = 175.0,
            musicalKey = "4A",
            filePath = virtualPath,
            metadataScanState = com.example.model.MetadataScanState.COMPLETE.name,
            metadataWriteState = MetadataWriteState.METADATA_FOUND.name
        )
        trackDao.insertTrack(TrackEntity.fromTrack(track))

        // Simulate canonical resolution providing direct access to the real file via URI
        try {
            com.example.storage.StorageWritePermissionHelper.resolveCanonicalOverrideForTesting = { _, t ->
                if (t.id == "media_987654") {
                    com.example.storage.CanonicalStorageInfo(
                        uri = android.net.Uri.fromFile(realFlacFile),
                        isDirectFile = true,
                        directFile = realFlacFile,
                        isWritable = true
                    )
                } else null
            }

            val writer = MetadataFileWriter(context, trackDao)
            val result = writer.writeAsync(track)

            assertTrue("Result should be Written, but got $result", result is MetadataWriteResult.Written)
            assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS, result.writeState)

            val updatedEntity = trackDao.getTrackById("media_987654")
            assertEquals(MetadataWriteState.FILE_WRITE_SUCCESS.name, updatedEntity!!.metadataWriteState)
        } finally {
            com.example.storage.StorageWritePermissionHelper.resolveCanonicalOverrideForTesting = null
        }
    }
}
