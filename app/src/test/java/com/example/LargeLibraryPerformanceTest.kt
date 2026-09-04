package com.example

import com.example.data.TrackEntity
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Track
import com.example.model.TrackFolder
import com.example.storage.StorageAvailabilityHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.system.measureTimeMillis

class LargeLibraryPerformanceTest {

    @Before
    fun setUp() {
        StorageAvailabilityHelper.clearCache()
    }

    // ========================================================================
    // 1. StorageAvailabilityHelper Root & Path Tests
    // ========================================================================

    @Test
    fun testStorageRootExtraction() {
        // Internal emulated storage
        assertEquals("/storage/emulated/0", StorageAvailabilityHelper.getStorageRoot("/storage/emulated/0/Music/song.mp3"))
        assertEquals("/storage/emulated/0", StorageAvailabilityHelper.getStorageRoot("file:///storage/emulated/0/Download/song.flac"))

        // External removable SD / USB storage
        assertEquals("/storage/ABCD-1234", StorageAvailabilityHelper.getStorageRoot("/storage/ABCD-1234/Music/song.mp3"))
        assertEquals("/storage/9E1A-1B0A", StorageAvailabilityHelper.getStorageRoot("file:///storage/9E1A-1B0A/DJ/track.wav"))

        // Media RW paths
        assertEquals("/mnt/media_rw/USB1", StorageAvailabilityHelper.getStorageRoot("/mnt/media_rw/USB1/Audio/track.mp3"))

        // Special schemes return null
        assertNull(StorageAvailabilityHelper.getStorageRoot("content://com.android.providers.media.documents/document/audio%3A123"))
        assertNull(StorageAvailabilityHelper.getStorageRoot("demo://track/1"))
    }

    @Test
    fun testExternalStorageIdentification() {
        // Internal paths
        assertFalse(StorageAvailabilityHelper.isExternalStoragePath("/storage/emulated/0/Music/song.mp3"))
        assertFalse(StorageAvailabilityHelper.isExternalStoragePath("demo://track/1"))

        // External paths
        assertTrue(StorageAvailabilityHelper.isExternalStoragePath("/storage/ABCD-1234/Music/song.mp3"))
        assertTrue(StorageAvailabilityHelper.isExternalStoragePath("/storage/1234-5678/track.flac"))
        assertTrue(StorageAvailabilityHelper.isExternalStoragePath("/mnt/media_rw/USB_DRIVE/song.wav"))

        // External track via sourceId
        val usbTrack = Track(
            id = "t1",
            title = "Song",
            artist = "Artist",
            filePath = "/storage/emulated/0/Music/cached.mp3",
            sourceId = "usb_drive_1"
        )
        assertTrue(StorageAvailabilityHelper.isExternalStorageTrack(usbTrack))

        val internalTrack = Track(
            id = "t2",
            title = "Song",
            artist = "Artist",
            filePath = "/storage/emulated/0/Music/song.mp3",
            sourceId = "internal"
        )
        assertFalse(StorageAvailabilityHelper.isExternalStorageTrack(internalTrack))
    }

    // ========================================================================
    // 2. Storage Availability Toggling & Root Cache Tests
    // ========================================================================

    @Test
    fun testTrackAvailabilityWithRootMap() {
        val rootMap = mapOf(
            "/storage/USB1-2345" to true,
            "/storage/USB2-6789" to false
        )

        // Internal track always available
        assertTrue(
            StorageAvailabilityHelper.isTrackRootAvailable(
                "/storage/emulated/0/Music/song.mp3",
                rootMap
            )
        )

        // Demo track always available
        assertTrue(
            StorageAvailabilityHelper.isTrackRootAvailable(
                "demo://track-1",
                rootMap
            )
        )

        // USB1 is mounted -> available
        assertTrue(
            StorageAvailabilityHelper.isTrackRootAvailable(
                "/storage/USB1-2345/Music/song1.mp3",
                rootMap
            )
        )

        // USB2 is unmounted -> unavailable
        assertFalse(
            StorageAvailabilityHelper.isTrackRootAvailable(
                "/storage/USB2-6789/Music/song2.mp3",
                rootMap
            )
        )
    }

