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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThemeMode

@Composable
fun AppearanceSettingsScreen(
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onSetWaveformStyle: (WaveformStyle) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Theme Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("appearance_section_card"),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("SoundSync Appearance & Themes", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Choose the app shell colour scheme. Signal colors for waveforms, spectrograms, EQ bands, and Haas effects remain preserved and constant.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppearanceOptionTile(
                        title = "Default Cyan Theme",
                        description = "Obsidian with deck cyan accents",
                        selected = themeMode == ThemeMode.CURRENT,
                        accent = DeckACyan,
                        onClick = { onSetThemeMode(ThemeMode.CURRENT) },
                        modifier = Modifier.weight(1f)
                    )

                    AppearanceOptionTile(
                        title = "Blood-Red Dark Theme",
                        description = "Pitch black with crimson red accents",
                        selected = themeMode == ThemeMode.DARK,
                        accent = BloodRedPrimary,
                        onClick = { onSetThemeMode(ThemeMode.DARK) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Waveform Display Style Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("waveform_style_section_card"),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Waveform Display Style", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Select how waveforms are rendered in Now Playing and DJ views. Both modes operate with real-time 60fps scrolling and strict audio synchronisation.",
                    color = TextSecondary,
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
                        accent = DeckACyan,
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
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag ?: "appearance_option_${title.lowercase().replace(" ", "_")}"),
        color = if (selected) accent.copy(alpha = 0.16f) else DjSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (selected) accent else DjSurfaceBorder)
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
                    color = if (selected) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
            Text(
                description,
                color = TextSecondary,
                fontSize = 9.5.sp
            )
        }
    }
}
