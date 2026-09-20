package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.example.model.WaveformStyle
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.DjSurfaceElevated
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSettingsSheet(
    crossfadeSeconds: Int,
    onCrossfadeSecondsChange: (Int) -> Unit,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onSetWaveformStyle: (WaveformStyle) -> Unit = {},
    // EQ state
    eqEnabled: Boolean = true,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    onSetEqEnabled: (Boolean) -> Unit = {},
    onSetEqLow: (Float) -> Unit = {},
    onSetEqMid: (Float) -> Unit = {},
    onSetEqHigh: (Float) -> Unit = {},
    eq6Bands: List<Float> = emptyList(),
    onSetEqBandGain: (Int, Float) -> Unit = { _, _ -> },
    onResetEqBands: () -> Unit = {},
    onApplyEqPreset: (String) -> Unit = {},
    // Haas state
    haasEnabled: Boolean = false,
    haasAmount: Float = 0.5f,
    haasDelayMs: Float = 5f,
    haasBassProtect: Boolean = true,
    haasPreset: com.example.audio.HaasSurroundEffect.HaasPreset = com.example.audio.HaasSurroundEffect.HaasPreset.WIDE,
    haasMode: com.example.audio.HaasSurroundEffect.ProcessingMode = com.example.audio.HaasSurroundEffect.ProcessingMode.SIDE_ONLY,
    haasStereoWidth: Float = com.example.audio.HaasSurroundEffect.DEFAULT_STEREO_WIDTH,
    haasCrossfeed: Float = com.example.audio.HaasSurroundEffect.DEFAULT_CROSSFEED,
    haasLowCutoffHz: Float = com.example.audio.HaasSurroundEffect.DEFAULT_LOW_CUTOFF_HZ,
    haasHighCutoffHz: Float = com.example.audio.HaasSurroundEffect.DEFAULT_HIGH_CUTOFF_HZ,
    haasBalance: Float = com.example.audio.HaasSurroundEffect.DEFAULT_BALANCE,
    haasOutputCompDb: Float = com.example.audio.HaasSurroundEffect.DEFAULT_OUTPUT_COMP_DB,
    onSetHaasEnabled: (Boolean) -> Unit = {},
    onSetHaasAmount: (Float) -> Unit = {},
    onSetHaasDelayMs: (Float) -> Unit = {},
    onSetHaasBassProtect: (Boolean) -> Unit = {},
    onSetHaasPreset: (com.example.audio.HaasSurroundEffect.HaasPreset) -> Unit = {},
    onSetHaasMode: (com.example.audio.HaasSurroundEffect.ProcessingMode) -> Unit = {},
    onSetHaasStereoWidth: (Float) -> Unit = {},
    onSetHaasCrossfeed: (Float) -> Unit = {},
    onSetHaasLowCutoffHz: (Float) -> Unit = {},
    onSetHaasHighCutoffHz: (Float) -> Unit = {},
    onSetHaasBalance: (Float) -> Unit = {},
    onSetHaasOutputCompDb: (Float) -> Unit = {},
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DjSurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = DeckACyan)
                    Text("Now Playing Settings", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Now Playing Settings", tint = TextSecondary)
                }
            }

            // ── Audio Effects Panel (6-Band EQ & Haas 3D Surround) ───
            AudioEffectsPanel(
                eqEnabled = eqEnabled,
                eqLow = eqLow,
                eqMid = eqMid,
                eqHigh = eqHigh,
                onSetEqEnabled = onSetEqEnabled,
                onSetEqLow = onSetEqLow,
                onSetEqMid = onSetEqMid,
                onSetEqHigh = onSetEqHigh,
                eq6Bands = eq6Bands,
                onSetEqBandGain = onSetEqBandGain,
                onResetEqBands = onResetEqBands,
                onApplyEqPreset = onApplyEqPreset,
                haasEnabled = haasEnabled,
                haasAmount = haasAmount,
                haasDelayMs = haasDelayMs,
                haasBassProtect = haasBassProtect,
                haasPreset = haasPreset,
                haasMode = haasMode,
                haasStereoWidth = haasStereoWidth,
                haasCrossfeed = haasCrossfeed,
                haasLowCutoffHz = haasLowCutoffHz,
                haasHighCutoffHz = haasHighCutoffHz,
                haasBalance = haasBalance,
                haasOutputCompDb = haasOutputCompDb,
                onSetHaasEnabled = onSetHaasEnabled,
                onSetHaasAmount = onSetHaasAmount,
                onSetHaasDelayMs = onSetHaasDelayMs,
                onSetHaasBassProtect = onSetHaasBassProtect,
                onSetHaasPreset = onSetHaasPreset,
                onSetHaasMode = onSetHaasMode,
                onSetHaasStereoWidth = onSetHaasStereoWidth,
                onSetHaasCrossfeed = onSetHaasCrossfeed,
                onSetHaasLowCutoffHz = onSetHaasLowCutoffHz,
                onSetHaasHighCutoffHz = onSetHaasHighCutoffHz,
                onSetHaasBalance = onSetHaasBalance,
                onSetHaasOutputCompDb = onSetHaasOutputCompDb,
                modifier = Modifier.fillMaxWidth()
            )

            // ── Playback Transitions (Crossfade) ──────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DjObsidian,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Crossfade", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Overlap the end of one track with the next", color = TextMuted, fontSize = 11.sp)
                        }
                        Text(
                            text = if (crossfadeSeconds == 0) "OFF" else String.format(Locale.US, "%ds", crossfadeSeconds),
                            color = DeckACyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Slider(
                        value = crossfadeSeconds.toFloat(),
                        onValueChange = { onCrossfadeSecondsChange(it.toInt().coerceIn(0, 12)) },
                        valueRange = 0f..12f,
                        steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = DeckACyan,
                            activeTrackColor = DeckACyan,
                            inactiveTrackColor = DjSurfaceCard
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("OFF", color = TextMuted, fontSize = 10.sp)
                        Text("12 seconds", color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }

            // ── Waveform Display Style ──────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("now_playing_waveform_style_section"),
                color = DjObsidian,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text("Waveform Display Style", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Choose between Detailed 60fps transients or classic Retro chunky waveform", color = TextMuted, fontSize = 11.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { onSetWaveformStyle(WaveformStyle.DETAILED) }
                                .testTag("sheet_waveform_detailed"),
                            color = if (waveformStyle == WaveformStyle.DETAILED) DeckACyan.copy(alpha = 0.16f) else DjSurfaceElevated,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (waveformStyle == WaveformStyle.DETAILED) DeckACyan else DjSurfaceBorder
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Detailed",
                                    color = if (waveformStyle == WaveformStyle.DETAILED) DeckACyan else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text("High resolution transients & dynamic range", color = TextSecondary, fontSize = 9.5.sp)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { onSetWaveformStyle(WaveformStyle.RETRO) }
                                .testTag("sheet_waveform_retro"),
                            color = if (waveformStyle == WaveformStyle.RETRO) NeonAmber.copy(alpha = 0.16f) else DjSurfaceElevated,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (waveformStyle == WaveformStyle.RETRO) NeonAmber else DjSurfaceBorder
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Retro",
                                    color = if (waveformStyle == WaveformStyle.RETRO) NeonAmber else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text("Classic chunky nostalgic pixel styling", color = TextSecondary, fontSize = 9.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
