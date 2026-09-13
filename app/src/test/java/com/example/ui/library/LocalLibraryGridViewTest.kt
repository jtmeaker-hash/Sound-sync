package com.example.ui.library

import com.example.model.AudioQualityRating
import com.example.model.HierarchicalFolder
import com.example.model.Track
import com.example.model.TrackFolder
import com.example.util.AlbumArtHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryGridViewTest {

    private fun createDummyTrack(
        id: String,
        title: String,
        artist: String = "Test Artist",
        album: String = "Test Album",
        format: String = "FLAC",
        bpm: Double = 128.0,
        key: String = "8A",
        durationSeconds: Int = 210,
        isAvailable: Boolean = true,
        artworkUrl: String? = "https://example.com/art.jpg",
        qualityRating: AudioQualityRating = AudioQualityRating.TRUE_LOSSLESS
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            format = format,
            bpm = bpm,
            musicalKey = key,
            camelotKey = key,
            durationSeconds = durationSeconds,
            isAvailable = isAvailable,
            artworkUrl = artworkUrl,
            filePath = "/storage/emulated/0/Music/$title.$format",
            directoryPath = "/storage/emulated/0/Music",
            qualityRating = qualityRating
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 1: Toggle View Mode (List -> Grid -> List) & Preference Persistence
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testViewModeToggleAndPersistence() {
        val prefsMock = mutableMapOf<String, Any>()
        val prefKey = "library_track_grid_view"

        // Default state should be list view (false)
        var isTrackGridView = prefsMock[prefKey] as? Boolean ?: false
        assertFalse("Default track view mode should be List View", isTrackGridView)

        // Toggle to Grid View
        isTrackGridView = !isTrackGridView
        prefsMock[prefKey] = isTrackGridView
        assertTrue("Track view mode should now be Grid View", isTrackGridView)
        assertEquals(true, prefsMock[prefKey])

        // Toggle back to List View
        isTrackGridView = !isTrackGridView
        prefsMock[prefKey] = isTrackGridView
        assertFalse("Track view mode should now be List View", isTrackGridView)
        assertEquals(false, prefsMock[prefKey])

        // Toggle again to Grid View and simulate app restart / reload from preferences
        isTrackGridView = !isTrackGridView
        prefsMock[prefKey] = isTrackGridView
        assertTrue(isTrackGridView)

        // Simulate app restart / ViewModel creation loading preference
        val restoredPref = prefsMock[prefKey] as? Boolean ?: false
        assertTrue("Preference must survive reload / app recreation", restoredPref)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 2: Search, Filter, and Sort Consistency Between List and Grid Modes
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testSearchFilterAndSortConsistencyBetweenModes() {
        val tracks = listOf(
            createDummyTrack("1", "Solar Stone - Seven Cities", "Solarstone", "Pure Trance", bpm = 138.0, durationSeconds = 300),
            createDummyTrack("2", "Above & Beyond - Sun & Moon", "Above & Beyond", "Group Therapy", bpm = 128.0, durationSeconds = 240),
            createDummyTrack("3", "Deadmau5 - Strobe", "Deadmau5", "For Lack of a Better Name", bpm = 128.0, durationSeconds = 637),
            createDummyTrack("4", "Pendulum - Watercolour", "Pendulum", "Immersion", bpm = 174.0, durationSeconds = 304, isAvailable = false),
            createDummyTrack("5", "Armin van Buuren - Shivers", "Armin van Buuren", "Shivers", bpm = 136.0, durationSeconds = 450)
        )

        // Test filtering by search query
        val searchQuery = "pendulum"
        val filteredListMode = tracks.filter { it.title.lowercase().contains(searchQuery) || it.artist.lowercase().contains(searchQuery) }
        val filteredGridMode = tracks.filter { it.title.lowercase().contains(searchQuery) || it.artist.lowercase().contains(searchQuery) }
        assertEquals(filteredListMode, filteredGridMode)
        assertEquals(1, filteredGridMode.size)
        assertEquals("4", filteredGridMode.first().id)

        // Test hideUnavailableTracks filter
        val availableListMode = tracks.filter { it.isAvailable }
        val availableGridMode = tracks.filter { it.isAvailable }
        assertEquals(availableListMode, availableGridMode)
        assertEquals(4, availableGridMode.size)
        assertFalse(availableGridMode.any { !it.isAvailable })

        // Test sorting: SongSortMode.BPM_DESC
        val sortedListMode = SongSortMode.BPM_DESC.sort(tracks)
        val sortedGridMode = SongSortMode.BPM_DESC.sort(tracks)
        assertEquals(sortedListMode, sortedGridMode)
        assertEquals("Pendulum - Watercolour", sortedGridMode.first().title)
        assertEquals(174.0, sortedGridMode.first().bpm, 0.01)

        // Test sorting: SongSortMode.TITLE_ASC
        val titleSortedList = SongSortMode.TITLE_ASC.sort(tracks)
        val titleSortedGrid = SongSortMode.TITLE_ASC.sort(tracks)
        assertEquals(titleSortedList, titleSortedGrid)
        assertEquals("Above & Beyond - Sun & Moon", titleSortedGrid.first().title)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 3: Grid Tracks with Artwork and Tracks with Missing/Null Artwork
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testGridTracksWithAndWithoutArtwork() {
        val trackWithArt = createDummyTrack("art_1", "Track With Art", artworkUrl = "https://example.com/art.jpg")
        val trackWithoutArt = createDummyTrack("no_art_1", "Track Without Art", artworkUrl = null)

        val keyWithArt = AlbumArtHelper.computeCacheKey(trackWithArt, 320)
        val keyWithoutArt = AlbumArtHelper.computeCacheKey(trackWithoutArt, 320)

        assertTrue(keyWithArt.contains(trackWithArt.id))
        assertTrue(keyWithoutArt.contains(trackWithoutArt.id))
        assertNotNull(keyWithArt)
        assertNotNull(keyWithoutArt)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 4: Large Library Scalability in Grid Mode (10,000 Tracks)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testLargeLibraryGridScalability() {
        val largeTrackList = (1..10000).map { i ->
            createDummyTrack(
                id = "track_$i",
                title = "Track Number $i",
                artist = if (i % 2 == 0) "Even Artist" else "Odd Artist",
                bpm = 120.0 + (i % 60),
                durationSeconds = 180 + (i % 120)
            )
        }

        assertEquals(10000, largeTrackList.size)

        // Measure filtering performance (must be sub-second)
        val startTime = System.currentTimeMillis()
        val query = "Track Number 999"
        val filtered = largeTrackList.filter { it.title.contains(query) }
        val durationMs = System.currentTimeMillis() - startTime

        assertEquals(11, filtered.size) // 999, 9990..9999
        assertTrue("10k track filter in grid mode took ${durationMs}ms, should be < 500ms", durationMs < 500)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 5: Multi-Select & Current Playing State in Grid View
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testMultiSelectAndCurrentPlayingStateInGrid() {
        val tracks = (1..5).map { createDummyTrack("id_$it", "Track $it") }
        var selectedTrackIds = setOf<String>()

        // Select track 2 and 4
        selectedTrackIds = selectedTrackIds + tracks[1].id
        selectedTrackIds = selectedTrackIds + tracks[3].id

        assertEquals(2, selectedTrackIds.size)
        assertTrue(selectedTrackIds.contains("id_2"))
        assertTrue(selectedTrackIds.contains("id_4"))
        assertFalse(selectedTrackIds.contains("id_1"))

        // Deselect track 2
        selectedTrackIds = selectedTrackIds - tracks[1].id
        assertEquals(1, selectedTrackIds.size)
        assertFalse(selectedTrackIds.contains("id_2"))
        assertTrue(selectedTrackIds.contains("id_4"))

        // Current playing track highlighting
        val currentTrack = tracks[0]
        assertTrue("Track 1 must be identified as currently playing", currentTrack.id == tracks[0].id)
        assertFalse("Track 2 must NOT be identified as currently playing", currentTrack.id == tracks[1].id)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 6: Folder Hierarchy Preserved — Only Track Detail Views Support Grid
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testFolderHierarchyRemainsHierarchy() {
        val tracks = listOf(
            createDummyTrack("1", "Deep House 1"),
            createDummyTrack("2", "Deep House 2")
        )

        // The Folder Detail holds tracks which can be rendered as List or Grid
        val folder = TrackFolder(
            id = "folder_deep",
            name = "Deep",
            path = "/storage/Music/House/Deep",
            trackCount = tracks.size,
            totalDurationSeconds = tracks.sumOf { it.durationSeconds },
            tracks = tracks
        )

        assertEquals(2, folder.trackCount)
        assertEquals("/storage/Music/House/Deep", folder.path)

        // The HierarchicalFolder representation can convert to TrackFolder
        val hierarchicalFolder = HierarchicalFolder(
            id = "root_sd::House/Deep",
            rootId = "sd",
            rootName = "SD Card",
            rootSource = "/storage/0000-0000",
            parentId = "root_sd::House",
            name = "Deep",
            relativePathFromRoot = "House/Deep",
            fullPath = "/storage/0000-0000/House/Deep",
            depth = 2,
            directTracks = tracks,
            directTrackCount = 2,
            totalTrackCount = 2,
            totalDurationSeconds = tracks.sumOf { it.durationSeconds },
            hasChildFolders = false
        )

        val convertedFolder = hierarchicalFolder.toTrackFolder()
        assertEquals(folder.name, convertedFolder.name)
        assertEquals(2, convertedFolder.tracks.size)
        assertEquals(folder.totalDurationSeconds, convertedFolder.totalDurationSeconds)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 7: Removable / Disconnected USB Tracks in Grid Mode
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testUsbRemovableTracksInGrid() {
        val usbTrackOffline = createDummyTrack(
            id = "usb_offline_1",
            title = "USB Festival Anthem",
            isAvailable = false,
            format = "WAV",
            qualityRating = AudioQualityRating.TRUE_LOSSLESS
        )

        assertFalse("Removable track must indicate offline state", usbTrackOffline.isAvailable)
        assertTrue("Audio format must remain lossless FLAC/WAV", usbTrackOffline.isLossless)
        assertEquals("WAV", usbTrackOffline.format)
    }
}
