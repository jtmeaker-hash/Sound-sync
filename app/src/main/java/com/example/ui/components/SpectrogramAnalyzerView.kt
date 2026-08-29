package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.SpectrogramAnalysis
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SpectroFloor
import com.example.ui.theme.SpectroHigh
import com.example.ui.theme.SpectroLow
import com.example.ui.theme.SpectroMid
import com.example.ui.theme.SpectroMidHigh
import com.example.ui.theme.SpectroMidLow
import com.example.ui.theme.SpectroPeak
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun SpectrogramAnalyzerView(
    analyzedTrack: Track?,
    spectrogramData: SpectrogramAnalysis?,
    allTracks: List<Track>,
    onSelectTrack: (Track) -> Unit,
    onLoadToDeck: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var inspectXRatio by remember { mutableFloatStateOf(0.45f) }
    var inspectYRatio by remember { mutableFloatStateOf(0.35f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Track Selection Ribbon
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "SELECT TRACK TO VERIFY AUDIO QUALITY",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allTracks) { track ->
                    val isSelected = track.id == analyzedTrack?.id
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectTrack(track) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) DeckACyan.copy(alpha = 0.2f) else DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) DeckACyan else DjSurfaceBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QualityPill(track.qualityRating)
                            Text(
                                text = track.title,
                                color = if (isSelected) DeckACyan else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (analyzedTrack != null && spectrogramData != null) {
            // Main Spectrogram Heatmap Canvas Card
            SpectrogramCanvasCard(
                track = analyzedTrack,
                analysis = spectrogramData,
                inspectX = inspectXRatio,
                inspectY = inspectYRatio,
                onInspectChange = { x, y ->
                    inspectXRatio = x
                    inspectYRatio = y
                }
            )

            // Dynamic Inspection Crosshair readout
            val inspectedKhz = (1.0f - inspectYRatio) * 24.0f
            val inspectedSec = (inspectXRatio * analyzedTrack.durationSeconds).toInt()
            val inspectedDb = -48.0f + (1.0f - inspectYRatio) * 44.0f

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DjSurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SPECTRAL PROBE: ${String.format(Locale.US, "%.1f kHz", inspectedKhz)} • ${String.format(Locale.US, "%02d:%02d", inspectedSec / 60, inspectedSec % 60)} • ${String.format(Locale.US, "%.1f dB", inspectedDb)}",
                        color = DeckACyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("TOUCH TO PROBE", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Quality Verdict & Spectral Cutoff Verification Card
            QualityAssessmentCard(
                track = analyzedTrack,
                analysis = spectrogramData,
                onLoadToDeck = { onLoadToDeck(analyzedTrack) }
            )

            // Audio Specs Metric Grid
            AudioSpecsMetricGrid(track = analyzedTrack, analysis = spectrogramData)

            // Sound Quality Education & Analysis Guide
            SpectrogramGuideCard()
        }
    }
}

@Composable
private fun SpectrogramCanvasCard(
    track: Track,
    analysis: SpectrogramAnalysis,
    inspectX: Float,
    inspectY: Float,
    onInspectChange: (Float, Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .testTag("spectrogram_canvas_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            // Header with Frequency Range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                    Text(
                        text = "ACOUSTIC FREQUENCY SPECTROGRAM (0 Hz – 24.0 kHz)",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Cutoff Alert Tag
                val isSuspicious = analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED
                Text(
                    text = "Ceiling: ${String.format(Locale.US, "%.1f kHz", analysis.cutoffKhz)}",
                    color = if (isSuspicious) NeonRed else NeonGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Interactive Spectrogram Heatmap Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF07080F))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val rx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ry = (offset.y / size.height).coerceIn(0f, 1f)
                            onInspectChange(rx, ry)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val rx = (change.position.x / size.width).coerceIn(0f, 1f)
                            val ry = (change.position.y / size.height).coerceIn(0f, 1f)
                            onInspectChange(rx, ry)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val slices = analysis.spectralSlices
                    if (slices.isNotEmpty()) {
                        val numSlices = slices.size
                        val numBins = slices[0].size
                        val cellW = w / numSlices
                        val cellH = h / numBins

                        // Draw spectral density cells
                        for (t in 0 until numSlices) {
                            val col = slices[t]
                            for (f in 0 until numBins) {
                                val energy = col[f]
                                val color = getHeatmapColor(energy)
                                // Invert Y so low frequencies are at the bottom and high at top
                                val y = h - (f + 1) * cellH
                                val x = t * cellW

                                drawRect(
                                    color = color,
                                    topLeft = Offset(x, y),
                                    size = Size(cellW + 0.5f, cellH + 0.5f)
                                )
                            }
                        }
                    }

                    // Draw Standard Frequency Reference Lines (22.05k, 20.0k, 16.0k, 10.0k)
                    val freqMarkers = listOf(
                        22.05f to "22.05k (FLAC)",
                        20.0f to "20.0k (320k)",
                        16.0f to "16.0k (128k Cutoff)",
                        10.0f to "10.0k",
                        2.0f to "2.0k"
                    )

                    freqMarkers.forEach { (khz, label) ->
                        val yRatio = 1.0f - (khz / 24.0f)
                        val yPos = h * yRatio
                        drawLine(
                            color = Color(0x33FFFFFF),
                            start = Offset(0f, yPos),
                            end = Offset(w, yPos),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                        )
                    }

                    // Draw Detected High Frequency Cutoff line
                    val cutoffY = h * (1.0f - (analysis.cutoffKhz / 24.0f))
                    val cutoffColor = if (analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) NeonRed else NeonGreen
                    drawLine(
                        color = cutoffColor,
                        start = Offset(0f, cutoffY),
                        end = Offset(w, cutoffY),
                        strokeWidth = 2.dp.toPx()
                    )

                    // Draw Inspection Crosshair
                    val inspectPxX = w * inspectX
                    val inspectPxY = h * inspectY
                    drawLine(
                        color = DeckACyan.copy(alpha = 0.8f),
                        start = Offset(inspectPxX, 0f),
                        end = Offset(inspectPxX, h),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = DeckACyan.copy(alpha = 0.8f),
                        start = Offset(0f, inspectPxY),
                        end = Offset(w, inspectPxY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(
                        color = DeckACyan,
                        radius = 4.dp.toPx(),
                        center = Offset(inspectPxX, inspectPxY)
                    )
                }
            }
        }
    }
}

