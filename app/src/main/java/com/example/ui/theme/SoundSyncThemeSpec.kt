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
    val proDarkVariant: ProDarkVariant? = null,

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
    val onAccent: Color = Color.White,

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
) {
    // Reusable semantic token aliases matching standard audio workstation design conventions
    val backgroundPrimary: Color get() = background
    val backgroundSecondary: Color get() = surfaceSunken
    val surfaceElevated: Color get() = surfaceRaised
    val textDisabledColor: Color get() = textDisabled
    val accentPrimary: Color get() = accent
    val accentSecondary: Color get() = accentHover
    val border: Color get() = divider
    val selected: Color get() = selectedSurface
    val pressed: Color get() = accentHover
    val disabled: Color get() = textDisabled
}

/**
 * Dark appearance variants available in Pro theme.
 */
enum class ProDarkVariant(val id: String, val label: String, val description: String) {
    BLACK_WHITE(
        id = "black_white",
        label = "Black & White",
        description = "Monochrome technical dark: pitch-black panels with crisp white typography and neutral-grey highlights"
    ),
    BLACK_RED(
        id = "black_red",
        label = "Black & Red",
        description = "SoundSync performance dark: deep black surfaces with vivid crimson red accents and playback indicators"
    );

    companion object {
        fun fromStoredValue(value: String?): ProDarkVariant = when (value) {
            BLACK_RED.name, BLACK_RED.id -> BLACK_RED
            BLACK_WHITE.name, BLACK_WHITE.id -> BLACK_WHITE
            else -> BLACK_WHITE
        }
    }
}

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

    background = Color(0xFF0E1013), // Matte hardware charcoal
    surface = Color(0xFF14171C),    // Workspace surface
    surfaceRaised = Color(0xFF1A1D23), // Panel housing / channel
    surfaceSunken = Color(0xFF0A0B0E), // Recessed waveform & list well
    divider = Color(0xFF252932),    // 1dp hairline border

    textPrimary = Color(0xFFF0F2F5),
    textSecondary = Color(0xFF98A0AE),
    textMuted = Color(0xFF656D7C),
    textDisabled = Color(0xFF404652),

    accent = Color(0xFFFF8A00), // Pioneer/CDJ Cue Amber-Orange
    accentHover = Color(0xFFFFA233),
    accentMuted = Color(0x33FF8A00),
    onAccent = Color(0xFF0E1013),

    selectedSurface = Color(0xFF232834),
    playingSurface = Color(0xFF1C2029),

    warning = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    success = Color(0xFF10B981),

    cornerSmall = 2.dp,
    cornerMedium = 4.dp,
    cornerLarge = 6.dp,

    libraryCompactRowHeight = 44.dp,
    libraryComfortableRowHeight = 56.dp,

    controlSmallHeight = 30.dp,
    controlNormalHeight = 40.dp
)

/**
 * Pro Dark — Black & White
 * True monochrome technical dark mode inspired by professional desktop audio workstations.
 * Pure dark surfaces, crisp white typography, neutral grey inactive states, and high contrast.
 */
val ProBlackWhiteThemeSpec = SoundSyncThemeSpec(
    id = "pro_black_white",
    name = "Pro (Black & White)",
    isPro = true,
    is3BandColoring = true,
    proDarkVariant = ProDarkVariant.BLACK_WHITE,

    background = Color(0xFF0A0A0C),      // Near-black main background
    surface = Color(0xFF131316),         // Slightly lighter dark panels and cards
    surfaceRaised = Color(0xFF1E1E23),   // Subtle elevation step for menus / headers
    surfaceSunken = Color(0xFF050507),   // Deep sunken well for waveforms & lists
    divider = Color(0xFF282830),         // Subtle dark-grey border / hairline separator

    textPrimary = Color(0xFFFFFFFF),     // Crisp white primary text
    textSecondary = Color(0xFFA1A1AA),   // Light grey secondary text
    textMuted = Color(0xFF71717A),       // Muted grey inactive/auxiliary text
    textDisabled = Color(0xFF3F3F46),    // Disabled grey

    accent = Color(0xFFFFFFFF),          // White active controls, indicators, and highlights
    accentHover = Color(0xFFE4E4E7),     // Neutral light-grey hover
    accentMuted = Color(0x33FFFFFF),     // Translucent white highlight
    onAccent = Color(0xFF000000),        // Pitch black on active white buttons for maximum contrast

    selectedSurface = Color(0xFF26262E), // Subtle neutral dark-grey selection row
    playingSurface = Color(0xFF1C1C22),  // Subtle playing row highlight

    warning = Color(0xFFEAB308),
    error = Color(0xFFEF4444),
    success = Color(0xFF22C55E),

    cornerSmall = 2.dp,
    cornerMedium = 4.dp,
    cornerLarge = 6.dp,

    libraryCompactRowHeight = 38.dp,
    libraryComfortableRowHeight = 48.dp,

    controlSmallHeight = 28.dp,
    controlNormalHeight = 38.dp
)