    @Test
    fun testUsbDisconnectSimulationAcrossTracks() {
        val internalTracks = (1..50).map { i ->
            Track(
                id = "internal-$i",
                title = "Internal Song $i",
                artist = "Local Artist",
                filePath = "/storage/emulated/0/Music/Track$i.mp3",
                isAvailable = true
            )
        }

        val usbTracks = (1..50).map { i ->
            Track(
                id = "usb-$i",
                title = "USB Song $i",
                artist = "USB Artist",
                filePath = "/storage/ABCD-1234/Music/Track$i.mp3",
                isAvailable = true
            )
        }

        val all = internalTracks + usbTracks

        // When USB is connected
        val connectedMap = mapOf("/storage/ABCD-1234" to true)
        val connectedResult = all.map { track ->
            val isAvail = StorageAvailabilityHelper.isTrackRootAvailable(track.filePath, connectedMap)
            if (track.isAvailable == isAvail) track else track.copy(isAvailable = isAvail)
        }
        assertTrue(connectedResult.all { it.isAvailable })

        // When USB is disconnected
        val disconnectedMap = mapOf("/storage/ABCD-1234" to false)
        val disconnectedResult = all.map { track ->
            val isAvail = StorageAvailabilityHelper.isTrackRootAvailable(track.filePath, disconnectedMap)
            if (track.isAvailable == isAvail) track else track.copy(isAvailable = isAvail)
        }

        // All internal tracks remain available
        assertTrue(disconnectedResult.filter { it.filePath.startsWith("/storage/emulated") }.all { it.isAvailable })
        // All USB tracks are now unavailable
        assertTrue(disconnectedResult.filter { it.filePath.startsWith("/storage/ABCD-1234") }.none { it.isAvailable })
    }

    // ========================================================================
    // 3. Synthetic Scale Benchmark (10,000+ Tracks)
    // ========================================================================