private fun getHeatmapColor(intensity: Float): Color {
    val clamped = intensity.coerceIn(0f, 1f)
    return when {
        clamped < 0.15f -> lerpColor(SpectroFloor, SpectroLow, clamped / 0.15f)
        clamped < 0.35f -> lerpColor(SpectroLow, SpectroMidLow, (clamped - 0.15f) / 0.20f)
        clamped < 0.60f -> lerpColor(SpectroMidLow, SpectroMid, (clamped - 0.35f) / 0.25f)
        clamped < 0.80f -> lerpColor(SpectroMid, SpectroMidHigh, (clamped - 0.60f) / 0.20f)
        clamped < 0.95f -> lerpColor(SpectroMidHigh, SpectroHigh, (clamped - 0.80f) / 0.15f)
        else -> lerpColor(SpectroHigh, SpectroPeak, (clamped - 0.95f) / 0.05f)
    }
}

private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
    val factor = t.coerceIn(0f, 1f)
    return Color(
        red = c1.red + (c2.red - c1.red) * factor,
        green = c1.green + (c2.green - c1.green) * factor,
        blue = c1.blue + (c2.blue - c1.blue) * factor,
        alpha = 1.0f
    )
}

@Composable
private fun QualityAssessmentCard(
    track: Track,
    analysis: SpectrogramAnalysis,
    onLoadToDeck: () -> Unit
) {
    val isLossless = analysis.qualityRating.isLossless
    val isFake = analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED
    val accentColor = when {
        isLossless -> NeonGreen
        isFake -> NeonRed
        analysis.qualityRating == AudioQualityRating.TRUE_320 -> DeckACyan
        else -> NeonAmber
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quality_assessment_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, accentColor)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isFake) Icons.Default.Warning else if (isLossless) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = analysis.qualityRating.label,
                            color = accentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Acoustic Verification Result",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                Button(
                    onClick = onLoadToDeck,
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PLAY / PREVIEW", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                text = analysis.notes,
                color = TextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun AudioSpecsMetricGrid(
    track: Track,
    analysis: SpectrogramAnalysis
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricItem(
            label = "SAMPLE RATE",
            value = "${analysis.sampleRate / 1000.0} kHz",
            sub = if (analysis.sampleRate >= 48000) "Pro Studio" else "CD Standard",
            modifier = Modifier.weight(1f)
        )
        MetricItem(
            label = "BIT DEPTH",
            value = "${analysis.bitDepth}-bit",
            sub = if (analysis.bitDepth > 16) "Hi-Res" else "16-Bit Audio",
            modifier = Modifier.weight(1f)
        )
        MetricItem(
            label = "DYNAMIC RANGE",
            value = "${analysis.dynamicRangeDb} dB",
            sub = "Club Punch",
            modifier = Modifier.weight(1f)
        )
        MetricItem(
            label = "FILE SIZE",
            value = "${track.fileSizeMb} MB",
            sub = track.format,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(sub, color = TextSecondary, fontSize = 9.sp)
        }
    }
}

@Composable
fun QualityPill(rating: AudioQualityRating) {
    val color = when {
        rating.isLossless -> NeonGreen
        rating == AudioQualityRating.SUSPICIOUS_UPSCALED -> NeonRed
        rating == AudioQualityRating.TRUE_320 -> DeckACyan
        else -> NeonAmber
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (rating.isLossless) "FLAC" else if (rating == AudioQualityRating.SUSPICIOUS_UPSCALED) "FAKE 320" else "320K",
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SpectrogramGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HOW TO DETECT FAKE / UPSCALED AUDIO", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "• True Lossless (FLAC/WAV): Extends smoothly all the way up to 22.05 kHz or 24 kHz.\n" +
                       "• True 320 kbps MP3: Reaches ~20.5 kHz before naturally sloping off.\n" +
                       "• 128 kbps & YouTube Rips: Have a sharp 'brickwall' cutoff at exactly 15.0 - 16.0 kHz.\n" +
                       "• Fake 320k: File headers say 320kbps, but the spectrogram cuts dead at 16kHz—always delete these before festival gigs!",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
