package com.example.ui.components

import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.ZoomIn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.pow

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
    errorMessage: String? = null,
    onRetryAnalysis: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var inspectXRatio by remember { mutableFloatStateOf(0.45f) }
    var inspectYRatio by remember { mutableFloatStateOf(0.35f) }
    var zoomLevel by remember { mutableIntStateOf(1) } // 1x, 2x, 4x, 8x
    var panRatio by remember { mutableFloatStateOf(0.0f) } // 0f..1f for horizontal inspection when zoomed

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Track Selection Carousel Ribbon
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "SELECT AUDIO FILE TO AUDIT (HD SPEK / STUDIO STFT)",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp)
            ) {
                items(allTracks) { track ->
                    val isSelected = track.id == analyzedTrack?.id
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelectTrack(track)
                                panRatio = 0.0f
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) DeckACyan.copy(alpha = 0.2f) else DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) DeckACyan else DjSurfaceBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
        } else if (errorMessage != null && analyzedTrack != null) {
            // Inline Error Recovery Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spectrogram_error_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonRed)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(22.dp))
                        Text(
                            text = "Couldn't analyze this track",
                            color = NeonRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        text = errorMessage,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onRetryAnalysis,
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("spectrogram_retry_button")
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Analysis", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
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
                        text = if (analysisProgressPercent > 0) "Computing HD Spectrogram... $analysisProgressPercent%" else "Calculating High-Definition STFT (1024 slices × 256 bins)...",
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
                        text = "Extracting high-resolution frequency bins & checking ultrasonic cutoff ceiling",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        } else if (analyzedTrack != null && spectrogramData != null) {
            // Main High-Definition Spectrogram Heatmap Canvas Card
            SpectrogramCanvasCard(
                track = analyzedTrack,
                analysis = spectrogramData,
                playbackProgress = playbackProgress,
                zoomLevel = zoomLevel,
                panRatio = panRatio,
                onZoomChange = { newZoom ->
                    zoomLevel = newZoom
                    panRatio = panRatio.coerceIn(0.0f, 1.0f)
                },
                onPanChange = { newPan -> panRatio = newPan.coerceIn(0.0f, 1.0f) },
                inspectX = inspectXRatio,
                inspectY = inspectYRatio,
                onInspectChange = { x, y ->
                    inspectXRatio = x
                    inspectYRatio = y
                },
                onSeek = { ratio -> onSeekToRatio(ratio) }
            )

            // Dynamic Inspection Crosshair & Acoustic Metric Readout
            val inspectedKhz = calculateFrequencyForYRatio(inspectYRatio)
            val safeDuration = analyzedTrack.durationSeconds.coerceAtLeast(0)
            val actualGlobalX = if (zoomLevel > 1) {
                val windowSize = 1.0f / zoomLevel
                (panRatio * (1.0f - windowSize) + inspectXRatio * windowSize).coerceIn(0f, 1f)
            } else {
                inspectXRatio
            }
            val inspectedSec = (actualGlobalX * safeDuration).toInt()
            val sliceIdx = (actualGlobalX * (spectrogramData.spectralSlices.size - 1)).toInt().coerceIn(0, spectrogramData.spectralSlices.size - 1)
            val binIdx = ((1.0f - inspectYRatio) * (if (spectrogramData.spectralSlices.isNotEmpty()) spectrogramData.spectralSlices[0].size - 1 else 1)).toInt()
            val sliceEnergy = if (spectrogramData.spectralSlices.isNotEmpty() && binIdx in 0 until spectrogramData.spectralSlices[sliceIdx].size) {
                spectrogramData.spectralSlices[sliceIdx][binIdx]
            } else 0.5f
            val inspectedDb = -72.0f + (sliceEnergy * 72.0f)

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
                        text = "PROBE: ${String.format(Locale.US, "%.1f kHz", inspectedKhz)} • ${String.format(Locale.US, "%02d:%02d", inspectedSec / 60, inspectedSec % 60)} • ${String.format(Locale.US, "%.1f dB", inspectedDb)}",
                        color = DeckACyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (zoomLevel > 1) "${zoomLevel}X ZOOM ACTIVE" else "TAP / DRAG TO PROBE",
                        color = if (zoomLevel > 1) NeonAmber else TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
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

            // Metadata Provenance Breakdown (MusicBrainz Canonical Catalogue vs Local Audio DSP)
            MetadataProvenanceCard(track = analyzedTrack, modifier = Modifier.fillMaxWidth())

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
    zoomLevel: Int,
    panRatio: Float,
    onZoomChange: (Int) -> Unit,
    onPanChange: (Float) -> Unit,
    inspectX: Float,
    inspectY: Float,
    onInspectChange: (Float, Float) -> Unit,
    onSeek: (Float) -> Unit
) {
    // Generate high-definition ImageBitmap (1024 slices × 256 frequency bins = ~1 MB)
    val cachedBitmap = remember(analysis) {
        val slices = analysis.spectralSlices
        if (slices.isNotEmpty()) {
            val numSlices = slices.size
            val numBins = slices[0].size
            if (numSlices > 0 && numBins > 0) {
                try {
                    val bmp = Bitmap.createBitmap(numSlices, numBins, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(numSlices * numBins)
                    for (f in 0 until numBins) {
                        // Invert Y so low frequencies are at the bottom of the image
                        val y = numBins - 1 - f
                        val rowOffset = y * numSlices
                        for (t in 0 until numSlices) {
                            val energy = slices[t][f]
                            pixels[rowOffset + t] = getHeatmapColorArgb(energy)
                        }
                    }
                    bmp.setPixels(pixels, 0, numSlices, 0, 0, numSlices, numBins)
                    bmp.asImageBitmap()
                } catch (e: Throwable) {
                    android.util.Log.e("SoundSyncSpectrum", "Failed creating HD spectrogram bitmap: ${e.message}", e)
                    null
                }
            } else null
        } else null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("spectrogram_canvas_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            // Header with Frequency Range, Cutoff tag & Zoom Controls directly above graph
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                    Text(
                        text = "HD SPECTROGRAM (20 Hz – 24.0 kHz)",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Zoom selector chips
                    listOf(1, 2, 4, 8).forEach { z ->
                        val isSelected = (zoomLevel == z)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) DeckACyan else DjSurfaceDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) DeckACyan else DjSurfaceBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onZoomChange(z) }
                        ) {
                            Text(
                                text = "${z}X",
                                color = if (isSelected) DjObsidian else TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Cutoff Alert Tag
                    val isSuspicious = analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = (if (isSuspicious) NeonRed else NeonGreen).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSuspicious) NeonRed else NeonGreen)
                    ) {
                        Text(
                            text = "Ceiling: ${String.format(Locale.US, "%.1f kHz", analysis.cutoffKhz)}",
                            color = if (isSuspicious) NeonRed else NeonGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Spectrogram Display with Y-axis frequency ruler on the left (Expanded vertical height)
            val graphHeight = 290.dp
            Row(modifier = Modifier.fillMaxWidth().height(graphHeight)) {
                // Frequency Ruler labels (22k, 20k, 16k, 10k, 5k, 1k, 100Hz, 20Hz)
                Column(
                    modifier = Modifier.padding(end = 4.dp).height(graphHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text("22k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("20k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("16k", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
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
                        .background(Color(0xFF05060C))
                        .pointerInput(zoomLevel, panRatio) {
                            detectTapGestures { offset ->
                                val rx = (offset.x / size.width).coerceIn(0f, 1f)
                                val ry = (offset.y / size.height).coerceIn(0f, 1f)
                                onInspectChange(rx, ry)
                                val actualSeekRatio = if (zoomLevel > 1) {
                                    val window = 1.0f / zoomLevel
                                    (panRatio * (1.0f - window) + rx * window).coerceIn(0f, 1f)
                                } else {
                                    rx
                                }
                                onSeek(actualSeekRatio)
                            }
                        }
                        .pointerInput(zoomLevel, panRatio) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val rx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val ry = (change.position.y / size.height).coerceIn(0f, 1f)
                                onInspectChange(rx, ry)

                                if (zoomLevel > 1 && dragAmount.x != 0f) {
                                    // Pan horizontally when dragging
                                    val panDelta = -dragAmount.x / (size.width * (zoomLevel - 1).coerceAtLeast(1))
                                    onPanChange((panRatio + panDelta).coerceIn(0f, 1f))
                                } else {
                                    onSeek(rx)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        if (w <= 0f || h <= 0f) return@Canvas

                        // Draw high-resolution spectrogram bitmap texture
                        if (cachedBitmap != null) {
                            val totalSlices = cachedBitmap.width
                            val totalBins = cachedBitmap.height

                            if (zoomLevel <= 1) {
                                // Full view: draw full 1024 slices across component width with Medium filter
                                drawImage(
                                    image = cachedBitmap,
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                                    filterQuality = FilterQuality.Medium
                                )
                            } else {
                                // Zoomed view: crop source rectangle with full resolution detail
                                val visibleSliceCount = (totalSlices / zoomLevel).coerceIn(16, totalSlices)
                                val startSlice = (panRatio * (totalSlices - visibleSliceCount)).toInt().coerceIn(0, totalSlices - visibleSliceCount)

                                drawImage(
                                    image = cachedBitmap,
                                    srcOffset = IntOffset(startSlice, 0),
                                    srcSize = IntSize(visibleSliceCount, totalBins),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                                    filterQuality = FilterQuality.Low
                                )
                            }
                        }

                        // Standard Frequency Reference Lines (22.05k, 20.0k, 16.0k, 10.0k, 1.0k)
                        val freqMarkers = listOf(
                            22.05f to "22.05k (FLAC)",
                            20.0f to "20.0k (320k)",
                            16.0f to "16.0k (128k)",
                            10.0f to "10.0k",
                            1.0f to "1.0k"
                        )

                        freqMarkers.forEach { (khz, _) ->
                            val yRatio = getYRatioForFrequency(khz)
                            val yPos = h * yRatio
                            drawLine(
                                color = Color(0x33FFFFFF),
                                start = Offset(0f, yPos),
                                end = Offset(w, yPos),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )
                        }

                        // Draw Detected High Frequency Cutoff line
                        val cutoffY = h * getYRatioForFrequency(analysis.cutoffKhz)
                        val cutoffColor = if (analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) NeonRed else NeonGreen
                        drawLine(
                            color = cutoffColor.copy(alpha = 0.9f),
                            start = Offset(0f, cutoffY),
                            end = Offset(w, cutoffY),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = if (analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) {
                                PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                            } else null
                        )

                        // Draw Active Playback Cursor Line
                        if (playbackProgress in 0f..1f) {
                            val cursorX = if (zoomLevel > 1) {
                                val window = 1.0f / zoomLevel
                                val start = panRatio * (1.0f - window)
                                ((playbackProgress - start) / window).coerceIn(-0.1f, 1.1f) * w
                            } else {
                                playbackProgress * w
                            }

                            if (cursorX in 0f..w) {
                                drawLine(
                                    color = Color.White,
                                    start = Offset(cursorX, 0f),
                                    end = Offset(cursorX, h),
                                    strokeWidth = 2.dp.toPx()
                                )
                                drawCircle(
                                    color = DeckACyan,
                                    radius = 3.5.dp.toPx(),
                                    center = Offset(cursorX, 4.dp.toPx())
                                )
                            }
                        }

                        // Draw Inspection Crosshair
                        val inspectPxX = (w * inspectX).coerceIn(0f, w)
                        val inspectPxY = (h * inspectY).coerceIn(0f, h)
                        drawLine(
                            color = DeckACyan.copy(alpha = 0.7f),
                            start = Offset(inspectPxX, 0f),
                            end = Offset(inspectPxX, h),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = DeckACyan.copy(alpha = 0.7f),
                            start = Offset(0f, inspectPxY),
                            end = Offset(w, inspectPxY),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }

            // Time axis labels & zoom viewport minimap
            if (zoomLevel > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                // Mini timeline track navigator showing current zoomed window position
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DjSurfaceDark)
                ) {
                    val windowFraction = 1.0f / zoomLevel
                    val leftOffsetFraction = panRatio * (1.0f - windowFraction)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = windowFraction)
                            .padding(start = (leftOffsetFraction * 200).dp) // approximate visual offset
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(DeckACyan.copy(alpha = 0.6f))
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dur = track.durationSeconds.coerceAtLeast(0)
                if (zoomLevel > 1) {
                    val windowSec = dur / zoomLevel
                    val startSec = (panRatio * (dur - windowSec)).toInt()
                    Text(String.format(Locale.US, "%d:%02d", startSec / 60, startSec % 60), color = DeckACyan, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "Zoom %dx Window (%ds)", zoomLevel, windowSec), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "%d:%02d", (startSec + windowSec) / 60, (startSec + windowSec) % 60), color = DeckACyan, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                } else {
                    Text("0:00", color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "%d:%02d", (dur / 4) / 60, (dur / 4) % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "%d:%02d", (dur / 2) / 60, (dur / 2) % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "%d:%02d", (dur * 3 / 4) / 60, (dur * 3 / 4) % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "%d:%02d", dur / 60, dur % 60), color = TextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/**
 * Maps frequency in kHz to the Y ratio (0f = top = 24 kHz, 1f = bottom = 20 Hz).
 */
private fun getYRatioForFrequency(freqKhz: Float): Float {
    val minF = 20.0f
    val maxF = 24000.0f
    val fHz = (freqKhz * 1000.0f).coerceIn(minF, maxF)
    val ratio = kotlin.math.log10(fHz / minF) / kotlin.math.log10(maxF / minF)
    return (1.0f - ratio.toFloat()).coerceIn(0f, 1f)
}

/**
 * Maps Y ratio (0f = top, 1f = bottom) to frequency in kHz.
 */
private fun calculateFrequencyForYRatio(yRatio: Float): Float {
    val minF = 20.0f
    val maxF = 24000.0f
    val ratio = (1.0f - yRatio.coerceIn(0f, 1f)).toDouble()
    val fHz = minF * (maxF / minF).toDouble().pow(ratio)
    return (fHz / 1000.0f).toFloat().coerceIn(0.02f, 24.0f)
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

private fun getHeatmapColorArgb(intensity: Float): Int {
    val c = getHeatmapColor(intensity)
    val a = (c.alpha * 255f).toInt().coerceIn(0, 255)
    val r = (c.red * 255f).toInt().coerceIn(0, 255)
    val g = (c.green * 255f).toInt().coerceIn(0, 255)
    val b = (c.blue * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
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
    val isUnknown = analysis.qualityRating == AudioQualityRating.UNKNOWN_BITRATE
    val accentColor = when {
        isLossless -> NeonGreen
        isFake -> NeonRed
        analysis.qualityRating == AudioQualityRating.TRUE_320 -> DeckACyan
        isUnknown -> TextMuted
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
                        // CBR is only claimed when the bitstream genuinely verified it;
                        // otherwise show the bitrate source we actually read.
                        text = when {
                            isFake -> "TRANSDETECT REJECT"
                            isLossless -> "LOSSLESS PASSED"
                            analysis.bitrateMode == com.example.model.BitrateMode.CBR -> "VERIFIED CBR"
                            analysis.bitrateMode == com.example.model.BitrateMode.VBR -> "VBR (avg)"
                            analysis.encodedBitrateKbps > 0 -> "READ FROM FILE"
                            else -> "BITRATE UNKNOWN"
                        },
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
                    title = "ENCODED BITRATE",
                    value = if (analysis.encodedBitrateKbps > 0) "${analysis.encodedBitrateKbps} kbps" else "Unknown",
                    accent = when {
                        analysis.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED -> NeonRed
                        analysis.encodedBitrateKbps > 0 -> TextPrimary
                        else -> TextMuted
                    },
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "DECLARED (TAG)",
                    value = if (track.bitrateKbps > 0) "${track.bitrateKbps} kbps" else "—",
                    accent = TextMuted,
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
                "• Fake / Upscaled 320 kbps: Sharp brickwall cutoff at ~16.0 kHz indicating a low 128 kbps transcode re-encoded inside a high bitrate wrapper.\n" +
                "• Transients: Vertical bright lines represent kick drums, snares, and fast percussive drops.",
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
        AudioQualityRating.UNKNOWN_BITRATE -> TextMuted to "? KBPS"
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
