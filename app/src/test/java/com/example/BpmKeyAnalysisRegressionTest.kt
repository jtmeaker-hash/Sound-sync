package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.metadata.LocalPcmAudioAnalyzer
import com.example.model.AnalysisState
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BpmKeyAnalysisRegressionTest {

    private lateinit var context: Context
    private lateinit var analyzer: LocalPcmAudioAnalyzer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        analyzer = LocalPcmAudioAnalyzer(context)
    }

    /**
     * Synthesizes a realistic kick drum transient at the given sample index.
     * Pitch envelope sweeps from 160 Hz down to 45 Hz with exponential decay.
     */
    private fun renderKick(samples: FloatArray, startSample: Int, sampleRate: Int) {
        val kickDurationSamples = (sampleRate * 0.18).toInt()
        var phase = 0.0
        for (i in 0 until kickDurationSamples) {
            val idx = startSample + i
            if (idx >= samples.size) break
            val t = i.toDouble() / sampleRate
            val freq = 45.0 + 115.0 * exp(-t * 25.0)
            phase += 2.0 * PI * freq / sampleRate
            val env = exp(-t * 18.0)
            samples[idx] = (samples[idx] + (sin(phase) * env * 0.8).toFloat()).coerceIn(-1.0f, 1.0f)
        }
    }

    /**
     * Synthesizes audio with kicks on every beat of a target BPM (4-on-the-floor).
     */
    private fun generateFourOnTheFloorTrack(bpm: Double, durationSec: Double = 10.0, sampleRate: Int = 44100): FloatArray {
        val totalSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(totalSamples)
        val beatIntervalSec = 60.0 / bpm
        var currentSec = 0.0
        while (currentSec < durationSec) {
            val sampleIdx = (currentSec * sampleRate).toInt()
            renderKick(samples, sampleIdx, sampleRate)
            currentSec += beatIntervalSec
        }
        return samples
    }

    /**
     * Synthesizes a genuine slow-tempo track (e.g. 75 or 80 BPM).
     * Kicks only occur on the slow-tempo beats (NO kicks on the double-time off-beats).
     */
    private fun generateGenuineSlowTempoTrack(bpm: Double, durationSec: Double = 10.0, sampleRate: Int = 44100): FloatArray {
        val totalSamples = (durationSec * sampleRate).toInt()
        val samples = FloatArray(totalSamples)
        val beatIntervalSec = 60.0 / bpm
        var currentSec = 0.0
        while (currentSec < durationSec) {
            val sampleIdx = (currentSec * sampleRate).toInt()
            renderKick(samples, sampleIdx, sampleRate)
            currentSec += beatIntervalSec
        }
        return samples
    }

    @Test
    fun testHardstyle150Bpm_detectedAt150Bpm_notHalfTime75Bpm() {
        val samples = generateFourOnTheFloorTrack(bpm = 150.0, durationSec = 10.0, sampleRate = 44100)
        val result = analyzer.detectBpm(samples, 44100)
        assertNotNull("BPM detection should produce result for 150 BPM track", result)
        val detectedBpm = result!!.first
        assertTrue(
            "150 BPM Hardstyle must be detected in 148..152 BPM, but was $detectedBpm (must NOT collapse to ~75 BPM)",
            detectedBpm in 148.0..152.0
        )
    }

    @Test
    fun testFastHardcore160Bpm_detectedAt160Bpm_notHalfTime80Bpm() {
        val samples = generateFourOnTheFloorTrack(bpm = 160.0, durationSec = 10.0, sampleRate = 44100)
        val result = analyzer.detectBpm(samples, 44100)
        assertNotNull("BPM detection should produce result for 160 BPM track", result)
        val detectedBpm = result!!.first
        assertTrue(
            "160 BPM Hardcore must be detected in 158..162 BPM, but was $detectedBpm (must NOT collapse to ~80 BPM)",
            detectedBpm in 158.0..162.0
        )
    }

    @Test
    fun testFastHardDance170Bpm_detectedAt170Bpm_notHalfTime85Bpm() {
        val samples = generateFourOnTheFloorTrack(bpm = 170.0, durationSec = 10.0, sampleRate = 44100)
        val result = analyzer.detectBpm(samples, 44100)
        assertNotNull("BPM detection should produce result for 170 BPM track", result)
        val detectedBpm = result!!.first
        assertTrue(
            "170 BPM Hard Dance must be detected in 168..172 BPM, but was $detectedBpm (must NOT collapse to ~85 BPM)",
            detectedBpm in 168.0..172.0
        )
    }

    @Test
    fun testGenuineSlowTempo75Bpm_preservedAt75Bpm_notDoubleTime150Bpm() {
        val samples = generateGenuineSlowTempoTrack(bpm = 75.0, durationSec = 10.0, sampleRate = 44100)
        val result = analyzer.detectBpm(samples, 44100)
        assertNotNull("BPM detection should produce result for 75 BPM track", result)
        val detectedBpm = result!!.first
        assertTrue(
            "Genuine 75 BPM track must be preserved in 73..77 BPM, but was $detectedBpm (must NOT be doubled blindly to 150 BPM)",
            detectedBpm in 73.0..77.0
        )
    }

    @Test
    fun testGenuineSlowTempo80Bpm_preservedAt80Bpm_notDoubleTime160Bpm() {
        val samples = generateGenuineSlowTempoTrack(bpm = 80.0, durationSec = 10.0, sampleRate = 44100)
        val result = analyzer.detectBpm(samples, 44100)
        assertNotNull("BPM detection should produce result for 80 BPM track", result)
        val detectedBpm = result!!.first
        assertTrue(
            "Genuine 80 BPM track must be preserved in 78..82 BPM, but was $detectedBpm (must NOT be doubled blindly to 160 BPM)",
            detectedBpm in 78.0..82.0
        )
    }

    @Test
    fun testMusicalKeyDetection_AMajorAndAMinor() {
        val sampleRate = 44100
        val durationSec = 6.0
        val numSamples = (durationSec * sampleRate).toInt()

        // 1. Synthesize A Major chord (A=440Hz, C#=554.37Hz, E=659.25Hz)
        val aMajorSamples = FloatArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            ((sin(2.0 * PI * 440.0 * t) +
              sin(2.0 * PI * 554.37 * t) +
              sin(2.0 * PI * 659.25 * t)) / 3.0).toFloat()
        }
        val majorResult = analyzer.detectKey(aMajorSamples, sampleRate)
        assertNotNull("Key detector should produce result for A major chord", majorResult)
        assertTrue("Detected key should be A major (11B), was ${majorResult?.first}", majorResult!!.first.contains("A major", ignoreCase = true))
        assertEquals("11B", majorResult.second)

        // 2. Synthesize A Minor chord (A=440Hz, C=523.25Hz, E=659.25Hz)
        val aMinorSamples = FloatArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            ((sin(2.0 * PI * 440.0 * t) +
              sin(2.0 * PI * 523.25 * t) +
              sin(2.0 * PI * 659.25 * t)) / 3.0).toFloat()
        }
        val minorResult = analyzer.detectKey(aMinorSamples, sampleRate)
        assertNotNull("Key detector should produce result for A minor chord", minorResult)
        assertTrue("Detected key should be A minor (8A), was ${minorResult?.first}", minorResult!!.first.contains("A minor", ignoreCase = true))
        assertEquals("8A", minorResult.second)
    }

    @Test
    fun testStaleAnalyzingJobsRecovery() {
        val trackStore = mutableMapOf<String, TrackEntity>()
        var staleRecoveredCount = 0

        val fakeDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "recoverStaleAnalyzingTracks" -> {
                    val stale = trackStore.values.filter { it.analysisState == AnalysisState.ANALYSING.name }
                    stale.forEach {
                        trackStore[it.id] = it.copy(analysisState = AnalysisState.QUEUED.name)
                        staleRecoveredCount++
                    }
                    stale.size
                }
                "getTrackById" -> trackStore[args[0] as String]
                else -> null
            }
        } as TrackDao

        val entity1 = TrackEntity(id = "1", title = "Track 1", artist = "A", filePath = "/path1", analysisState = AnalysisState.ANALYSING.name)
        val entity2 = TrackEntity(id = "2", title = "Track 2", artist = "B", filePath = "/path2", analysisState = AnalysisState.COMPLETE.name)
        val entity3 = TrackEntity(id = "3", title = "Track 3", artist = "C", filePath = "/path3", analysisState = AnalysisState.ANALYSING.name)
        trackStore["1"] = entity1
        trackStore["2"] = entity2
        trackStore["3"] = entity3

        runBlocking {
            val count = fakeDao.recoverStaleAnalyzingTracks()
            assertEquals("Should recover 2 stale tracks", 2, count)
            assertEquals("Track 1 must be QUEUED", AnalysisState.QUEUED.name, trackStore["1"]?.analysisState)
            assertEquals("Track 2 must remain COMPLETE", AnalysisState.COMPLETE.name, trackStore["2"]?.analysisState)
            assertEquals("Track 3 must be QUEUED", AnalysisState.QUEUED.name, trackStore["3"]?.analysisState)
        }
    }
}
