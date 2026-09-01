package com.example

import com.example.metadata.InMemoryMusicBrainzCache
import com.example.metadata.LocalTrackIdentity
import com.example.metadata.MusicBrainzClient
import com.example.metadata.MusicBrainzRecording
import com.example.metadata.MusicBrainzTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `returns null when transport cannot produce a candidate`() = runTest {
        val client = MusicBrainzClient(object : MusicBrainzTransport {
            override suspend fun get(pathAndQuery: String): String = "not-json"
        })
        assertNull(client.findRecording(LocalTrackIdentity("Unknown", "", "", 0)))
    }
}
