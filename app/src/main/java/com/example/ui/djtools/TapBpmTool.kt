package com.example.ui.djtools

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

class TapBpmCalculator {
    private val tapTimestamps = mutableListOf<Long>()
    var currentBpm: Double = 0.0
        private set
    val tapCount: Int
        get() = tapTimestamps.size

    fun reset() {
        tapTimestamps.clear()
        currentBpm = 0.0
    }

    fun recordTap(now: Long = System.currentTimeMillis()): Double {
        if (tapTimestamps.isNotEmpty() && (now - tapTimestamps.last()) > 2500L) {
            tapTimestamps.clear()
        }

        tapTimestamps.add(now)
        while (tapTimestamps.size > 12) {
            tapTimestamps.removeAt(0)
        }

        if (tapTimestamps.size >= 2) {
            val intervals = mutableListOf<Long>()
            for (i in 1 until tapTimestamps.size) {
                val dt = tapTimestamps[i] - tapTimestamps[i - 1]
                if (dt in 180..2500) {
                    intervals.add(dt)
                }
            }

            if (intervals.isNotEmpty()) {
                val validIntervals = if (intervals.size >= 3) {
                    val sorted = intervals.sorted()
                    val median = sorted[sorted.size / 2]
                    intervals.filter { Math.abs(it - median) <= median * 0.45 }
                } else {
                    intervals
                }

                if (validIntervals.isNotEmpty()) {
                    val avgIntervalMs = validIntervals.average()
                    currentBpm = 60000.0 / avgIntervalMs
                }
            }
        } else {
            currentBpm = 0.0
        }
        return currentBpm
    }
}

/**
 * Functional DJ Tap BPM calculator.
 * Calculates tempo based on moving-window intervals, rejects statistical outliers,
 * resets automatically on pause > 2500ms, and provides manual reset.
 */
@Composable
fun TapBpmTool(
    onSyncBpm: ((Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val calculator = remember { TapBpmCalculator() }
    var calculatedBpm by remember { mutableDoubleStateOf(0.0) }
    var tapCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val buttonScale = remember { Animatable(1f) }

    fun reset() {
        calculator.reset()
        calculatedBpm = 0.0
        tapCount = 0
    }

    fun handleTap() {
        scope.launch {
            buttonScale.snapTo(0.92f)
            buttonScale.animateTo(1f, animationSpec = tween(150, easing = FastOutSlowInEasing))
        }

        calculatedBpm = calculator.recordTap()
        tapCount = calculator.tapCount
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TAP BPM COUNTER",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Real-time rhythmic interval averaging & outlier rejection",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                OutlinedButton(
                    onClick = { reset() },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(12.dp), tint = TextSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", fontSize = 10.sp, color = TextSecondary)
                }
            }

            // BPM Readout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DjObsidian, RoundedCornerShape(10.dp))
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (calculatedBpm > 0.0) String.format(Locale.US, "%.1f", calculatedBpm) else "--.-",
                    color = if (calculatedBpm > 0.0) DeckACyan else TextMuted,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (tapCount >= 2) "$tapCount taps averaged" else "Tap 2+ times to calculate",
                    color = if (tapCount >= 2) NeonGreen else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Big Interactive Tap Button
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(buttonScale.value)
                    .clip(CircleShape)
                    .background(if (tapCount > 0) DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated)
                    .border(2.dp, if (tapCount > 0) DeckACyan else DjSurfaceBorder, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { handleTap() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = "Tap",
                        tint = if (tapCount > 0) DeckACyan else TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "TAP HERE",
                        color = if (tapCount > 0) DeckACyan else TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Sync with metronome button if callback provided
            if (onSyncBpm != null && calculatedBpm > 0.0) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSyncBpm(calculatedBpm) },
                    color = DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Set Metronome to ${String.format(Locale.US, "%.0f", calculatedBpm)} BPM",
                            color = DeckACyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