/**
 * Pro Dark — Black & Red
 * Integrates the existing black and red dark colour setting used by the Default theme into
 * the Pro workstation layout, reusing the existing Default black/red tokens.
 */
val ProBlackRedThemeSpec = SoundSyncThemeSpec(
    id = "pro_black_red",
    name = "Pro (Black & Red)",
    isPro = true,
    is3BandColoring = true,
    proDarkVariant = ProDarkVariant.BLACK_RED,

    background = Color(0xFF050505),      // Matches BloodRedAppearancePalette.background
    surface = Color(0xFF101010),         // Matches BloodRedAppearancePalette.surface
    surfaceRaised = Color(0xFF241516),   // Matches BloodRedAppearancePalette.elevated
    surfaceSunken = Color(0xFF070505),   // Deep sunken well
    divider = Color(0xFF472024),         // Matches BloodRedAppearancePalette.border

    textPrimary = Color(0xFFF8F7F7),     // Matches BloodRedAppearancePalette.primaryText
    textSecondary = Color(0xFFB7AAAC),   // Matches BloodRedAppearancePalette.secondaryText
    textMuted = Color(0xFF817477),       // Matches BloodRedAppearancePalette.mutedText
    textDisabled = Color(0xFF4E3D3F),    // Disabled dark crimson grey

    accent = BloodRedPrimary,            // Reuse existing BloodRedPrimary (0xFFB11226)
    accentHover = Color(0xFFD61830),     // Brighter red active highlight
    accentMuted = Color(0x33B11226),     // Translucent red highlight
    onAccent = Color(0xFFFFFFFF),        // White text/icons on red accent

    selectedSurface = Color(0xFF261215), // Subtle dark-red selected row
    playingSurface = Color(0xFF1A0B0E),  // Restrained playing row

    warning = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    success = Color(0xFF10B981),

    cornerSmall = 2.dp,
    cornerMedium = 4.dp,
    cornerLarge = 6.dp,

    libraryCompactRowHeight = 38.dp,
    libraryComfortableRowHeight = 48.dp,

    controlSmallHeight = 28.dp,
    controlNormalHeight = 38.dp
)

/**
 * Classic Pro Charcoal Theme Spec (Pioneer rekordbox graphite & cool blue).
 * Retained for backward compatibility and test suite verification.
 */
val ProCharcoalThemeSpec = SoundSyncThemeSpec(
    id = "pro",
    name = "Pro",
    isPro = true,
    is3BandColoring = true,

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
    onAccent = Color.White,

    selectedSurface = Color(0xFF182338), // Dark blue-charcoal selection row
    playingSurface = Color(0xFF131D2E),  // Restrained playing row background

    warning = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    success = Color(0xFF10B981),

    cornerSmall = 2.dp,
    cornerMedium = 4.dp,
    cornerLarge = 6.dp,

    libraryCompactRowHeight = 38.dp,
    libraryComfortableRowHeight = 48.dp,

    controlSmallHeight = 28.dp,
    controlNormalHeight = 38.dp
)

val ProThemeSpec: SoundSyncThemeSpec get() = ProCharcoalThemeSpec

fun getProThemeSpec(variant: ProDarkVariant): SoundSyncThemeSpec = when (variant) {
    ProDarkVariant.BLACK_WHITE -> ProBlackWhiteThemeSpec
    ProDarkVariant.BLACK_RED -> ProBlackRedThemeSpec
}

val LocalSoundSyncTheme = staticCompositionLocalOf { DefaultThemeSpec }
val LocalLibraryDensity = compositionLocalOf { ProLibraryDensity.COMPACT }
val LocalProDarkVariant = compositionLocalOf { ProDarkVariant.BLACK_WHITE }

