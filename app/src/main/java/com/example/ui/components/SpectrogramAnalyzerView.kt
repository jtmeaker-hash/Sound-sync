package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
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
    isPlaying: Boolean = false,
    currentPositionSec: Int = 0,
    playbackProgress: Float = 0f,
    onSelectTrack: (Track) -> Unit,
    onTogglePlayPause: () -> Unit = {},
    onSeekToRatio: (Float) -> Unit = {},
    onLoadToDeck: (Track) -> Unit = {},
    isLoading: Boolean = false,
    analysisProgressPercent: Int = 0,
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
        // Track Selection Carousel Ribbon
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "SELECT AUDIO FILE TO AUDIT (SPEC / SPEK DSP)",
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

        // Spotify Track Notice
        if (analyzedTrack != null && analyzedTrack.platforms.contains(MusicPlatform.SPOTIFY)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1DB954))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF1DB954), modifier = Modifier.size(20.dp))
                        Text(
                            text = "Spectrogram unavailable for Spotify playback",
                            color = Color(0xFF1DB954),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "Spotify streams are protected and decoded by the Spotify client service. Local acoustic STFT spectrogram analysis is available for all Local files and SoundCloud audio streams.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else if (isLoading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = DeckACyan, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (analysisProgressPercent > 0) "Analyzing spectrum... $analysisProgressPercent%" else "Calculating STFT Acoustic Fourier Transform...",
                        color = DeckACyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (analysisProgressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.6f).height(4.dp),
                        color = DeckACyan,
                        trackColor = DjSurfaceDark
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Decoding PCM mono samples and scanning high-frequency acoustic ceiling in background",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        } else if (analyzedTrack != null && spectrogramData != null) {
            // Main Spectrogram Heatmap Canvas Card (Spec / Spek style)
            SpectrogramCanvasCard(
                track = analyzedTrack,
                analysis = spectrogramData,
                playbackProgress = playbackProgress,
                inspectX = inspectXRatio,
                inspectY = inspectYRatio,
                onInspectChange = { x, y ->
                    inspectXRatio = x
                    inspectYRatio = y
                },
                onSeek = { ratio -> onSeekToRatio(ratio) }
            )

            // Dynamic Inspection Crosshair readout
            val inspectedKhz = (1.0f - inspectYRatio) * 24.0f
            val inspectedSec = (inspectXRatio * analyzedTrack.durationSeconds).toInt()
            val inspectedDb = -54.0f + (1.0f - inspectYRatio) * 54.0f

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
                    Text("TAP/DRAG TO SEEK & PROBE", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Spectrogram Playback Control Strip
            SpectrogramPlaybackControls(
                track = analyzedTrack,
                isPlaying = isPlaying,
                currentPositionSec = currentPositionSec,
                playbackProgress = playbackProgress,
                onTogglePlayPause = onTogglePlayPause,
                onSeekToRatio = onSeekToRatio
            )

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
    playbackProgress: Float,
    inspectX: Float,
    inspectY: Float,
    onInspectChange: (Float, Float) -> Unit,
    onSeek: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp)
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
                        text = "ACOUSTIC SPECTROGRAM (20 Hz – 24.0 kHz)",
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

            // Spectrogram Display with Y-axis frequency ruler on the left
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Frequency Ruler labels (20k, 10k, 5k, 1k, 100Hz, 20Hz)
                Column(
                    modifier = Modifier.padding(end = 4.dp).height(210.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text("20k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("10k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("5k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("1k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("100", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("20Hz", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }

                // Interactive Spectrogram Heatmap Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF07080F))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val rx = (offset.x / size.width).coerceIn(0f, 1f)
                                val ry = (offset.y / size.height).coerceIn(0f, 1f)
                                onInspectChange(rx, ry)
                                onSeek(rx)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val rx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val ry = (change.position.y / size.height).coerceIn(0f, 1f)
                                onInspectChange(rx, ry)
                                onSeek(rx)
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

                            // Draw spectral density cells (Magma / Inferno heatmap)
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

                        // Standard Frequency Reference Lines (22.05k, 20.0k, 16.0k, 10.0k)
                        val freqMarkers = listOf(
                            22.05f to "22.05k (FLAC)",
                            20.0f to "20.0k (320k)",
                            16.0f to "16.0k (128k Cutoff)",
                            10.0f to "10.0k",
                            1.0f to "1.0k"
                        )

                        freqMarkers.forEach { (khz, _) ->
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

                        // Draw Active Playback Cursor Line
                        if (playbackProgress in 0f..1f) {
                            val playheadX = w * playbackProgress
                            drawLine(
                                color = Color.White,
                                start = Offset(playheadX, 0f),
                                end = Offset(playheadX, h),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawCircle(
                                color = DeckACyan,
                                radius = 3.5.dp.toPx(),
                                center = Offset(playheadX, 4.dp.toPx())
                            )
                        }

                        // Draw Inspection Crosshair
                        val inspectPxX = w * inspectX
                        val inspectPxY = h * inspectY
                        drawLine(
                            color = DeckACyan.copy(alpha = 0.6f),
                            start = Offset(inspectPxX, 0f),
                            end = Offset(inspectPxX, h),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = DeckACyan.copy(alpha = 0.6f),
                            start = Offset(0f, inspectPxY),
                            end = Offset(w, inspectPxY),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }

            // Time axis labels
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dur = track.durationSeconds
                Text("0:00", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(String.format(Locale.US, "%d:%02d", (dur / 4) / 60, (dur / 4) % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(String.format(Locale.US, "%d:%02d", (dur / 2) / 60, (dur / 2) % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(String.format(Locale.US, "%d:%02d", (dur * 3 / 4) / 60, (dur * 3 / 4) % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(String.format(Locale.US, "%d:%02d", dur / 60, dur % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SpectrogramPlaybackControls(
    track: Track,
    isPlaying: Boolean,
    currentPositionSec: Int,
    playbackProgress: Float,
    onTogglePlayPause: () -> Unit,
    onSeekToRatio: (Float) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DjSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = track.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = "${track.artist} • ${track.format} ${track.bitrateKbps}kbps",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val curM = currentPositionSec / 60
                val curS = currentPositionSec % 60
                val durM = track.durationSeconds / 60
                val durS = track.durationSeconds % 60

                Text(
                    text = String.format(Locale.US, "%02d:%02d / %02d:%02d", curM, curS, durM, durS),
                    color = DeckACyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = { onSeekToRatio(0f) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Replay, contentDescription = "Restart", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }

                Surface(
                    shape = CircleShape,
                    color = DeckACyan,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onTogglePlayPause() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = DjObsidian,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = analysis.qualityRating.label,
                        color = accentColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accentColor)
                ) {
                    Text(
                        text = if (isFake) "TRANSDETECT REJECT" else if (isLossless) "LOSSLESS PASSED" else "VERIFIED CBR",
                        color = accentColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = analysis.notes,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Button(
                onClick = onLoadToDeck,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Load to Active Player Deck", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AudioSpecsMetricGrid(track: Track, analysis: SpectrogramAnalysis) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DjSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DETAILED ACOUSTIC METRICS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    title = "ACOUSTIC CEILING",
                    value = String.format(Locale.US, "%.1f kHz", analysis.cutoffKhz),
                    accent = if (analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) NeonRed else NeonGreen,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "SAMPLING RATE",
                    value = "${analysis.sampleRate / 1000} kHz",
                    accent = DeckACyan,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    title = "DECLARED BITRATE",
                    value = "${track.bitrateKbps} kbps",
                    accent = if (analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) NeonRed else TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "CONTAINER FORMAT",
                    value = track.format,
                    accent = if (analysis.qualityRating.isLossless) NeonGreen else TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(title, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SpectrogramGuideCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HOW TO READ SPECTROGRAMS (SPEC / SPEK GUIDE)", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(
                "• FLAC / Lossless: Audio harmonics extend smoothly to 22.05 kHz (or 24 kHz for Hi-Res) with no artificial hard ceiling.\n" +
                "• True 320 kbps: High frequencies taper off smoothly at ~20.5 kHz.\n" +
                "• Fake / Upscaled 320 kbps: Sharp brickwall cutoff at ~16.0 kHz indicating a low 128 kbps transcode re-encoded inside a high bitrate wrapper.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun QualityPill(rating: AudioQualityRating, modifier: Modifier = Modifier) {
    val (color, text) = when (rating) {
        AudioQualityRating.STUDIO_LOSSLESS -> NeonGreen to "24-BIT"
        AudioQualityRating.TRUE_LOSSLESS -> NeonGreen to "FLAC"
        AudioQualityRating.TRUE_320 -> DeckACyan to "320K"
        AudioQualityRating.TRUE_256 -> NeonAmber to "256K"
        AudioQualityRating.SUSPICIOUS_UPSCALED -> NeonRed to "FAKE 320K"
        AudioQualityRating.LOW_128 -> NeonRed to "128K"
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        modifier = modifier
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}
