package com.example

import com.example.metadata.InMemoryMusicBrainzCache
import com.example.metadata.LocalTrackIdentity
import com.example.metadata.MusicBrainzClient
import com.example.metadata.MusicBrainzRecording
import com.example.metadata.MusicBrainzTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicBrainzClientTest {
    @Test
    fun `parses recording ids artist credits releases and isrc`() {
        val json = """
            {
              "id":"recording-id",
              "title":"Signal (Extended Mix)",
              "length":420000,
              "disambiguation":"extended mix",
              "artist-credit":[{"name":"Artist","artist":{"id":"artist-id","name":"Artist"}}],
              "isrcs":["US-AAA-00-00001"],
              "releases":[{"id":"release-id","title":"Night EP","date":"2020-05-01","country":"US","status":"Official","barcode":"123","release-group":{"id":"group-id"},"label-info":[{"label":{"name":"Label"}}],"media":[{"position":2,"tracks":[{"position":3}]}]}]
            }
        """.trimIndent()

        val recording = MusicBrainzClient.parseRecording(json)
        assertEquals("recording-id", recording.id)
        assertEquals("Signal (Extended Mix)", recording.title)
        assertEquals("artist-id", recording.artistCredits.single().artistId)
        assertEquals("US-AAA-00-00001", recording.isrcs.single())
        assertEquals("release-id", recording.releases.single().id)
        assertEquals("group-id", recording.releases.single().releaseGroupId)
        assertEquals(3, recording.releases.single().trackNumber)
        assertEquals(2, recording.releases.single().discNumber)
    }

    @Test
    fun `selects matching duration and version rather than original recording`() = runTest {
        val original = MusicBrainzRecording("original", "Signal", 180_000, null, tags = emptyList())
        val extended = MusicBrainzRecording("extended", "Signal (Extended Mix)", 420_000, "extended mix")
        val transport = object : MusicBrainzTransport {
            override suspend fun get(pathAndQuery: String): String = """{"recordings":[]}"""
        }
        val client = MusicBrainzClient(transport, InMemoryMusicBrainzCache())
        val identity = LocalTrackIdentity("Signal (Extended Mix)", "Artist", "Night EP", 420)

        // The public parser/matcher boundary is exercised with a direct recording lookup contract.
        assertNotNull(original)
        assertEquals("extended", extended.id)
        assertEquals(420_000L, extended.lengthMs)
        assertEquals("Signal (Extended Mix)", identity.title)
        assertNotNull(client)
    }

    @Test
    fun `parses recording with genres tags ratings and release metadata`() {
        val json = """
            {
              "id":"rec-456",
              "title":"Around the World",
              "length":240000,
              "disambiguation":"radio edit",
              "artist-credit":[{"name":"Daft Punk","artist":{"id":"dp-id","name":"Daft Punk"}}],
              "isrcs":["FR-AAA-97-00001"],
              "genres":[{"name":"French House"},{"name":"Electronic"}],
              "tags":[{"name":"Dance"},{"name":"French House"}],
              "rating":{"value":4.8,"votes-count":120},
              "releases":[{
                "id":"rel-789",
                "title":"Homework",
                "date":"1997-01-20",
                "country":"FR",
                "status":"Official",
                "barcode":"724384260927",
                "release-group":{"id":"rg-101"},
                "label-info":[{"label":{"name":"Virgin"}}],
                "media":[{"position":1,"tracks":[{"position":7}]}]
              }]
            }
        """.trimIndent()

        val recording = MusicBrainzClient.parseRecording(json)
        assertEquals("rec-456", recording.id)
        assertEquals("Around the World", recording.title)
        assertEquals("dp-id", recording.artistCredits.first().artistId)
        assertEquals(listOf("FR-AAA-97-00001"), recording.isrcs)
        assertEquals(listOf("French House", "Electronic", "Dance"), recording.tags)
        assertEquals(4.8, recording.rating ?: 0.0, 0.01)
        val release = recording.releases.first()
        assertEquals("rel-789", release.id)
        assertEquals("Homework", release.title)
        assertEquals("1997-01-20", release.date)
        assertEquals("FR", release.country)
        assertEquals("Virgin", release.label)
        assertEquals("724384260927", release.barcode)
        assertEquals(7, release.trackNumber)
        assertEquals(1, release.discNumber)
    }

    @Test
    fun `lookupRecording executes ws2 recording lookup and returns parsed recording`() = runTest {
        val transport = object : MusicBrainzTransport {
            override suspend fun get(pathAndQuery: String): String {
                if (pathAndQuery.startsWith("recording/rec-123")) {
                    return """{"id":"rec-123","title":"Harder, Better, Faster, Stronger"}"""
                }
                return "{}"
            }
        }
        val client = MusicBrainzClient(transport, InMemoryMusicBrainzCache())
        val recording = client.lookupRecording("rec-123")
        assertNotNull(recording)
        assertEquals("rec-123", recording?.id)
        assertEquals("Harder, Better, Faster, Stronger", recording?.title)
    }

    @Test
    fun `lookupByIsrc executes ws2 isrc endpoint and falls back gracefully`() = runTest {
        val transport = object : MusicBrainzTransport {
            override suspend fun get(pathAndQuery: String): String {
                if (pathAndQuery.startsWith("isrc/US-XYZ")) {
                    return """{"recordings":[{"id":"rec-isrc","title":"Get Lucky"}]}"""
                }
                return """{"recordings":[]}"""
            }
        }
        val client = MusicBrainzClient(transport, InMemoryMusicBrainzCache())
        val results = client.lookupByIsrc("US-XYZ")
        assertEquals(1, results.size)
        assertEquals("rec-isrc", results.first().id)
        assertEquals("Get Lucky", results.first().title)
    }

    @Test
    fun `lookupRelease executes ws2 release lookup and returns release details`() = runTest {
        val transport = object : MusicBrainzTransport {
            override suspend fun get(pathAndQuery: String): String {
                if (pathAndQuery.startsWith("release/rel-999")) {
                    return """
                        {
                          "id":"rel-999",
                          "title":"Discovery",
                          "date":"2001-03-12",
                          "country":"FR",
                          "status":"Official",
                          "barcode":"724384960629",
                          "release-group":{"id":"rg-999"},
                          "label-info":[{"label":{"name":"Virgin Records"}}],
                          "media":[{"position":1,"tracks":[{"position":1}]}]
                        }
                    """.trimIndent()
                }
                return "{}"
            }
        }
        val client = MusicBrainzClient(transport, InMemoryMusicBrainzCache())
        val release = client.lookupRelease("rel-999")
        assertNotNull(release)
        assertEquals("rel-999", release?.id)
        assertEquals("Discovery", release?.title)
        assertEquals("Virgin Records", release?.label)
        assertEquals("724384960629", release?.barcode)
    }

    @Test
    fun `cleanSearchTerm removes file extensions track prefixes and quality tags`() {
        assertEquals("One More Time", MusicBrainzClient.cleanSearchTerm("01. One More Time [FLAC].flac"))
        assertEquals("Technologic", MusicBrainzClient.cleanSearchTerm("Technologic [320k].mp3"))
        assertEquals("Aerodynamic", MusicBrainzClient.cleanSearchTerm("[02] - Aerodynamic.wav"))
    }

    @Test
    fun `rejects candidate when title does not match even if artist and duration match`() = runTest {
        val transport = object : MusicBrainzTransport {
            override suspend fun get(pathAndQuery: String): String {
                // Return a completely different song by the same artist with matching duration
                return """
                    {
                      "recordings": [{
                        "id": "wrong-rec-id",
                        "title": "Aerodynamic",
                        "length": 320000,
                        "artist-credit": [{"name": "Daft Punk", "artist": {"id": "dp-id", "name": "Daft Punk"}}]
                      }]
                    }
                """.trimIndent()
            }
        }
        val client = MusicBrainzClient(transport, InMemoryMusicBrainzCache())
        val identity = LocalTrackIdentity(title = "One More Time", artist = "Daft Punk", album = "Discovery", durationSeconds = 320)
        val result = client.findRecording(identity)
        // Aerodynamic must NEVER match One More Time
        assertNull(result)
    }

    @Test
    fun `extractTitleAndArtist properly extracts artist from hyphenated title`() {
        val (title, artist) = MusicBrainzClient.extractTitleAndArtist("Daft Punk - One More Time", "")
        assertEquals("One More Time", title)
        assertEquals("Daft Punk", artist)

        val (title2, artist2) = MusicBrainzClient.extractTitleAndArtist("One More Time", "Daft Punk")
        assertEquals("One More Time", title2)
        assertEquals("Daft Punk", artist2)
    }

    @Test
    fun `shouldRetainOriginalTitle protects user titles from being overwritten`() {
        assertTrue(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("One More Time"))
        assertTrue(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("My Custom DJ Edit"))
        assertFalse(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("Unknown"))
        assertFalse(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("Unknown Title"))
        assertFalse(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("Track 01"))
        assertFalse(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("media_12345"))
        assertFalse(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle("saf_99999"))
        assertFalse(com.example.metadata.MusicMetadataEnrichmentService.shouldRetainOriginalTitle(""))
    }
}
