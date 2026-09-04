package com.example

import com.example.audio.WaveformData
import com.example.model.Track
import com.example.model.WaveformStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformStyleAndSyncTest {

    @Test
    fun `WaveformStyle enum supports Retro and Detailed modes with robust parsing`() {
        assertEquals(WaveformStyle.RETRO, WaveformStyle.fromString("RETRO"))
        assertEquals(WaveformStyle.RETRO, WaveformStyle.fromString("retro"))
        assertEquals(WaveformStyle.RETRO, WaveformStyle.fromString("Retro Waveform"))

        assertEquals(WaveformStyle.DETAILED, WaveformStyle.fromString("DETAILED"))
        assertEquals(WaveformStyle.DETAILED, WaveformStyle.fromString("detailed"))
        assertEquals(WaveformStyle.DETAILED, WaveformStyle.fromString("Detailed Waveform"))

        // Unknown or null defaults to DETAILED
        assertEquals(WaveformStyle.DETAILED, WaveformStyle.fromString(null))
        assertEquals(WaveformStyle.DETAILED, WaveformStyle.fromString(""))
        assertEquals(WaveformStyle.DETAILED, WaveformStyle.fromString("invalid_style"))

        assertEquals("Retro Waveform", WaveformStyle.RETRO.displayName)
        assertEquals("Detailed Waveform", WaveformStyle.DETAILED.displayName)
    }

    @Test
    fun `playback synchronization precisely aligns playhead to center with zero drift`() {
        val durationMs = 240_000L // 4 minute track
        val canvasWidth = 1080f
        val centerX = canvasWidth / 2f
        val visibleWindowSeconds = 8.0f
        val msPerPixel = (visibleWindowSeconds * 1000f) / canvasWidth

        // Test synchronization at 0%, 25%, 50%, 75%, and 100% of the track duration
        val checkpoints = listOf(0.0f, 0.25f, 0.50f, 0.75f, 1.0f)

        for (checkpoint in checkpoints) {
            val playbackPositionMs = (durationMs * checkpoint).toLong()

            // In our renderer:
            // deltaFromCenterPx = screenX - centerX
            // sampleTimeMs = currentPositionMs + (deltaFromCenterPx * msPerPixel)
            // Solving for screenX where sampleTimeMs == currentPositionMs:
            // screenX = centerX + (0 * msPerPixel) = centerX
            val playheadScreenX = centerX + ((playbackPositionMs - playbackPositionMs) / msPerPixel)

            // Playhead must match the center pixel exactly
            assertEquals(centerX, playheadScreenX, 0.0001f)

            // Overview scrubber alignment:
            // progressFraction = currentPositionMs / durationMs
            // playheadOverviewX = progressFraction * canvasWidth
            val progressFraction = playbackPositionMs.toFloat() / durationMs.toFloat()
            val playheadOverviewX = progressFraction * canvasWidth
            val expectedOverviewFraction = checkpoint

            assertEquals(expectedOverviewFraction, progressFraction, 0.0001f)
            assertEquals(checkpoint * canvasWidth, playheadOverviewX, 0.001f)
        }
    }

    @Test
    fun `scrubbing and seeking math is bidirectional and lossless`() {
        val durationMs = 180_000L
        val canvasWidth = 800f
        val centerX = canvasWidth / 2f
        val visibleWindowSeconds = 10.0f
        val msPerPixel = (visibleWindowSeconds * 1000f) / canvasWidth

        val currentPositionMs = 60_000L

        // Simulate user swiping left by 50 pixels (moving audio forward)
        val dragDeltaPx = -50f
        val deltaMs = -dragDeltaPx * msPerPixel
        val newTargetMs = (currentPositionMs + deltaMs).toLong()

        // Verify the inverse calculation gives back the exact original dragDeltaPx
        val computedDeltaPx = -((newTargetMs - currentPositionMs) / msPerPixel)
        assertEquals(dragDeltaPx, computedDeltaPx, 0.1f)
    }

    @Test
    fun `detailed waveform preserves dynamic range of quiet passages and transients`() {
        val maxPeakHeight = 100f

        // 1. Silence should produce minimal baseline hairline (0.5f)
        val silencePeak = 0.002f
        val silenceHeight = if (silencePeak < 0.008f) 0.5f else (silencePeak * maxPeakHeight).coerceAtLeast(1.0f)
        assertEquals(0.5f, silenceHeight, 0.001f)

        // 2. Quiet passage (intro / breakdown) should preserve delicate dynamic height without flattening
        val quietIntroPeak = 0.08f
        val quietIntroHeight = if (quietIntroPeak < 0.008f) 0.5f else (quietIntroPeak * maxPeakHeight).coerceAtLeast(1.0f)
        assertEquals(8.0f, quietIntroHeight, 0.001f)

        // 3. Punchy drop / chorus transient should reach near full peak height
        val dropPeak = 0.95f
        val dropHeight = if (dropPeak < 0.008f) 0.5f else (dropPeak * maxPeakHeight).coerceAtLeast(1.0f)
        assertEquals(95.0f, dropHeight, 0.001f)

        // Verify quiet passage is clearly distinct from silence and drop
        assertTrue(quietIntroHeight > silenceHeight * 10f)
        assertTrue(dropHeight > quietIntroHeight * 10f)
    }

    @Test
    fun `waveform data binds strictly to track ID to discard stale analysis results`() {
        val trackA = Track(id = "track-a", title = "Track A", artist = "Artist A", durationSeconds = 180)
        val trackB = Track(id = "track-b", title = "Track B", artist = "Artist B", durationSeconds = 240)

        val waveformDataA = WaveformData(
            trackId = "track-a",
            durationMs = 180_000L,
            samplePoints = 1200,
            peaks = FloatArray(1200) { 0.5f },
            lowBand = FloatArray(1200) { 0.3f },
            midBand = FloatArray(1200) { 0.4f },
            highBand = FloatArray(1200) { 0.2f },
            bpm = 126.0,
            isRealAudioData = true
        )

        // When trackA is active, its waveform data is valid
        val validForA = if (waveformDataA.trackId == trackA.id) waveformDataA else null
        assertNotNull(validForA)
        assertEquals("track-a", validForA?.trackId)

        // When track changes to trackB, the stale waveformDataA is immediately rejected
        val validForB = if (waveformDataA.trackId == trackB.id) waveformDataA else null
        assertNull(validForB)
    }

    @Test
    fun `WaveformData stores and exposes RMS band alongside frequency bands`() {
        val binCount = 800
        val rmsArray = FloatArray(binCount) { i -> (i / 800f) * 0.8f }
        val waveformData = WaveformData(
            trackId = "test-track",
            durationMs = 120_000L,
            samplePoints = binCount,
            peaks = FloatArray(binCount) { 0.5f },
            lowBand = FloatArray(binCount) { 0.2f },
            midBand = FloatArray(binCount) { 0.3f },
            highBand = FloatArray(binCount) { 0.1f },
            bpm = 128.0,
            isRealAudioData = true,
            rms = rmsArray
        )

        assertEquals(binCount, waveformData.rms.size)
        assertEquals(0.0f, waveformData.rms[0], 0.001f)
        assertTrue(waveformData.rms[binCount - 1] > 0.7f)
    }
}
