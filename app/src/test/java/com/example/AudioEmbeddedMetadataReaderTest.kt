package com.example

import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.EmbeddedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEmbeddedMetadataReaderTest {

    @Test
    fun `embedded metadata defaults and validity indicators`() {
        val empty = EmbeddedAudioMetadata()
        assertFalse(empty.hasBpm)
        assertFalse(empty.hasKey)
        assertFalse(empty.hasEmbeddedMusicBrainz)

        val withData = EmbeddedAudioMetadata(
            title = "Digital Love",
            artist = "Daft Punk",
            bpm = 125.0,
            musicalKey = "8A",
            musicBrainzRecordingId = "mbid-recording-123",
            musicBrainzReleaseId = "mbid-release-456"
        )
        assertTrue(withData.hasBpm)
        assertTrue(withData.hasKey)
        assertTrue(withData.hasEmbeddedMusicBrainz)
        assertEquals("mbid-recording-123", withData.musicBrainzRecordingId)
        assertEquals("mbid-release-456", withData.musicBrainzReleaseId)
        assertEquals(125.0, withData.bpm ?: 0.0, 0.001)
        assertEquals("8A", withData.musicalKey)
    }

    @Test
    fun `reads empty metadata for empty or blank file path`() {
        val result = AudioEmbeddedMetadataReader.read(org.robolectric.RuntimeEnvironment.getApplication(), "")
        assertFalse(result.hasBpm)
        assertFalse(result.hasKey)
        assertFalse(result.hasEmbeddedMusicBrainz)
    }
}
