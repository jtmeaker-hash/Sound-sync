package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic specification for SoundSync visual themes.
 * Allows switching between Default and Pro themes cleanly without scattered conditionals.
 */
data class SoundSyncThemeSpec(
    val id: String,
    val name: String,
    val isPro: Boolean,
    val is3BandColoring: Boolean,

    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val divider: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,

    val accent: Color,
    val accentHover: Color,
    val accentMuted: Color,

    val selectedSurface: Color,
    val playingSurface: Color,

    val warning: Color,
    val error: Color,
    val success: Color,

    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,

    val libraryCompactRowHeight: Dp,
    val libraryComfortableRowHeight: Dp,

    val controlSmallHeight: Dp,
    val controlNormalHeight: Dp
)

/**
 * Library row density modes available in Pro theme.
 */
enum class ProLibraryDensity(val label: String) {
    COMPACT("Compact"),
    COMFORTABLE("Comfortable");

    companion object {
        fun fromStoredValue(value: String?): ProLibraryDensity =
            if (value == COMFORTABLE.name) COMFORTABLE else COMPACT
    }
}

val DefaultThemeSpec = SoundSyncThemeSpec(
    id = "default",
    name = "Default",
    isPro = false,
    is3BandColoring = false,

    background = Color(0xFF090B10),
    surface = Color(0xFF121620),
    surfaceRaised = Color(0xFF222B3D),
    surfaceSunken = Color(0xFF07090D),
    divider = Color(0xFF2D384E),

    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    textDisabled = Color(0xFF475569),

    accent = Color(0xFF00F0FF), // DeckACyan
    accentHover = Color(0xFF33F3FF),
    accentMuted = Color(0x3300F0FF),

    selectedSurface = Color(0x2600F0FF),
    playingSurface = Color(0x1400F0FF),

    warning = Color(0xFFFFB800), // NeonAmber
    error = Color(0xFFFF3333),   // NeonRed
    success = Color(0xFF05FFA1), // NeonGreen

    cornerSmall = 6.dp,
    cornerMedium = 10.dp,
    cornerLarge = 16.dp,

    libraryCompactRowHeight = 64.dp,
    libraryComfortableRowHeight = 72.dp,

    controlSmallHeight = 36.dp,
    controlNormalHeight = 48.dp
)

val ProThemeSpec = SoundSyncThemeSpec(
    id = "pro",
    name = "Pro",
    isPro = true,
    is3BandColoring = true,

    // Near-black / graphite professional visual system inspired by Pioneer rekordbox
    background = Color(0xFF111317),      // Dark charcoal background
    surface = Color(0xFF181B21),         // Primary panel graphite
    surfaceRaised = Color(0xFF22262F),   // Secondary / raised panel subtle step lighter
    surfaceSunken = Color(0xFF0C0E11),   // Sunken panel / track background
    divider = Color(0xFF2B313C),         // Thin low-contrast cool-grey lines

    textPrimary = Color(0xFFF0F2F5),     // Near-white
    textSecondary = Color(0xFF8E95A2),   // Cool grey
    textMuted = Color(0xFF5E6573),       // Muted grey
    textDisabled = Color(0xFF3F444F),    // Disabled grey

    accent = Color(0xFF1E6CFF),          // Professional cool blue (authoritative, non-neon)
    accentHover = Color(0xFF3B7FFF),
    accentMuted = Color(0x2B1E6CFF),

    selectedSurface = Color(0xFF182338), // Dark blue-charcoal selection row
    playingSurface = Color(0xFF131D2E),  // Restrained playing row background

    warning = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    success = Color(0xFF10B981),

    // Restrained corner radii (2-3dp small, 3-5dp normal, max 5-6dp container)
    cornerSmall = 2.dp,
    cornerMedium = 4.dp,
    cornerLarge = 6.dp,

    libraryCompactRowHeight = 38.dp,
    libraryComfortableRowHeight = 48.dp,

    controlSmallHeight = 28.dp,
    controlNormalHeight = 38.dp
)

val LocalSoundSyncTheme = staticCompositionLocalOf { DefaultThemeSpec }
val LocalLibraryDensity = compositionLocalOf { ProLibraryDensity.COMPACT }
