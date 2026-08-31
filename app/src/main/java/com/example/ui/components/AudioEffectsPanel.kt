package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.HaasSurroundEffect
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Audio Effects panel for the Now Playing screen.
 * Contains a 3-band EQ (Low/Mid/High) and Haas Surround effect controls.
 */
@Composable
fun AudioEffectsPanel(
    // EQ state
    eqLow: Float,
    eqMid: Float,
    eqHigh: Float,
    onSetEqLow: (Float) -> Unit,
    onSetEqMid: (Float) -> Unit,
    onSetEqHigh: (Float) -> Unit,
    // Haas state
    haasEnabled: Boolean,
    haasAmount: Float,
    haasDelayMs: Float,
    onSetHaasEnabled: (Boolean) -> Unit,
    onSetHaasAmount: (Float) -> Unit,
    onSetHaasDelayMs: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DjSurfaceDark)
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                text = "AUDIO EFFECTS",
                color = NeonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // ── 3-Band EQ ──────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = DjObsidian,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "EQ",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                // LOW band
                EqSliderRow(
                    label = "LOW",
                    labelColor = DeckACyan,
                    value = eqLow,
                    onValueChange = onSetEqLow,
                    valueRange = 0f..2f,
                    displayValue = "${String.format(java.util.Locale.US, "%.1f", eqLow)}x"
                )

                // MID band
                EqSliderRow(
                    label = "MID",
                    labelColor = DeckBPink,
                    value = eqMid,
                    onValueChange = onSetEqMid,
                    valueRange = 0f..2f,
                    displayValue = "${String.format(java.util.Locale.US, "%.1f", eqMid)}x"
                )

                // HIGH band
                EqSliderRow(
                    label = "HIGH",
                    labelColor = NeonGreen,
                    value = eqHigh,
                    onValueChange = onSetEqHigh,
                    valueRange = 0f..2f,
                    displayValue = "${String.format(java.util.Locale.US, "%.1f", eqHigh)}x"
                )
            }
        }

        // ── Haas Surround Effect ───────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = DjObsidian,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "HAAS SURROUND",
                            color = if (haasEnabled) DeckBPink else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Amount slider
                        EqSliderRow(
                            label = "WIDTH",
                            labelColor = DeckBPink,
                            value = haasAmount,
                            onValueChange = onSetHaasAmount,
                            valueRange = HaasSurroundEffect.MIN_AMOUNT..HaasSurroundEffect.MAX_AMOUNT,
                            displayValue = "${(haasAmount * 100).toInt()}%"
                        )

                        // Delay slider
                        EqSliderRow(
                            label = "DELAY",
                            labelColor = DeckBPink,
                            value = haasDelayMs,
                            onValueChange = onSetHaasDelayMs,
                            valueRange = HaasSurroundEffect.MIN_DELAY_MS..HaasSurroundEffect.MAX_DELAY_MS,
                            displayValue = "${String.format(java.util.Locale.US, "%.1f", haasDelayMs)}ms"
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single EQ slider row with label, slider, and value display.
 */
@Composable
private fun EqSliderRow(
    label: String,
    labelColor: Color,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp)
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = labelColor,
                activeTrackColor = labelColor,
                inactiveTrackColor = DjSurfaceCard,
                disabledThumbColor = labelColor.copy(alpha = 0.5f)
            )
        )

        Text(
            text = displayValue,
            color = TextPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp)
        )
    }
}
