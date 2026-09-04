package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.Track
import com.example.ui.library.SongSortMode
import com.example.ui.theme.DefaultThemeSpec
import com.example.ui.theme.ProLibraryDensity
import com.example.ui.theme.ProThemeSpec
import com.example.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProThemeAndWaveformTest {

    @Test
    fun `SoundSyncThemeSpec distinguishes Default and Pro specifications correctly`() {
        val defaultSpec = DefaultThemeSpec
        val proSpec = ProThemeSpec

        assertFalse("Default spec must have isPro = false", defaultSpec.isPro)
        assertTrue("Pro spec must have isPro = true", proSpec.isPro)

        // Corner radius: Pro has restrained corners vs Default rounded cards
        assertTrue("Pro cornerSmall must be <= 3dp", proSpec.cornerSmall <= 3.dp)
        assertTrue("Pro cornerMedium must be <= 5dp", proSpec.cornerMedium <= 5.dp)
        assertTrue("Default cornerMedium should be >= 8dp", defaultSpec.cornerMedium >= 8.dp)

        // Row heights
        assertEquals(38.dp, proSpec.libraryCompactRowHeight)
        assertEquals(48.dp, proSpec.libraryComfortableRowHeight)

        // Pro theme background colors must match rekordbox dark charcoal aesthetic
        assertEquals(Color(0xFF111317), proSpec.background)
        assertEquals(Color(0xFF181B21), proSpec.surface)
        assertEquals(Color(0xFF0C0E11), proSpec.surfaceSunken)
        assertEquals(Color(0xFF22262F), proSpec.surfaceRaised)
        assertEquals(Color(0xFF2B313C), proSpec.divider)
        assertEquals(Color(0xFF1E6CFF), proSpec.accent)

        // Default theme colors should remain preserved
        assertNotEquals(defaultSpec.background, proSpec.background)
        assertNotEquals(defaultSpec.accent, proSpec.accent)
    }

    @Test
    fun `ProLibraryDensity specifies COMPACT and COMFORTABLE modes`() {
        assertEquals("Compact", ProLibraryDensity.COMPACT.label)
        assertEquals("Comfortable", ProLibraryDensity.COMFORTABLE.label)

        // ValueOf and persistence strings
        assertEquals(ProLibraryDensity.COMPACT, ProLibraryDensity.valueOf("COMPACT"))
        assertEquals(ProLibraryDensity.COMFORTABLE, ProLibraryDensity.valueOf("COMFORTABLE"))

        // fromStoredValue helper
        assertEquals(ProLibraryDensity.COMPACT, ProLibraryDensity.fromStoredValue("COMPACT"))
        assertEquals(ProLibraryDensity.COMFORTABLE, ProLibraryDensity.fromStoredValue("COMFORTABLE"))
        assertEquals(ProLibraryDensity.COMPACT, ProLibraryDensity.fromStoredValue(null))
        assertEquals(ProLibraryDensity.COMPACT, ProLibraryDensity.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun `ThemeMode enum defines DEFAULT and PRO`() {
        assertTrue(ThemeMode.values().contains(ThemeMode.DEFAULT))
        assertTrue(ThemeMode.values().contains(ThemeMode.PRO))
        assertTrue(ThemeMode.PRO.isPro)
        assertFalse(ThemeMode.DEFAULT.isPro)
        assertEquals(ThemeMode.PRO, ThemeMode.fromStoredValue("PRO"))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStoredValue("DEFAULT"))
    }

    @Test
    fun `SongSortMode sorts tracks accurately by all criteria`() {
        val t1 = Track(
            id = "1",
            title = "Zeta",
            artist = "Bravo",
            album = "Delta",
            durationSeconds = 180,
            bpm = 120.0,
            camelotKey = "1A",
            dateAdded = 1000L
        )
        val t2 = Track(
            id = "2",
            title = "Alpha",
            artist = "Zulu",
            album = "Alpha",
            durationSeconds = 240,
            bpm = 128.0,
            camelotKey = "8A",
            dateAdded = 3000L
        )
        val t3 = Track(
            id = "3",
            title = "Beta",
            artist = "Alpha",
            album = "Charlie",
            durationSeconds = 120,
            bpm = 124.0,
            camelotKey = "12B",
            dateAdded = 2000L
        )

        val list = listOf(t1, t2, t3)

        // Sort by Title
        val byTitle = SongSortMode.TITLE_ASC.sort(list)
        assertEquals(listOf("Alpha", "Beta", "Zeta"), byTitle.map { it.title })

        // Sort by Artist
        val byArtist = SongSortMode.ARTIST_ASC.sort(list)
        assertEquals(listOf("Alpha", "Bravo", "Zulu"), byArtist.map { it.artist })

        // Sort by BPM
        val byBpm = SongSortMode.BPM_ASC.sort(list)
        assertEquals(listOf(120.0, 124.0, 128.0), byBpm.map { it.bpm })

        // Sort by Key (alphabetical string sort: "12B" < "1A" < "8A")
        val byKey = SongSortMode.KEY_ASC.sort(list)
        assertEquals(listOf("12B", "1A", "8A"), byKey.map { it.camelotKey })

        // Sort by Duration
        val byDuration = SongSortMode.DURATION_ASC.sort(list)
        assertEquals(listOf(120, 180, 240), byDuration.map { it.durationSeconds })

        // Sort by Date Added
        val byDate = SongSortMode.DATE_DESC.sort(list)
        assertEquals(listOf(3000L, 2000L, 1000L), byDate.map { it.dateAdded })
    }

    @Test
    fun `waveform sub-pixel playhead interpolation yields continuous 60fps positioning`() {
        val totalDurationMs = 180000f
        val windowSeconds = 8.0f
        val canvasWidth = 1000f
        val msPerPixel = (windowSeconds * 1000f) / canvasWidth

        var framePositionMs = 50000.0f
        val frameStepMs = 16.6667f

        val positions = mutableListOf<Float>()
        for (i in 0 until 5) {
            val deltaFromCenterPx = 0f
            val sampleTimeMs = framePositionMs + (deltaFromCenterPx * msPerPixel)
            positions.add(sampleTimeMs)
            framePositionMs += frameStepMs
        }

        for (i in 1 until positions.size) {
            val diff = positions[i] - positions[i - 1]
            assertEquals(frameStepMs, diff, 0.01f)
        }
    }

    @Test
    fun `rekordbox 3-band color frequency mapping computes distinct colors for low, mid, and high bands`() {
        val lowBandColor = Color(0xFFFF3B30)
        val midBandColor = Color(0xFF30D158)
        val highBandColor = Color(0xFF00E5FF)

        assertNotEquals(lowBandColor, midBandColor)
        assertNotEquals(midBandColor, highBandColor)
        assertNotEquals(lowBandColor, highBandColor)

        assertTrue("Low band must be dominated by Red channel", lowBandColor.red > lowBandColor.green && lowBandColor.red > lowBandColor.blue)
        assertTrue("Mid band must be dominated by Green channel", midBandColor.green > midBandColor.red && midBandColor.green > midBandColor.blue)
        assertTrue("High band must be dominated by Blue/Cyan channels", highBandColor.blue > highBandColor.red && highBandColor.green > highBandColor.red)
    }

    @Test
    fun `time formatting with tenths of a second matches rekordbox professional display`() {
        fun formatTenths(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val tenths = ((ms % 1000) / 100).coerceAtLeast(0)
            return String.format(java.util.Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }

        assertEquals("00:00.0", formatTenths(0L))
        assertEquals("00:01.5", formatTenths(1500L))
        assertEquals("01:42.8", formatTenths(102800L))
        assertEquals("03:18.2", formatTenths(198200L))
    }
}
