package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjAudioEngine
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import com.example.storage.MediaScannerHelper
import com.example.storage.ScanStateManager
import com.example.storage.ScanStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private fun createSampleTrack(id: String = "test_1", path: String = "/nonexistent/test.mp3"): Track {
        return Track(
            id = id,
            title = "Test Sound",
            artist = "Audio Artist",
            album = "Test Album",
            genre = "Techno",
            subGenre = "Peak",
            bpm = 128.0,
            musicalKey = "8A",
            durationSeconds = 180,
            bitrateKbps = 320,
            format = "MP3",
            fileSizeMb = 8.5,
            filePath = path,
            directoryPath = "/nonexistent",
            isOfflineReady = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            energyRating = 7,
            hotCues = listOf(0, 30, 60),
            isAiTagged = true,
            qualityRating = AudioQualityRating.TRUE_320,
            crateId = "default",
            sourceId = "internal"
        )
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SoundSync", appName)
    }

    @Test
    fun `player is initialized in paused state with no auto playback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DjAudioEngine(context)

        assertFalse("Player must start in paused state", engine.isPlaying.value)
        assertNull("Current track must initially be null", engine.currentTrack.value)
        assertEquals(0f, engine.playbackProgress.value, 0.001f)
        assertEquals(0, engine.currentPositionSec.value)
    }

    @Test
    fun `loadTrack with autoPlay false restores track metadata but remains strictly paused`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DjAudioEngine(context)
        val track = createSampleTrack()

        engine.loadTrack(track, autoPlay = false, initialPositionSec = 45)

        assertFalse("Audio must remain strictly paused after loading track on startup", engine.isPlaying.value)
        assertEquals("Test Sound", engine.currentTrack.value?.title)
        assertEquals(45, engine.currentPositionSec.value)
        assertNotNull(engine.waveformHeights.value)
        engine.release()
    }

    @Test
    fun `loadTrack with invalid or inaccessible path does not crash and falls back safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DjAudioEngine(context)
        val badTrack = createSampleTrack(path = "content://media/external/audio/media/99999999")

        // Should not throw SecurityException or FileNotFoundException
        engine.loadTrack(badTrack, autoPlay = false)
        assertFalse(engine.isPlaying.value)
        assertEquals(badTrack.id, engine.currentTrack.value?.id)

        // Toggling playback on unavailable file uses safe synthesis fallback without crash
        engine.play()
        assertTrue(engine.isPlaying.value)
        engine.pause()
        assertFalse(engine.isPlaying.value)
        engine.release()
    }

    @Test
    fun `explicit play and pause lifecycle transitions smoothly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DjAudioEngine(context)
        val track = createSampleTrack()

        engine.loadTrack(track, autoPlay = false)
        assertFalse(engine.isPlaying.value)

        // User explicit play action
        engine.play()
        assertTrue("Player should be playing after user calls play()", engine.isPlaying.value)

        // User explicit pause action
        engine.pause()
        assertFalse("Player should be paused after user calls pause()", engine.isPlaying.value)

        // Toggle
        engine.togglePlayPause()
        assertTrue(engine.isPlaying.value)
        engine.togglePlayPause()
        assertFalse(engine.isPlaying.value)

        engine.release()
    }

    @Test
    fun `ScanStateManager correctly recovers from interrupted scan`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanManager = ScanStateManager(context)

        // Set to SCANNING as if the app was killed mid-scan
        scanManager.status = ScanStatus.SCANNING
        assertEquals(ScanStatus.SCANNING, scanManager.status)

        // Run recovery check
        val wasInterrupted = scanManager.checkAndRecoverInterruptedScan()
        assertTrue("Should detect interrupted scan", wasInterrupted)
        assertEquals("Status must transition to FAILED to prevent auto-scan loop", ScanStatus.FAILED, scanManager.status)
        assertNotNull(scanManager.lastErrorMessage)

        // Running check again should return false since it is no longer SCANNING
        assertFalse(scanManager.checkAndRecoverInterruptedScan())
    }

    @Test
    fun `MediaScannerHelper resolves formats and extensions accurately`() {
        assertEquals("FLAC", MediaScannerHelper.resolveFormat("/Music/song.flac", "audio/flac"))
        assertEquals("WAV", MediaScannerHelper.resolveFormat("/Music/song.wav", "audio/wav"))
        assertEquals("AAC", MediaScannerHelper.resolveFormat("/Music/song.aac", "audio/aac"))
        assertEquals("M4A", MediaScannerHelper.resolveFormat("/Music/song.m4a", "audio/mp4"))
        assertEquals("OGG", MediaScannerHelper.resolveFormat("/Music/song.ogg", "audio/ogg"))
        assertEquals("AIFF", MediaScannerHelper.resolveFormat("/Music/song.aiff", "audio/aiff"))
        assertEquals("MP3", MediaScannerHelper.resolveFormat("/Music/song.mp3", "audio/mpeg"))
    }

    @Test
    fun `SpectrogramEngine analyzeTrack produces valid STFT frequency heatmap and cutoffKhz`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val track = createSampleTrack()

        val analysis = com.example.audio.SpectrogramEngine.analyzeTrack(context, track)

        assertNotNull(analysis)
        assertTrue(analysis.cutoffKhz in 14.0f..24.0f)
        assertEquals(com.example.audio.SpectrogramEngine.NUM_TIME_SLICES, analysis.spectralSlices.size)
        assertTrue(analysis.spectralSlices.isNotEmpty())
        assertEquals(com.example.audio.SpectrogramEngine.NUM_FREQ_BINS, analysis.spectralSlices[0].size)
        assertNotNull(analysis.qualityRating)
        assertNotNull(analysis.notes)
    }

    @Test
    fun `SpectrogramEngine extractWaveform produces normalized amplitude bars`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val track = createSampleTrack()

        val waveform = com.example.audio.SpectrogramEngine.extractWaveform(context, track, barCount = 60)

        assertNotNull(waveform)
        assertEquals(60, waveform.size)
        waveform.forEach { bar ->
            assertTrue(bar in 0.0f..1.0f)
        }
    }
}
