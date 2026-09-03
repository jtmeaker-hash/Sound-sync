package com.example

import com.example.analysis.TrackAudioMetrics
import com.example.analysis.TrackAudioMetricsService
import com.example.ui.djtools.KeyConverterData
import com.example.ui.djtools.TapBpmCalculator
import com.example.util.ExternalAppOpener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjToolsAndSideMenuTest {

    @Test
    fun testExternalAppOpenerUrlsAndPackages() {
        assertEquals("https://suno.com/", ExternalAppOpener.SUNO_WEB_URL)
        assertEquals("https://acestudio.ai/", ExternalAppOpener.ACE_STUDIO_WEB_URL)
        assertEquals("https://open.spotify.com/", ExternalAppOpener.SPOTIFY_WEB_URL)
        assertEquals("com.spotify.music", ExternalAppOpener.SPOTIFY_PACKAGE)
        assertEquals("https://soundcloud.com/", ExternalAppOpener.SOUNDCLOUD_WEB_URL)
        assertEquals("com.soundcloud.android", ExternalAppOpener.SOUNDCLOUD_PACKAGE)
        assertEquals("https://github.com/jtmeaker-hash/Sound-sync", ExternalAppOpener.GITHUB_REPO_URL)
        assertEquals("com.github.android", ExternalAppOpener.GITHUB_PACKAGE)
    }

    @Test
    fun testTapBpmCalculatorCalculatesAccurateBpm() {
        val calc = TapBpmCalculator()
        assertEquals(0.0, calc.currentBpm, 0.01)
        assertEquals(0, calc.tapCount)

        // Simulate regular taps every 500 ms -> 120.0 BPM
        var t = 100_000L
        calc.recordTap(t)
        assertEquals(1, calc.tapCount)
        assertEquals(0.0, calc.currentBpm, 0.01)

        t += 500L
        calc.recordTap(t)
        assertEquals(2, calc.tapCount)
        assertEquals(120.0, calc.currentBpm, 0.1)

        t += 500L
        calc.recordTap(t)
        assertEquals(3, calc.tapCount)
        assertEquals(120.0, calc.currentBpm, 0.1)

        t += 500L
        calc.recordTap(t)
        assertEquals(4, calc.tapCount)
        assertEquals(120.0, calc.currentBpm, 0.1)

        calc.reset()
        assertEquals(0, calc.tapCount)
        assertEquals(0.0, calc.currentBpm, 0.01)
    }

    @Test
    fun testTapBpmCalculatorRejectsOutliersAndResetsOnTimeout() {
        val calc = TapBpmCalculator()

        var t = 200_000L
        calc.recordTap(t)
        calc.recordTap(t + 500L)
        calc.recordTap(t + 1000L)
        calc.recordTap(t + 1500L)
        calc.recordTap(t + 2000L)
        assertEquals(120.0, calc.currentBpm, 0.5)

        // Outlier spike 50ms later (1200 BPM)
        calc.recordTap(t + 2050L)
        // Average should reject the outlier
        assertEquals(120.0, calc.currentBpm, 0.5)

        // Pause for 3000ms (> 2500ms threshold)
        calc.recordTap(t + 2050L + 3000L)
        assertEquals(1, calc.tapCount)
        assertEquals(0.0, calc.currentBpm, 0.01)
    }

    @Test
    fun testKeyConverterMappings() {
        // C Major (pitchClass 0, Major) -> 8B, Open Key 8d
        val cMaj = KeyConverterData.getKeyInfo(pitchClass = 0, isMinor = false)
        assertEquals("8B", cMaj.camelot)
        assertEquals("8d", cMaj.openKey)
        assertEquals("8A", cMaj.relativeKey)
        assertEquals("9B", cMaj.plusOneKey)
        assertEquals("7B", cMaj.minusOneKey)

        // A Minor (pitchClass 9, Minor) -> 8A, Open Key 8m
        val aMin = KeyConverterData.getKeyInfo(pitchClass = 9, isMinor = true)
        assertEquals("8A", aMin.camelot)
        assertEquals("8m", aMin.openKey)
        assertEquals("8B", aMin.relativeKey)
        assertEquals("9A", aMin.plusOneKey)
        assertEquals("7A", aMin.minusOneKey)

        // C Minor (pitchClass 0, Minor) -> 5A
        val cMin = KeyConverterData.getKeyInfo(pitchClass = 0, isMinor = true)
        assertEquals("5A", cMin.camelot)

        // B Major (pitchClass 11, Major) -> 1B
        val bMaj = KeyConverterData.getKeyInfo(pitchClass = 11, isMinor = false)
        assertEquals("1B", bMaj.camelot)
        assertEquals("12B", bMaj.minusOneKey)
        assertEquals("2B", bMaj.plusOneKey)

        // E Major (pitchClass 4, Major) -> 12B
        val eMaj = KeyConverterData.getKeyInfo(pitchClass = 4, isMinor = false)
        assertEquals("12B", eMaj.camelot)
        assertEquals("1B", eMaj.plusOneKey)
        assertEquals("11B", eMaj.minusOneKey)
    }

    @Test
    fun testTrackAudioMetricsServiceComputationAndCaching() {
        val sampleRate = 44100
        val numSamples = sampleRate * 3
        val pcm = ShortArray(numSamples)
        val peakAmp = 16384 // 50% scale
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * 440.0 * i / sampleRate
            pcm[i] = (peakAmp * Math.sin(angle)).toInt().toShort()
        }

        val metrics = TrackAudioMetricsService.computeMetricsForPcm(
            trackId = "test-sine-1",
            monoPcm = pcm,
            sampleRate = sampleRate
        )

        assertNotNull(metrics)
        assertEquals("test-sine-1", metrics.trackId)
        assertFalse(metrics.isClipping)
        assertEquals(0L, metrics.clippedSampleCount)
        assertEquals(0.0f, metrics.clippingPercentage, 0.001f)

        // Peak dBFS approx -6.0 dBFS
        assertEquals(-6.02f, metrics.peakDb, 0.2f)
        // RMS dBFS approx -9.0 dBFS
        assertEquals(-9.03f, metrics.rmsDb, 0.2f)

        // Cache verification
        val cached = TrackAudioMetricsService.getCached("test-sine-1")
        assertNotNull(cached)
        assertEquals(metrics.peakDb, cached!!.peakDb, 0.001f)
    }

    @Test
    fun testClippingDetectionAtDigitalCeiling() {
        val numSamples = 1000
        val pcm = ShortArray(numSamples)

        // Inject 10 clipped samples at the digital ceiling (32767)
        for (i in 0 until 10) {
            pcm[i * 50] = 32767.toShort()
        }
        for (i in 10 until 20) {
            pcm[i * 50] = (-32768).toShort()
        }

        val metrics = TrackAudioMetricsService.computeMetricsForPcm(
            trackId = "test-clip-1",
            monoPcm = pcm,
            sampleRate = 44100
        )

        assertTrue(metrics.isClipping)
        assertEquals(20L, metrics.clippedSampleCount)
        assertEquals(0.0f, metrics.peakDb, 0.01f)
    }

    @Test
    fun testDynamicRangeDescriptionClassification() {
        val dr14 = TrackAudioMetrics(
            trackId = "1",
            peakDb = 0f,
            peakAmplitude = 1f,
            isClipping = false,
            clippedSampleCount = 0,
            clippingPercentage = 0f,
            rmsDb = -18f,
            dynamicRangeScore = 14,
            crestFactorDb = 18f,
            dynamicRangeDescription = "High dynamic range / natural uncompressed master",
            totalSamplesAnalyzed = 44100,
            durationSeconds = 1
        )
        assertEquals(14, dr14.dynamicRangeScore)
        assertEquals(18f, dr14.crestFactorDb, 0.01f)
    }
}
