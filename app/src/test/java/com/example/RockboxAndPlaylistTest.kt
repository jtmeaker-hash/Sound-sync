package com.example

import com.example.model.AudioQualityRating
import com.example.model.Track
import com.example.storage.M3uPlaylistManager
import com.example.storage.RockboxPathResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RockboxAndPlaylistTest {

    @Test
    fun testComputeStorageRelativePath_fromEmulatedStorage() {
        val path = "/storage/emulated/0/Music/Electronic/Daft Punk/Discovery/01 One More Time.flac"
        val relative = RockboxPathResolver.computeStorageRelativePath(path)
        assertEquals("Music/Electronic/Daft Punk/Discovery/01 One More Time.flac", relative)
    }

    @Test
    fun testComputeStorageRelativePath_fromSdCard() {
        val path = "/storage/9C33-6BBD/DJ Sets/2026 Live/Track01.mp3"
        val relative = RockboxPathResolver.computeStorageRelativePath(path)
        assertEquals("DJ Sets/2026 Live/Track01.mp3", relative)
    }

    @Test
    fun testComputeStorageRelativePath_fromSafDocumentId() {
        val docIdPrimary = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FHouse%2FDeep%20Vibes.wav"
        val relPrimary = RockboxPathResolver.computeStorageRelativePath(docIdPrimary)
        assertEquals("Music/House/Deep Vibes.wav", relPrimary)
    }

    @Test
    fun testCalculateRelativePath_fromPlaylistsFolder() {
        val trackRelative = "Music/Artist/Album/01 Track.mp3"
        val playlistRelative = "Playlists/Favorites.m3u8"
        val rockboxEntry = RockboxPathResolver.calculateRelativePath(
            playlistRelativePath = playlistRelative,
            trackRelativePath = trackRelative
        )
        assertEquals("../Music/Artist/Album/01 Track.mp3", rockboxEntry)
    }

    @Test
    fun testResolveTrackPathFromPlaylistEntry() {
        val resolved = RockboxPathResolver.resolveTrackPathFromPlaylistEntry(
            playlistDir = "Playlists",
            entry = "../Music/Artist/Album/01 Track.mp3"
        )
        assertEquals("Music/Artist/Album/01 Track.mp3", resolved)
    }

    @Test
    fun testCrossStorageDetection() {
        val track1 = createMockTrack("1", "primary", "Music/Track1.mp3")
        val track2 = createMockTrack("2", "primary", "Music/Track2.mp3")
        val track3 = createMockTrack("3", "sdcard_9c33", "Music/Track3.mp3")

        assertFalse(RockboxPathResolver.detectCrossStorageMismatch(listOf(track1, track2)))
        assertTrue(RockboxPathResolver.detectCrossStorageMismatch(listOf(track1, track3)))
    }

    @Test
    fun testM3u8ContentGeneration_utf8AndExtInf() {
        val track1 = createMockTrack(
            id = "1",
            sourceId = "primary",
            relativePath = "Music/宇多田ヒカル/First Love.flac",
            title = "First Love",
            artist = "宇多田ヒカル",
            durationSec = 258
        )
        val track2 = createMockTrack(
            id = "2",
            sourceId = "primary",
            relativePath = "Music/Daft Punk/Harder Better.mp3",
            title = "Harder, Better, Faster, Stronger",
            artist = "Daft Punk",
            durationSec = 224
        )

        val m3uContent = M3uPlaylistManager.generateM3u8(
            playlistName = "J-Pop & French Touch",
            tracks = listOf(track1, track2),
            playlistRelativePath = "Playlists/Mix.m3u8"
        )

        assertTrue(m3uContent.contains("#EXTM3U"))
        assertTrue(m3uContent.contains("#EXTINF:258,宇多田ヒカル - First Love"))
        assertTrue(m3uContent.contains("../Music/宇多田ヒカル/First Love.flac"))
        assertTrue(m3uContent.contains("#EXTINF:224,Daft Punk - Harder, Better, Faster, Stronger"))
        assertTrue(m3uContent.contains("../Music/Daft Punk/Harder Better.mp3"))
    }

    @Test
    fun testM3u8ContentParsing() {
        val rawM3u = """
            #EXTM3U
            #EXTINF:180,Artist Name - Song Title
            ../Music/Artist Name/Album/Song Title.mp3
            #EXTINF:240,Another Artist - Another Song
            /Music/Another Artist/Another Song.flac
        """.trimIndent()

        val entries = M3uPlaylistManager.parseM3u(rawM3u, playlistDir = "Playlists")
        assertEquals(2, entries.size)
        assertEquals("Song Title", entries[0].title)
        assertEquals("Artist Name", entries[0].artist)
        assertEquals(180, entries[0].durationSeconds)
        assertEquals("Music/Artist Name/Album/Song Title.mp3", entries[0].path)

        assertEquals("Another Song", entries[1].title)
        assertEquals("Another Artist", entries[1].artist)
        assertEquals(240, entries[1].durationSeconds)
        assertEquals("Music/Another Artist/Another Song.flac", entries[1].path)
    }

    private fun createMockTrack(
        id: String,
        sourceId: String,
        relativePath: String,
        title: String = "Test Track",
        artist: String = "Test Artist",
        durationSec: Int = 180
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = "Test Album",
            durationSeconds = durationSec,
            bpm = 128.0,
            musicalKey = "8A",
            filePath = "/storage/emulated/0/$relativePath",
            directoryPath = "/storage/emulated/0/Music",
            format = "FLAC",
            bitrateKbps = 1411,
            qualityRating = AudioQualityRating.TRUE_LOSSLESS,
            sourceId = sourceId,
            storageRelativePath = relativePath
        )
    }
}
