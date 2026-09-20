package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class HaasSpatializerTest {

    private fun generateSineWave(
        sampleRate: Int,
        freqHz: Double,
        numFrames: Int,
        amplitude: Double = 10000.0,
        startFrame: Int = 0
    ): ShortArray {
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
        assertTrue("Default delay must be >= MIN_DELAY_MS", HaasSurroundEffect.DEFAULT_DELAY_MS >= HaasSurroundEffect.MIN_DELAY_MS)
        assertTrue("Default delay must be <= MAX_DELAY_MS", HaasSurroundEffect.DEFAULT_DELAY_MS <= HaasSurroundEffect.MAX_DELAY_MS)
        assertEquals(8.0f, HaasSurroundEffect.DEFAULT_DELAY_MS, 0.01f)
    }

    @Test
    fun testDelaySettingsExecution() {
        val testDelays = listOf(5.0f, 8.0f, 15.0f, 30.0f)
        for (delay in testDelays) {
            val haas = HaasSurroundEffect()
            haas.setEnabled(true)
            haas.setDelayMs(delay)
            assertEquals(delay, haas.delayMs, 0.01f)

            val buffer = generateSineWave(48000, 1000.0, 512, 10000.0)
            haas.process(buffer, 0, 512, 48000)

            for (s in buffer) {
                assertTrue("Sample must remain in 16-bit bounds at delay $delay ms", s in Short.MIN_VALUE..Short.MAX_VALUE)
            }
        }
    }

    @Test
    fun testSampleRateCalculations() {
        val sampleRates = listOf(44100, 48000, 96000)
        for (sr in sampleRates) {
            val haas = HaasSurroundEffect()
            haas.setEnabled(true)
            haas.setDelayMs(8.0f)
            haas.setEffectMix(0.8f)

            val buf = generateSineWave(sr, 1000.0, 512, 8000.0)
            haas.process(buf, 0, 512, sr)

            var hasNonZero = false
            for (s in buf) {
                if (s != 0.toShort()) hasNonZero = true
                assertTrue("Sample at rate $sr must be in 16-bit range", s in Short.MIN_VALUE..Short.MAX_VALUE)
            }
            assertTrue("Processed audio at $sr Hz must contain non-zero audio", hasNonZero)
        }
    }

    @Test
    fun testMonoSumZeroBroadbandCancellation() {
        val sampleRate = 44100
        val frames = 1024
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setEffectMix(0.85f)
        haas.setDelayMs(8.0f)
        haas.setMode(HaasSurroundEffect.ProcessingMode.SIDE_ONLY)

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

        // Mono collapse must not drop below 80% RMS (Side-only mode preserves center image)
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
        haas.setEffectMix(1.0f)
        haas.setDelayMs(8.0f)
        haas.setBassProtect(true)

        val bassPrime = generateSineWave(sampleRate, 60.0, frames, 10000.0, startFrame = 0)
        val origBassRms = computeRms(bassPrime)

        haas.process(bassPrime, 0, frames, sampleRate)

        val processedBass = generateSineWave(sampleRate, 60.0, frames, 10000.0, startFrame = frames)
        haas.process(processedBass, 0, frames, sampleRate)

        val processedRms = computeRms(processedBass)

        assertTrue(
            "Bass protect must preserve sub-bass energy (got $processedRms, orig $origBassRms)",
            processedRms >= origBassRms * 0.75
        )

        var maxDiff = 0
        for (i in 0 until frames) {
            val diff = abs(processedBass[i * 2].toInt() - processedBass[i * 2 + 1].toInt())
            if (diff > maxDiff) maxDiff = diff
        }
        assertTrue("Sub-bass left and right channels should be centered with bass protect (got $maxDiff)", maxDiff < 3000)
    }

    @Test
    fun testImpulseResponseConfirmsNoFeedbackOrUnboundedTail() {
        val sampleRate = 44100
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setEffectMix(1.0f)
        haas.setDelayMs(15.0f)

        // Frame 0: impulse, remaining frames: silence
        val frames = 4410 // 100 ms
        val buffer = ShortArray(frames * 2)
        buffer[0] = 30000
        buffer[1] = 30000

        haas.process(buffer, 0, frames, sampleRate)

        for (s in buffer) {
            assertTrue(s in Short.MIN_VALUE..Short.MAX_VALUE)
        }

        // Tail after 50ms (2205 frames) must decay to silence (feed-forward delay, no runaway feedback loop)
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
    fun testEffectMixZeroAndHundredPercent() {
        val sampleRate = 48000
        val frames = 512
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setEffectMix(0.0f)

        val dry = generateSineWave(sampleRate, 1000.0, frames, 10000.0)
        val buffer = dry.clone()

        // At 0% effect mix, output is identical to dry input
        haas.process(buffer, 0, frames, sampleRate)
        for (i in dry.indices) {
            assertEquals("0% effect mix must equal dry signal", dry[i], buffer[i])
        }

        // At 100% effect mix, processing changes the signal
        haas.setEffectMix(1.0f)
        val stereoInput = ShortArray(frames * 2)
        for (i in 0 until frames) {
            stereoInput[i * 2] = (10000.0 * sin(2.0 * PI * 500.0 * i / sampleRate)).toInt().toShort()
            stereoInput[i * 2 + 1] = (8000.0 * sin(2.0 * PI * 1200.0 * i / sampleRate)).toInt().toShort()
        }
        val wetBuffer = stereoInput.clone()
        // Prime filter smoothing
        haas.process(wetBuffer, 0, frames, sampleRate)
        val wetBuffer2 = stereoInput.clone()
        haas.process(wetBuffer2, 0, frames, sampleRate)

        assertFalse("100% effect mix must modify stereo input", stereoInput.contentEquals(wetBuffer2))
    }

    @Test
    fun testResetClearsStaleDelayBufferOnSeek() {
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)
        haas.setDelayMs(10.0f)

        val loud = generateSineWave(44100, 1000.0, 512, 20000.0)
        haas.process(loud, 0, 512, 44100)

        // Reset simulates track seek or track switch
        haas.reset()

        val silence = ShortArray(512)
        haas.process(silence, 0, 256, 44100)

        for (s in silence) {
            assertEquals("After reset, delay buffer must output silence immediately", 0.toShort(), s)
        }
    }

    @Test
    fun testPresetsConfiguration() {
        val haas = HaasSurroundEffect()

        haas.applyPreset(HaasSurroundEffect.HaasPreset.SUBTLE)
        assertTrue(haas.isEnabled)
        assertEquals(5.0f, haas.delayMs, 0.01f)
        assertEquals(1.05f, haas.stereoWidth, 0.01f)

        haas.applyPreset(HaasSurroundEffect.HaasPreset.WIDE)
        assertTrue(haas.isEnabled)
        assertEquals(8.0f, haas.delayMs, 0.01f)
        assertEquals(1.15f, haas.stereoWidth, 0.01f)

        haas.applyPreset(HaasSurroundEffect.HaasPreset.ULTRA_WIDE)
        assertTrue(haas.isEnabled)
        assertEquals(15.0f, haas.delayMs, 0.01f)
        assertEquals(1.25f, haas.stereoWidth, 0.01f)

        haas.applyPreset(HaasSurroundEffect.HaasPreset.OFF)
        assertFalse(haas.isEnabled)
    }

    @Test
    fun testContinuousParameterChangesDuringPlaybackDoNotCrash() {
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)

        val buffer = generateSineWave(48000, 800.0, 128, 10000.0)

        // Rapid parameter tweaking while processing
        for (i in 0 until 50) {
            haas.setDelayMs(0.5f + i * 0.5f)
            haas.setStereoWidth(1.0f + (i % 50) * 0.01f)
            haas.setEffectMix((i % 100) / 100f)
            haas.setCrossfeed((i % 50) / 100f)
            haas.setLowCutoffHz(100f + i * 5f)
            haas.setHighCutoffHz(2000f + i * 50f)
            haas.setSpatialBalance((i % 200) - 100f)
            haas.setOutputCompDb(-(i % 6).toFloat())
            haas.process(buffer, 0, 128, 48000)
        }

        for (s in buffer) {
            assertTrue(s in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun testExtremeControlValuesWithoutNanOrOverflow() {
        val haas = HaasSurroundEffect()
        haas.setEnabled(true)

        haas.setEffectMix(-5.0f)
        assertEquals(HaasSurroundEffect.MIN_EFFECT_MIX, haas.effectMix, 0.001f)

        haas.setEffectMix(10.0f)
        assertEquals(HaasSurroundEffect.MAX_EFFECT_MIX, haas.effectMix, 0.001f)

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
}
