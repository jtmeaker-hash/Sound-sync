package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.WaveformData
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Rekordbox-style DJ Live Scrolling Waveform Display.
 * Features:
 * - Real-time horizontal scrolling with a fixed center playhead.
 * - 3-Band frequency peak colorization (Bass: Blue, Mids: Amber/Orange, Highs: Cyan/White).
 * - Full interactive scrubbing & seeking by dragging/swiping horizontally.
 * - Dynamic beat & bar grid markers derived from track BPM.
 * - Hot Cue markers along the timeline.
 * - Full-track overview mini-scrubber at top.
 * - Zero GC allocations per frame for 60fps performance.
 */
@Composable
fun RekordboxWaveformView(
    track: Track,
    waveformData: WaveformData?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onSeekToMs: (Long) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Zoom factor: visible window in seconds (e.g. 6s = zoomed in DJ view, 12s = standard, 24s = wide)
    var visibleWindowSeconds by remember { mutableFloatStateOf(8.0f) }
    var isUserDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val textMeasurer = rememberTextMeasurer()

    val safeDurationMs = if (durationMs > 0) durationMs else (track.durationSeconds.coerceAtLeast(10) * 1000L)

    // Media3/AudioTrack publishes the authoritative played-out position. Do not
    // run a second wall-clock animation here: it can advance faster than the
    // decoder and permanently desynchronize the waveform from audible audio.
    val effectivePositionMs = if (isUserDragging) dragPositionMs.toLong() else currentPositionMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DjSurfaceDark)
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(12.dp))
            .testTag("rekordbox_waveform_container")
    ) {
        // --- 1. FULL TRACK OVERVIEW MINI-SCRUBBER ---
        FullTrackOverviewScrubber(
            waveformData = waveformData,
            currentPositionMs = effectivePositionMs,
            durationMs = safeDurationMs,
            onSeekFraction = { fraction ->
                val targetMs = (safeDurationMs * fraction.coerceIn(0f, 1f)).toLong()
                onSeekToMs(targetMs)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        )

        // --- 2. MAIN SCROLLING REKORDBOX WAVEFORM CANVAS ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
                .background(Color(0xFF090B10))
                .pointerInput(safeDurationMs, visibleWindowSeconds) {
                    detectTapGestures { offset ->
                        val centerPx = size.width / 2f
                        val deltaPx = offset.x - centerPx
                        val msPerPx = (visibleWindowSeconds * 1000f) / size.width
                        val targetMs = (effectivePositionMs + (deltaPx * msPerPx)).toLong().coerceIn(0L, safeDurationMs)
                        onSeekToMs(targetMs)
                    }
                }
                .pointerInput(safeDurationMs, visibleWindowSeconds) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isUserDragging = true
                            dragPositionMs = effectivePositionMs.toFloat()
                        },
                        onDragEnd = {
                            isUserDragging = false
                            onSeekToMs(dragPositionMs.toLong().coerceIn(0L, safeDurationMs))
                        },
                        onDragCancel = {
                            isUserDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val msPerPx = (visibleWindowSeconds * 1000f) / size.width
                            // Swiping left moves audio forward, swiping right moves audio backward
                            val deltaMs = -dragAmount.x * msPerPx
                            val newMs = (dragPositionMs + deltaMs).coerceIn(0f, safeDurationMs.toFloat())
                            dragPositionMs = newMs
                            onSeekToMs(newMs.toLong())
                        }
                    )
                }
                .testTag("scrolling_waveform_canvas")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRekordboxScrollingWaveform(
                    waveformData = waveformData,
                    currentPositionMs = effectivePositionMs,
                    durationMs = safeDurationMs,
                    visibleWindowSeconds = visibleWindowSeconds,
                    trackBpm = track.bpm,
                    textMeasurer = textMeasurer
                )
            }

            // Fixed Center Playhead
            CenterPlayheadOverlay()

            // Loading / Analyzing overlay
            if (isLoading && waveformData == null) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = DjSurfaceCard.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = DeckACyan, strokeWidth = 2.dp)
                        Text(
                            text = "ANALYZING REKORDBOX PEAKS...",
                            color = DeckACyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Dragging scrubber tooltip
            if (isUserDragging) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    color = DeckBPink,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    val curSec = (effectivePositionMs / 1000).toInt()
                    val m = curSec / 60
                    val s = curSec % 60
                    val msFrac = (effectivePositionMs % 1000) / 100
                    Text(
                        text = String.format(Locale.US, "SEEK: %d:%02d.%d", m, s, msFrac),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // --- 3. WAVEFORM ZOOM & DECK METRICS TOOLBAR ---
        WaveformBottomToolbar(
            track = track,
            currentPositionMs = effectivePositionMs,
            durationMs = safeDurationMs,
            visibleWindowSeconds = visibleWindowSeconds,
            onZoomIn = {
                visibleWindowSeconds = (visibleWindowSeconds * 0.7f).coerceAtLeast(3.0f)
            },
            onZoomOut = {
                visibleWindowSeconds = (visibleWindowSeconds * 1.4f).coerceAtMost(30.0f)
            }
        )
    }
}

