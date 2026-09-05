package com.example

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.BloodRedPrimary
import com.example.ui.theme.ProDarkVariant
import com.example.ui.theme.ProBlackRedThemeSpec
import com.example.ui.theme.ProBlackWhiteThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProDarkVariantsTest {

    @Test
    fun `ProDarkVariant enum defines BLACK_WHITE and BLACK_RED with proper labels`() {
        assertEquals("Black & White", ProDarkVariant.BLACK_WHITE.label)
        assertEquals("Black & Red", ProDarkVariant.BLACK_RED.label)

        assertEquals(ProDarkVariant.BLACK_WHITE, ProDarkVariant.valueOf("BLACK_WHITE"))
        assertEquals(ProDarkVariant.BLACK_RED, ProDarkVariant.valueOf("BLACK_RED"))

        // fromStoredValue robustness
        assertEquals(ProDarkVariant.BLACK_WHITE, ProDarkVariant.fromStoredValue("BLACK_WHITE"))
        assertEquals(ProDarkVariant.BLACK_RED, ProDarkVariant.fromStoredValue("BLACK_RED"))
        assertEquals(ProDarkVariant.BLACK_WHITE, ProDarkVariant.fromStoredValue(null))
        assertEquals(ProDarkVariant.BLACK_WHITE, ProDarkVariant.fromStoredValue("UNKNOWN_VARIANT"))
    }

    @Test
    fun `ProThemeBlackWhitePalette implements true monochrome dark aesthetic`() {
        val palette = ProBlackWhiteThemeSpec

        assertEquals(ProDarkVariant.BLACK_WHITE, palette.proDarkVariant)
        assertEquals(Color(0xFF0A0A0C), palette.background)
        assertEquals(Color(0xFF131316), palette.surface)
        assertEquals(Color(0xFFFFFFFF), palette.textPrimary)
        assertEquals(Color(0xFFFFFFFF), palette.accent)
        assertEquals(Color(0xFF282830), palette.divider)
    }

    @Test
    fun `ProThemeBlackRedPalette implements performance dark with blood-red accents`() {
        val palette = ProBlackRedThemeSpec

        assertEquals(ProDarkVariant.BLACK_RED, palette.proDarkVariant)
        assertEquals(Color(0xFF050505), palette.background)
        assertEquals(Color(0xFF101010), palette.surface)
        assertEquals(BloodRedPrimary, palette.accent)
        assertEquals(Color(0xFFF8F7F7), palette.textPrimary)
        assertNotEquals(ProBlackWhiteThemeSpec.accent, palette.accent)
    }

    @Test
    fun `Pro dark variants maintain professional contrast and typography readability`() {
        listOf(ProBlackWhiteThemeSpec, ProBlackRedThemeSpec).forEach { palette ->
            // Surface must be distinct from background
            assertNotEquals(palette.background, palette.surface)
            // Both must be Pro specs
            assertTrue(palette.isPro)
        }
    }
}
