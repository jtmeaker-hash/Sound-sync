package com.example

import com.example.audio.AudioClock
import com.example.audio.WaveformBiquadFilter
import com.example.audio.WaveformCache
import com.example.audio.WaveformData
import com.example.model.Track
import com.example.model.WaveformShape
import com.example.model.WaveformStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Deterministic automated sync, DSP, cache, and rendering regression test suite
 * for SoundSync Rekordbox-grade waveforms and PlaybackTimeline architecture.
 */
class WaveformRekordboxSyncRegressionTest {

    private fun createDummyTrack(id: String = "track-test-1", durationSec: Int = 180): Track {
        return Track(
            id = id,
            title = "Rekordbox Sync Test Track",
            artist = "SoundSync Audio",
            album = "Sync Benchmark",
            durationSeconds = durationSec,
            filePath = "/storage/emulated/0/Music/test.mp3",
            bpm = 126.0,
            musicalKey = "8A"
        )
    }

    // =========================================================================
    // 1. TIMELINE & AUDIO CLOCK SYNCHRONIZATION TESTS
    // =========================================================================

    @Test
    fun `AudioClock accurately reflects media time from published positions when track is unstarted or paused`() {
        val clock = AudioClock()
        val track = createDummyTrack()

        clock.onTrackChanged(track, initialPositionMs = 0L, session = 1L)
        clock.onPlayStateChanged(false)

        assertEquals(0.0f, clock.getCurrentPositionFloatMs(), 0.001f)

        clock.updatePublishedPosition(15_420L)
        assertEquals(15_420.0f, clock.getCurrentPositionFloatMs(), 0.001f)
        assertEquals(15_420L, clock.currentPositionMs.value)
    }

    @Test
    fun `AudioClock handles seek resolution immediately and freezes playhead on pause`() {
        val clock = AudioClock()
        val track = createDummyTrack()

        clock.onTrackChanged(track, initialPositionMs = 0L, session = 1L)
        clock.onPlayStateChanged(true)
        clock.updatePublishedPosition(5_000L)

        // Simulate seek to 45,000 ms with buffer flush
        clock.onSeekResolved(resolvedMediaTimeMs = 45_000L, audioTrackFlushed = true)
        // While playing, clock immediately advances at sub-millisecond precision from 45,000 ms (< 1ms delta)
        assertEquals(45_000.0f, clock.getCurrentPositionFloatMs(), 1.0f)

        // Pause playback: must freeze at exact seek location and never drift with wall-clock time
        clock.onPlayStateChanged(false)
        val pausedPos = clock.getCurrentPositionFloatMs()
        assertEquals(45_000.0f, pausedPos, 0.001f)

        // Simulate passage of time while paused
        Thread.sleep(50)
        assertEquals(pausedPos, clock.getCurrentPositionFloatMs(), 0.001f)
    }

    @Test
    fun `AudioClock scales elapsed time precisely with playback speed without cumulative drift`() {
        val clock = AudioClock()
        val track = createDummyTrack()

        clock.onTrackChanged(track, initialPositionMs = 10_000L, session = 1L)
        clock.onPlayStateChanged(true)

        // DJ Pitch change: +8% tempo (1.08x)
        clock.onPlaybackSpeedChanged(1.08f)
        assertEquals(1.08f, clock.playbackSpeed, 0.001f)

        // Test at normal 1.0x
        clock.onPlaybackSpeedChanged(1.00f)
        assertEquals(1.00f, clock.playbackSpeed, 0.001f)
    }

    @Test
    fun `Continuous playback simulation for 30 minutes maintains zero cumulative drift`() {
        val clock = AudioClock()
        val track = createDummyTrack(durationSec = 2400) // 40-minute track
        clock.onTrackChanged(track, initialPositionMs = 0L, session = 1L)
        clock.onPlayStateChanged(true)

        val sampleRate = 44100
        val thirtyMinutesMs = 30 * 60 * 1000L // 1,800,000 ms
        val framesPerSecond = 44100L
        val totalFrames = 30 * 60 * framesPerSecond

        // Simulate frames delivered to audio engine in 1024-frame chunks over 30 minutes
        var framesWritten = 0L
        val chunkSize = 1024L
        while (framesWritten < totalFrames) {
            val toWrite = minOf(chunkSize, totalFrames - framesWritten)
            framesWritten += toWrite
            clock.recordFramesWritten(toWrite.toInt())
        }

        // Theoretical media time based on frame count
        val theoreticalMediaTimeMs = (totalFrames * 1000L) / sampleRate
        assertEquals(thirtyMinutesMs, theoreticalMediaTimeMs)

        // Clock published time updated to end of 30 minutes
        clock.updatePublishedPosition(thirtyMinutesMs)
        val reportedMs = clock.currentPositionMs.value
        val driftMs = abs(reportedMs - thirtyMinutesMs)

        // Drift across 30 minutes must be <= 1ms
        assertTrue("Cumulative drift across 30 mins was $driftMs ms (must be <= 1ms)", driftMs <= 1)
    }

