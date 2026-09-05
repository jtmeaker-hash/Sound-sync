package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.metadata.AudioEmbeddedMetadataReader
import com.example.metadata.EmbeddedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioEmbeddedMetadataReaderTest {

    @Test
    fun `embedded metadata defaults and validity indicators`() {
        val empty = EmbeddedAudioMetadata()
        assertFalse(empty.hasBpm)
        assertFalse(empty.hasKey)

        val withData = EmbeddedAudioMetadata(
            title = "Digital Love",
            artist = "Daft Punk",
            bpm = 125.0,
            musicalKey = "8A"
        )
        assertTrue(withData.hasBpm)
        assertTrue(withData.hasKey)
        assertEquals(125.0, withData.bpm ?: 0.0, 0.001)
        assertEquals("8A", withData.musicalKey)
    }

    @Test
    fun `reads empty metadata for empty or blank file path with application context`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val result = AudioEmbeddedMetadataReader.read(context, "")
        assertFalse(result.hasBpm)
        assertFalse(result.hasKey)
    }

    @Test
    fun `reads empty metadata for null context or non-existent file path`() {
        val resultNullContext = AudioEmbeddedMetadataReader.read(null, "")
        assertFalse(resultNullContext.hasBpm)
        assertFalse(resultNullContext.hasKey)

        val context: Context = ApplicationProvider.getApplicationContext()
        val resultNonExistent = AudioEmbeddedMetadataReader.read(context, "/nonexistent/path/track.mp3")
        assertFalse(resultNonExistent.hasBpm)
        assertFalse(resultNonExistent.hasKey)
    }
}
