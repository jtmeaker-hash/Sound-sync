package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.DjAudioEngine
import com.example.audio.HaasSurroundEffect
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

private data class EqBandInfo(
    val index: Int,
    val frequencyLabel: String,
    val role: String,
    val color: Color
)

private val BAND_INFOS = listOf(
    EqBandInfo(0, "60 Hz", "SUB-BASS", DeckACyan),
    EqBandInfo(1, "150 Hz", "BASS", Color(0xFF00E5FF)),
    EqBandInfo(2, "400 Hz", "LOW-MID", NeonAmber),
    EqBandInfo(3, "1 kHz", "MID", DeckBPink),
    EqBandInfo(4, "2.4 kHz", "UPPER-MID", Color(0xFFFF5252)),
    EqBandInfo(5, "8 kHz", "TREBLE", NeonGreen)
)

/**
 * Audio Effects panel for the Now Playing screen and Settings.
 * Features a true 6-band Equalizer (~60 Hz, 150 Hz, 400 Hz, 1 kHz, 2.4 kHz, 8 kHz)
 * with independent real-time dB gain sliders, presets, neutral indicator, and a
 * genuine Haas Surround 3D widening effect with mono bass protection.
 */
@Composable
fun AudioEffectsPanel(
    // EQ state
    eqEnabled: Boolean = true,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    onSetEqEnabled: (Boolean) -> Unit = {},
    onSetEqLow: (Float) -> Unit = {},
    onSetEqMid: (Float) -> Unit = {},
    onSetEqHigh: (Float) -> Unit = {},
    // 6-Band EQ
    eq6Bands: List<Float> = emptyList(),
    onSetEqBandGain: (Int, Float) -> Unit = { _, _ -> },
    onResetEqBands: () -> Unit = {},
    onApplyEqPreset: (String) -> Unit = {},
    // Haas state
    haasEnabled: Boolean = false,
    haasAmount: Float = 0.5f,
    haasDelayMs: Float = 16f,
    haasBassProtect: Boolean = true,
    onSetHaasEnabled: (Boolean) -> Unit = {},
    onSetHaasAmount: (Float) -> Unit = {},
    onSetHaasDelayMs: (Float) -> Unit = {},
    onSetHaasBassProtect: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Resolve current 6-band gains
    val currentBands = if (eq6Bands.size == 6) {
        eq6Bands
    } else {
        // Fallback: derive 6 bands from 3-band linear gains if 6-band list not provided
        val lowDb = if (eqLow <= 0.001f) -12f else (20f * log10(eqLow)).coerceIn(-12f, 12f)
        val midDb = if (eqMid <= 0.001f) -12f else (20f * log10(eqMid)).coerceIn(-12f, 12f)
        val highDb = if (eqHigh <= 0.001f) -12f else (20f * log10(eqHigh)).coerceIn(-12f, 12f)
        listOf(lowDb, lowDb, midDb, midDb, highDb, highDb)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DjSurfaceDark)
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "AUDIO EFFECTS & DSP",
                color = NeonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // ── 6-Band Equalizer ──────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = DjObsidian,
            border = BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // EQ Header & Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "6-BAND EQUALIZER",
                            color = if (eqEnabled) NeonGreen else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (eqEnabled) "(-12 to +12 dB)" else "(BYPASSED)",
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = onSetEqEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = NeonGreen,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = DjSurfaceCard
                        ),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Presets & Reset Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reset Button
                    Surface(
                        color = DjSurfaceCard,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, DjSurfaceBorder),
                        modifier = Modifier.clickable(enabled = eqEnabled) { onResetEqBands() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Reset EQ",
                                tint = if (eqEnabled) DeckACyan else TextMuted,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "RESET FLAT",
                                color = if (eqEnabled) DeckACyan else TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Preset Chips
                    val presets = listOf(
                        "Flat",
                        "Bass Boost",
                        "Bass Reduction",
                        "Vocal Clarity",
                        "Electronic",
                        "Rock",
                        "Acoustic",
                        "Treble Boost"
                    )
                    presets.forEach { presetName ->
                        val presetGains = DjAudioEngine.EQ_6_PRESETS[presetName]
                        val isCurrent = presetGains != null && currentBands.zip(presetGains).all { (a, b) -> abs(a - b) < 0.2f }
                        Surface(
                            color = if (isCurrent && eqEnabled) NeonGreen.copy(alpha = 0.2f) else DjSurfaceCard,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(
                                0.5.dp,
                                if (isCurrent && eqEnabled) NeonGreen else DjSurfaceBorder
                            ),
                            modifier = Modifier.clickable(enabled = eqEnabled) {
                                onApplyEqPreset(presetName)
                            }
                        ) {
                            Text(
                                text = presetName,
                                color = if (isCurrent && eqEnabled) NeonGreen else (if (eqEnabled) TextSecondary else TextMuted),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // 6 Independent Frequency Sliders
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BAND_INFOS.forEach { info ->
                        val gainDb = currentBands.getOrElse(info.index) { 0f }
                        Eq6BandSliderRow(
                            info = info,
                            gainDb = gainDb,
                            onGainChange = { newGain ->
                                onSetEqBandGain(info.index, newGain)
                                // Also update legacy 3-band callbacks if needed
                                when (info.index) {
                                    0, 1 -> onSetEqLow(Math.pow(10.0, (newGain / 20.0).toDouble()).toFloat().coerceIn(0f, 2f))
                                    2, 3 -> onSetEqMid(Math.pow(10.0, (newGain / 20.0).toDouble()).toFloat().coerceIn(0f, 2f))
                                    4, 5 -> onSetEqHigh(Math.pow(10.0, (newGain / 20.0).toDouble()).toFloat().coerceIn(0f, 2f))
                                }
                            },
                            enabled = eqEnabled
                        )
                    }
                }
            }
        }

        // ── Haas Surround Effect ───────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = DjObsidian,
            border = BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Haas header with toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SurroundSound,
                            contentDescription = null,
                            tint = if (haasEnabled) DeckBPink else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = "HAAS SURROUND (PRECEDENCE EFFECT)",
                                color = if (haasEnabled) DeckBPink else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (haasEnabled) "Inter-channel stereo delay" else "Disabled (Zero CPU Overhead)",
                                color = TextMuted,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Switch(
                        checked = haasEnabled,
                        onCheckedChange = onSetHaasEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = DeckBPink,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = DjSurfaceCard
                        ),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Haas controls (visible when enabled)
                AnimatedVisibility(
                    visible = haasEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Haas Width Presets
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val haasPresets = listOf(
                                Triple("Subtle", 8f, 0.45f),
                                Triple("Wide", 16f, 0.70f),
                                Triple("Very Wide", 25f, 1.0f)
                            )
                            haasPresets.forEach { (name, delay, amount) ->
                                val isSelected = abs(haasDelayMs - delay) < 1.0f && abs(haasAmount - amount) < 0.15f
                                Surface(
                                    color = if (isSelected) DeckBPink.copy(alpha = 0.25f) else DjSurfaceCard,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(
                                        0.5.dp,
                                        if (isSelected) DeckBPink else DjSurfaceBorder
                                    ),
                                    modifier = Modifier.clickable {
                                        onSetHaasDelayMs(delay)
                                        onSetHaasAmount(amount)
                                    }
                                ) {
                                    Text(
                                        text = "$name (${delay.toInt()}ms)",
                                        color = if (isSelected) DeckBPink else TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // Delay slider (0 to 30 ms)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.width(60.dp)) {
                                Text(
                                    text = "DELAY",
                                    color = DeckBPink,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "0–30 ms",
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Slider(
                                value = haasDelayMs,
                                onValueChange = onSetHaasDelayMs,
                                valueRange = HaasSurroundEffect.MIN_DELAY_MS..HaasSurroundEffect.MAX_DELAY_MS,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = DeckBPink,
                                    activeTrackColor = DeckBPink,
                                    inactiveTrackColor = DjSurfaceCard
                                )
                            )

                            Surface(
                                color = DeckBPink.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, DeckBPink.copy(alpha = 0.4f)),
                                modifier = Modifier.width(58.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.1f ms", haasDelayMs),
                                    color = DeckBPink,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }

                        // Width/Amount slider (0% to 100%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.width(60.dp)) {
                                Text(
                                    text = "WIDTH",
                                    color = DeckBPink,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Mix level",
                                    color = TextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Slider(
                                value = haasAmount,
                                onValueChange = onSetHaasAmount,
                                valueRange = HaasSurroundEffect.MIN_AMOUNT..HaasSurroundEffect.MAX_AMOUNT,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = DeckBPink,
                                    activeTrackColor = DeckBPink,
                                    inactiveTrackColor = DjSurfaceCard
                                )
                            )

                            Surface(
                                color = DeckBPink.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.5.dp, DeckBPink.copy(alpha = 0.4f)),
                                modifier = Modifier.width(58.dp)
                            ) {
                                Text(
                                    text = "${(haasAmount * 100).toInt()}%",
                                    color = DeckBPink,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }

                        // Mono Bass Protect toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "MONO BASS PROTECT (< 140 Hz)",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Keeps punchy sub-bass 100% in phase to prevent cancellation",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = haasBassProtect,
                                onCheckedChange = onSetHaasBassProtect,
                                modifier = Modifier.size(width = 40.dp, height = 24.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DjObsidian,
                                    checkedTrackColor = DeckBPink
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single 6-band EQ slider row with frequency label, role, slider (-12 dB to +12 dB), and dB readout.
 */
@Composable
private fun Eq6BandSliderRow(
    info: EqBandInfo,
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    enabled: Boolean
) {
    val isNeutral = abs(gainDb) < 0.1f
    val displayGain = when {
        isNeutral -> "0.0 dB"
        gainDb > 0f -> String.format(Locale.US, "+%.1f dB", gainDb)
        else -> String.format(Locale.US, "%.1f dB", gainDb)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Frequency label and role
        Column(modifier = Modifier.width(62.dp)) {
            Text(
                text = info.frequencyLabel,
                color = if (enabled) info.color else TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = info.role,
                color = TextMuted,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // -12 dB to +12 dB Slider
        Slider(
            value = gainDb,
            onValueChange = onGainChange,
            valueRange = -12f..12f,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = info.color,
                activeTrackColor = info.color,
                inactiveTrackColor = DjSurfaceCard,
                disabledThumbColor = info.color.copy(alpha = 0.3f),
                disabledActiveTrackColor = info.color.copy(alpha = 0.3f)
            )
        )

        // Readout container with neutral indication
        Surface(
            color = if (isNeutral || !enabled) DjSurfaceCard else info.color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(
                0.5.dp,
                if (isNeutral || !enabled) DjSurfaceBorder else info.color.copy(alpha = 0.4f)
            ),
            modifier = Modifier.width(58.dp)
        ) {
            Text(
                text = displayGain,
                color = if (!enabled) TextMuted else (if (isNeutral) TextSecondary else info.color),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