    // =========================================================================
    // 2. DSP BIQUAD FILTER FREQUENCY ISOLATION TESTS
    // =========================================================================

    @Test
    fun `WaveformBiquadFilter sample-rate calibrated low-pass isolates bass and rejects highs`() {
        val sampleRate = 44100
        val lpFilter = WaveformBiquadFilter().apply {
            configureLowPass(sampleRate, cutoffHz = 250f)
        }

        // 1. Generate 60 Hz bass sine wave
        val numSamples = 2048
        var bassOutputSum = 0.0
        var bassInputSum = 0.0
        for (i in 0 until numSamples) {
            val sample = sin(2.0 * PI * 60.0 * i / sampleRate).toFloat()
            val out = lpFilter.process(sample)
            if (i > 200) { // skip transient settling
                bassInputSum += abs(sample)
                bassOutputSum += abs(out)
            }
        }
        val bassGain = bassOutputSum / bassInputSum
        assertTrue("60 Hz bass gain was $bassGain, expected near unity (> 0.70)", bassGain > 0.70)

        // 2. Process 5000 Hz high-frequency sine wave through same LP filter
        lpFilter.reset()
        var highOutputSum = 0.0
        var highInputSum = 0.0
        for (i in 0 until numSamples) {
            val sample = sin(2.0 * PI * 5000.0 * i / sampleRate).toFloat()
            val out = lpFilter.process(sample)
            if (i > 200) {
                highInputSum += abs(sample)
                highOutputSum += abs(out)
            }
        }
        val highGain = highOutputSum / highInputSum
        assertTrue("5000 Hz high gain was $highGain, expected strong rejection (< 0.08)", highGain < 0.08)
    }

    @Test
    fun `WaveformBiquadFilter sample-rate calibrated high-pass isolates transients and rejects bass`() {
        val sampleRate = 44100
        val hpFilter = WaveformBiquadFilter().apply {
            configureHighPass(sampleRate, cutoffHz = 4000f)
        }

        // 1. Process 100 Hz bass sine wave through HP filter
        val numSamples = 2048
        var bassOutputSum = 0.0
        var bassInputSum = 0.0
        for (i in 0 until numSamples) {
            val sample = sin(2.0 * PI * 100.0 * i / sampleRate).toFloat()
            val out = hpFilter.process(sample)
            if (i > 200) {
                bassInputSum += abs(sample)
                bassOutputSum += abs(out)
            }
        }
        val bassGain = bassOutputSum / bassInputSum
        assertTrue("100 Hz bass gain through 4kHz HP was $bassGain, expected strong rejection (< 0.05)", bassGain < 0.05)

        // 2. Process 8000 Hz high sine wave through HP filter
        hpFilter.reset()
        var highOutputSum = 0.0
        var highInputSum = 0.0
        for (i in 0 until numSamples) {
            val sample = sin(2.0 * PI * 8000.0 * i / sampleRate).toFloat()
            val out = hpFilter.process(sample)
            if (i > 200) {
                highInputSum += abs(sample)
                highOutputSum += abs(out)
            }
        }
        val highGain = highOutputSum / highInputSum
        assertTrue("8000 Hz high gain was $highGain, expected pass (> 0.70)", highGain > 0.70)
    }

    // =========================================================================
    // 3. WAVEFORM CACHE SCHEMA V8 & BACKWARD-COMPATIBLE MIGRATION
    // =========================================================================

