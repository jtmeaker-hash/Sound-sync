package com.example

import com.example.command.CommandCategory
import com.example.command.CommandPaletteEngine
import com.example.command.CommandPaletteParser
import com.example.command.MissingFieldType
import com.example.command.PaletteFilter
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteEngineTest {

    @Test
    fun testPlainTextQuery() {
        val query = "Fred again"
        val parsed = CommandPaletteParser.parse(query)
        assertEquals(listOf("Fred", "again"), parsed.textTerms)
        assertTrue(parsed.filters.isEmpty())
    }

    @Test
    fun testArtistFilterUnquotedAndQuoted() {
        val parsed1 = CommandPaletteParser.parse("artist Fred again")
        val filter1 = parsed1.filters.filterIsInstance<PaletteFilter.Artist>().firstOrNull()
        assertNotNull(filter1)
        assertEquals("Fred again", filter1?.artist)

        val parsed2 = CommandPaletteParser.parse("artist:\"Fred again..\"")
        val filter2 = parsed2.filters.filterIsInstance<PaletteFilter.Artist>().firstOrNull()
        assertNotNull(filter2)
        assertEquals("Fred again..", filter2?.artist)
    }

    @Test
    fun testBpmExactAndRange() {
        val exact = CommandPaletteParser.parse("bpm 128")
        val exactFilter = exact.filters.filterIsInstance<PaletteFilter.BpmExact>().firstOrNull()
        assertNotNull(exactFilter)
        assertEquals(128.0, exactFilter?.bpm ?: 0.0, 0.01)

        val range = CommandPaletteParser.parse("bpm 124-130")
        val rangeFilter = range.filters.filterIsInstance<PaletteFilter.BpmRange>().firstOrNull()
        assertNotNull(rangeFilter)
        assertEquals(124.0, rangeFilter?.minBpm ?: 0.0, 0.01)
        assertEquals(130.0, rangeFilter?.maxBpm ?: 0.0, 0.01)
    }

    @Test
    fun testKeyCamelotAndMusical() {
        val camelot = CommandPaletteParser.parse("key 8A")
        val camelotFilter = camelot.filters.filterIsInstance<PaletteFilter.Key>().firstOrNull()
        assertNotNull(camelotFilter)
        assertEquals("8A", camelotFilter?.camelotKey)

        val musical = CommandPaletteParser.parse("key Am")
        val musicalFilter = musical.filters.filterIsInstance<PaletteFilter.Key>().firstOrNull()
        assertNotNull(musicalFilter)
        assertEquals("8A", musicalFilter?.camelotKey) // Am maps to 8A in Camelot
        assertEquals("Am", musicalFilter?.musicalKey)
    }

    @Test
    fun testFolderFilter() {
        val parsed = CommandPaletteParser.parse("folder Downloads")
        val folderFilter = parsed.filters.filterIsInstance<PaletteFilter.Folder>().firstOrNull()
        assertNotNull(folderFilter)
        assertEquals("Downloads", folderFilter?.folderName)
    }

    @Test
    fun testMissingFilters() {
        val artwork = CommandPaletteParser.parse("missing artwork")
        assertTrue(artwork.filters.any { it is PaletteFilter.Missing && it.fieldType == MissingFieldType.ARTWORK })

        val bpm = CommandPaletteParser.parse("missing bpm")
        assertTrue(bpm.filters.any { it is PaletteFilter.Missing && it.fieldType == MissingFieldType.BPM })

        val key = CommandPaletteParser.parse("missing key")
        assertTrue(key.filters.any { it is PaletteFilter.Missing && it.fieldType == MissingFieldType.KEY })

        val metadata = CommandPaletteParser.parse("missing metadata")
        assertTrue(metadata.filters.any { it is PaletteFilter.Missing && it.fieldType == MissingFieldType.METADATA })
    }

    @Test
    fun testRecentAndUnplayedFilters() {
        val added = CommandPaletteParser.parse("recently added")
        assertTrue(added.filters.any { it is PaletteFilter.RecentlyAdded })

        val played = CommandPaletteParser.parse("recently played")
        assertTrue(played.filters.any { it is PaletteFilter.RecentlyPlayed })

        val unplayed = CommandPaletteParser.parse("unplayed")
        assertTrue(unplayed.filters.any { it is PaletteFilter.Unplayed })
    }

    @Test
    fun testMultiFilterQuery() {
        val parsed = CommandPaletteParser.parse("artist Fred again bpm 120-140 key 8A")
        val artist = parsed.filters.filterIsInstance<PaletteFilter.Artist>().firstOrNull()
        val bpm = parsed.filters.filterIsInstance<PaletteFilter.BpmRange>().firstOrNull()
        val key = parsed.filters.filterIsInstance<PaletteFilter.Key>().firstOrNull()

        assertNotNull(artist)
        assertEquals("Fred again", artist?.artist)
        assertNotNull(bpm)
        assertEquals(120.0, bpm?.minBpm ?: 0.0, 0.01)
        assertEquals(140.0, bpm?.maxBpm ?: 0.0, 0.01)
        assertNotNull(key)
        assertEquals("8A", key?.camelotKey)
    }

    @Test
    fun testCommandsMatching() {
        val testTracks = listOf(
            Track(id = "1", title = "Delilah (pull me out of this)", artist = "Fred again..", bpm = 134.0, camelotKey = "8A", filePath = "/storage/emulated/0/Download/delilah.mp3"),
            Track(id = "2", title = "Glue", artist = "Bicep", bpm = 130.0, camelotKey = "11A", filePath = "/storage/emulated/0/Music/glue.mp3")
        )

        // Rescan selected without selection -> disabled
        val resNoSelection = CommandPaletteEngine.search("rescan selected", testTracks, selectedTrackIds = emptySet())
        val cmdState1 = resNoSelection.commands.firstOrNull { it.command.id == "rescan_selected" }
        assertNotNull(cmdState1)
        assertFalse(cmdState1!!.isEnabled)

        // Rescan selected WITH selection -> enabled
        val resWithSelection = CommandPaletteEngine.search("rescan selected", testTracks, selectedTrackIds = setOf("1"))
        val cmdState2 = resWithSelection.commands.firstOrNull { it.command.id == "rescan_selected" }
        assertNotNull(cmdState2)
        assertTrue(cmdState2!!.isEnabled)
        assertEquals(1, cmdState2.selectionCount)

        // Other commands match correctly
        val clearQ = CommandPaletteEngine.search("clear queue", testTracks)
        assertTrue(clearQ.commands.any { it.command.id == "clear_queue" })

        val openCar = CommandPaletteEngine.search("open car mode", testTracks)
        assertTrue(openCar.commands.any { it.command.id == "open_car_mode" })

        val openDj = CommandPaletteEngine.search("open dj prep", testTracks)
        assertTrue(openDj.commands.any { it.command.id == "open_dj_prep" })
    }

    @Test
    fun testTrackFilteringAndRanking() {
        val testTracks = listOf(
            Track(id = "1", title = "Jungle", artist = "Fred again..", bpm = 134.0, camelotKey = "8A", filePath = "/storage/emulated/0/Download/jungle.mp3"),
            Track(id = "2", title = "Danielle", artist = "Fred again..", bpm = 130.0, camelotKey = "9A", filePath = "/storage/emulated/0/Music/danielle.mp3"),
            Track(id = "3", title = "Glue", artist = "Bicep", bpm = 130.0, camelotKey = "11A", filePath = "/storage/emulated/0/Music/glue.mp3")
        )

        // Filter by bpm exact
        val bpmResults = CommandPaletteEngine.search("bpm 130", testTracks)
        assertEquals(2, bpmResults.tracks.size)
        assertTrue(bpmResults.tracks.all { it.bpm == 130.0 })

        // Filter by folder Downloads
        val folderResults = CommandPaletteEngine.search("folder Download", testTracks)
        assertEquals(1, folderResults.tracks.size)
        assertEquals("Jungle", folderResults.tracks.first().title)

        // Title ranking over artist match
        val titleMatch = CommandPaletteEngine.search("Glue", testTracks)
        assertEquals("Glue", titleMatch.tracks.first().title)
    }
}
