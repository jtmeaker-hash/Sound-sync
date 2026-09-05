package com.example

import com.example.metadata.AudioAnalysisResult
import com.example.metadata.CamelotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnalysisContractTest {
    @Test
    fun `supports all major and minor Camelot pitch classes`() {
        for (pitchClass in 0 until 12) {
            assertTrue(CamelotKey.fromPitchClass(pitchClass, isMinor = false).endsWith("B"))
            assertTrue(CamelotKey.fromPitchClass(pitchClass, isMinor = true).endsWith("A"))
        }
        assertEquals("8A", CamelotKey.fromMusicalKey("A minor"))
        assertEquals("11B", CamelotKey.fromMusicalKey("A major"))
        assertEquals("8B", CamelotKey.fromMusicalKey("C major"))
        assertEquals("5A", CamelotKey.fromMusicalKey("C minor"))
    }

    @Test
    fun `unknown audio analysis contains no fabricated values`() {
        val result = AudioAnalysisResult()
        assertNull(result.bpm)
        assertNull(result.musicalKey)
        assertNull(result.camelotKey)
        assertEquals(0.0, result.bpmConfidence, 0.0)
        assertEquals(0.0, result.keyConfidence, 0.0)
    }

    @Test
    fun `classifies metadata provenance correctly between Apple Search, local DSP, hybrid, and embedded`() {
        val embeddedTrack = com.example.model.Track(
            id = "1",
            title = "Track 1",
            artist = "Artist"
        )
        assertEquals(com.example.model.MetadataProvenance.EMBEDDED_TAGS, embeddedTrack.metadataProvenance)

        val appleTrack = embeddedTrack.copy(
            appleTrackId = 123456L
        )
        assertEquals(com.example.model.MetadataProvenance.APPLE_SEARCH, appleTrack.metadataProvenance)

        val localDspTrack = embeddedTrack.copy(
            bpm = 128.0,
            bpmLastAnalyzed = 1700000000000L,
            bpmAnalysisVersion = "v1.0-stft"
        )
        assertEquals(com.example.model.MetadataProvenance.LOCAL_DSP_ANALYZED, localDspTrack.metadataProvenance)

        val hybridTrack = embeddedTrack.copy(
            appleTrackId = 123456L,
            bpm = 128.0,
            bpmLastAnalyzed = 1700000000000L,
            bpmAnalysisVersion = "v1.0-stft"
        )
        assertEquals(com.example.model.MetadataProvenance.VERIFIED_HYBRID, hybridTrack.metadataProvenance)
    }

    @Test
    fun `invalid and blank musical keys remain unknown`() {
        assertNull(CamelotKey.fromMusicalKey(null))
        assertNull(CamelotKey.fromMusicalKey(""))
        assertNull(CamelotKey.fromMusicalKey("unknown"))
        assertNull(CamelotKey.fromMusicalKey("H minor"))
    }
}
