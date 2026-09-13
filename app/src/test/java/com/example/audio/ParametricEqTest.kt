package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ParametricEqTest {

    private fun generateSineWave(sampleRate: Int, freqHz: Double, numFrames: Int, amplitude: Double = 10000.0): ShortArray {
        val buffer = ShortArray(numFrames * 2)
        for (i in 0 until numFrames) {
            val sample = (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toInt().toShort()
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
    fun testZeroDbNeutralResponseIsBitExactUnity() {
        val sampleRate = 44100
        val eq = ParametricEq(sampleRate)
        eq.resetToFlat()

        val input = generateSineWave(sampleRate, 440.0, 512, 12000.0)
        val copy = input.clone()

        eq.processStereo(input, 0, 512)

        for (i in copy.indices) {
            assertEquals("Sample at index $i must remain bit-exact identical when neutral", copy[i], input[i])
        }
    }

    @Test
    fun testBypassEquivalence() {
        val sampleRate = 44100
        val eq = ParametricEq(sampleRate)

        // Set huge boost
        eq.updateBand(0, 100.0, 18.0, 1.0, true)
        eq.preampDb = 6.0
        eq.isEnabled = false // Bypassed

        val input = generateSineWave(sampleRate, 100.0, 256, 8000.0)
        val copy = input.clone()

        eq.processStereo(input, 0, 256)

        for (i in copy.indices) {
            assertEquals("Bypassed EQ must leave dry path bit-exact", copy[i], input[i])
        }
    }

    @Test
    fun testPeakingBoostAndCut() {
        val sampleRate = 44100
        val f0 = 1000.0
        val frames = 1024

        // 1. Flat reference
        val flatEq = ParametricEq(sampleRate)
        flatEq.resetToFlat()
        val flatBuf = generateSineWave(sampleRate, f0, frames, 5000.0)
        flatEq.processStereo(flatBuf, 0, frames)
        val flatRms = computeRms(flatBuf)

        // 2. Boost +6dB at 1000 Hz
        val boostEq = ParametricEq(sampleRate)
        boostEq.resetToFlat()
        boostEq.updateBand(3, f0, 6.0, 2.0, true)
        val boostBuf = generateSineWave(sampleRate, f0, frames, 5000.0)
        // Prime filter
        boostEq.processStereo(boostBuf, 0, frames)
        val boostRms = computeRms(boostBuf)

        // 3. Cut -6dB at 1000 Hz
        val cutEq = ParametricEq(sampleRate)
        cutEq.resetToFlat()
        cutEq.updateBand(3, f0, -6.0, 2.0, true)
        val cutBuf = generateSineWave(sampleRate, f0, frames, 5000.0)
        cutEq.processStereo(cutBuf, 0, frames)
        val cutRms = computeRms(cutBuf)

        assertTrue("Boosted RMS ($boostRms) must be significantly higher than Flat RMS ($flatRms)", boostRms > flatRms * 1.3)
        assertTrue("Cut RMS ($cutRms) must be significantly lower than Flat RMS ($flatRms)", cutRms < flatRms * 0.75)
    }

    @Test
    fun testLowShelfAndHighShelfBehavior() {
        val sampleRate = 44100
        val frames = 1024

        // Test Low Shelf: boosts 60 Hz tone, ignores 10 kHz tone
        val lowTone = generateSineWave(sampleRate, 60.0, frames, 4000.0)
        val highTone = generateSineWave(sampleRate, 10000.0, frames, 4000.0)

        val lowFlatRms = computeRms(lowTone)
        val highFlatRms = computeRms(highTone)

        val shelfEq = ParametricEq(sampleRate)
        shelfEq.resetToFlat()
        shelfEq.updateBandType(0, EqFilterType.LOW_SHELF)
        shelfEq.updateBand(0, 100.0, 8.0, 0.71, true)

        val lowBuf = lowTone.clone()
        val highBuf = highTone.clone()

        shelfEq.processStereo(lowBuf, 0, frames)
        shelfEq.processStereo(highBuf, 0, frames)

        val lowProcessedRms = computeRms(lowBuf)
        val highProcessedRms = computeRms(highBuf)

        assertTrue("Low shelf must boost 60 Hz tone", lowProcessedRms > lowFlatRms * 1.8)
        assertTrue("Low shelf must not significantly alter 10 kHz tone", abs(highProcessedRms - highFlatRms) < highFlatRms * 0.15)
    }

    @Test
    fun testHighPassLowPassAndNotch() {
        val sampleRate = 44100
        val frames = 1024

        // High pass at 1000 Hz against 100 Hz tone
        val hpEq = ParametricEq(sampleRate)
        hpEq.resetToFlat()
        hpEq.updateBandType(0, EqFilterType.HIGH_PASS, immediate = true)
        hpEq.updateBand(0, 1000.0, 0.0, 0.71, true, immediate = true)

        val lowTone = generateSineWave(sampleRate, 100.0, frames, 10000.0)
        val originalRms = computeRms(lowTone)
        hpEq.processStereo(lowTone, 0, frames)
        val hpRms = computeRms(lowTone)

        assertTrue("High-pass at 1kHz must heavily attenuate 100 Hz tone", hpRms < originalRms * 0.05)

        // Low pass at 300 Hz against 5000 Hz tone
        val lpEq = ParametricEq(sampleRate)
        lpEq.resetToFlat()
        lpEq.updateBandType(0, EqFilterType.LOW_PASS, immediate = true)
        lpEq.updateBand(0, 300.0, 0.0, 0.71, true, immediate = true)

        val highTone = generateSineWave(sampleRate, 5000.0, frames, 10000.0)
        val highOrigRms = computeRms(highTone)
        lpEq.processStereo(highTone, 0, frames)
        val lpRms = computeRms(highTone)

        assertTrue("Low-pass at 300 Hz must heavily attenuate 5kHz tone", lpRms < highOrigRms * 0.05)

        // Notch at 1000 Hz against 1000 Hz tone vs 200 Hz tone
        val notchEq = ParametricEq(sampleRate)
        notchEq.resetToFlat()
        notchEq.updateBandType(0, EqFilterType.NOTCH, immediate = true)
        notchEq.updateBand(0, 1000.0, 0.0, 5.0, true, immediate = true)

        val centerTone = generateSineWave(sampleRate, 1000.0, frames, 10000.0)
        val offTone = generateSineWave(sampleRate, 200.0, frames, 10000.0)

        val centerOrigRms = computeRms(centerTone)
        val offOrigRms = computeRms(offTone)

        notchEq.processStereo(centerTone, 0, frames)
        notchEq.processStereo(offTone, 0, frames)

        assertTrue("Notch at 1000 Hz must attenuate 1000 Hz tone by >15dB", computeRms(centerTone) < centerOrigRms * 0.2)
        assertTrue("Notch at 1000 Hz with Q=5 must leave 200 Hz tone almost untouched", abs(computeRms(offTone) - offOrigRms) < offOrigRms * 0.1)
    }

    @Test
    fun testCoefficientStabilityNearMinMaxFrequencyAndQ() {
        val sampleRates = listOf(44100, 48000, 96000)
        val testFreqs = listOf(20.0, 50.0, 1000.0, 15000.0, 20000.0)
        val testQs = listOf(0.1, 0.707, 1.41, 10.0, 20.0)
        val testGains = listOf(-24.0, -12.0, 0.0, 12.0, 24.0)
        val coefs = DoubleArray(5)

        for (sr in sampleRates) {
            for (type in EqFilterType.values()) {
                for (f in testFreqs) {
                    for (q in testQs) {
                        for (g in testGains) {
                            ParametricEq.calculateBiquadCoefficients(sr, type, f, g, q, coefs)
                            for (c in coefs) {
                                assertFalse("Coefficient must not be NaN for $type at ${f}Hz, Q=$q, G=${g}dB, SR=$sr", c.isNaN())
                                assertFalse("Coefficient must not be Infinite for $type at ${f}Hz, Q=$q, G=${g}dB, SR=$sr", c.isInfinite())
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testSampleRateVariants() {
        val rates = listOf(44100, 48000, 88200, 96000, 192000)
        for (sr in rates) {
            val eq = ParametricEq(sr)
            eq.updateBand(0, 100.0, 3.0, 1.0, true)
            eq.updateBand(4, 5000.0, -4.0, 2.0, true)
            val buf = generateSineWave(sr, 100.0, 256, 10000.0)
            eq.processStereo(buf, 0, 256)
            for (sample in buf) {
                assertTrue(sample in Short.MIN_VALUE..Short.MAX_VALUE)
            }
        }
    }

    @Test
    fun testRapidParameterChangesWithoutClicksOrNaN() {
        val sampleRate = 44100
        val eq = ParametricEq(sampleRate)
        val frames = 128
        val buf = ShortArray(frames * 2)

        for (step in 0 until 50) {
            // Rapidly sweep frequency and gain
            val freq = 20.0 + (step * 350.0)
            val gain = -24.0 + (step * 0.95)
            val q = 0.2 + (step * 0.35)

            eq.updateBand(0, freq, gain, q, true)
            eq.preampDb = (step % 5 - 2).toDouble()

            // Fill buffer with noise/audio
            for (i in buf.indices) {
                buf[i] = ((i * 137) % 20000 - 10000).toShort()
            }

            eq.processStereo(buf, 0, frames)

            for (sample in buf) {
                assertTrue("Sample must remain in 16-bit range under rapid sweeps", sample in Short.MIN_VALUE..Short.MAX_VALUE)
            }
        }
    }

    @Test
    fun testAutoHeadroomCompensation() {
        val sampleRate = 44100
        val eq = ParametricEq(sampleRate)
        eq.resetToFlat()

        // Apply +12dB boost on sub-bass
        eq.updateBand(0, 60.0, 12.0, 1.0, true)

        eq.autoHeadroomEnabled = false
        val inputOff = generateSineWave(sampleRate, 1000.0, 512, 10000.0)
        // Prime filter
        eq.processStereo(inputOff, 0, 512)
        val rmsWithoutHeadroom = computeRms(inputOff)

        eq.autoHeadroomEnabled = true
        val inputOn = generateSineWave(sampleRate, 1000.0, 512, 10000.0)
        // Process through multiple buffers to allow gain smoother to ramp to target
        for (b in 0 until 5) {
            val block = inputOn.clone()
            eq.processStereo(block, 0, 512)
            if (b == 4) {
                val rmsWithHeadroom = computeRms(block)
                // With +12dB boost, auto-headroom scales output by 10^(-12/20) = ~0.25 (-12dB)
                assertTrue("Auto-headroom must reduce overall output gain when boosting", rmsWithHeadroom < rmsWithoutHeadroom * 0.6)
            }
        }
    }

    @Test
    fun testDefault8BandsAndPresets() {
        val bands = ParametricEq.DEFAULT_8_BANDS
        assertEquals(8, bands.size)
        assertEquals("Sub-Bass", bands[0].name)
        assertEquals(EqFilterType.LOW_SHELF, bands[0].type)
        assertEquals("Air", bands[7].name)
        assertEquals(EqFilterType.HIGH_SHELF, bands[7].type)

        // Verify presets
        val presets = ParametricEqManager.BUILT_IN_PRESETS
        assertTrue(presets.isNotEmpty())
        for (p in presets) {
            assertEquals("Preset ${p.name} must have 8 bands", 8, p.bands.size)
        }
    }

    @Test
    fun testFrequencyResponseDbCalculation() {
        val sampleRate = 44100
        val bands = ParametricEq.DEFAULT_8_BANDS.map { it.copy() }
        val freqs = floatArrayOf(20f, 100f, 1000f, 10000f, 20000f)
        val outDb = FloatArray(freqs.size)

        // Flat response
        ParametricEq.computeFrequencyResponseDb(sampleRate, bands, 0.0, false, freqs, outDb)
        for (db in outDb) {
            assertEquals(0.0f, db, 0.01f)
        }

        // +6dB peaking at 1000Hz
        bands[3].frequencyHz = 1000.0
        bands[3].gainDb = 6.0
        bands[3].q = 2.0
        ParametricEq.computeFrequencyResponseDb(sampleRate, bands, 0.0, false, freqs, outDb)

        // Check that at 1000Hz the response is ~6.0 dB
        assertEquals(6.0f, outDb[2], 0.1f)
        // At 20 Hz, response is ~0.0 dB
        assertEquals(0.0f, outDb[0], 0.15f)
    }
}
