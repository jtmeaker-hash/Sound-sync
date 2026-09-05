package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WaveformStyle
import com.example.ui.theme.BloodRedPrimary
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.ProDarkVariant
import com.example.ui.theme.ProLibraryDensity
import com.example.ui.theme.SoundSyncTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThemeMode

@Composable
fun AppearanceSettingsScreen(
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    proDarkVariant: ProDarkVariant = ProDarkVariant.BLACK_WHITE,
    onSetProDarkVariant: (ProDarkVariant) -> Unit = {},
    libraryDensity: ProLibraryDensity = ProLibraryDensity.COMPACT,
    onSetLibraryDensity: (ProLibraryDensity) -> Unit = {},
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onSetWaveformStyle: (WaveformStyle) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = SoundSyncTheme.current
    val isDefault = (themeMode == ThemeMode.DEFAULT || themeMode == ThemeMode.CURRENT)
    val isPro = (themeMode == ThemeMode.PRO)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Theme Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("appearance_section_card"),
            colors = CardDefaults.cardColors(containerColor = theme.surface),
            shape = RoundedCornerShape(theme.cornerMedium),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("SoundSync Appearance & Themes", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Switch between the classic SoundSync UI and the professional desktop audio workstation UI language.",
                    color = theme.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppearanceOptionTile(
                        title = "Default",
                        description = "Classic SoundSync obsidian dark shell with deck cyan accents",
                        selected = isDefault,
                        accent = DeckACyan,
                        onClick = { onSetThemeMode(ThemeMode.DEFAULT) },
                        modifier = Modifier.weight(1f),
                        testTag = "appearance_option_default"
                    )

                    AppearanceOptionTile(
                        title = "Pro",
                        description = "Desktop DJ workstation UI: high-density controls, technical typography, and customizable dark variants",
                        selected = isPro,
                        accent = if (proDarkVariant == ProDarkVariant.BLACK_RED) BloodRedPrimary else Color.White,
                        onClick = { onSetThemeMode(ThemeMode.PRO) },
                        modifier = Modifier.weight(1f),
                        testTag = "appearance_option_pro"
                    )
                }
            }
        }

        // Pro Dark Colour Variant Card (Shown when Pro Theme is selected)
        if (isPro) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pro_dark_variant_section_card"),
                colors = CardDefaults.cardColors(containerColor = theme.surface),
                shape = RoundedCornerShape(theme.cornerMedium),
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Pro Dark Colour Variant",
                        color = theme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        "Select the dark appearance variant for the Pro workstation. All variants retain the professional layout, controls, density, waveforms, and DJ tool styling.",
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppearanceOptionTile(
                            title = "Black & White",
                            description = "Monochrome technical dark: pitch-black background with crisp white typography and neutral-grey highlights",
                            selected = proDarkVariant == ProDarkVariant.BLACK_WHITE,
                            accent = Color.White,
                            onClick = { onSetProDarkVariant(ProDarkVariant.BLACK_WHITE) },
                            modifier = Modifier.weight(1f),
                            testTag = "pro_variant_option_black_white"
                        )

                        AppearanceOptionTile(
                            title = "Black & Red",
                            description = "SoundSync performance dark: deep black surfaces with vivid crimson red accents and playback indicators",
                            selected = proDarkVariant == ProDarkVariant.BLACK_RED,
                            accent = BloodRedPrimary,
                            onClick = { onSetProDarkVariant(ProDarkVariant.BLACK_RED) },
                            modifier = Modifier.weight(1f),
                            testTag = "pro_variant_option_black_red"
                        )
                    }
                }
            }
        }

        // Library Density Card (Shown when Pro Theme is selected)
        if (isPro) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("library_density_section_card"),
                colors = CardDefaults.cardColors(containerColor = theme.surface),
                shape = RoundedCornerShape(theme.cornerMedium),
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Pro Library Density", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Configure the row density of the Pro sortable music browser. Compact fits maximum tracks on screen, while Comfortable provides relaxed touch spacing.",
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppearanceOptionTile(
                            title = "Compact",
                            description = "Desktop-style dense rows, maximum visible tracks, small thumbnails, tight padding",
                            selected = libraryDensity == ProLibraryDensity.COMPACT,
                            accent = theme.accent,
                            onClick = { onSetLibraryDensity(ProLibraryDensity.COMPACT) },
                            modifier = Modifier.weight(1f),
                            testTag = "density_option_compact"
                        )

                        AppearanceOptionTile(
                            title = "Comfortable",
                            description = "Slightly taller rows with easier touch targets, still denser than Default theme",
                            selected = libraryDensity == ProLibraryDensity.COMFORTABLE,
                            accent = theme.accent,
                            onClick = { onSetLibraryDensity(ProLibraryDensity.COMFORTABLE) },
                            modifier = Modifier.weight(1f),
                            testTag = "density_option_comfortable"
                        )
                    }
                }
            }
        }

        // Waveform Display Style Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("waveform_style_section_card"),
            colors = CardDefaults.cardColors(containerColor = theme.surface),
            shape = RoundedCornerShape(theme.cornerMedium),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Waveform Display Style", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Select how waveforms are rendered in Now Playing and DJ views. Both modes operate with real-time continuous scrolling and strict audio synchronisation.",
                    color = theme.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppearanceOptionTile(
                        title = "Detailed Waveform",
                        description = "High-resolution multi-band peaks with crisp transient needles & dynamic nuance",
                        selected = waveformStyle == WaveformStyle.DETAILED,
                        accent = if (isPro) theme.accent else DeckACyan,
                        onClick = { onSetWaveformStyle(WaveformStyle.DETAILED) },
                        modifier = Modifier.weight(1f),
                        testTag = "waveform_style_option_detailed"
                    )

                    AppearanceOptionTile(
                        title = "Retro Waveform",
                        description = "Classic chunky pixel-style waveform with nostalgic visual aesthetic",
                        selected = waveformStyle == WaveformStyle.RETRO,
                        accent = NeonAmber,
                        onClick = { onSetWaveformStyle(WaveformStyle.RETRO) },
                        modifier = Modifier.weight(1f),
                        testTag = "waveform_style_option_retro"
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceOptionTile(
    title: String,
    description: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val theme = SoundSyncTheme.current

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(theme.cornerSmall))
            .clickable(onClick = onClick)
            .testTag(testTag ?: "appearance_option_${title.lowercase().replace(" ", "_")}"),
        color = if (selected) accent.copy(alpha = 0.16f) else theme.surfaceRaised,
        shape = RoundedCornerShape(theme.cornerSmall),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) accent else theme.divider
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(if (selected) accent else Color.Transparent, CircleShape)
                        .border(1.5.dp, accent, CircleShape)
                )
                Text(
                    title,
                    color = if (selected) theme.textPrimary else theme.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
            Text(
                description,
                color = theme.textSecondary,
                fontSize = 9.5.sp,
                lineHeight = 13.sp
            )
        }
    }
}
