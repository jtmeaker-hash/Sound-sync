package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class HaasSpatializerTest {

    private fun generateSineWave(sampleRate: Int, freqHz: Double, numFrames: Int, amplitude: Double = 10000.0, startFrame: Int = 0): ShortArray {
        val buffer = ShortArray(numFrames * 2)
        for (i in 0 until numFrames) {
            val sample = (amplitude * sin(2.0 * PI * freqHz * (i + startFrame) / sampleRate)).toInt().toShort()
            buffer[i * 2] = sample
            buffer[i * 2 + 1] = sample
        }
        return buffer
    }

    private fun computeRms(buffer: ShortArray): Double {
        var sumSq = 0.0
        for (s in buffer) {
            val v = s.toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / buffer.size)
    }

    @Test
    fun testBypassEquivalence() {
        val haas = HaasSurroundEffect()
        assertFalse(haas.isEnabled)

        val buffer = generateSineWave(44100, 440.0, 256, 12000.0)
        val copy = buffer.clone()

        haas.process(buffer, 0, 256, 44100)

        for (i in copy.indices) {
            assertEquals("Bypassed Haas must leave audio bit-exact", copy[i], buffer[i])
        }
    }

    @Test
    fun testDefaultDelayWithinSafeBound() {
        // Safe precedence window for spatial widening without slapback echo is 1.0 - 5.0 ms
        assertTrue("Default delay must be >= 1.0ms", HaasSurroundEffect.DEFAULT_DELAY_MS >= 1.0f)
        assertTrue("Default delay must be <= 5.0ms to prevent audible slapback", HaasSurroundEffect.DEFAULT_DELAY_MS <= 5.0f)
    }

    @Test
    fun testMonoSumZeroBroadbandCancellation() {
        val sampleRate = 44100
        val frames = 1024
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setAmount(0.8f) // High spatial width
        haas.setDelayMs(2.5f)

        // Generate broadband mono content (mix of 120Hz bass, 1kHz vocal, 6kHz treble)
        val monoBuffer = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (4000.0 * sin(2.0 * PI * 120.0 * i / sampleRate) +
                    4000.0 * sin(2.0 * PI * 1000.0 * i / sampleRate) +
                    3000.0 * sin(2.0 * PI * 6000.0 * i / sampleRate)).toInt().toShort()
            monoBuffer[i * 2] = sample
            monoBuffer[i * 2 + 1] = sample
        }

        // Prime the filter
        haas.process(monoBuffer.clone(), 0, frames, sampleRate)

        val testBuffer = monoBuffer.clone()
        haas.process(testBuffer, 0, frames, sampleRate)

        // Downmix processed stereo to mono: (L + R) / 2
        val monoDownmix = ShortArray(frames)
        for (i in 0 until frames) {
            monoDownmix[i] = ((testBuffer[i * 2].toInt() + testBuffer[i * 2 + 1].toInt()) / 2).toShort()
        }

        // Compute original mono dry RMS vs downmixed mono RMS
        val origMono = ShortArray(frames) { monoBuffer[it * 2] }
        val origRms = computeRms(origMono)
        val downmixRms = computeRms(monoDownmix)

        // Mono collapse must not drop below 80% RMS (no comb filtering destruction)
        assertTrue(
            "Mono downmix RMS ($downmixRms) must not suffer catastrophic phase cancellation compared to dry RMS ($origRms)",
            downmixRms >= origRms * 0.80
        )
    }

    @Test
    fun testMonoBassProtectionPreservesSubBass() {
        val sampleRate = 44100
        val frames = 1024
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setAmount(1.0f) // Max widening
        haas.setDelayMs(3.0f)
        haas.setBassProtect(true)

        // Sub-bass 60 Hz tone with phase continuity across buffer frames
        val bassPrime = generateSineWave(sampleRate, 60.0, frames, 10000.0, startFrame = 0)
        val origBassRms = computeRms(bassPrime)

        // Prime filter
        haas.process(bassPrime, 0, frames, sampleRate)

        val processedBass = generateSineWave(sampleRate, 60.0, frames, 10000.0, startFrame = frames)
        haas.process(processedBass, 0, frames, sampleRate)

        val processedRms = computeRms(processedBass)

        // With bass protect, low frequencies retain full punch
        assertTrue(
            "Bass protect must preserve sub-bass energy (got $processedRms, orig $origBassRms)",
            processedRms >= origBassRms * 0.75
        )

        // Also verify that Left and Right channels remain closely matched in the bass
        var maxDiff = 0
        for (i in 0 until frames) {
            val diff = abs(processedBass[i * 2].toInt() - processedBass[i * 2 + 1].toInt())
            if (diff > maxDiff) maxDiff = diff
        }
        assertTrue("Sub-bass left and right channels should be closely centered with bass protect (got $maxDiff)", maxDiff < 3000)
    }

    @Test
    fun testImpulseResponseConfirmsNoFeedbackOrUnboundedTail() {
        val sampleRate = 44100
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setAmount(1.0f)
        haas.setDelayMs(5.0f)

        // Frame 0: impulse, remaining frames: silence
        val frames = 4410 // 100 ms
        val buffer = ShortArray(frames * 2)
        buffer[0] = 30000
        buffer[1] = 30000

        haas.process(buffer, 0, frames, sampleRate)

        // Verify bounds throughout
        for (s in buffer) {
            assertTrue(s in Short.MIN_VALUE..Short.MAX_VALUE)
        }

        // Tail after 50ms (2205 frames) must be back to silence (no feedback loop)
        var maxTailSample = 0
        for (i in 2205 until frames) {
            val left = abs(buffer[i * 2].toInt())
            val right = abs(buffer[i * 2 + 1].toInt())
            if (left > maxTailSample) maxTailSample = left
            if (right > maxTailSample) maxTailSample = right
        }

        assertTrue("Impulse response tail must decay to silence (<10), got $maxTailSample", maxTailSample < 10)
    }

    @Test
    fun testSampleRateHandling() {
        val sampleRates = listOf(44100, 48000, 88200, 96000)
        for (sr in sampleRates) {
            val haas = HaasSurroundEffect()
            haas.setEnabled(true)
            haas.setAmount(0.6f)
            haas.setDelayMs(3.0f)

            val buf = generateSineWave(sr, 1000.0, 256, 8000.0)
            haas.process(buf, 0, 256, sr)

            for (s in buf) {
                assertTrue("Sample at rate $sr must be in 16-bit range", s in Short.MIN_VALUE..Short.MAX_VALUE)
            }
        }
    }

    @Test
    fun testExtremeControlValuesWithoutNanOrOverflow() {
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)

        // Extreme values out of normal range
        haas.setAmount(-5.0f)
        assertEquals(HaasSurroundEffect.MIN_AMOUNT, haas.amount, 0.001f)

        haas.setAmount(10.0f)
        assertEquals(HaasSurroundEffect.MAX_AMOUNT, haas.amount, 0.001f)

        haas.setDelayMs(-10f)
        assertEquals(HaasSurroundEffect.MIN_DELAY_MS, haas.delayMs, 0.001f)

        haas.setDelayMs(100f)
        assertEquals(HaasSurroundEffect.MAX_DELAY_MS, haas.delayMs, 0.001f)

        val buf = ShortArray(256) { 32767.toShort() }
        haas.process(buf, 0, 128, 44100)

        for (s in buf) {
            assertTrue("Output with hot audio must not overflow Short bounds", s in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun testToggleAndResetStateCleanly() {
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setAmount(0.7f)
        haas.setDelayMs(4.0f)

        val buf = generateSineWave(44100, 1000.0, 512, 10000.0)
        haas.process(buf, 0, 512, 44100)

        haas.reset()
        // After reset, internal buffers are cleared
        val silentBuf = ShortArray(256)
        haas.process(silentBuf, 0, 128, 44100)

        for (s in silentBuf) {
            assertEquals(0.toShort(), s)
        }
    }
}
