package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Appearance tokens used by the app shell. Signal colours (deck, waveform and
 * spectrogram colours) intentionally remain constant because they carry meaning.
 */
enum class ThemeMode {
    CURRENT,
    DARK;

    companion object {
        fun fromStoredValue(value: String?): ThemeMode =
            if (value == DARK.name) DARK else CURRENT
    }
}

private data class AppearancePalette(
    val background: Color,
    val surface: Color,
    val card: Color,
    val elevated: Color,
    val border: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color
)

private val CurrentAppearancePalette = AppearancePalette(
    background = Color(0xFF090B10),
    surface = Color(0xFF121620),
    card = Color(0xFF19202E),
    elevated = Color(0xFF222B3D),
    border = Color(0xFF2D384E),
    primaryText = Color(0xFFF1F5F9),
    secondaryText = Color(0xFF94A3B8),
    mutedText = Color(0xFF64748B)
)

private val BloodRedAppearancePalette = AppearancePalette(
    background = Color(0xFF050505),
    surface = Color(0xFF101010),
    card = Color(0xFF171313),
    elevated = Color(0xFF241516),
    border = Color(0xFF472024),
    primaryText = Color(0xFFF8F7F7),
    secondaryText = Color(0xFFB7AAAC),
    mutedText = Color(0xFF817477)
)

@Volatile
private var activeAppearancePalette = CurrentAppearancePalette

internal fun setActiveAppearancePalette(mode: ThemeMode) {
    activeAppearancePalette = if (mode == ThemeMode.DARK) {
        BloodRedAppearancePalette
    } else {
        CurrentAppearancePalette
    }
}

// DJ Pro Obsidian & Neon Palette
val DjObsidian: Color get() = activeAppearancePalette.background
val DjSurfaceDark: Color get() = activeAppearancePalette.surface
val DjSurfaceCard: Color get() = activeAppearancePalette.card
val DjSurfaceElevated: Color get() = activeAppearancePalette.elevated
val DjSurfaceBorder: Color get() = activeAppearancePalette.border

// Theme-only primary accent. Functional signal colours below remain unchanged.
val BloodRedPrimary = Color(0xFFB11226)

// Deck A - Electric Cyan
val DeckACyan = Color(0xFF00F0FF)
val DeckACyanDark = Color(0xFF0099A8)
val DeckACyanGlow = Color(0x3300F0FF)

// Deck B - Neon Hot Pink / Orange
val DeckBPink = Color(0xFFFF2A6D)
val DeckBPinkDark = Color(0xFFB8184C)
val DeckBPinkGlow = Color(0x33FF2A6D)

// Accents & Quality Indicators
val NeonGreen = Color(0xFF05FFA1)      // Lossless / True 320k / In Sync
val NeonAmber = Color(0xFFFFB800)      // Warning / 192k Cutoff / Moderate
val NeonRed = Color(0xFFFF3333)        // Fake 320k / Transcode / Duplicate
val NeonPurple = Color(0xFF9D4EDD)     // Cloud / AI Tagged
val SpotifyGreen = Color(0xFF1DB954)   // Spotify Brand
val SoundCloudOrange = Color(0xFFFF5500) // SoundCloud Brand

val TextPrimary: Color get() = activeAppearancePalette.primaryText
val TextSecondary: Color get() = activeAppearancePalette.secondaryText
val TextMuted: Color get() = activeAppearancePalette.mutedText

// Spectrogram Spectral Heatmap Colors
val SpectroFloor = Color(0xFF050510)
val SpectroLow = Color(0xFF1A1054)
val SpectroMidLow = Color(0xFF5E127E)
val SpectroMid = Color(0xFFB81B6C)
val SpectroMidHigh = Color(0xFFF15A24)
val SpectroHigh = Color(0xFFFEE825)
val SpectroPeak = Color(0xFFFFFFFF)