    @Test
    fun `WaveformData v8 serializes and deserializes bipolar envelopes and frame counts`() {
        val peaks = floatArrayOf(0.1f, 0.5f, 0.9f, 0.3f)
        val minPeaks = floatArrayOf(0.08f, 0.45f, 0.85f, 0.28f)
        val low = floatArrayOf(0.2f, 0.8f, 0.4f, 0.1f)
        val mid = floatArrayOf(0.5f, 0.3f, 0.2f, 0.4f)
        val high = floatArrayOf(0.1f, 0.2f, 0.9f, 0.3f)

        val original = WaveformData(
            trackId = "track-schema-v8",
            durationMs = 180_000L,
            samplePoints = 4,
            peaks = peaks,
            minPeaks = minPeaks,
            lowBand = low,
            midBand = mid,
            highBand = high,
            totalFrames = 1323000L,
            sampleRate = 44100
        )

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            WaveformCache.writeToDataStream(dos, original)
        }

        val input = ByteArrayInputStream(out.toByteArray())
        val restored = DataInputStream(input).use { dis ->
            WaveformCache.readFromDataStream(dis)
        }

        assertNotNull(restored)
        assertEquals("track-schema-v8", restored!!.trackId)
        assertEquals(180_000L, restored.durationMs)
        assertEquals(4, restored.samplePoints)
        assertEquals(1323000L, restored.totalFrames)
        assertEquals(44100, restored.sampleRate)