    @Test
    fun testLargeLibraryTransformationsPerformance() {
        val trackCount = 10_000
        val artistCount = 100
        val albumsPerArtist = 3
        val foldersCount = 50

        val rootMap = mapOf(
            "/storage/ABCD-1234" to true,
            "/storage/emulated/0" to true
        )

        // 1. Generate 10,000 TrackEntity instances
        val entities = ArrayList<TrackEntity>(trackCount)
        for (i in 0 until trackCount) {
            val artistIdx = i % artistCount
            val albumIdx = (i / artistCount) % albumsPerArtist
            val folderIdx = i % foldersCount
            val isUsb = i % 2 == 0
            val basePath = if (isUsb) "/storage/ABCD-1234/Music" else "/storage/emulated/0/Music"

            val modelTrack = Track(
                id = "track_$i",
                title = "Track Title $i",
                artist = "Artist $artistIdx",
                album = "Album $artistIdx-$albumIdx",
                filePath = "$basePath/Folder$folderIdx/Track_$i.mp3",
                directoryPath = "$basePath/Folder$folderIdx",
                durationSeconds = 180 + (i % 120),
                bpm = 120.0 + (i % 20),
                trackNumber = (i % 12) + 1,
                discNumber = 1,
                isAvailable = true
            )
            entities.add(TrackEntity.fromTrack(modelTrack))
        }
        assertEquals(trackCount, entities.size)

        // 2. Measure TrackEntity -> Track conversion with cached root availability
        var tracks: List<Track>
        val entityConversionTime = measureTimeMillis {
            tracks = entities.map { entity ->
                val track = entity.toTrack()
                val isAvail = StorageAvailabilityHelper.isTrackRootAvailable(track.filePath, rootMap)
                if (track.isAvailable == isAvail) track else track.copy(isAvailable = isAvail)
            }
        }
        assertEquals(trackCount, tracks.size)
        println("Converted $trackCount TrackEntities in ${entityConversionTime}ms")
        // Conversion of 10,000 tracks without per-file filesystem I/O should be well under 500ms
        assertTrue("Entity conversion took too long: ${entityConversionTime}ms", entityConversionTime < 500)

        // 3. Measure Album Grouping (same logic as MainDjViewModel.allAlbums)
        var albums: List<Album>
        val albumGroupingTime = measureTimeMillis {
            albums = tracks.filter { it.album.isNotBlank() }
                .groupBy { "${it.artist.trim().lowercase(Locale.ROOT)}:::${it.album.trim().lowercase(Locale.ROOT)}" }
                .map { entry ->
                    val albumTracks = entry.value.sortedWith(
                        compareBy<Track> { it.discNumber }
                            .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                            .thenBy { it.title.lowercase(Locale.ROOT) }
                    )
                    val firstTrack = albumTracks.first()
                    val albumTitle = firstTrack.album.ifBlank { "Single" }
                    val artistName = firstTrack.artist.ifBlank { "Unknown Artist" }
                    val totalSec = albumTracks.sumOf { it.durationSeconds }
                    Album(
                        id = "album_${artistName.hashCode()}_${albumTitle.hashCode()}",
                        title = albumTitle,
                        artist = artistName,
                        trackCount = albumTracks.size,
                        totalDurationSeconds = totalSec,
                        tracks = albumTracks,
                        artworkUri = null
                    )
                }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
        }
        println("Grouped ${albums.size} albums in ${albumGroupingTime}ms")
        assertTrue("Album grouping took too long: ${albumGroupingTime}ms", albumGroupingTime < 600)
        assertTrue(albums.isNotEmpty())

        // 4. Measure Artist Grouping with O(N+M) pre-grouped album map (same logic as MainDjViewModel.allArtists)
        var artists: List<Artist>
        val artistGroupingTime = measureTimeMillis {
            val albumsByArtist = albums.groupBy { it.artist.trim().lowercase(Locale.ROOT) }
            artists = tracks.groupBy { it.artist.trim().lowercase(Locale.ROOT) }
                .map { entry ->
                    val artistSongs = entry.value.sortedBy { it.title.lowercase(Locale.ROOT) }
                    val artistName = artistSongs.firstOrNull()?.artist?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
                    val artistAlbums = albumsByArtist[entry.key] ?: emptyList()
                    val totalSec = artistSongs.sumOf { it.durationSeconds }
                    Artist(
                        id = "artist_${artistName.hashCode()}",
                        name = artistName,
                        albumCount = artistAlbums.size,
                        songCount = artistSongs.size,
                        totalDurationSeconds = totalSec,
                        albums = artistAlbums,
                        songs = artistSongs
                    )
                }
                .sortedBy { if (it.name.equals("Unknown Artist", ignoreCase = true)) "zzzz" else it.name.lowercase(Locale.ROOT) }
        }
        println("Grouped ${artists.size} artists in ${artistGroupingTime}ms")
        // Linear O(N+M) grouping should complete comfortably under 500ms for 10k tracks
        assertTrue("Artist grouping took too long: ${artistGroupingTime}ms", artistGroupingTime < 500)
        assertEquals(artistCount, artists.size)

        // 5. Measure Folder Grouping (same logic as MainDjViewModel.allFolders)
        var folders: List<TrackFolder>
        val folderGroupingTime = measureTimeMillis {
            folders = tracks.groupBy { track ->
                val dir = track.directoryPath.ifBlank {
                    if (track.filePath.contains('/')) track.filePath.substringBeforeLast('/') else "/Music"
                }
                dir.trimEnd('/')
            }.map { (folderPath, folderTracks) ->
                val folderName = folderPath.substringAfterLast('/').ifBlank { folderPath.ifBlank { "Root" } }
                val sortedTracks = folderTracks.sortedWith(
                    compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                )
                TrackFolder(
                    id = "folder_${folderPath.hashCode()}",
                    name = folderName,
                    path = folderPath,
                    trackCount = sortedTracks.size,
                    totalDurationSeconds = sortedTracks.sumOf { it.durationSeconds },
                    tracks = sortedTracks
                )
            }.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
        println("Grouped ${folders.size} folders in ${folderGroupingTime}ms")
        assertTrue("Folder grouping took too long: ${folderGroupingTime}ms", folderGroupingTime < 500)
        assertTrue(folders.isNotEmpty())
    }

    // ========================================================================
    // 4. Metadata Update Simulation Test
    // ========================================================================

    @Test
    fun testSingleTrackMetadataUpdateDoesNotBlock() {
        val trackCount = 10_000
        val rootMap = mapOf("/storage/emulated/0" to true)

        val tracks = (0 until trackCount).map { i ->
            Track(
                id = "track_$i",
                title = "Original Title $i",
                artist = "Artist ${i % 50}",
                album = "Album ${i % 100}",
                filePath = "/storage/emulated/0/Music/track_$i.mp3",
                isAvailable = true
            )
        }

        // Simulate metadata update on track 42
        val targetId = "track_42"
        val updatedTrack = tracks[42].copy(
            title = "Updated Title 42",
            artist = "Updated Artist",
            rating = 5,
            notes = "Checked in library"
        )

        // Measure time to replace in list and re-run cached root availability check
        val updateTime = measureTimeMillis {
            val updatedList = tracks.map { track ->
                if (track.id == targetId) {
                    val isAvail = StorageAvailabilityHelper.isTrackRootAvailable(updatedTrack.filePath, rootMap)
                    if (updatedTrack.isAvailable == isAvail) updatedTrack else updatedTrack.copy(isAvailable = isAvail)
                } else {
                    track
                }
            }
            assertEquals("Updated Title 42", updatedList[42].title)
            assertEquals("Updated Artist", updatedList[42].artist)
            assertEquals(5, updatedList[42].rating)
        }

        println("Single track metadata update completed in ${updateTime}ms")
        // Replacing a single item and mapping with cached root should take under 100ms
        assertTrue("Single track update took too long: ${updateTime}ms", updateTime < 100)
    }
}