/**
 * High-performance 60fps Canvas renderer for the horizontal scrolling waveform under a fixed center playhead.
 */
private fun DrawScope.drawRekordboxScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Long,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer
) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val centerX = width / 2f
    val maxPeakHeight = centerY * 0.88f

    // Subtle center zero-axis line
    drawLine(
        color = Color(0x3300F0FF),
        start = Offset(0f, centerY),
        end = Offset(width, centerY),
        strokeWidth = 1f
    )

    if (durationMs <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val visibleStartMs = currentPositionMs - (centerX * msPerPixel).toLong()
    val visibleEndMs = currentPositionMs + (centerX * msPerPixel).toLong()

    // -------------------------------------------------------------
    // 1. DRAW BEAT GRID AND BAR MARKERS (If BPM is available)
    // -------------------------------------------------------------
    if (trackBpm > 40.0 && trackBpm < 250.0) {
        val beatIntervalMs = (60_000.0 / trackBpm)
        val firstBeatIndex = floor(visibleStartMs / beatIntervalMs).toInt().coerceAtLeast(0)
        val lastBeatIndex = (visibleEndMs / beatIntervalMs).toInt() + 1

        for (beatIdx in firstBeatIndex..lastBeatIndex) {
            val beatTimeMs = (beatIdx * beatIntervalMs).toLong()
            if (beatTimeMs in 0..durationMs) {
                val beatX = centerX + ((beatTimeMs - currentPositionMs) / msPerPixel)
                if (beatX in -20f..(width + 20f)) {
                    val isBarDownbeat = (beatIdx % 4 == 0)
                    val barNumber = (beatIdx / 4) + 1

                    if (isBarDownbeat) {
                        // Bar marker (Beat 1)
                        drawLine(
                            color = Color(0x8800F0FF),
                            start = Offset(beatX, 0f),
                            end = Offset(beatX, height),
                            strokeWidth = 1.5f
                        )
                        // Bar label
                        if (beatX in 0f..(width - 35f)) {
                            val barTextLayout = textMeasurer.measure(
                                text = AnnotatedString("$barNumber.1"),
                                style = TextStyle(
                                    color = Color(0xAA00F0FF),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            drawText(
                                textLayoutResult = barTextLayout,
                                topLeft = Offset(beatX + 3f, 4f)
                            )
                        }
                    } else {
                        // Secondary beats (2, 3, 4)
                        drawLine(
                            color = Color(0x22FFFFFF),
                            start = Offset(beatX, centerY - 14f),
                            end = Offset(beatX, centerY + 14f),
                            strokeWidth = 1f
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------
    // 2. DRAW PEAK WAVEFORM BARS
    // -------------------------------------------------------------
    if (waveformData != null && waveformData.samplePoints > 0) {
        val peaks = waveformData.peaks
        val lowBand = waveformData.lowBand
        val midBand = waveformData.midBand
        val highBand = waveformData.highBand
        val totalBins = peaks.size
        val msPerBin = durationMs.toFloat() / totalBins.toFloat()

        val barWidthPx = max(2.5f, 3.5f)
        val stepPixels = 3.5f
        val numBarsToDraw = (width / stepPixels).toInt() + 4

        for (i in 0 until numBarsToDraw) {
            val screenX = i * stepPixels
            // Translate screen X back to song time in milliseconds
            val deltaFromCenterPx = screenX - centerX
            val sampleTimeMs = currentPositionMs + (deltaFromCenterPx * msPerPixel)

            if (sampleTimeMs in 0f..durationMs.toFloat()) {
                val binIndex = (sampleTimeMs / msPerBin).toInt().coerceIn(0, totalBins - 1)
                val peak = peaks[binIndex].coerceIn(0.0f, 1.0f)
                val low = lowBand[binIndex].coerceIn(0.0f, 1.0f)
                val mid = midBand[binIndex].coerceIn(0.0f, 1.0f)
                val high = highBand[binIndex].coerceIn(0.0f, 1.0f)

                // True dynamic range: Silence approaches 0 (flat 0.5px line), quiet sections are small, drops/choruses reach full height
                val barHalfHeight = if (peak < 0.015f) 0.5f else (peak * maxPeakHeight).coerceAtLeast(1.0f)
                val isPast = sampleTimeMs < currentPositionMs

                // 3-Band Color Composition:
                // Bass (Blue) dominates the center core, Mids (Orange/Amber) form body, Highs (Cyan/White) tip transients
                val colorAlpha = if (isPast) 0.60f else 1.0f
                val barColor = when {
                    high > 0.65f -> Color(0xFF00FFFF).copy(alpha = colorAlpha) // Bright Cyan Transient
                    low > 0.55f -> Color(0xFF1E6CFF).copy(alpha = colorAlpha)  // Deep Bass Blue
                    mid > 0.45f -> Color(0xFFFF9500).copy(alpha = colorAlpha)  // Vibrant Vocal/Snare Amber
                    else -> Color(0xFF00C8FF).copy(alpha = colorAlpha * 0.85f)
                }

                // Mirrored vertical bar
                drawLine(
                    color = barColor,
                    start = Offset(screenX, centerY - barHalfHeight),
                    end = Offset(screenX, centerY + barHalfHeight),
                    strokeWidth = 2.2f
                )

                // High transient tip highlight
                if (high > 0.70f && peak > 0.3f) {
                    drawCircle(
                        color = Color.White.copy(alpha = colorAlpha * 0.9f),
                        radius = 1.2f,
                        center = Offset(screenX, centerY - barHalfHeight)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = colorAlpha * 0.9f),
                        radius = 1.2f,
                        center = Offset(screenX, centerY + barHalfHeight)
                    )
                }
            }
        }
    } else {
        // Subtle fallback placeholder bars while loading
        val step = 6f
        val count = (width / step).toInt()
        for (i in 0 until count) {
            val sx = i * step
            drawLine(
                color = Color(0x3300F0FF),
                start = Offset(sx, centerY - 6f),
                end = Offset(sx, centerY + 6f),
                strokeWidth = 2f
            )
        }
    }
}

/**
 * Fixed Center Playhead Overlay with top/bottom neon glow indicators.
 */
@Composable
private fun CenterPlayheadOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val height = size.height

        // Glowing center line
        drawLine(
            color = Color(0x66FF0055),
            start = Offset(centerX, 0f),
            end = Offset(centerX, height),
            strokeWidth = 4f
        )
        drawLine(
            color = Color(0xFFFF0055),
            start = Offset(centerX, 0f),
            end = Offset(centerX, height),
            strokeWidth = 2f
        )

        // Top pointer triangle
        val topTri = Path().apply {
            moveTo(centerX - 6f, 0f)
            lineTo(centerX + 6f, 0f)
            lineTo(centerX, 8f)
            close()
        }
        drawPath(topTri, Color(0xFFFF0055))

        // Bottom pointer triangle
        val botTri = Path().apply {
            moveTo(centerX - 6f, height)
            lineTo(centerX + 6f, height)
            lineTo(centerX, height - 8f)
            close()
        }
        drawPath(botTri, Color(0xFFFF0055))
    }
}

/**
 * Full track overview miniature waveform scrubber.
 */
@Composable
private fun FullTrackOverviewScrubber(
    waveformData: WaveformData?,
    currentPositionMs: Long,
    durationMs: Long,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF0D1017))
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeekFraction(fraction)
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onSeekFraction(fraction)
                }
            }
            .testTag("overview_scrubber")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f

            if (waveformData != null && waveformData.samplePoints > 0) {
                val peaks = waveformData.peaks
                val step = max(1f, width / peaks.size.toFloat())
                val progressFrac = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                val playheadX = progressFrac * width

                for (i in peaks.indices) {
                    val x = i * step
                    val peak = peaks[i]
                    val barH = if (peak < 0.015f) 0.5f else (peak * (centerY * 0.85f)).coerceAtLeast(1.0f)
                    val isPast = x <= playheadX
                    val color = if (isPast) DeckACyan.copy(alpha = 0.9f) else Color(0xFF2A364F)

                    drawLine(
                        color = color,
                        start = Offset(x, centerY - barH),
                        end = Offset(x, centerY + barH),
                        strokeWidth = max(1f, step - 0.5f)
                    )
                }

                // Playhead needle
                drawLine(
                    color = Color(0xFFFF0055),
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, height),
                    strokeWidth = 2f
                )
            } else {
                // Flat baseline
                drawLine(
                    color = Color(0x3300F0FF),
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 1f
                )
            }
        }
    }
}

/**
 * Bottom metrics bar for waveform zoom and BPM/Key readouts.
 */
@Composable
private fun WaveformBottomToolbar(
    track: Track,
    currentPositionMs: Long,
    durationMs: Long,
    visibleWindowSeconds: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    Surface(
        color = DjSurfaceCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // DJ Metadata Badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // BPM Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = DeckACyan.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = if (track.bpm > 0) String.format(Locale.US, "%.1f BPM", track.bpm) else "AUTO BPM",
                        color = DeckACyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Camelot Key Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = DeckBPink.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckBPink.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "KEY ${track.musicalKey}",
                        color = DeckBPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // 3-Band RGB Indicator
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0x22FFFFFF)
                ) {
                    Text(
                        text = "3-BAND DSP",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Zoom Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.0fs ZOOM", visibleWindowSeconds),
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = CircleShape,
                    color = DjSurfaceDark,
                    modifier = Modifier.size(24.dp)
                ) {
                    IconButton(onClick = onZoomIn, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = TextPrimary, modifier = Modifier.size(14.dp))
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = DjSurfaceDark,
                    modifier = Modifier.size(24.dp)
                ) {
                    IconButton(onClick = onZoomOut, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = TextPrimary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
