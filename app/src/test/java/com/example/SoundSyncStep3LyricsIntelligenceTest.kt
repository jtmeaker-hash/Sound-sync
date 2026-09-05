package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.LyricsDao
import com.example.data.LyricsEntity
import com.example.intelligence.SoundSyncIntelligenceEngine
import com.example.lyrics.LyricLine
import com.example.lyrics.LyricsManager
import com.example.lyrics.LyricsParser
import com.example.lyrics.LyricsSource
import com.example.lyrics.LyricsTimestampEditor
import com.example.model.AudioQualityRating
import com.example.model.Track
import com.example.player.PersistentQueueManager
import com.example.player.QueueRepeatMode
import com.example.player.SmartContinueMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class SoundSyncStep3LyricsIntelligenceTest {

    private lateinit var context: Context
    private lateinit var fakeLyricsDao: LyricsDao

    private fun createFakeLyricsDao(): LyricsDao {
        val map = mutableMapOf<String, LyricsEntity>()
        return object : LyricsDao {
            override fun getLyricsForTrackFlow(trackId: String): Flow<LyricsEntity?> =
                flowOf(map[trackId])

            override suspend fun getLyricsForTrack(trackId: String): LyricsEntity? = map[trackId]

            override fun getLyricsForTrackSync(trackId: String): LyricsEntity? = map[trackId]

            override suspend fun insertOrUpdateLyrics(lyrics: LyricsEntity) {
                map[lyrics.trackId] = lyrics
            }

            override fun insertOrUpdateLyricsSync(lyrics: LyricsEntity) {
                map[lyrics.trackId] = lyrics
            }

            override suspend fun deleteLyricsForTrack(trackId: String) {
                map.remove(trackId)
            }

            override suspend fun getSyncedLyricsCount(): Int = map.values.count { it.isSynced }

            override suspend fun getUserEditedLyricsCount(): Int = map.values.count { it.isUserEdited }

            override suspend fun getTotalLyricsCount(): Int = map.size

            override suspend fun clearAllLyrics() {
                map.clear()
            }
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeLyricsDao = createFakeLyricsDao()
    }

    private fun createTestTrack(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Track",
        artist: String = "Test Artist",
        album: String = "Test Album",
        genre: String = "House",
        bpm: Double = 126.0,
        camelotKey: String = "8A",
        musicalKey: String = "Am",
        durationSeconds: Int = 240,
        bitrateKbps: Int = 320,
        format: String = "MP3",
        qualityRating: AudioQualityRating = AudioQualityRating.TRUE_320,
        filePath: String = "/dummy/path/$id.mp3",
        rating: Int = 3,
        dateAdded: Long = System.currentTimeMillis(),
        releaseYear: Int? = 2021,
        artworkUrl: String? = "https://example.com/art.jpg",
        energyRating: Int = 7
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
            rating = rating,
            dateAdded = dateAdded,
            releaseYear = releaseYear,
            artworkUrl = artworkUrl,
            energyRating = energyRating
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. LyricsParser Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testLyricsParserStandardLrcWithMilliseconds() {
        val lrcContent = """
            [ti:Levels]
            [ar:Avicii]
            [al:Levels EP]
            [offset:+200]
            [00:15.50]Oh sometimes I get a good feeling
            [01:02.123]Yeah I get a feeling that I never never never
        """.trimIndent()

        val parsed = LyricsParser.parse(lrcContent)

        assertTrue("Parsed lyrics should be synced", parsed.isSynced)
        assertEquals("Levels", parsed.metadataTags["ti"])
        assertEquals("Avicii", parsed.metadataTags["ar"])
        assertEquals("Levels EP", parsed.metadataTags["al"])
        assertEquals(200L, parsed.offsetMs)
        assertEquals(2, parsed.lines.size)

        // 15.50s -> 15500ms
        assertEquals(15500L, parsed.lines[0].timeMs)
        assertEquals("Oh sometimes I get a good feeling", parsed.lines[0].text)

        // 1m 02.123s -> 62123ms
        assertEquals(62123L, parsed.lines[1].timeMs)
        assertEquals("Yeah I get a feeling that I never never never", parsed.lines[1].text)
    }

    @Test
    fun testLyricsParserPlainTextFallback() {
        val plain = """
            First verse with no timestamps
            Second verse with no timestamps
            Chorus singing loud
        """.trimIndent()

        val parsed = LyricsParser.parse(plain)

        assertFalse("Plain lyrics should not be synced", parsed.isSynced)
        assertTrue("Lines list should be empty for unaligned text", parsed.lines.isEmpty())
        assertEquals(plain, parsed.plainText)
    }

    @Test
    fun testLyricsGeneratorRoundTrip() {
        val lines = listOf(
            LyricLine(timeMs = 5200L, text = "Intro beat dropping"),
            LyricLine(timeMs = 12850L, text = "Main melody begins")
        )

        val generatedLrc = LyricsParser.toLrcString(
            lines = lines,
            title = "Roundtrip Track",
            artist = "SoundSync DJ",
            album = "Test Album",
            offsetMs = 100L
        )

        assertTrue(generatedLrc.contains("[ti:Roundtrip Track]"))
        assertTrue(generatedLrc.contains("[ar:SoundSync DJ]"))
        assertTrue(generatedLrc.contains("[00:05.20]Intro beat dropping"))
        assertTrue(generatedLrc.contains("[00:12.85]Main melody begins"))

        // Re-parse the generated LRC string and verify parity
        val reParsed = LyricsParser.parse(generatedLrc)
        assertTrue(reParsed.isSynced)
        assertEquals(2, reParsed.lines.size)
        assertEquals(5200L, reParsed.lines[0].timeMs)
        assertEquals("Intro beat dropping", reParsed.lines[0].text)
        assertEquals(12850L, reParsed.lines[1].timeMs)
        assertEquals("Main melody begins", reParsed.lines[1].text)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. LyricsTimestampEditor Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testLyricsTimestampEditorStamping() {
        val rawLines = listOf(
            LyricLine(timeMs = 0L, text = "Line 1"),
            LyricLine(timeMs = 0L, text = "Line 2"),
            LyricLine(timeMs = 0L, text = "Line 3")
        )
        val editor = LyricsTimestampEditor(initialLines = rawLines)

        assertEquals(0, editor.activeLineIndex)
        assertEquals(0L, editor.lines[0].timeMs)

        // Stamp line 0 at 4500ms
        editor.stampCurrentTime(4500L)
        assertEquals(4500L, editor.lines[0].timeMs)
        assertEquals(1, editor.activeLineIndex) // Auto advances to next line

        // Stamp line 1 at 8200ms
        editor.stampCurrentTime(8200L)
        assertEquals(8200L, editor.lines[1].timeMs)
        assertEquals(2, editor.activeLineIndex)
    }

    @Test
    fun testLyricsTimestampEditorAdjustmentsAndShift() {
        val lines = listOf(
            LyricLine(timeMs = 5000L, text = "Line 1"),
            LyricLine(timeMs = 10000L, text = "Line 2")
        )
        val editor = LyricsTimestampEditor(initialLines = lines)

        // Line timestamp adjustment (+150ms on line 0, -200ms on line 1)
        editor.adjustLineTimestamp(0, 150L)
        assertEquals(5150L, editor.lines[0].timeMs)

        editor.adjustLineTimestamp(1, -200L)
        assertEquals(9800L, editor.lines[1].timeMs)

        // Global shift by +500ms
        editor.shiftAllTimestamps(500L)
        assertEquals(5650L, editor.lines[0].timeMs)
        assertEquals(10300L, editor.lines[1].timeMs)
    }

    @Test
    fun testLyricsTimestampEditorUndoRedo() {
        val lines = listOf(LyricLine(timeMs = 5000L, text = "Initial line"))
        val editor = LyricsTimestampEditor(initialLines = lines)

        assertFalse("Cannot undo initially", editor.canUndo)

        editor.adjustLineTimestamp(0, 300L)
        assertEquals(5300L, editor.lines[0].timeMs)
        assertTrue("Can undo after change", editor.canUndo)

        // Perform undo
        val undone = editor.undo()
        assertTrue(undone)
        assertEquals(5000L, editor.lines[0].timeMs)
        assertTrue("Can redo after undo", editor.canRedo)

        // Perform redo
        val redone = editor.redo()
        assertTrue(redone)
        assertEquals(5300L, editor.lines[0].timeMs)
    }

    @Test
    fun testLyricsTimestampEditorExportLrc() {
        val lines = listOf(
            LyricLine(timeMs = 3000L, text = "Verse line"),
            LyricLine(timeMs = 7000L, text = "Chorus line")
        )
        val editor = LyricsTimestampEditor(initialLines = lines)
        val track = createTestTrack(title = "My Track", artist = "My Artist", album = "My Album")

        val exported = LyricsParser.toLrcString(
            lines = editor.lines,
            title = track.title,
            artist = track.artist,
            album = track.album,
            offsetMs = editor.offsetMs
        )
        assertTrue(exported.contains("[ti:My Track]"))
        assertTrue(exported.contains("[00:03.00]Verse line"))
        assertTrue(exported.contains("[00:07.00]Chorus line"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. LyricsManager Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testLyricsManagerUserEditedPriority() {
        runBlocking {
            val track = createTestTrack(id = "priority_test_1", title = "Priority Track")
            val manager = LyricsManager.createForTesting(context, fakeLyricsDao)

            // Save user-edited lyrics in Room DB
            manager.saveUserEditedLyrics(
                trackId = track.id,
                lines = listOf(LyricLine(timeMs = 1000L, text = "User lyric line")),
                plainText = "User lyric line",
                offsetMs = 50L
            )

            val resolved = manager.getLyrics(track)
            assertNotNull(resolved)
            assertEquals(LyricsSource.USER_EDITED, resolved?.source)
            assertTrue(resolved?.isUserEdited == true)
            assertEquals(1, resolved?.lines?.size)
            assertEquals("User lyric line", resolved?.lines?.first()?.text)
        }
    }

    @Test
    fun testLyricsManagerExportToLrcFile() {
        runBlocking {
            val track = createTestTrack(id = "export_test_1", title = "Export Track", artist = "DJ SoundSync")
            val manager = LyricsManager.createForTesting(context, fakeLyricsDao)
            val tempFile = File(context.cacheDir, "test_export.lrc")
            if (tempFile.exists()) tempFile.delete()

            manager.saveUserEditedLyrics(
                trackId = track.id,
                lines = listOf(LyricLine(timeMs = 2500L, text = "Exported line")),
                plainText = "Exported line",
                offsetMs = 0L
            )

            val success = manager.exportToLrcFile(track, tempFile)
            assertTrue(success)
            assertTrue(tempFile.exists())
            val content = tempFile.readText()
            assertTrue(content.contains("[00:02.50]Exported line"))
            tempFile.delete()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. SoundSyncIntelligenceEngine Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testMixCompatibilityScoring() {
        val track1 = createTestTrack(id = "t1", bpm = 124.0, camelotKey = "8A", energyRating = 7)
        val track2 = createTestTrack(id = "t2", bpm = 125.0, camelotKey = "8A", energyRating = 8)
        val track3 = createTestTrack(id = "t3", bpm = 175.0, camelotKey = "1B", energyRating = 2)

        val score1_2 = SoundSyncIntelligenceEngine.scoreMixCompatibility(track1, track2)
        val score1_3 = SoundSyncIntelligenceEngine.scoreMixCompatibility(track1, track3)

        assertTrue("Compatible track overall score should be >= 75", score1_2.overallScore >= 75)
        assertTrue("Compatible track should have high harmonic score", score1_2.harmonicScore >= 0.8f)
        assertTrue("Compatible track should provide explanation reasons", score1_2.reasons.isNotEmpty())

        assertTrue("Incompatible track overall score should be < 50", score1_3.overallScore < 50)
        assertTrue("Incompatible track should have lower harmonic score", score1_3.harmonicScore < 0.6f)
    }

    @Test
    fun testGetSimilarTracks() {
        runBlocking {
            val base = createTestTrack(id = "base", title = "Base Track", genre = "Tech House", bpm = 126.0, camelotKey = "7A", releaseYear = 2021)
            val matchCandidate = createTestTrack(id = "match", title = "Match Candidate", genre = "Tech House", bpm = 126.5, camelotKey = "7A", releaseYear = 2022)
            val mismatchCandidate = createTestTrack(id = "mismatch", title = "Mismatch Candidate", genre = "Classical", bpm = 70.0, camelotKey = "2B", releaseYear = 1890)

            val similar = SoundSyncIntelligenceEngine.getSimilarTracks(base, listOf(matchCandidate, mismatchCandidate))

            assertEquals(1, similar.size)
            assertEquals("match", similar.first().track.id)
            assertTrue(similar.first().genreMatch)
            assertTrue(similar.first().tempoMatch)
            assertTrue(similar.first().similarityScore >= 60)
        }
    }

    @Test
    fun testGetRecommendedNextTracksExcludesHistory() {
        runBlocking {
            val current = createTestTrack(id = "cur", genre = "House", bpm = 125.0, camelotKey = "8A")
            val availableNext = createTestTrack(id = "avail", genre = "House", bpm = 125.5, camelotKey = "8A")
            val recentPlayed = createTestTrack(id = "recent", genre = "House", bpm = 125.0, camelotKey = "8A")

            val library = listOf(current, availableNext, recentPlayed)
            val history = listOf(recentPlayed)

            val recommendations = SoundSyncIntelligenceEngine.getRecommendedNextTracks(
                currentTrack = current,
                library = library,
                recentHistory = history,
                limit = 5
            )

            assertEquals(1, recommendations.size)
            assertEquals("avail", recommendations.first().id)
            assertFalse("Should not recommend current track", recommendations.any { it.id == current.id })
            assertFalse("Should not recommend recently played tracks", recommendations.any { it.id == recentPlayed.id })
        }
    }

    @Test
    fun testForgottenAndRarelyPlayedTracks() {
        val now = System.currentTimeMillis()
        val oldTrack = createTestTrack(id = "old", dateAdded = now - (90L * 24 * 3600 * 1000), rating = 4)
        val newTrack = createTestTrack(id = "new", dateAdded = now - (10L * 24 * 3600 * 1000), rating = 5)
        val lowRatedTrack = createTestTrack(id = "low", dateAdded = now - (20L * 24 * 3600 * 1000), rating = 1)

        val forgotten = SoundSyncIntelligenceEngine.getForgottenTracks(listOf(oldTrack, newTrack, lowRatedTrack))
        assertTrue(forgotten.any { it.id == "old" })
        assertFalse(forgotten.any { it.id == "new" })

        val rarely = SoundSyncIntelligenceEngine.getRarelyPlayedTracks(listOf(oldTrack, newTrack, lowRatedTrack))
        assertTrue(rarely.any { it.id == "low" })
    }

    @Test
    fun testGetLibraryHealthInsights() {
        val completeTrack = createTestTrack(
            id = "complete",
            artist = "Swedish House Mafia",
            artworkUrl = "https://example.com/art.jpg",
            bpm = 126.0,
            camelotKey = "8A",
            format = "FLAC",
            qualityRating = AudioQualityRating.TRUE_LOSSLESS
        )
        val defectiveTrack = createTestTrack(
            id = "defective",
            artist = "",
            artworkUrl = null,
            bpm = 0.0,
            musicalKey = "",
            camelotKey = "",
            format = "MP3",
            qualityRating = AudioQualityRating.SUSPICIOUS_UPSCALED
        )

        val report = SoundSyncIntelligenceEngine.getLibraryHealthInsights(listOf(completeTrack, defectiveTrack))

        assertEquals(2, report.totalTracks)
        assertEquals(1, report.tracksMissingArtist)
        assertEquals(1, report.tracksMissingArtwork)
        assertEquals(1, report.tracksMissingBpmOrKey)
        assertEquals(1, report.suspiciousTranscodeCount)
        assertEquals(1, report.losslessTracksCount)
    }

    @Test
    fun testGetSmartCrateSuggestions() {
        val suggestions = SoundSyncIntelligenceEngine.getSmartCrateSuggestions(emptyList())

        assertTrue("Should return crate suggestions", suggestions.isNotEmpty())
        assertTrue("Should include Peak-Time suggestion", suggestions.any { it.name.contains("Peak-Time", ignoreCase = true) })
        assertTrue("Should include Lossless suggestion", suggestions.any { it.name.contains("Lossless", ignoreCase = true) })
    }

    @Test
    fun testGetTrackIntelligence() {
        val track = createTestTrack(
            id = "intel_1",
            title = "Acoustic Sunset",
            artist = "Deep Waves",
            artworkUrl = "https://example.com/art.jpg",
            bpm = 124.0,
            camelotKey = "8A",
            format = "FLAC",
            qualityRating = AudioQualityRating.TRUE_LOSSLESS
        )
        val compatibleOther = createTestTrack(id = "intel_2", bpm = 125.0, camelotKey = "8A", genre = "House")

        val data = SoundSyncIntelligenceEngine.getTrackIntelligence(track, listOf(track, compatibleOther))

        assertTrue(data.metadataTrustScore >= 0.7f)
        assertTrue(data.hasBpmKey)
        assertTrue(data.isLossless)
        assertFalse(data.isSuspiciousTranscode)
        assertEquals(1, data.mixCompatibleTracksCount)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. PersistentQueueManager Smart Continue Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun testSmartContinueMixCompatible() {
        val queueManager = PersistentQueueManager(context)
        val current = createTestTrack(id = "current_dj", bpm = 124.0, camelotKey = "8A")
        val compatible = createTestTrack(id = "compat_dj", bpm = 125.0, camelotKey = "8A")
        val incompatible = createTestTrack(id = "incompat_dj", bpm = 175.0, camelotKey = "1B")

        queueManager.contextTrackProvider = { listOf(current, compatible, incompatible) }
        queueManager.setSmartContinueMode(SmartContinueMode.MIX_COMPATIBLE)
        queueManager.setQueue(listOf(current), current)

        // Advance queue to trigger exhaustion
        val next = queueManager.nextTrack()

        assertNotNull(next)
        assertEquals("compat_dj", next?.id)
    }

    @Test
    fun testSmartContinueSimilarMusic() {
        val queueManager = PersistentQueueManager(context)
        val current = createTestTrack(id = "current_ambient", genre = "Ambient")
        val matchGenre = createTestTrack(id = "ambient_cand", genre = "Ambient")
        val otherGenre = createTestTrack(id = "rock_cand", genre = "Rock")

        queueManager.contextTrackProvider = { listOf(current, matchGenre, otherGenre) }
        queueManager.setSmartContinueMode(SmartContinueMode.SIMILAR_MUSIC)
        queueManager.setQueue(listOf(current), current)

        val next = queueManager.nextTrack()

        assertNotNull(next)
        assertEquals("ambient_cand", next?.id)
    }

    @Test
    fun testSmartContinueExcludesRecentHistory() {
        val queueManager = PersistentQueueManager(context)
        val t1 = createTestTrack(id = "track_1", genre = "Techno")
        val t2 = createTestTrack(id = "track_2", genre = "Techno")
        val t3 = createTestTrack(id = "track_3", genre = "Techno")

        queueManager.contextTrackProvider = { listOf(t1, t2, t3) }
        queueManager.setSmartContinueMode(SmartContinueMode.SIMILAR_MUSIC)
        queueManager.setQueue(listOf(t1, t2), t1)

        // Advance to t2 (t1 is moved to history)
        val next1 = queueManager.nextTrack()
        assertEquals("track_2", next1?.id)

        // Advance past t2 (upcoming is empty -> smart continue excludes t1 and t2)
        val next2 = queueManager.nextTrack()
        assertNotNull(next2)
        assertEquals("track_3", next2?.id)
    }

    @Test
    fun testSmartContinueModeSerialization() {
        val qm1 = PersistentQueueManager(context)
        qm1.setSmartContinueMode(SmartContinueMode.MIX_COMPATIBLE)
        assertEquals(SmartContinueMode.MIX_COMPATIBLE, qm1.smartContinueMode.value)
        runBlocking {
            qm1.saveToDisk()
        }

        val qm2 = PersistentQueueManager(context)
        qm2.restoreFromDisk()
        assertEquals(SmartContinueMode.MIX_COMPATIBLE, qm2.smartContinueMode.value)
    }
}
