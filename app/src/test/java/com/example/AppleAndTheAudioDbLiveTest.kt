package com.example

import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.theaudiodb.TheAudioDbArtworkProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppleAndTheAudioDbLiveTest {

    @Test
    fun `Phase 4 - Prove Apple iTunes Search API works with real request for Daft Punk - Get Lucky`() = runBlocking {
        println("=== START PHASE 4: APPLE ITUNES SEARCH API LIVE PROOF ===")
        val provider = AppleMetadataProvider()

        // Test: Daft Punk - Get Lucky
        val results = provider.searchTracks("Daft Punk Get Lucky", country = "AU", limit = 5)

        assertFalse("Apple API must return real candidates", results.isEmpty())
        val first = results.first()
        println("AppleMetadataProvider: parsed top track: ${first.artistName} - ${first.trackName}")
        println("collectionName: ${first.collectionName}")
        println("trackTimeMillis: ${first.trackTimeMillis}")
        println("releaseDate: ${first.releaseDate}")
        println("primaryGenreName: ${first.primaryGenreName}")
        println("trackNumber: ${first.trackNumber}")
        println("trackCount: ${first.trackCount}")
        println("discNumber: ${first.discNumber}")
        println("discCount: ${first.discCount}")
        println("trackExplicitness: ${first.trackExplicitness}")

        assertTrue("Artist name must contain Daft Punk", first.artistName.contains("Daft Punk", ignoreCase = true))
        assertTrue("Track name must contain Get Lucky", first.trackName.contains("Get Lucky", ignoreCase = true))
        assertTrue("Track ID must be valid", first.trackId > 0)
        println("=== PHASE 4 PROOF PASSED SUCCESSFULLY ===")
    }

    @Test
    fun `Phase 12 - Prove TheAudioDB API works with real request and downloads valid cover image`() = runBlocking {
        println("=== START PHASE 12: THEAUDIODB ARTWORK LIVE PROOF ===")
        val provider = TheAudioDbArtworkProvider()

        val candidates = provider.findArtwork(
            artist = "Daft Punk",
            album = "Random Access Memories",
            track = "Get Lucky"
        )

        assertFalse("TheAudioDB must return artwork candidates", candidates.isEmpty())
        val topArtwork = candidates.first()
        println("TheAudioDbArtworkProvider: top candidate: ${topArtwork.artworkUrl} (HQ=${topArtwork.isHighQuality})")
        assertTrue("Artwork URL must not be blank", topArtwork.artworkUrl.isNotBlank())

        val downloaded = provider.downloadArtwork(topArtwork.artworkUrl)
        assertNotNull("Downloaded artwork must not be null", downloaded)
        println("Downloaded artwork dimensions: ${downloaded!!.width}x${downloaded.height}, size: ${downloaded.bytes.size} bytes, mime: ${downloaded.mimeType}")

        assertTrue("Image width must be >= 150", downloaded.width >= 150)
        assertTrue("Image height must be >= 150", downloaded.height >= 150)
        assertTrue("Bytes must be non-empty", downloaded.bytes.isNotEmpty())
        println("=== PHASE 12 PROOF PASSED SUCCESSFULLY ===")
    }
}
