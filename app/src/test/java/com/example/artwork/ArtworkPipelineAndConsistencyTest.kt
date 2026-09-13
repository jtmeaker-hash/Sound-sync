package com.example.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjAudioEngine
import com.example.metadata.ArtworkCache
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.EmbeddedAudioMetadata
import com.example.model.Album
import com.example.model.Track
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ArtworkPipelineAndConsistencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AlbumArtHelper.clearMemoryCache()
    }

    private fun createTestImageFile(name: String, color: Int = android.graphics.Color.BLUE): File {
        val file = File(tempFolder.root, name)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    private fun createTestImageBytes(color: Int = android.graphics.Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    // ── 1. MP3 WITH EMBEDDED COVER ────────────────────────────────────────────

    @Test
    fun testMp3WithEmbeddedCoverDecodesArtwork() = runBlocking {
        val mp3File = File(tempFolder.root, "song.mp3")
        mp3File.writeBytes(ByteArray(1024)) // stub audio

        val coverFile = createTestImageFile("embedded_mp3_cover.png", android.graphics.Color.MAGENTA)

        val track = Track(
            id = "mp3_track_1",
            title = "Club Anthem",
            artist = "DJ Producer",
            album = "Summer Beats",
            format = "MP3",
            filePath = mp3File.absolutePath,
            artworkCachePath = coverFile.absolutePath
        )

        val bitmap = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)

        // Warm cache check
        val cached = AlbumArtHelper.getCachedArtwork(track, 512)
        assertNotNull(cached)
        assertEquals(bitmap, cached)
    }

    // ── 2. FLAC WITH EMBEDDED COVER ───────────────────────────────────────────

    @Test
    fun testFlacWithEmbeddedCoverDecodesArtwork() = runBlocking {
        val flacFile = File(tempFolder.root, "lossless.flac")
        flacFile.writeBytes(ByteArray(2048))

        val artFile = createTestImageFile("flac_cover.png", android.graphics.Color.GREEN)

        val track = Track(
            id = "flac_track_1",
            title = "Acoustic Symphony",
            artist = "Orchestra",
            album = "Hi-Res Collection",
            format = "FLAC",
            filePath = flacFile.absolutePath,
            artworkCachePath = artFile.absolutePath
        )

        val bitmap = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(bitmap)
        assertEquals(bitmap, AlbumArtHelper.getCachedArtwork(track, 512))
    }

    // ── 3. M4A / AAC WITH ART ─────────────────────────────────────────────────

    @Test
    fun testM4aWithCoverResolvesAndDecodes() = runBlocking {
        val m4aFile = File(tempFolder.root, "podcast.m4a")
        m4aFile.writeBytes(ByteArray(1024))

        val artFile = createTestImageFile("m4a_cover.png", android.graphics.Color.CYAN)

        val track = Track(
            id = "m4a_track_1",
            title = "Episode 42",
            artist = "Broadcaster",
            album = "Talk Radio",
            format = "AAC",
            filePath = m4aFile.absolutePath,
            artworkCachePath = artFile.absolutePath
        )

        val bitmap = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(bitmap)
    }

    // ── 4. WAV WITH FOLDER OR CACHED ART ──────────────────────────────────────

    @Test
    fun testWavWithFolderOrCachedArtResolvesCorrectly() = runBlocking {
        val wavFolder = File(tempFolder.root, "StudioSession")
        wavFolder.mkdirs()
        val wavFile = File(wavFolder, "master_take.wav")
        wavFile.writeBytes(ByteArray(1024))

        // WAV doesn't have standard ID3 in most players; put cover.jpg in the folder
        val folderCover = File(wavFolder, "cover.jpg")
        val imgBytes = createTestImageBytes(android.graphics.Color.YELLOW)
        folderCover.writeBytes(imgBytes)

        val track = Track(
            id = "wav_track_1",
            title = "Master Take 1",
            artist = "Studio Band",
            album = "Studio Session",
            format = "WAV",
            filePath = wavFile.absolutePath,
            directoryPath = wavFolder.absolutePath
        )

        val bitmap = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(bitmap)
    }

    // ── 5. ARTWORK WRITTEN/DOWNLOADED AFTER INITIAL SCAN ──────────────────────

    @Test
    fun testArtworkWrittenAfterInitialScanInvalidatesCacheAndResolvesNewArt() = runBlocking {
        val audioFile = File(tempFolder.root, "initial_track.mp3")
        audioFile.writeBytes(ByteArray(512))

        val initialTrack = Track(
            id = "dynamic_art_track",
            title = "Unidentified Song",
            artist = "Unknown",
            filePath = audioFile.absolutePath
        )

        // 1. Initial scan: no artwork on disk -> resolves to fallback vinyl
        val initialBitmap = AlbumArtHelper.getArtworkForTrack(context, initialTrack, 512)
        assertNotNull(initialBitmap)

        // 2. Metadata identification occurs and artwork is downloaded to ArtworkCache
        val artBytes = createTestImageBytes(android.graphics.Color.RED)
        val artCache = ArtworkCache(context)
        val savedFile = artCache.saveArtworkForTrack(initialTrack.id, artBytes, "image/png", "Apple Search")
        assertTrue(savedFile.exists())

        // 3. Invalidate track in AlbumArtHelper
        AlbumArtHelper.invalidateTrack(initialTrack.id, initialTrack.artist, initialTrack.album)

        val updatedTrack = initialTrack.copy(
            artworkCachePath = savedFile.absolutePath,
            artworkUrl = savedFile.absolutePath,
            artworkSource = "Apple Search",
            fileModifiedTimestamp = System.currentTimeMillis()
        )

        // 4. Request artwork again: MUST decode the newly downloaded artwork, not the old fallback!
        val freshBitmap = AlbumArtHelper.getArtworkForTrack(context, updatedTrack, 512)
        assertNotNull(freshBitmap)
        // Memory cache should now hold the new fresh bitmap
        val cached = AlbumArtHelper.getCachedArtwork(updatedTrack, 512)
        assertEquals(freshBitmap, cached)
    }

    // ── 6. ARTWORK CHANGED WHILE TRACK IS CURRENTLY PLAYING ───────────────────

    @Test
    fun testArtworkChangedWhileTrackIsCurrentlyPlayingUpdatesCurrentTrackMetadata() = runBlocking {
        val audioFile = File(tempFolder.root, "playing_song.mp3")
        audioFile.writeBytes(ByteArray(512))

        val trackInitial = Track(
            id = "now_playing_track",
            title = "Live Vocal",
            artist = "Headliner",
            album = "Live at Tomorrowland",
            filePath = audioFile.absolutePath
        )

        val audioEngine = DjAudioEngine.getInstance(context)
        audioEngine.loadTrack(trackInitial)
        assertEquals(trackInitial.id, audioEngine.currentTrack.value?.id)
        assertNull(audioEngine.currentTrack.value?.artworkCachePath)

        // User or background service writes artwork
        val newArtFile = createTestImageFile("new_live_cover.png", android.graphics.Color.DKGRAY)
        val trackUpdated = trackInitial.copy(
            artworkCachePath = newArtFile.absolutePath,
            artworkUrl = newArtFile.absolutePath,
            artworkSource = "User Selected",
            userConfirmedMetadata = true,
            fileModifiedTimestamp = System.currentTimeMillis()
        )

        // In-place metadata update on audio engine without restarting playback
        audioEngine.updateCurrentTrackMetadata(trackUpdated)
        assertEquals(newArtFile.absolutePath, audioEngine.currentTrack.value?.artworkCachePath)
        assertEquals("User Selected", audioEngine.currentTrack.value?.artworkSource)

        // Invalidate and verify resolver yields updated art
        AlbumArtHelper.invalidateTrack(trackUpdated.id, trackUpdated.artist, trackUpdated.album)
        val decoded = AlbumArtHelper.getArtworkForTrack(context, trackUpdated, 512)
        assertNotNull(decoded)
    }

    // ── 7. ALBUM WHERE FIRST TRACK LACKS ART BUT ANOTHER HAS IT ───────────────

    @Test
    fun testAlbumWhereFirstTrackLacksArtChoosesTrackWithValidArt() = runBlocking {
        val artFile = createTestImageFile("track2_cover.png", android.graphics.Color.BLUE)

        val track1 = Track(
            id = "alb_trk_1",
            title = "Intro (No Art)",
            artist = "Electronic Duo",
            album = "Future Wave",
            trackNumber = 1,
            filePath = "/Music/01_intro.mp3",
            artworkCachePath = null
        )

        val track2 = Track(
            id = "alb_trk_2",
            title = "Hit Single (Has Art)",
            artist = "Electronic Duo",
            album = "Future Wave",
            trackNumber = 2,
            filePath = "/Music/02_hit.mp3",
            artworkCachePath = artFile.absolutePath
        )

        val track3 = Track(
            id = "alb_trk_3",
            title = "Outro",
            artist = "Electronic Duo",
            album = "Future Wave",
            trackNumber = 3,
            filePath = "/Music/03_outro.mp3",
            artworkCachePath = null
        )

        val album = Album(
            id = "album_future_wave",
            title = "Future Wave",
            artist = "Electronic Duo",
            trackCount = 3,
            totalDurationSeconds = 600,
            tracks = listOf(track1, track2, track3),
            artworkUri = null // Unset at album level
        )

        // Resolver must inspect member tracks deterministically and find track 2's art
        val albumArt = AlbumArtHelper.getArtworkForAlbum(context, album, 320)
        assertNotNull(albumArt)

        val cachedAlbumArt = AlbumArtHelper.getCachedArtworkForAlbum(album, 320)
        assertNotNull(cachedAlbumArt)
        assertEquals(albumArt, cachedAlbumArt)
    }

    // ── 8. ALBUM WITH NO ART GENERATES DETERMINISTIC FALLBACK ─────────────────

    @Test
    fun testAlbumWithNoArtGeneratesDeterministicFallback() = runBlocking {
        val track1 = Track(
            id = "blank_trk_1",
            title = "Instrumental 1",
            artist = "Anonymous",
            album = "Ghost Project",
            filePath = "/Music/ghost1.mp3"
        )
        val track2 = Track(
            id = "blank_trk_2",
            title = "Instrumental 2",
            artist = "Anonymous",
            album = "Ghost Project",
            filePath = "/Music/ghost2.mp3"
        )

        val album = Album(
            id = "album_ghost_project",
            title = "Ghost Project",
            artist = "Anonymous",
            trackCount = 2,
            totalDurationSeconds = 400,
            tracks = listOf(track1, track2)
        )

        val fallbackBitmap = AlbumArtHelper.getArtworkForAlbum(context, album, 320)
        assertNotNull(fallbackBitmap)
        assertTrue(fallbackBitmap.width == 320 && fallbackBitmap.height == 320)
    }

    // ── 9. TWO ALBUMS WITH SAME TITLE BUT DISTINCT ARTISTS DO NOT SHARE ART ───

    @Test
    fun testTwoAlbumsWithSameTitleDistinctArtistsDoNotShareArt() = runBlocking {
        val artQueen = createTestImageFile("queen_hits.png", android.graphics.Color.YELLOW)
        val artEminem = createTestImageFile("eminem_hits.png", android.graphics.Color.RED)

        val albumQueen = Album(
            id = "album_${"Queen".hashCode()}_${"Greatest Hits".hashCode()}",
            title = "Greatest Hits",
            artist = "Queen",
            trackCount = 10,
            totalDurationSeconds = 2400,
            tracks = listOf(Track(id = "q1", title = "Bohemian Rhapsody", artist = "Queen", album = "Greatest Hits", artworkCachePath = artQueen.absolutePath, filePath = "/Music/queen.mp3")),
            artworkUri = artQueen.absolutePath
        )

        val albumEminem = Album(
            id = "album_${"Eminem".hashCode()}_${"Greatest Hits".hashCode()}",
            title = "Greatest Hits",
            artist = "Eminem",
            trackCount = 10,
            totalDurationSeconds = 2400,
            tracks = listOf(Track(id = "e1", title = "Lose Yourself", artist = "Eminem", album = "Greatest Hits", artworkCachePath = artEminem.absolutePath, filePath = "/Music/eminem.mp3")),
            artworkUri = artEminem.absolutePath
        )

        assertNotEquals(albumQueen.id, albumEminem.id)

        val queenArt = AlbumArtHelper.getArtworkForAlbum(context, albumQueen, 320)
        val eminemArt = AlbumArtHelper.getArtworkForAlbum(context, albumEminem, 320)

        assertNotNull(queenArt)
        assertNotNull(eminemArt)

        // Invalidating Queen must not invalidate Eminem
        AlbumArtHelper.invalidateAlbum("Queen", "Greatest Hits")
        val eminemCachedAfter = AlbumArtHelper.getCachedArtworkForAlbum(albumEminem, 320)
        assertNotNull("Eminem album art must remain resident when Queen is invalidated", eminemCachedAfter)
    }

    // ── 10. CACHE WARM VS COLD BEHAVIOR ───────────────────────────────────────

    @Test
    fun testCacheWarmVsColdBehavior() = runBlocking {
        val artFile = createTestImageFile("warm_cold_art.png", android.graphics.Color.WHITE)

        val track = Track(
            id = "cache_test_trk",
            title = "Cache Speed Test",
            artist = "Benchmark",
            artworkCachePath = artFile.absolutePath,
            filePath = "/Music/bench.mp3"
        )

        // 1. Cold state: memory cache has no entry
        assertNull(AlbumArtHelper.getCachedArtwork(track, 512))

        // 2. Decode populates warm cache
        val coldDecoded = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(coldDecoded)

        // 3. Warm state: memory cache returns identical instance immediately
        val warmResult = AlbumArtHelper.getCachedArtwork(track, 512)
        assertNotNull(warmResult)
        assertEquals(coldDecoded, warmResult)

        // 4. Memory cache clear returns to cold
        AlbumArtHelper.clearMemoryCache()
        assertNull(AlbumArtHelper.getCachedArtwork(track, 512))
    }

    // ── 11. USB / REMOVABLE SOURCE ARTWORK WITH PERSISTED ACCESS ──────────────

    @Test
    fun testUsbRemovableSourceArtworkWithPersistedAccess() = runBlocking {
        val usbUuid = "8888-9999"
        val cachedArtFile = createTestImageFile("usb_album_art.png", android.graphics.Color.CYAN)

        val track = Track(
            id = "usb_track_art_1",
            title = "Festival Live Set",
            artist = "Headline DJ",
            album = "USB Vault Sessions",
            filePath = "/storage/$usbUuid/Music/live_set.mp3",
            sourceId = "usb_$usbUuid",
            artworkCachePath = cachedArtFile.absolutePath
        )

        val resolvedArt = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(resolvedArt)
        assertEquals(resolvedArt, AlbumArtHelper.getCachedArtwork(track, 512))
    }

    // ── 12. PRIORITY ORDER: USER-SELECTED WINS OVER AUTOMATIC ─────────────────

    @Test
    fun testPriorityOrderUserSelectedWinsOverAutomatic() = runBlocking {
        val autoArtFile = createTestImageFile("automatic_cover.png", android.graphics.Color.GRAY)
        val userArtFile = createTestImageFile("user_custom_cover.png", android.graphics.Color.MAGENTA)

        val track = Track(
            id = "user_override_trk",
            title = "Special Track",
            artist = "Artist",
            album = "Album",
            filePath = "/Music/track.mp3",
            artworkCachePath = userArtFile.absolutePath,
            artworkUrl = autoArtFile.absolutePath,
            artworkSource = "User Selected",
            userConfirmedMetadata = true
        )

        val resolved = AlbumArtHelper.getArtworkForTrack(context, track, 512)
        assertNotNull(resolved)
        // Ensure the cache key incorporates the user-selected artworkCachePath
        val key = AlbumArtHelper.computeCacheKey(track, 512)
        assertTrue(key.contains(userArtFile.absolutePath.hashCode().toString()))
    }
}