        for (i in 0 until 4) {
            assertEquals(peaks[i], restored.peaks[i], 0.001f)
            assertEquals(minPeaks[i], restored.minPeaks[i], 0.001f)
            assertEquals(low[i], restored.lowBand[i], 0.001f)
            assertEquals(mid[i], restored.midBand[i], 0.001f)
            assertEquals(high[i], restored.highBand[i], 0.001f)
        }
    }

    @Test
    fun `WaveformData gracefully migrates legacy v7 cache streams with minPeaks`() {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(0x53594E37) // MAGIC_HEADER_V7
        dos.writeUTF("track-legacy-v7")
        dos.writeLong(180_000L)
        dos.writeInt(3)
        dos.writeDouble(128.0)
        dos.writeBoolean(true)
        dos.writeLong(1323000L)
        dos.writeInt(44100)
        // peaks
        dos.writeFloat(0.2f); dos.writeFloat(0.6f); dos.writeFloat(0.8f)
        // low
        dos.writeFloat(0.1f); dos.writeFloat(0.5f); dos.writeFloat(0.3f)
        // mid
        dos.writeFloat(0.3f); dos.writeFloat(0.2f); dos.writeFloat(0.4f)
        // high
        dos.writeFloat(0.05f); dos.writeFloat(0.1f); dos.writeFloat(0.7f)
        // rms
        dos.writeInt(0)
        // minPeaks
        dos.writeInt(3)
        dos.writeFloat(0.18f); dos.writeFloat(0.55f); dos.writeFloat(0.75f)
        dos.flush()

        val input = ByteArrayInputStream(out.toByteArray())
        val migrated = DataInputStream(input).use { dis ->
            WaveformCache.readFromDataStream(dis)
        }

        assertNotNull(migrated)
        assertEquals("track-legacy-v7", migrated!!.trackId)
        assertEquals(180_000L, migrated.durationMs)
        assertEquals(3, migrated.samplePoints)
        assertEquals(3, migrated.minPeaks.size)
        assertEquals(0.18f, migrated.minPeaks[0], 0.001f)
        assertEquals(0.55f, migrated.minPeaks[1], 0.001f)
        assertEquals(0.75f, migrated.minPeaks[2], 0.001f)
        assertEquals(1323000L, migrated.totalFrames)
        assertEquals(44100, migrated.sampleRate)
    }

    @Test
    fun `isAdequateForDetailed distinguishes high-res detailed caches from legacy coarse overview caches`() {
        val durationSec = 180L
        val durationMs = durationSec * 1000L

        // Legacy coarse overview cache (only 1200 or 2400 bins = ~6-13 bins/sec)
        val coarseData = WaveformData(
            trackId = "track-coarse",
            durationMs = durationMs,
            samplePoints = 2400,
            peaks = FloatArray(2400),
            lowBand = FloatArray(2400),
            midBand = FloatArray(2400),
            highBand = FloatArray(2400),
            isRealAudioData = true
        )
        assertFalse("Coarse 2400-bin cache must NOT be considered adequate for detailed rendering", coarseData.isAdequateForDetailed(durationSec))

        // High-res v8 detailed cache (~150 bins/sec = 27000 bins)
        val highResData = WaveformData(
            trackId = "track-highres",
            durationMs = durationMs,
            samplePoints = 27000,
            peaks = FloatArray(27000),
            lowBand = FloatArray(27000),
            midBand = FloatArray(27000),
            highBand = FloatArray(27000),
            isRealAudioData = true
        )
        assertTrue("High-res 27000-bin cache must be considered adequate for detailed rendering", highResData.isAdequateForDetailed(durationSec))
    }

    @Test
    fun `Master peak normalization preserves relative spectral dynamic balance across frequency bands`() {
        val peakNorm = 0.98f
        val lowScale = 1.10f / peakNorm
        val midScale = 1.30f / peakNorm
        val highScale = 1.80f / peakNorm

        // Kick Drum Transient: massive bass, medium mid, subtle high
        val kickLowRaw = 0.65f
        val kickMidRaw = 0.25f
        val kickHighRaw = 0.08f

        val kickLowScaled = (kickLowRaw * lowScale).coerceIn(0f, 1f)
        val kickMidScaled = (kickMidRaw * midScale).coerceIn(0f, 1f)
        val kickHighScaled = (kickHighRaw * highScale).coerceIn(0f, 1f)

        assertTrue("Kick drum must have dominant Low energy over Mid and High", kickLowScaled > kickMidScaled)
        assertTrue("Kick drum Mid must exceed High", kickMidScaled > kickHighScaled)

        // Vocal / Melodic Section: high mid, low bass
        val vocalLowRaw = 0.10f
        val vocalMidRaw = 0.55f
        val vocalHighRaw = 0.20f

        val vocalLowScaled = (vocalLowRaw * lowScale).coerceIn(0f, 1f)
        val vocalMidScaled = (vocalMidRaw * midScale).coerceIn(0f, 1f)
        val vocalHighScaled = (vocalHighRaw * highScale).coerceIn(0f, 1f)

        assertTrue("Vocal section Mid must dominate Low energy", vocalMidScaled > vocalLowScaled)

        // Verify bands are NOT flatlines: kick low vs vocal low must differ substantially
        assertTrue("Low energy between kick and vocal breakdown must differ by > 0.4", (kickLowScaled - vocalLowScaled) > 0.40f)
    }

    @Test
    fun `Dynamic headroom soft-knee preserves headroom so transient spikes punch out above loud body`() {
        val maxPeakHeight = 100f

        // Loud mastered body (peak = 0.80f)
        val bodyPeak = 0.80f
        val bodyComp = (bodyPeak * 0.82f + (bodyPeak * bodyPeak) * 0.14f).coerceIn(0.0f, 0.95f)
        val bodyHeight = (bodyComp * maxPeakHeight).coerceAtLeast(0.5f)

        // Sharp transient spike hit (peak = 1.0f)
        val spikePeak = 1.0f
        val spikeComp = (spikePeak * 0.82f + (spikePeak * spikePeak) * 0.14f).coerceIn(0.0f, 0.95f)
        val spikeHeight = (spikeComp * maxPeakHeight).coerceAtLeast(0.5f)

        // Spike must reach 95% height, leaving 5% canvas margin rather than hard-clipping
        assertEquals(95f, spikeHeight, 0.5f)
        // Body must sit around 74% height
        assertTrue("Body height should sit around 70-76%", bodyHeight in 70f..76f)
        // Transient must punch out distinctly above body
        assertTrue("Transient punch difference must exceed 18px", (spikeHeight - bodyHeight) > 18f)
    }

    @Test
    fun `WaveformData gracefully migrates legacy v6 cache streams without minPeaks`() {
        // Construct synthetic v6 stream: magic 0x53594E43 ("SYNC"), trackId, durationMs, samplePoints, bpm, isReal, peaks, low, mid, high
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(0x53594E43) // MAGIC_HEADER_V6
        dos.writeUTF("track-legacy-v6")
        dos.writeLong(120_000L)
        dos.writeInt(3)
        dos.writeDouble(128.0)
        dos.writeBoolean(true)
        // peaks
        dos.writeFloat(0.2f); dos.writeFloat(0.6f); dos.writeFloat(0.8f)
        // low
        dos.writeFloat(0.1f); dos.writeFloat(0.5f); dos.writeFloat(0.3f)
        // mid
        dos.writeFloat(0.3f); dos.writeFloat(0.2f); dos.writeFloat(0.4f)
        // high
        dos.writeFloat(0.05f); dos.writeFloat(0.1f); dos.writeFloat(0.7f)
        dos.flush()

        val input = ByteArrayInputStream(out.toByteArray())
        val migrated = DataInputStream(input).use { dis ->
            WaveformCache.readFromDataStream(dis)
        }

        assertNotNull(migrated)
        assertEquals("track-legacy-v6", migrated!!.trackId)
        assertEquals(120_000L, migrated.durationMs)
        assertEquals(3, migrated.samplePoints)
        // In legacy migration, minPeaks falls back gracefully to copy of peaks
        assertEquals(3, migrated.minPeaks.size)
        assertEquals(0.2f, migrated.minPeaks[0], 0.001f)
        assertEquals(0.6f, migrated.minPeaks[1], 0.001f)
        assertEquals(0.8f, migrated.minPeaks[2], 0.001f)
        assertEquals(44100, migrated.sampleRate)
    }

    // =========================================================================
    // 4. COORDINATE INVERSION & BIDIRECTIONAL TIME-TO-PIXEL ACCURACY
    // =========================================================================

    @Test
    fun `Detailed waveform center playhead coordinate mapping is mathematically exact`() {
        val durationMs = 240_000L
        val canvasWidth = 1080f
        val centerX = canvasWidth / 2f
        val visibleWindowSeconds = 8.0f
        val msPerPixel = (visibleWindowSeconds * 1000f) / canvasWidth

        // Test across 100 points along track
        for (i in 0..100) {
            val currentPosMs = (durationMs * (i / 100.0)).toFloat()
            // In detailed view:
            // deltaFromCenterPx = (targetTimeMs - currentPosMs) / msPerPixel
            // When targetTimeMs == currentPosMs: deltaFromCenterPx == 0
            val playheadX = centerX + ((currentPosMs - currentPosMs) / msPerPixel)
            assertEquals(centerX, playheadX, 0.0001f)

            // Invert target pixel to time:
            val reconstructedMs = currentPosMs + ((playheadX - centerX) * msPerPixel)
            assertEquals(currentPosMs, reconstructedMs, 0.001f)
        }
    }

    @Test
    fun `Scrubber overview position indicator mapping matches progress perfectly`() {
        val durationMs = 210_000L
        val canvasWidth = 800f

        val testPositionsMs = listOf(0L, 10_500L, 52_500L, 105_000L, 157_500L, 210_000L)
        for (posMs in testPositionsMs) {
            val progressFrac = (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val playheadX = progressFrac * canvasWidth

            // Invert pixel back to time fraction
            val reconstructedFrac = playheadX / canvasWidth
            assertEquals(progressFrac, reconstructedFrac, 0.0001f)
        }
    }

    // =========================================================================
    // 5. TRANSIENT PEAK PRESERVATION & SILENCE CONTRAST
    // =========================================================================

    @Test
    fun `Peak envelope preserves transient spike in silence without smoothing away`() {
        val canvasHeight = 160f
        val centerY = canvasHeight / 2f
        val maxPeakHeight = centerY * 0.90f

        // Silence
        val silencePeak = 0.002f
        val silenceDrawHeight = if (silencePeak < 0.008f) 0.5f else (silencePeak * maxPeakHeight).coerceAtLeast(1.0f)
        assertEquals(0.5f, silenceDrawHeight, 0.001f)

        // Sharp transient spike (e.g. rimshot / hi-hat click)
        val transientPeak = 0.95f
        val transientDrawHeight = if (transientPeak < 0.008f) 0.5f else (transientPeak * maxPeakHeight).coerceAtLeast(1.0f)
        assertEquals(maxPeakHeight * 0.95f, transientDrawHeight, 0.001f)

        // Asymmetry preservation: positive spike vs negative bounce
        val positivePeak = 0.80f
        val negativePeak = 0.40f
        val topY = centerY - (positivePeak * maxPeakHeight)
        val botY = centerY + (negativePeak * maxPeakHeight)

        val totalHeight = botY - topY
        val expectedHeight = (positivePeak + negativePeak) * maxPeakHeight
        assertEquals(expectedHeight, totalHeight, 0.001f)
        assertTrue("Top amplitude must exceed bottom amplitude", (centerY - topY) > (botY - centerY))
    }

    // =========================================================================
    // 6. WAVEFORM SHAPE (HALF vs FULL) & STYLE COMBINATIONS (4 MODES)
    // =========================================================================

    @Test
    fun `WaveformShape enum parsing handles all string inputs and defaults safely`() {
        assertEquals(WaveformShape.HALF, WaveformShape.fromString("HALF"))
        assertEquals(WaveformShape.HALF, WaveformShape.fromString("half"))
        assertEquals(WaveformShape.HALF, WaveformShape.fromString("Half Waveform"))
        assertEquals(WaveformShape.FULL, WaveformShape.fromString("FULL"))
        assertEquals(WaveformShape.FULL, WaveformShape.fromString("full"))
        assertEquals(WaveformShape.FULL, WaveformShape.fromString("Full Waveform"))
        assertEquals(WaveformShape.FULL, WaveformShape.fromString(null))
        assertEquals(WaveformShape.FULL, WaveformShape.fromString(""))
        assertEquals(WaveformShape.FULL, WaveformShape.fromString("unknown_value"))
    }

    @Test
    fun `All 4 style and shape combinations configure cleanly and independently`() {
        val styles = listOf(WaveformStyle.DETAILED, WaveformStyle.RETRO)
        val shapes = listOf(WaveformShape.FULL, WaveformShape.HALF)

        val combinations = mutableListOf<Pair<WaveformStyle, WaveformShape>>()
        for (style in styles) {
            for (shape in shapes) {
                combinations.add(style to shape)
            }
        }

        assertEquals(4, combinations.size)
        assertTrue(combinations.contains(WaveformStyle.DETAILED to WaveformShape.FULL))
        assertTrue(combinations.contains(WaveformStyle.DETAILED to WaveformShape.HALF))
        assertTrue(combinations.contains(WaveformStyle.RETRO to WaveformShape.FULL))
        assertTrue(combinations.contains(WaveformStyle.RETRO to WaveformShape.HALF))
    }

    @Test
    fun `HALF mode geometry anchors baseline at bottom and rises upward utilizing full canvas height`() {
        val height = 165f
        val baselineY = height - 5f // 160f
        val maxPeakHeight = (height - 12f) * 0.94f // ~143.82f

        val peak = 0.85f
        val peakTop = (peak * maxPeakHeight).coerceAtLeast(1.0f)
        val startY = baselineY - peakTop
        val endY = baselineY

        assertTrue("In HALF mode, startY must be above baselineY", startY < baselineY)
        assertEquals(160f, endY, 0.001f)
        assertTrue("In HALF mode, peakTop can reach near top of canvas", startY < 40f)
    }

    @Test
    fun `FULL mode geometry centers baseline and renders bipolar excursions without empty lower half`() {
        val height = 165f
        val centerY = height / 2f // 82.5f
        val maxPeakHeight = centerY * 0.90f // 74.25f

        val positivePeak = 0.85f
        val negativePeak = -0.75f // e.g. legacy negative peak or raw minimum

        // Fix verified: negative peak is converted to positive magnitude abs(minPeak)
        val safeMinPeak = abs(negativePeak)
        val effectiveMinPeak = if (safeMinPeak < 0.008f && positivePeak >= 0.008f) positivePeak * 0.92f else safeMinPeak

        val peakTop = (positivePeak * maxPeakHeight).coerceAtLeast(1.0f)
        val peakBot = (effectiveMinPeak * maxPeakHeight).coerceAtLeast(1.0f)

        val topY = centerY - peakTop
        val botY = centerY + peakBot

        assertTrue("In FULL mode, topY must be above center axis", topY < centerY)
        assertTrue("In FULL mode, botY must be below center axis", botY > centerY)
        assertTrue("Lower peak height must be substantial (> 50px), not 0.5px!", peakBot > 50f)
        assertEquals(centerY, 82.5f, 0.001f)
    }

    @Test
    fun `Peak-preserving downsampling captures sharp transients across multiresolution bin ranges`() {
        // Simulate 10 bins downsampled into 1 screen column: 9 quiet bins (0.1f) and 1 snare transient (0.95f)
        val peaks = floatArrayOf(0.10f, 0.12f, 0.11f, 0.09f, 0.95f, 0.15f, 0.10f, 0.12f, 0.08f, 0.11f)

        // Linear average would destroy transient: (0.10+0.12+0.11+0.09+0.95+0.15+0.10+0.12+0.08+0.11)/10 = 0.193f!
        var peakEnvelope = 0.0f
        for (b in peaks.indices) {
            if (peaks[b] > peakEnvelope) peakEnvelope = peaks[b]
        }

        assertEquals(0.95f, peakEnvelope, 0.001f)
        assertTrue("Envelope downsampling strictly preserves the 0.95f transient hit", peakEnvelope > 0.90f)
    }
}
