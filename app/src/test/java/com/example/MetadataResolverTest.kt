package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.metadata.ArtworkCache
import com.example.metadata.MetadataResolver
import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.theaudiodb.DownloadedArtwork
import com.example.metadata.theaudiodb.TheAudioDbArtworkProvider
import com.example.model.MetadataScanState
import com.example.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataResolverTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.filesDir, "artwork_cache").apply { mkdirs() }
    }

    @Test
    fun `MetadataResolver respects user-confirmed metadata and skips resolution`() = runBlocking {
        val resolver = MetadataResolver(context)
        val protectedTrack = Track(
            id = "t1",
            title = "Manual Title",
            artist = "Manual Artist",
            userConfirmedMetadata = true,
            metadataScanState = MetadataScanState.USER_CONFIRMED.name
        )

        val result = resolver.resolveTrackMetadata(protectedTrack, forceRefresh = false)
        assertEquals(MetadataScanState.USER_CONFIRMED, result.scanState)
        assertEquals("Manual Title", result.updatedTrack.title)
        assertEquals("Manual Artist", result.updatedTrack.artist)
        assertFalse(result.wasRepaired)
    }

    @Test
    fun `MetadataResolver skips redundant lookup when track is already complete and not force-refreshed`() = runBlocking {
        val resolver = MetadataResolver(context)
        val completeTrack = Track(
            id = "t2",
            title = "Get Lucky",
            artist = "Daft Punk",
            appleTrackId = 636990666L,
            metadataScanState = MetadataScanState.COMPLETE.name,
            metadataConfidence = 95.0
        )

        val result = resolver.resolveTrackMetadata(completeTrack, forceRefresh = false)
        assertEquals(MetadataScanState.COMPLETE, result.scanState)
        assertEquals(95.0, result.confidence, 0.01)
        assertFalse(result.wasRepaired)
    }

    @Test
    fun `ArtworkCache computes deterministic filenames and persists image bytes`() {
        val cache = ArtworkCache(context)
        val dummyBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val artwork = DownloadedArtwork(
            bytes = dummyBytes,
            mimeType = "image/jpeg",
            width = 500,
            height = 500,
            sourceUrl = "https://www.theaudiodb.com/images/media/album/thumb/test.jpg"
        )

        val cachedFile = cache.saveArtwork(
            artist = "Daft Punk",
            album = "Discovery",
            artwork = artwork
        )

        assertNotNull(cachedFile)
        assertTrue(cachedFile.exists())
        assertEquals(4, cachedFile.length())
        assertTrue(cache.hasCachedArtwork("Daft Punk", "Discovery"))

        val retrieved = cache.getCachedArtworkFile("Daft Punk", "Discovery")
        assertNotNull(retrieved)
        assertEquals(cachedFile.absolutePath, retrieved!!.absolutePath)
    }

    @Test
    fun `MetadataResolver preserves local audio DSP analysis during metadata updates`() = runBlocking {
        val localTrack = Track(
            id = "t3",
            title = "Strobe",
            artist = "Deadmau5",
            durationSeconds = 637,
            bpm = 128.0,
            bpmConfidence = 0.99,
            bpmLastAnalyzed = 1700000000L,
            bpmAnalysisVersion = "v1.0-stft",
            musicalKey = "A# Minor",
            camelotKey = "3A",
            keyConfidence = 0.95,
            hotCues = listOf(0, 32, 64, 128),
            energyRating = 8
        )

        // Mock Apple provider
        val mockApple = object : AppleMetadataProvider() {
            override suspend fun searchTracks(query: String, country: String, limit: Int): List<AppleTrackResult> {
                return listOf(
                    AppleTrackResult(
                        trackId = 999123L,
                        trackName = "Strobe",
                        artistId = 888L,
                        artistName = "Deadmau5",
                        collectionId = 777L,
                        collectionName = "For Lack of a Better Name",
                        trackTimeMillis = 637000L,
                        releaseDate = "2009-09-22T07:00:00Z",
                        primaryGenreName = "Dance"
                    )
                )
            }
        }

        val customResolver = MetadataResolver(
            context = context,
            appleProvider = mockApple,
            artworkProvider = TheAudioDbArtworkProvider(),
            artworkCache = ArtworkCache(context)
        )

        val result = customResolver.resolveTrackMetadata(localTrack, forceRefresh = true)
        val updated = result.updatedTrack

        assertEquals(999123L, updated.appleTrackId)
        assertEquals(888L, updated.appleArtistId)
        assertEquals("For Lack of a Better Name", updated.album)

        // Verify DSP attributes were NOT overwritten
        assertEquals(128.0, updated.bpm, 0.001)
        assertEquals(0.99, updated.bpmConfidence, 0.001)
        assertEquals("A# Minor", updated.musicalKey)
        assertEquals("3A", updated.camelotKey)
        assertEquals(listOf(0, 32, 64, 128), updated.hotCues)
        assertEquals(8, updated.energyRating)
    }
}
