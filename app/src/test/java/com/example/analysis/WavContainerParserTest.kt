package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class WavContainerParserTest {

    @Test
    fun testParseFailingWavWithOddId3ChunkBeforeData() {
        val testFile = File("/root/.gemini/antigravity-cli/brain/e5468edc-7642-4ff0-af84-9fe070c9ebf9/scratch/forensics/Act_of_Rage_-_Kamikaze_TEST_COPY.wav")
        if (!testFile.exists()) return

        val info = FileInputStream(testFile).use { WavContainerParser.parseStream(it) }
        assertTrue("WAV with odd id3 chunk must be parsed as valid: ${info.errorMessage}", info.isValid)
        assertEquals("AudioFormat must be 1 (PCM)", 1, info.audioFormat)
        assertEquals("Channels must be 2", 2, info.numChannels)
        assertEquals("SampleRate must be 44100", 44100, info.sampleRate)
        assertEquals("BitsPerSample must be 16", 16, info.bitsPerSample)
        assertEquals("Data size must be 43394400 bytes", 43394400L, info.dataSize)
        assertEquals("Data offset must be 1514 bytes (0x5ea)", 1514L, info.dataOffset)
        assertEquals("Duration must be ~246000 ms (4:06)", 246000L, info.durationMs)
        assertEquals("Bitrate must be 1411 kbps", 1411, info.bitrateKbps)
    }

    @Test
    fun testWavPcmReaderReadsBitExactSamples() {
        val testFile = File("/root/.gemini/antigravity-cli/brain/e5468edc-7642-4ff0-af84-9fe070c9ebf9/scratch/forensics/Act_of_Rage_-_Kamikaze_TEST_COPY.wav")
        if (!testFile.exists()) return

        val info = FileInputStream(testFile).use { WavContainerParser.parseStream(it) }
        assertTrue(info.isValid)

        WavPcmReader(null, testFile.absolutePath, info).use { reader ->
            val stereoBuf = ShortArray(20)
            val framesRead = reader.readStereoFrames(stereoBuf, 10)
            assertEquals("Must read exactly 10 frames", 10, framesRead)

            val expected = shortArrayOf(
                -24, -24, 572, 571, 310, 310, -2971, -2966, -912, -911,
                6328, 6315, 1555, 1552, -5937, -5926, -2781, -2776, -98, -98
            )
            for (i in expected.indices) {
                assertEquals("Sample $i must match bit-exact", expected[i], stereoBuf[i])
            }
        }
    }

    @Test
    fun testWavPcmReaderSeekAndRead() {
        val testFile = File("/root/.gemini/antigravity-cli/brain/e5468edc-7642-4ff0-af84-9fe070c9ebf9/scratch/forensics/Act_of_Rage_-_Kamikaze_TEST_COPY.wav")
        if (!testFile.exists()) return

        val info = FileInputStream(testFile).use { WavContainerParser.parseStream(it) }
        assertTrue(info.isValid)

        WavPcmReader(null, testFile.absolutePath, info).use { reader ->
            reader.seekToMs(10000L) // 10 seconds
            val stereoBuf = ShortArray(512)
            val framesRead = reader.readStereoFrames(stereoBuf, 256)
            assertEquals(256, framesRead)
            var hasNonZero = false
            for (s in stereoBuf) {
                if (s.toInt() != 0) {
                    hasNonZero = true
                    break
                }
            }
            assertTrue("Audio at 10 seconds should contain non-zero audio samples", hasNonZero)
        }
    }
}
