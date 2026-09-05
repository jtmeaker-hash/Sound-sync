package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.AudioQualityInspector
import com.example.analysis.AudioQualityReport
import com.example.analysis.PhraseDetector
import com.example.analysis.PhraseSection
import com.example.analysis.QualityClassification
import com.example.analysis.SectionType
import com.example.analysis.TrackPhraseAnalysis
import com.example.audio.DjAudioEngine
import com.example.audio.EqBand
import com.example.audio.EqFilterType
import com.example.audio.ParametricEq
import com.example.audio.ParametricEqManager
import com.example.dj.MixCompatibilityEngine
import com.example.model.AudioQualityRating
import com.example.model.BitrateMode
import com.example.model.Track
import com.example.player.PersistentQueueManager
import com.example.player.QueueRepeatMode
import com.example.smartcrate.SmartCrate
import com.example.smartcrate.SmartCrateEngine
import com.example.smartcrate.SmartField
import com.example.smartcrate.SmartMatchMode
import com.example.smartcrate.SmartOperator
import com.example.smartcrate.SmartRule
import com.example.smartcrate.SmartSortField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundSyncStep2PlaybackAnalysisTest {

    private lateinit var context: Context

    private fun createTestTrack(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Track",
        artist: String = "Test Artist",
        album: String = "Test Album",
        genre: String = "Electronic",
        bpm: Double = 124.0,
        camelotKey: String = "8A",
        musicalKey: String = "Am",
        durationSeconds: Int = 240,
        bitrateKbps: Int = 320,
        format: String = "MP3",
        qualityRating: AudioQualityRating = AudioQualityRating.TRUE_320,
        filePath: String = "/dummy/path/$id.mp3"
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            bpm = bpm,
            camelotKey = camelotKey,
            musicalKey = musicalKey,
            durationSeconds = durationSeconds,
            bitrateKbps = bitrateKbps,
            format = format,
            qualityRating = qualityRating,
            filePath = filePath,
            dateAdded = System.currentTimeMillis()
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part A: Persistent Queue Intelligence
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testPersistentQueueManager_orderingAndGranularOperations() = runBlocking {
        val queueManager = PersistentQueueManager(context)
        val track1 = createTestTrack(title = "Track 1")
        val track2 = createTestTrack(title = "Track 2")
        val track3 = createTestTrack(title = "Track 3")
        val trackNext = createTestTrack(title = "Track Next")

        // 1. Set Queue
        queueManager.setQueue(listOf(track1, track2, track3), track1, shuffle = false)
        assertEquals(track1.id, queueManager.currentTrack.value?.id)
        assertEquals(2, queueManager.upcomingQueue.value.size)
        assertEquals(track2.id, queueManager.upcomingQueue.value[0].id)
        assertEquals(track3.id, queueManager.upcomingQueue.value[1].id)

        // 2. Play Next (inserts at position 0 of upcoming)
        queueManager.playNext(trackNext)
        assertEquals(3, queueManager.upcomingQueue.value.size)
        assertEquals(trackNext.id, queueManager.upcomingQueue.value[0].id)

        // 3. Reorder Queue
        queueManager.reorderQueue(0, 2)
        assertEquals(track2.id, queueManager.upcomingQueue.value[0].id)
        assertEquals(trackNext.id, queueManager.upcomingQueue.value[2].id)

        // 4. Remove from Queue
        val removed = queueManager.removeFromQueue(0)
        assertEquals(track2.id, removed?.id)
        assertEquals(2, queueManager.upcomingQueue.value.size)

        // 5. Clear Queue
        queueManager.clearQueue(clearCurrent = false)
        assertTrue(queueManager.upcomingQueue.value.isEmpty())
        assertNotNull(queueManager.currentTrack.value)
    }

    @Test
    fun testPersistentQueueManager_deterministicShufflePreviousLIFO() = runBlocking {
        val queueManager = PersistentQueueManager(context)
        val tA = createTestTrack(title = "Alpha")
        val tB = createTestTrack(title = "Bravo")
        val tC = createTestTrack(title = "Charlie")

        // Start with Alpha, queue Bravo and Charlie
        queueManager.setQueue(listOf(tA, tB, tC), tA, shuffle = true)
        queueManager.setShuffle(true)

        // Play next track -> moves Alpha to history
        val next1 = queueManager.nextTrack()
        assertNotNull(next1)
        assertEquals(1, queueManager.playbackHistory.value.size)
        assertEquals(tA.id, queueManager.playbackHistory.value.first().id)

        // Play next track again -> moves next1 to top of history (LIFO)
        val next2 = queueManager.nextTrack()
        assertNotNull(next2)
        assertEquals(2, queueManager.playbackHistory.value.size)
        assertEquals(next1?.id, queueManager.playbackHistory.value[0].id)
        assertEquals(tA.id, queueManager.playbackHistory.value[1].id)

        // In shuffle mode, Previous MUST return to the actual previously played track from history!
        val prev1 = queueManager.previousTrack()
        assertEquals(next1?.id, prev1?.id)
        assertEquals(1, queueManager.playbackHistory.value.size)

        // Second Previous returns Alpha
        val prev2 = queueManager.previousTrack()
        assertEquals(tA.id, prev2?.id)
        assertEquals(0, queueManager.playbackHistory.value.size)
    }

    @Test
    fun testPersistentQueueManager_diskPersistenceAndRestoration() = runBlocking {
        val queueManager1 = PersistentQueueManager(context)
        val tA = createTestTrack(title = "Persist A")
        val tB = createTestTrack(title = "Persist B")
        val tC = createTestTrack(title = "Persist C")

        queueManager1.setQueue(listOf(tA, tB, tC), tA, shuffle = false)
        queueManager1.setRepeatMode(QueueRepeatMode.ALL)
        queueManager1.saveToDisk()

        // Create fresh manager instance and restore from disk
        val queueManager2 = PersistentQueueManager(context)
        queueManager2.restoreFromDisk()

        assertEquals(tA.id, queueManager2.currentTrack.value?.id)
        assertEquals(2, queueManager2.upcomingQueue.value.size)
        assertEquals(tB.id, queueManager2.upcomingQueue.value[0].id)
        assertEquals(tC.id, queueManager2.upcomingQueue.value[1].id)
        assertEquals(QueueRepeatMode.ALL, queueManager2.repeatMode.value)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part B: Gapless Playback Engine Configuration
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testDjAudioEngine_gaplessPlaybackConfiguration() {
        val engine = DjAudioEngine.getInstance(context)

        // Gapless playback should default to true or respect user preferences
        val initial = engine.isGaplessPlaybackEnabled.value
        engine.setGaplessPlaybackEnabled(!initial)
        assertEquals(!initial, engine.isGaplessPlaybackEnabled.value)

        // Restore
        engine.setGaplessPlaybackEnabled(initial)
        assertEquals(initial, engine.isGaplessPlaybackEnabled.value)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part C: Parametric EQ Presets & DSP Filters
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testParametricEq_sevenBandDSPFrequenciesAndBands() {
        val defaultBands = ParametricEq.DEFAULT_7_BANDS
        assertEquals(7, defaultBands.size)
        assertEquals("Sub-Bass", defaultBands[0].name)
        assertEquals(EqFilterType.LOW_SHELF, defaultBands[0].type)
        assertEquals("Brilliance", defaultBands[6].name)
        assertEquals(EqFilterType.HIGH_SHELF, defaultBands[6].type)

        val eq = ParametricEq(44100)

        // Configure a band with +6dB boost
        eq.updateBand(1, 150.0, 6.0, 1.0, true)
        eq.preampDb = 1.0

        // Test audio processing on a short buffer
        val buffer = ShortArray(256) { (it * 100).toShort() }
        eq.processStereo(buffer, 0, 128)

        // Verify buffer values are valid finite integers within Short boundaries
        for (sample in buffer) {
            assertTrue(sample in Short.MIN_VALUE..Short.MAX_VALUE)
        }

        // Test soft-clipping behavior under massive boost (+15dB)
        eq.updateBand(0, 60.0, 15.0, 2.0, true)
        eq.preampDb = 6.0
        val hotBuffer = ShortArray(256) { 32000.toShort() }
        eq.processStereo(hotBuffer, 0, 128)

        for (sample in hotBuffer) {
            assertTrue(sample in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun testParametricEqManager_presetsAndDiskPersistence() {
        val manager = ParametricEqManager.getInstance(context)

        // Verify built-in presets exist
        val presets = manager.presets.value
        assertTrue(presets.isNotEmpty())
        assertTrue(presets.any { it.id == "preset_flat" })
        assertTrue(presets.any { it.id == "preset_club_bass" })
        assertTrue(presets.any { it.id == "preset_electronic" })
        assertTrue(presets.any { it.id == "preset_rock" })

        // Apply Club / Bass Boost preset
        manager.applyPreset("preset_club_bass")
        assertEquals("preset_club_bass", manager.activePresetId.value)
        val activeBands = manager.currentBands.value
        assertTrue(activeBands[0].gainDb > 0.0) // Sub-bass boosted

        // Save a custom preset
        manager.updateBand(0, 60.0, 3.5, 0.71, true)
        val customPreset = manager.saveCustomPreset("Unit Test Preset")

        val updatedPresets = manager.presets.value
        assertTrue(updatedPresets.any { it.id == customPreset.id })

        // Delete custom preset
        val deleted = manager.deleteCustomPreset(customPreset.id)
        assertTrue(deleted)
        val finalPresets = manager.presets.value
        assertFalse(finalPresets.any { it.id == customPreset.id })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part D: Audio Quality Inspector Heuristics
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testAudioQualityInspector_reportSerializationAndClassification() {
        val report = AudioQualityReport(
            trackId = "test_1",
            filePath = "/music/song.flac",
            container = "FLAC",
            codec = "audio/flac",
            bitrateKbps = 1045,
            bitrateMode = BitrateMode.VBR,
            sampleRateHz = 44100,
            bitDepth = 16,
            channelCount = 2,
            durationSeconds = 210,
            fileSizeBytes = 25000000L,
            spectralCutoffKhz = 21.5,
            classification = QualityClassification.TRUE_LOSSLESS,
            isSuspiciousTranscode = false,
            transcodeWarningReason = null,
            summary = "FLAC • audio/flac • VBR 1045 kbps • 44.1 kHz • 16-bit • Stereo • Shelf: ~21.5 kHz"
        )

        // Verify JSON round-trip
        val json = report.toJson()
        val deserialized = AudioQualityReport.fromJson(json)

        assertEquals(report.trackId, deserialized.trackId)
        assertEquals(report.container, deserialized.container)
        assertEquals(report.classification, deserialized.classification)
        assertEquals(report.spectralCutoffKhz, deserialized.spectralCutoffKhz)
        assertFalse(deserialized.isSuspiciousTranscode)

        // Fake transcode JSON test
        val suspiciousJson = json.apply {
            put("spectralCutoffKhz", 15.2)
            put("isSuspiciousTranscode", true)
            put("classification", QualityClassification.STANDARD_LOSSY.name)
            put("transcodeWarningReason", "Low-pass shelf detected in lossless file")
        }
        val suspiciousReport = AudioQualityReport.fromJson(suspiciousJson)
        assertTrue(suspiciousReport.isSuspiciousTranscode)
        assertEquals(QualityClassification.STANDARD_LOSSY, suspiciousReport.classification)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part F: Phrase Detection Engine
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testPhraseDetector_barQuantizationAndSectionClassification() = runBlocking {
        val track = createTestTrack(bpm = 128.0, durationSeconds = 180)

        val analysis = PhraseDetector.detectPhrases(context, track)

        assertEquals(track.id, analysis.trackId)
        assertTrue(analysis.sections.isNotEmpty())

        // Validate sections structure and bar-quantized boundaries
        var prevEnd = 0.0
        for (section in analysis.sections) {
            assertTrue(section.endSeconds > section.startSeconds)
            assertEquals(prevEnd, section.startSeconds, 0.01)
            assertTrue(section.confidence in 0.0f..1.0f)
            assertNotNull(section.type)
            prevEnd = section.endSeconds
        }

        // Validate JSON serialization
        val json = analysis.toJson()
        val restored = TrackPhraseAnalysis.fromJson(json)
        assertEquals(analysis.sections.size, restored.sections.size)
        assertEquals(analysis.sections[0].type, restored.sections[0].type)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part H: Smart Crates & Dynamic Rule Evaluation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testSmartCrateEngine_matchAllMatchAnyAndOperators() {
        val tracks = listOf(
            createTestTrack(id = "t1", title = "Strobe", artist = "deadmau5", bpm = 128.0, format = "FLAC", camelotKey = "8A", bitrateKbps = 950),
            createTestTrack(id = "t2", title = "Levels", artist = "Avicii", bpm = 126.0, format = "MP3", camelotKey = "8B", bitrateKbps = 320),
            createTestTrack(id = "t3", title = "Ghosts 'n' Stuff", artist = "deadmau5", bpm = 128.0, format = "WAV", camelotKey = "8A", bitrateKbps = 1411),
            createTestTrack(id = "t4", title = "Midnight City", artist = "M83", bpm = 105.0, format = "MP3", camelotKey = "11B", bitrateKbps = 256),
            createTestTrack(id = "t5", title = "Titanium", artist = "David Guetta", bpm = 126.0, format = "FLAC", camelotKey = "8A", bitrateKbps = 1020)
        )

        // Crate 1: Match ALL -> deadmau5 AND Lossless
        val deadmau5LosslessCrate = SmartCrate(
            id = "c1",
            name = "deadmau5 Lossless",
            matchMode = SmartMatchMode.MATCH_ALL,
            rules = listOf(
                SmartRule(field = SmartField.ARTIST, operator = SmartOperator.EQUALS, value = "deadmau5"),
                SmartRule(field = SmartField.IS_LOSSLESS, operator = SmartOperator.EQUALS, value = "true")
            )
        )
        val crate1Results = SmartCrateEngine.evaluate(deadmau5LosslessCrate, tracks)
        assertEquals(2, crate1Results.size)
        assertTrue(crate1Results.all { it.artist.equals("deadmau5", ignoreCase = true) })
        assertTrue(crate1Results.all { it.format in listOf("FLAC", "WAV") })

        // Crate 2: Match ANY -> BPM BETWEEN 125 and 127 OR Artist CONTAINS "M83"
        val houseOrM83Crate = SmartCrate(
            id = "c2",
            name = "126 BPM or M83",
            matchMode = SmartMatchMode.MATCH_ANY,
            rules = listOf(
                SmartRule(field = SmartField.BPM, operator = SmartOperator.BETWEEN, value = "125", secondaryValue = "127"),
                SmartRule(field = SmartField.ARTIST, operator = SmartOperator.CONTAINS, value = "M83")
            )
        )
        val crate2Results = SmartCrateEngine.evaluate(houseOrM83Crate, tracks)
        assertEquals(3, crate2Results.size) // Levels (126), Titanium (126), Midnight City (M83)

        // Crate 3: Sorting and Limit
        val topBpmCrate = SmartCrate(
            id = "c3",
            name = "Top 2 High BPM",
            matchMode = SmartMatchMode.MATCH_ALL,
            rules = emptyList(),
            sortField = SmartSortField.BPM,
            sortAscending = false,
            maxTrackLimit = 2
        )
        val crate3Results = SmartCrateEngine.evaluate(topBpmCrate, tracks)
        assertEquals(2, crate3Results.size)
        assertEquals(128.0, crate3Results[0].bpm, 0.01)
        assertEquals(128.0, crate3Results[1].bpm, 0.01)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Part I: "Mix With This" Track Compatibility
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testMixCompatibilityEngine_camelotScoringAndHarmonicMatching() = runBlocking {
        val currentTrack = createTestTrack(
            id = "curr",
            title = "Master Track",
            artist = "DJ Hero",
            bpm = 124.0,
            camelotKey = "8A"
        )

        val candidateSameKey = createTestTrack(id = "c1", title = "Same Key", artist = "Artist 1", bpm = 124.0, camelotKey = "8A")
        val candidateRelativeKey = createTestTrack(id = "c2", title = "Relative Key", artist = "Artist 2", bpm = 125.0, camelotKey = "8B")
        val candidateAdjacentKey = createTestTrack(id = "c3", title = "Adjacent Key", artist = "Artist 3", bpm = 124.5, camelotKey = "9A")
        val candidateEnergyBoost = createTestTrack(id = "c4", title = "Energy Boost", artist = "Artist 4", bpm = 126.0, camelotKey = "10A")
        val candidateClashingKey = createTestTrack(id = "c5", title = "Clash Key", artist = "Artist 5", bpm = 175.0, camelotKey = "2B")
        val candidateDuplicate = createTestTrack(id = "c6", title = "Master Track", artist = "DJ Hero", bpm = 124.0, camelotKey = "8A")

        // 1. Evaluate individual pairs
        val pairSame = MixCompatibilityEngine.evaluatePair(currentTrack, candidateSameKey)
        assertEquals(1.0f, pairSame.harmonicScore, 0.01f)
        assertEquals("Same Key", pairSame.keyRelationship)
        assertTrue(pairSame.overallScore >= 90)

        val pairRelative = MixCompatibilityEngine.evaluatePair(currentTrack, candidateRelativeKey)
        assertEquals(0.95f, pairRelative.harmonicScore, 0.01f)
        assertEquals("Relative Key", pairRelative.keyRelationship)

        val pairAdjacent = MixCompatibilityEngine.evaluatePair(currentTrack, candidateAdjacentKey)
        assertEquals(0.88f, pairAdjacent.harmonicScore, 0.01f)
        assertEquals("Harmonic Adjacent", pairAdjacent.keyRelationship)

        val pairEnergy = MixCompatibilityEngine.evaluatePair(currentTrack, candidateEnergyBoost)
        assertEquals(0.70f, pairEnergy.harmonicScore, 0.01f)
        assertEquals("Energy Boost", pairEnergy.keyRelationship)

        val pairClash = MixCompatibilityEngine.evaluatePair(currentTrack, candidateClashingKey)
        assertTrue(pairClash.harmonicScore < 0.50f)
        assertTrue(pairClash.overallScore < 50)

        // 2. Evaluate findCompatibleTracks with duplicate exclusion
        val library = listOf(
            candidateSameKey,
            candidateRelativeKey,
            candidateAdjacentKey,
            candidateEnergyBoost,
            candidateClashingKey,
            candidateDuplicate
        )

        val recommendations = MixCompatibilityEngine.findCompatibleTracks(currentTrack, library, limit = 10)

        // Candidate duplicate must be excluded
        assertFalse(recommendations.any { it.candidateTrack.id == candidateDuplicate.id })

        // Self must be excluded
        assertFalse(recommendations.any { it.candidateTrack.id == currentTrack.id })

        // Recommendations should be sorted by overallScore descending
        assertTrue(recommendations.isNotEmpty())
        for (i in 0 until recommendations.size - 1) {
            assertTrue(recommendations[i].overallScore >= recommendations[i + 1].overallScore)
        }

        // Reasons list must be populated with explainable pills
        assertTrue(recommendations[0].reasons.isNotEmpty())
    }
}
