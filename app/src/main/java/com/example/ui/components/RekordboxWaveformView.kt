package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.withFrameNanos
import com.example.ui.theme.BloodRedPrimary
import com.example.ui.theme.ProDarkVariant
import com.example.ui.theme.SoundSyncTheme
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
import com.example.audio.PlaybackTimeline
import com.example.audio.WaveformData
import com.example.model.Track
import com.example.model.WaveformShape
import com.example.model.WaveformStyle
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

// Pre-allocated paths reused across 60fps/120fps render frames for zero heap allocation
private val reusableTopPlayheadPath = Path()
private val reusableBotPlayheadPath = Path()

/**
 * SoundSync DJ Live Scrolling Waveform Display.
 * Supports two distinct display modes:
 * - RETRO: Classic chunky/pixel-like nostalgic 3-band peak waveform.
 * - DETAILED: High-resolution, multi-band, transient-dense professional DJ waveform.
 *
 * Features:
 * - Real-time horizontal scrolling with a fixed center playhead.
 * - 3-Band frequency peak colorization (Bass: Blue, Mids: Amber/Orange, Highs: Cyan/White).
 * - Full interactive scrubbing & seeking by dragging/swiping horizontally.
 * - Dynamic beat & bar grid markers derived from track BPM.
 * - Full-track overview mini-scrubber at top.
 * - Immediate track binding: stale waveform discarded on track change.
 * - Zero GC allocations per frame for 60fps performance.
 */
@Composable
fun RekordboxWaveformView(
    track: Track,
    waveformData: WaveformData?,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    durationMs: Long,
    onSeekToMs: (Long) -> Unit,
    isLoading: Boolean = false,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onToggleWaveformStyle: (() -> Unit)? = null,
    waveformShape: WaveformShape = WaveformShape.FULL,
    onToggleWaveformShape: (() -> Unit)? = null,
    currentPositionFloatProvider: (() -> Float)? = null,
    playbackTimeline: PlaybackTimeline? = null,
    showDebugSyncOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Zoom factor: visible window in seconds (e.g. 6s = zoomed in DJ view, 12s = standard, 24s = wide)
    var visibleWindowSeconds by remember { mutableFloatStateOf(8.0f) }
    var isUserDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }
    var isDebugHudVisible by remember { mutableStateOf(false) }

    val textMeasurer = rememberTextMeasurer()

    val safeDurationMs = if (durationMs > 0) durationMs else (track.durationSeconds.coerceAtLeast(10) * 1000L)

    // Strictly associate waveform data with the active track to prevent stale waveforms across track changes
    val validWaveformData = if (waveformData != null && waveformData.trackId == track.id) waveformData else null

    val theme = SoundSyncTheme.current
    val isPro = theme.isPro
    val is3BandColoring = theme.is3BandColoring

    // Authoritative timeline provider: Query real AudioClock / PlaybackTimeline
    val activeFloatProvider = currentPositionFloatProvider ?: { currentPositionProvider().toFloat() }
    var animatedPositionMs by remember(track.id) { mutableFloatStateOf(activeFloatProvider()) }

    // 60fps/120fps display refresh rate loop: Reads authoritative audio time on each vsync frame
    LaunchedEffect(isPlaying, track.id) {
        if (!isPlaying) {
            animatedPositionMs = activeFloatProvider().coerceIn(0f, safeDurationMs.toFloat())
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { _ ->
                if (!isUserDragging) {
                    animatedPositionMs = activeFloatProvider().coerceIn(0f, safeDurationMs.toFloat())
                }
            }
        }
    }

    val effectivePositionFloatMs = if (isUserDragging) dragPositionMs else animatedPositionMs
    val effectivePositionMs = effectivePositionFloatMs.toLong()

    val proVariant = if (isPro) theme.proDarkVariant ?: ProDarkVariant.BLACK_WHITE else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.cornerMedium))
            .background(theme.surface)
            .border(1.dp, theme.divider, RoundedCornerShape(theme.cornerMedium))
            .testTag("rekordbox_waveform_container")
    ) {
        // --- 1. FULL TRACK OVERVIEW MINI-SCRUBBER ---
        FullTrackOverviewScrubber(
            waveformData = validWaveformData,
            currentPositionFloatMs = effectivePositionFloatMs,
            durationMs = safeDurationMs,
            waveformStyle = waveformStyle,
            visibleWindowSeconds = visibleWindowSeconds,
            is3BandProColoring = is3BandColoring,
            isPro = isPro,
            proVariant = proVariant,
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
                .background(theme.surfaceSunken)
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
                when (waveformStyle) {
                    WaveformStyle.RETRO -> {
                        drawRetroScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            is3BandProColoring = is3BandColoring,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                    WaveformStyle.DETAILED -> {
                        drawDetailedScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            is3BandProColoring = true,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                    WaveformStyle.FREQUENCY_COLOURED -> {
                        drawDetailedScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            is3BandProColoring = false,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                    WaveformStyle.CLASSIC_AMPLITUDE -> {
                        drawClassicAmplitudeScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                    WaveformStyle.SPECTRUM_INSPIRED -> {
                        drawSpectrumInspiredScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                    WaveformStyle.DJ_OVERVIEW -> {
                        drawDjOverviewScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                    WaveformStyle.MINIMAL -> {
                        drawMinimalScrollingWaveform(
                            waveformData = validWaveformData,
                            currentPositionMs = effectivePositionFloatMs,
                            durationMs = safeDurationMs,
                            visibleWindowSeconds = visibleWindowSeconds,
                            trackBpm = track.bpm,
                            textMeasurer = textMeasurer,
                            proVariant = proVariant,
                            waveformShape = waveformShape
                        )
                    }
                }
            }

            // Fixed Center Playhead
            CenterPlayheadOverlay(isPro = isPro, proVariant = proVariant, accent = theme.accent)

            // Optional DEBUG Waveform Sync HUD Overlay
            if (showDebugSyncOverlay || isDebugHudVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = Color(0xDD000000),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            text = "SYNC DEBUG HUD",
                            color = Color(0xFF00E5FF),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        val engineMs = currentPositionProvider()
                        val waveMs = effectivePositionFloatMs
                        val discrepancyMs = waveMs - engineMs.toFloat()
                        val discColor = if (kotlin.math.abs(discrepancyMs) <= 50f) Color(0xFF00FF66) else Color(0xFFFF3B30)
                        Text(
                            text = String.format(Locale.US, "Eng: %d ms", engineMs),
                            color = Color.White,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(Locale.US, "Wave: %.1f ms", waveMs),
                            color = Color.White,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(Locale.US, "Δ: %+.1f ms", discrepancyMs),
                            color = discColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        playbackTimeline?.let { tl ->
                            Text(
                                text = String.format(Locale.US, "Rate: %.2fx", tl.playbackSpeed),
                                color = Color(0xFFFFD600),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Loading / Analyzing overlay
            if (isLoading && waveformData == null) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = DjSurfaceCard.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(if (isPro) theme.cornerSmall else 8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, (if (isPro) theme.accent else DeckACyan).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = if (isPro) theme.accent else DeckACyan, strokeWidth = 2.dp)
                        Text(
                            text = "ANALYZING REKORDBOX PEAKS...",
                            color = if (isPro) theme.accent else DeckACyan,
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
                    color = if (isPro) theme.accent else DeckBPink,
                    shape = RoundedCornerShape(if (isPro) theme.cornerSmall else 4.dp)
                ) {
                    val curSec = (effectivePositionMs / 1000).toInt()
                    val m = curSec / 60
                    val s = curSec % 60
                    val msFrac = (effectivePositionMs % 1000) / 100
                    Text(
                        text = String.format(Locale.US, "SEEK: %d:%02d.%d", m, s, msFrac),
                        color = if (isPro) theme.onAccent else Color.White,
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
            currentPositionProvider = { effectivePositionMs.toLong() },
            durationMs = safeDurationMs,
            visibleWindowSeconds = visibleWindowSeconds,
            waveformStyle = waveformStyle,
            onToggleWaveformStyle = onToggleWaveformStyle,
            waveformShape = waveformShape,
            onToggleWaveformShape = onToggleWaveformShape,
            isPro = isPro,
            proVariant = proVariant,
            accent = theme.accent,
            onToggleDebugHud = { isDebugHudVisible = !isDebugHudVisible },
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
 * Classic Retro chunky 3-band peak waveform renderer.
 * Preserves the original SoundSync chunky/pixel-like aesthetic and behavior.
 */
private fun DrawScope.drawRetroScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Float,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer,
    is3BandProColoring: Boolean = false,
    proVariant: ProDarkVariant? = null,
    waveformShape: WaveformShape = WaveformShape.FULL
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val isHalf = waveformShape == WaveformShape.HALF

    val centerY = height / 2f
    val baselineY = if (isHalf) height - 5f else centerY
    val maxPeakHeight = if (isHalf) (height - 12f) * 0.92f else centerY * 0.88f

    // Subtle center zero-axis line
    val zeroAxisColor = when (proVariant) {
        ProDarkVariant.BLACK_WHITE -> Color(0x33FFFFFF)
        ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.25f)
        null -> Color(0x3300F0FF)
    }
    drawLine(
        color = zeroAxisColor,
        start = Offset(0f, baselineY),
        end = Offset(width, baselineY),
        strokeWidth = 1f
    )

    if (durationMs <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val visibleStartMs = currentPositionMs - (centerX * msPerPixel)
    val visibleEndMs = currentPositionMs + (centerX * msPerPixel)

    // -------------------------------------------------------------
    // 1. DRAW BEAT GRID AND BAR MARKERS (If BPM is available)
    // -------------------------------------------------------------
    if (trackBpm > 40.0 && trackBpm < 250.0) {
        val beatIntervalMs = (60_000.0 / trackBpm)
        val firstBeatIndex = floor(visibleStartMs / beatIntervalMs).toInt().coerceAtLeast(0)
        val lastBeatIndex = (visibleEndMs / beatIntervalMs).toInt() + 1

        val barLineColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0x88FFFFFF)
            ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.8f)
            null -> Color(0x8800F0FF)
        }
        val barTextColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0xCCFFFFFF)
            ProDarkVariant.BLACK_RED -> BloodRedPrimary
            null -> Color(0xAA00F0FF)
        }
        val secondaryBeatColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0x22FFFFFF)
            ProDarkVariant.BLACK_RED -> Color(0x33FFFFFF)
            null -> Color(0x22FFFFFF)
        }

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
                            color = barLineColor,
                            start = Offset(beatX, 0f),
                            end = Offset(beatX, height),
                            strokeWidth = 1.5f
                        )
                        // Bar label
                        if (beatX in 0f..(width - 35f)) {
                            val barTextLayout = textMeasurer.measure(
                                text = AnnotatedString("$barNumber.1"),
                                style = TextStyle(
                                    color = barTextColor,
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
                        val tickStart = if (isHalf) baselineY - 20f else centerY - 14f
                        val tickEnd = if (isHalf) baselineY else centerY + 14f
                        drawLine(
                            color = secondaryBeatColor,
                            start = Offset(beatX, tickStart),
                            end = Offset(beatX, tickEnd),
                            strokeWidth = 1f
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------
    // 2. DRAW PEAK WAVEFORM BARS (Retro chunky blocks)
    // -------------------------------------------------------------
    if (waveformData != null && waveformData.samplePoints > 0) {
        val peaks = waveformData.peaks
        val lowBand = waveformData.lowBand
        val midBand = waveformData.midBand
        val highBand = waveformData.highBand
        val totalBins = peaks.size
        val msPerBin = durationMs.toFloat() / totalBins.toFloat()

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

                // Alpha modulation for past vs future
                val colorAlpha = if (isPast) 0.60f else 1.0f
                val barColor = when (proVariant) {
                    ProDarkVariant.BLACK_WHITE -> {
                        if (is3BandProColoring) {
                            when {
                                high > 0.45f -> Color(0xFFFFFFFF).copy(alpha = colorAlpha)
                                mid > 0.45f -> Color(0xFFD4D4D8).copy(alpha = colorAlpha)
                                low > 0.50f -> Color(0xFFA1A1AA).copy(alpha = colorAlpha)
                                else -> Color(0xFF71717A).copy(alpha = colorAlpha * 0.85f)
                            }
                        } else {
                            when {
                                high > 0.65f -> Color(0xFFFFFFFF).copy(alpha = colorAlpha)
                                low > 0.55f -> Color(0xFFA1A1AA).copy(alpha = colorAlpha)
                                mid > 0.45f -> Color(0xFFD4D4D8).copy(alpha = colorAlpha)
                                else -> Color(0xFFE4E4E7).copy(alpha = colorAlpha * 0.85f)
                            }
                        }
                    }
                    ProDarkVariant.BLACK_RED -> {
                        if (is3BandProColoring) {
                            when {
                                low > 0.50f -> BloodRedPrimary.copy(alpha = colorAlpha)
                                mid > 0.45f -> Color(0xFFD4D4D8).copy(alpha = colorAlpha)
                                high > 0.45f -> Color(0xFFFFFFFF).copy(alpha = colorAlpha)
                                else -> Color(0xFF817477).copy(alpha = colorAlpha * 0.85f)
                            }
                        } else {
                            if (isPast) {
                                BloodRedPrimary.copy(alpha = colorAlpha)
                            } else {
                                when {
                                    high > 0.65f -> Color(0xFFFFFFFF).copy(alpha = colorAlpha)
                                    low > 0.55f -> Color(0xFF817477).copy(alpha = colorAlpha)
                                    mid > 0.45f -> Color(0xFFB7AAAC).copy(alpha = colorAlpha)
                                    else -> Color(0xFF554A4D).copy(alpha = colorAlpha * 0.85f)
                                }
                            }
                        }
                    }
                    null -> {
                        if (is3BandProColoring) {
                            when {
                                low > 0.50f -> Color(0xFFFF3B30).copy(alpha = colorAlpha) // Rekordbox Pro Low: Red
                                mid > 0.45f -> Color(0xFF30D158).copy(alpha = colorAlpha) // Rekordbox Pro Mid: Green
                                high > 0.45f -> Color(0xFF00E5FF).copy(alpha = colorAlpha) // Rekordbox Pro High: Cyan
                                else -> Color(0xFF30D158).copy(alpha = colorAlpha * 0.85f)
                            }
                        } else {
                            when {
                                high > 0.65f -> Color(0xFF00FFFF).copy(alpha = colorAlpha) // Bright Cyan Transient
                                low > 0.55f -> Color(0xFF1E6CFF).copy(alpha = colorAlpha)  // Deep Bass Blue
                                mid > 0.45f -> Color(0xFFFF9500).copy(alpha = colorAlpha)  // Vibrant Vocal/Snare Amber
                                else -> Color(0xFF00C8FF).copy(alpha = colorAlpha * 0.85f)
                            }
                        }
                    }
                }

                if (isHalf) {
                    // HALF MODE: Rising upward from bottom baseline
                    drawLine(
                        color = barColor,
                        start = Offset(screenX, baselineY - barHalfHeight),
                        end = Offset(screenX, baselineY),
                        strokeWidth = 2.2f
                    )

                    // High transient tip highlight
                    if (high > 0.70f && peak > 0.3f) {
                        val tipColor = if (proVariant == ProDarkVariant.BLACK_RED) {
                            BloodRedPrimary.copy(alpha = colorAlpha * 0.9f)
                        } else {
                            Color.White.copy(alpha = colorAlpha * 0.9f)
                        }
                        drawCircle(
                            color = tipColor,
                            radius = 1.2f,
                            center = Offset(screenX, baselineY - barHalfHeight)
                        )
                    }
                } else {
                    // FULL MODE: Mirrored vertical bar across center
                    drawLine(
                        color = barColor,
                        start = Offset(screenX, centerY - barHalfHeight),
                        end = Offset(screenX, centerY + barHalfHeight),
                        strokeWidth = 2.2f
                    )

                    // High transient tip highlight
                    if (high > 0.70f && peak > 0.3f) {
                        val tipColor = if (proVariant == ProDarkVariant.BLACK_RED) {
                            BloodRedPrimary.copy(alpha = colorAlpha * 0.9f)
                        } else {
                            Color.White.copy(alpha = colorAlpha * 0.9f)
                        }
                        drawCircle(
                            color = tipColor,
                            radius = 1.2f,
                            center = Offset(screenX, centerY - barHalfHeight)
                        )
                        drawCircle(
                            color = tipColor,
                            radius = 1.2f,
                            center = Offset(screenX, centerY + barHalfHeight)
                        )
                    }
                }
            }
        }
    } else {
        // Subtle fallback placeholder bars while loading
        val step = 6f
        val count = (width / step).toInt()
        val fallbackColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0x33FFFFFF)
            ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.3f)
            null -> Color(0x3300F0FF)
        }
        for (i in 0 until count) {
            val sx = i * step
            if (isHalf) {
                drawLine(
                    color = fallbackColor,
                    start = Offset(sx, baselineY - 10f),
                    end = Offset(sx, baselineY),
                    strokeWidth = 2f
                )
            } else {
                drawLine(
                    color = fallbackColor,
                    start = Offset(sx, centerY - 6f),
                    end = Offset(sx, centerY + 6f),
                    strokeWidth = 2f
                )
            }
        }
    }
}

/**
 * Backward compatibility alias for [drawRetroScrollingWaveform].
 */
@Suppress("unused")
private fun DrawScope.drawRekordboxScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionProvider: () -> Long,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer
) = drawRetroScrollingWaveform(waveformData, currentPositionProvider().toFloat(), durationMs, visibleWindowSeconds, trackBpm, textMeasurer)

/**
 * Professional high-resolution 60fps Canvas renderer for Detailed Waveform Mode.
 * Features:
 * - High horizontal sampling (1-pixel column density) capturing small transients (kicks, snares, hi-hats, percussive peaks).
 * - Multi-band stacked spectral layering (Bass, Mid, High).
 * - Full dynamic range preservation: quiet sections retain delicate musical detail without flattening.
 * - Accurate audio synchronization: maps precisely to true decoded timeline with zero drift.
 * - Zero GC allocations inside render loop for silky-smooth 60fps scrolling.
 */
private fun DrawScope.drawDetailedScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Float,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer,
    is3BandProColoring: Boolean = false,
    proVariant: ProDarkVariant? = null,
    waveformShape: WaveformShape = WaveformShape.FULL
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val isHalf = waveformShape == WaveformShape.HALF

    val centerY = height / 2f
    val baselineY = if (isHalf) height - 5f else centerY
    val maxPeakHeight = if (isHalf) (height - 12f) * 0.94f else centerY * 0.90f

    // Grid / amplitude reference lines
    val gridColor = when (proVariant) {
        ProDarkVariant.BLACK_WHITE -> Color(0x18FFFFFF)
        ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.15f)
        null -> Color(0x1000F0FF)
    }
    if (isHalf) {
        val halfRefY1 = baselineY - maxPeakHeight * 0.50f
        val halfRefY2 = baselineY - maxPeakHeight * 0.80f
        drawLine(color = gridColor, start = Offset(0f, halfRefY1), end = Offset(width, halfRefY1), strokeWidth = 1f)
        drawLine(color = gridColor, start = Offset(0f, halfRefY2), end = Offset(width, halfRefY2), strokeWidth = 1f)
    } else {
        val halfRefY1 = centerY - maxPeakHeight * 0.50f
        val halfRefY2 = centerY + maxPeakHeight * 0.50f
        drawLine(color = gridColor, start = Offset(0f, halfRefY1), end = Offset(width, halfRefY1), strokeWidth = 1f)
        drawLine(color = gridColor, start = Offset(0f, halfRefY2), end = Offset(width, halfRefY2), strokeWidth = 1f)
    }

    // Zero-axis hairline
    val zeroAxisColor = when (proVariant) {
        ProDarkVariant.BLACK_WHITE -> Color(0x38FFFFFF)
        ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.35f)
        null -> Color(0x3800F0FF)
    }
    drawLine(
        color = zeroAxisColor,
        start = Offset(0f, baselineY),
        end = Offset(width, baselineY),
        strokeWidth = 1f
    )

    if (durationMs <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val visibleStartMs = currentPositionMs - (centerX * msPerPixel)
    val visibleEndMs = currentPositionMs + (centerX * msPerPixel)

    // 1. BEAT GRID AND BAR MARKERS (From track BPM)
    if (trackBpm > 40.0 && trackBpm < 250.0) {
        val beatIntervalMs = (60_000.0 / trackBpm)
        val firstBeatIndex = floor(visibleStartMs / beatIntervalMs).toInt().coerceAtLeast(0)
        val lastBeatIndex = (visibleEndMs / beatIntervalMs).toInt() + 1

        val barLineColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0x99FFFFFF)
            ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.85f)
            null -> Color(0x9900F0FF)
        }
        val barTextColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0xCCFFFFFF)
            ProDarkVariant.BLACK_RED -> BloodRedPrimary
            null -> Color(0xCC00F0FF)
        }
        val secondaryBeatColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0x28FFFFFF)
            ProDarkVariant.BLACK_RED -> Color(0x33FFFFFF)
            null -> Color(0x28FFFFFF)
        }

        for (beatIdx in firstBeatIndex..lastBeatIndex) {
            val beatTimeMs = (beatIdx * beatIntervalMs).toLong()
            if (beatTimeMs in 0..durationMs) {
                val beatX = centerX + ((beatTimeMs - currentPositionMs) / msPerPixel)
                if (beatX in -20f..(width + 20f)) {
                    val isBarDownbeat = (beatIdx % 4 == 0)
                    val barNumber = (beatIdx / 4) + 1

                    if (isBarDownbeat) {
                        // Downbeat measure line
                        drawLine(
                            color = barLineColor,
                            start = Offset(beatX, 0f),
                            end = Offset(beatX, height),
                            strokeWidth = 1.5f
                        )
                        // Bar label
                        if (beatX in 0f..(width - 35f)) {
                            val barTextLayout = textMeasurer.measure(
                                text = AnnotatedString("$barNumber.1"),
                                style = TextStyle(
                                    color = barTextColor,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            drawText(
                                textLayoutResult = barTextLayout,
                                topLeft = Offset(beatX + 3f, 3f)
                            )
                        }
                    } else {
                        // Secondary beats (2, 3, 4) tick marks
                        val tickStart = if (isHalf) baselineY - 18f else centerY - 12f
                        val tickEnd = if (isHalf) baselineY else centerY + 12f
                        drawLine(
                            color = secondaryBeatColor,
                            start = Offset(beatX, tickStart),
                            end = Offset(beatX, tickEnd),
                            strokeWidth = 1f
                        )
                    }
                }
            }
        }
    }

    // 2. HIGH-RESOLUTION MULTI-BAND DETAILED WAVEFORM
    // Renders discrete needle-thin vertical peak strokes (step = 1.8f, strokeWidth = 1.3f)
    // preserving genuine transients and peak envelopes across all zoom levels
    if (waveformData != null && waveformData.samplePoints > 0) {
        val peaks = waveformData.peaks
        val minPeaks = waveformData.minPeaks
        val hasMinPeaks = minPeaks.isNotEmpty()
        val lowBand = waveformData.lowBand
        val midBand = waveformData.midBand
        val highBand = waveformData.highBand
        val totalBins = peaks.size
        val msPerBin = durationMs.toFloat() / totalBins.toFloat()

        val stepPixels = 1.8f
        val strokeW = 1.3f
        val halfStep = stepPixels * 0.5f
        val numCols = (width / stepPixels).toInt() + 1

        for (col in 0 until numCols) {
            val screenX = col * stepPixels
            val deltaFromCenterPx = screenX - centerX
            val sampleCenterMs = currentPositionMs + (deltaFromCenterPx * msPerPixel)
            val tStartMs = sampleCenterMs - (halfStep * msPerPixel)
            val tEndMs = sampleCenterMs + (halfStep * msPerPixel)

            if (tEndMs < 0f || tStartMs > durationMs.toFloat()) continue

            val bStart = (tStartMs / msPerBin).toInt().coerceIn(0, totalBins - 1)
            val bEnd = (tEndMs / msPerBin).toInt().coerceIn(0, totalBins - 1)

            var peak: Float
            var rawMinPeak: Float
            var low: Float
            var mid: Float
            var high: Float

            if (bStart == bEnd) {
                // High zoom: Individual bin resolution
                peak = peaks[bStart]
                rawMinPeak = if (hasMinPeaks) kotlin.math.abs(minPeaks[bStart]) else peak
                low = lowBand[bStart]
                mid = midBand[bStart]
                high = highBand[bStart]
            } else {
                // Multiresolution peak envelope preservation: find peak values across bin range
                val firstB = min(bStart, bEnd)
                val lastB = max(bStart, bEnd)
                peak = 0.0f
                rawMinPeak = 0.0f
                low = 0.0f
                mid = 0.0f
                high = 0.0f
                for (b in firstB..lastB) {
                    val p = peaks[b]
                    if (p > peak) peak = p
                    val mp = if (hasMinPeaks) kotlin.math.abs(minPeaks[b]) else p
                    if (mp > rawMinPeak) rawMinPeak = mp
                    val l = lowBand[b]
                    if (l > low) low = l
                    val m = midBand[b]
                    if (m > mid) mid = m
                    val h = highBand[b]
                    if (h > high) high = h
                }
            }

            // Safe effective negative peak: supports bipolar signals and safely mirrors legacy/unipolar data
            val effectiveMinPeak = if (rawMinPeak < 0.008f && peak >= 0.008f) {
                peak * 0.92f
            } else {
                rawMinPeak
            }

            val isPast = sampleCenterMs < currentPositionMs
            val alpha = if (isPast) 0.65f else 1.0f

            val peakTop = if (peak < 0.008f) 0.5f else (peak * maxPeakHeight).coerceAtLeast(1.0f)
            val midTop = min(peakTop, (mid * maxPeakHeight * 0.88f).coerceAtLeast(0.5f))
            val lowTop = min(midTop, (low * maxPeakHeight * 0.65f).coerceAtLeast(0.5f))

            // Frequency colors
            val highColor = when (proVariant) {
                ProDarkVariant.BLACK_WHITE -> {
                    if (high > 0.60f) Color(0xFFFFFFFF).copy(alpha = alpha) else Color(0xFFE4E4E7).copy(alpha = alpha * 0.90f)
                }
                ProDarkVariant.BLACK_RED -> {
                    if (high > 0.60f) Color(0xFFFFFFFF).copy(alpha = alpha) else Color(0xFFD4D4D8).copy(alpha = alpha * 0.90f)
                }
                null -> {
                    if (is3BandProColoring) {
                        Color(0xFF00E5FF).copy(alpha = alpha)
                    } else {
                        if (high > 0.60f) Color(0xFF00FFFF).copy(alpha = alpha) else Color(0xFF00C8FF).copy(alpha = alpha * 0.90f)
                    }
                }
            }

            val midColor = when (proVariant) {
                ProDarkVariant.BLACK_WHITE -> {
                    if (mid > 0.60f) Color(0xFFD4D4D8).copy(alpha = alpha * 0.95f) else Color(0xFFA1A1AA).copy(alpha = alpha * 0.85f)
                }
                ProDarkVariant.BLACK_RED -> {
                    if (mid > 0.60f) Color(0xFFB7AAAC).copy(alpha = alpha * 0.95f) else Color(0xFF817477).copy(alpha = alpha * 0.85f)
                }
                null -> {
                    if (is3BandProColoring) {
                        Color(0xFFFFF6D6).copy(alpha = alpha * 0.95f)
                    } else {
                        Color(0xFF30D158).copy(alpha = alpha * 0.95f) // Green in RGB mode
                    }
                }
            }

            val bassColor = when (proVariant) {
                ProDarkVariant.BLACK_WHITE -> {
                    if (low > 0.65f) Color(0xFFA1A1AA).copy(alpha = alpha) else Color(0xFF71717A).copy(alpha = alpha * 0.92f)
                }
                ProDarkVariant.BLACK_RED -> {
                    if (low > 0.65f) BloodRedPrimary.copy(alpha = alpha) else BloodRedPrimary.copy(alpha = alpha * 0.75f)
                }
                null -> {
                    if (is3BandProColoring) {
                        Color(0xFFFF6600).copy(alpha = alpha)
                    } else {
                        Color(0xFFFF3B30).copy(alpha = alpha) // Red in RGB mode
                    }
                }
            }

            if (isHalf) {
                // HALF MODE: Single-sided upward peaks rising from bottom baseline
                // Layer 1: Outer Highs
                drawLine(
                    color = highColor,
                    start = Offset(screenX, baselineY - peakTop),
                    end = Offset(screenX, baselineY),
                    strokeWidth = strokeW
                )

                // Layer 2: Mids Melodic / Vocal Body
                if (midTop > 0.8f) {
                    drawLine(
                        color = midColor,
                        start = Offset(screenX, baselineY - midTop),
                        end = Offset(screenX, baselineY),
                        strokeWidth = strokeW
                    )
                }

                // Layer 3: Inner Bass / Kick Core
                if (lowTop > 0.8f) {
                    drawLine(
                        color = bassColor,
                        start = Offset(screenX, baselineY - lowTop),
                        end = Offset(screenX, baselineY),
                        strokeWidth = strokeW
                    )
                }

                // Sharp transient diamond tip for prominent percussion hits
                if (high > 0.68f && peak > 0.35f) {
                    val diamondColor = if (proVariant == ProDarkVariant.BLACK_RED) {
                        BloodRedPrimary.copy(alpha = alpha * 0.95f)
                    } else {
                        Color.White.copy(alpha = alpha * 0.95f)
                    }
                    drawCircle(
                        color = diamondColor,
                        radius = 0.9f,
                        center = Offset(screenX, baselineY - peakTop)
                    )
                }
            } else {
                // FULL MODE: Vertically centred bipolar waveform across center axis
                val peakBot = if (effectiveMinPeak < 0.008f) 0.5f else (effectiveMinPeak * maxPeakHeight).coerceAtLeast(1.0f)
                val midBot = min(peakBot, (mid * maxPeakHeight * 0.88f).coerceAtLeast(0.5f))
                val lowBot = min(midBot, (low * maxPeakHeight * 0.65f).coerceAtLeast(0.5f))

                // Layer 1: Outer Highs (Cyan transients)
                drawLine(
                    color = highColor,
                    start = Offset(screenX, centerY - peakTop),
                    end = Offset(screenX, centerY + peakBot),
                    strokeWidth = strokeW
                )

                // Layer 2: Mids Melodic / Vocal Body
                if (midTop > 0.8f || midBot > 0.8f) {
                    drawLine(
                        color = midColor,
                        start = Offset(screenX, centerY - midTop),
                        end = Offset(screenX, centerY + midBot),
                        strokeWidth = strokeW
                    )
                }

                // Layer 3: Inner Bass / Kick Core
                if (lowTop > 0.8f || lowBot > 0.8f) {
                    drawLine(
                        color = bassColor,
                        start = Offset(screenX, centerY - lowTop),
                        end = Offset(screenX, centerY + lowBot),
                        strokeWidth = strokeW
                    )
                }

                // Sharp transient diamond tips
                if (high > 0.68f && peak > 0.35f) {
                    val diamondColor = if (proVariant == ProDarkVariant.BLACK_RED) {
                        BloodRedPrimary.copy(alpha = alpha * 0.95f)
                    } else {
                        Color.White.copy(alpha = alpha * 0.95f)
                    }
                    drawCircle(
                        color = diamondColor,
                        radius = 0.9f,
                        center = Offset(screenX, centerY - peakTop)
                    )
                    drawCircle(
                        color = diamondColor,
                        radius = 0.9f,
                        center = Offset(screenX, centerY + peakBot)
                    )
                }
            }
        }
    } else {
        // High-density loading placeholder lines
        val step = 3f
        val count = (width / step).toInt()
        val fallbackLineColor = when (proVariant) {
            ProDarkVariant.BLACK_WHITE -> Color(0x22FFFFFF)
            ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.22f)
            null -> Color(0x2200F0FF)
        }
        for (i in 0 until count) {
            val sx = i * step
            if (isHalf) {
                drawLine(
                    color = fallbackLineColor,
                    start = Offset(sx, baselineY - 8f),
                    end = Offset(sx, baselineY),
                    strokeWidth = 1f
                )
            } else {
                drawLine(
                    color = fallbackLineColor,
                    start = Offset(sx, centerY - 4f),
                    end = Offset(sx, centerY + 4f),
                    strokeWidth = 1f
                )
            }
        }
    }
}

/**
 * Fixed Center Playhead Overlay with top/bottom neon glow indicators.
 */
@Composable
private fun CenterPlayheadOverlay(
    isPro: Boolean = false,
    proVariant: ProDarkVariant? = null,
    accent: Color = Color(0xFF1E6CFF)
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val height = size.height

        if (isPro) {
            val playheadColor = when (proVariant) {
                ProDarkVariant.BLACK_WHITE -> Color.White
                ProDarkVariant.BLACK_RED -> BloodRedPrimary
                null -> accent
            }
            // Sleek Rekordbox needle playhead
            drawLine(
                color = playheadColor.copy(alpha = 0.40f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, height),
                strokeWidth = 3f
            )
            drawLine(
                color = if (proVariant == ProDarkVariant.BLACK_RED) Color.White else playheadColor,
                start = Offset(centerX, 0f),
                end = Offset(centerX, height),
                strokeWidth = 1.2f
            )
            reusableTopPlayheadPath.apply {
                rewind()
                moveTo(centerX - 4.5f, 0f)
                lineTo(centerX + 4.5f, 0f)
                lineTo(centerX, 6f)
                close()
            }
            drawPath(reusableTopPlayheadPath, playheadColor)
            reusableBotPlayheadPath.apply {
                rewind()
                moveTo(centerX - 4.5f, height)
                lineTo(centerX + 4.5f, height)
                lineTo(centerX, height - 6f)
                close()
            }
            drawPath(reusableBotPlayheadPath, playheadColor)
        } else {
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
            reusableTopPlayheadPath.apply {
                rewind()
                moveTo(centerX - 6f, 0f)
                lineTo(centerX + 6f, 0f)
                lineTo(centerX, 8f)
                close()
            }
            drawPath(reusableTopPlayheadPath, Color(0xFFFF0055))

            // Bottom pointer triangle
            reusableBotPlayheadPath.apply {
                rewind()
                moveTo(centerX - 6f, height)
                lineTo(centerX + 6f, height)
                lineTo(centerX, height - 8f)
                close()
            }
            drawPath(reusableBotPlayheadPath, Color(0xFFFF0055))
        }
    }
}

/**
 * Full track overview miniature waveform scrubber.
 */
@Composable
private fun FullTrackOverviewScrubber(
    waveformData: WaveformData?,
    currentPositionFloatMs: Float,
    durationMs: Long,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    visibleWindowSeconds: Float = 8.0f,
    is3BandProColoring: Boolean = false,
    isPro: Boolean = false,
    proVariant: ProDarkVariant? = null,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isPro) {
        SoundSyncTheme.current.surfaceSunken
    } else {
        Color(0xFF0A0A0C)
    }
    Box(
        modifier = modifier
            .background(bgColor)
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
                val lowBand = waveformData.lowBand
                val midBand = waveformData.midBand
                val highBand = waveformData.highBand
                val step = max(1f, width / peaks.size.toFloat())
                val progressFrac = if (durationMs > 0) (currentPositionFloatMs / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                val playheadX = progressFrac * width

                // Viewport window overlay bounds (8s visible window relative to full track duration)
                val windowWidthMs = visibleWindowSeconds * 1000f
                val windowFrac = if (durationMs > 0) (windowWidthMs / durationMs.toFloat()).coerceIn(0.01f, 1.0f) else 0.1f
                val windowWidthPx = (windowFrac * width).coerceAtLeast(14f)
                val windowLeft = (playheadX - windowWidthPx / 2f).coerceIn(0f, (width - windowWidthPx).coerceAtLeast(0f))
                val windowRight = windowLeft + windowWidthPx

                val colWidth = max(1f, step - 0.2f)

                for (i in peaks.indices) {
                    val x = i * step
                    val peak = peaks[i].coerceIn(0f, 1f)
                    val low = lowBand.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    val mid = midBand.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    val high = highBand.getOrElse(i) { 0f }.coerceIn(0f, 1f)

                    val barH = if (peak < 0.012f) 0.5f else (peak * (centerY * 0.88f)).coerceAtLeast(1.0f)
                    val isPast = x <= playheadX
                    val alpha = if (isPast) 1.0f else 0.45f

                    // Continuous Rekordbox-grade 3-band layering (No discrete barcode striping!)
                    if (waveformStyle == WaveformStyle.RETRO) {
                        // Retro overview mode
                        val retroColor = when (proVariant) {
                            ProDarkVariant.BLACK_WHITE -> if (isPast) Color(0xFFFFFFFF) else Color(0xFF52525B)
                            ProDarkVariant.BLACK_RED -> if (isPast) BloodRedPrimary else Color(0xFF554A4D)
                            null -> if (isPast) DeckACyan else Color(0xFF2A364F)
                        }
                        drawLine(
                            color = retroColor.copy(alpha = alpha),
                            start = Offset(x, centerY - barH),
                            end = Offset(x, centerY + barH),
                            strokeWidth = colWidth
                        )
                    } else {
                        // Continuous stacked 3Band overview: High outer, Mid body, Low bass core
                        val midH = (barH * (0.35f + 0.60f * mid)).coerceIn(0.5f, barH)
                        val lowH = (barH * (0.25f + 0.55f * low)).coerceIn(0.5f, midH)

                        val highColor = when (proVariant) {
                            ProDarkVariant.BLACK_WHITE -> Color(0xFFFFFFFF).copy(alpha = alpha)
                            ProDarkVariant.BLACK_RED -> Color(0xFFFFFFFF).copy(alpha = alpha)
                            null -> if (waveformStyle == WaveformStyle.FREQUENCY_COLOURED) Color(0xFF00E5FF).copy(alpha = alpha) else Color(0xFF00E5FF).copy(alpha = alpha)
                        }
                        drawLine(
                            color = highColor,
                            start = Offset(x, centerY - barH),
                            end = Offset(x, centerY + barH),
                            strokeWidth = colWidth
                        )

                        if (midH > 0.8f) {
                            val midColor = when (proVariant) {
                                ProDarkVariant.BLACK_WHITE -> Color(0xFFD4D4D8).copy(alpha = alpha * 0.95f)
                                ProDarkVariant.BLACK_RED -> Color(0xFFB7AAAC).copy(alpha = alpha * 0.95f)
                                null -> if (waveformStyle == WaveformStyle.FREQUENCY_COLOURED) Color(0xFF30D158).copy(alpha = alpha * 0.95f) else Color(0xFFFFF6D6).copy(alpha = alpha * 0.95f)
                            }
                            drawLine(
                                color = midColor,
                                start = Offset(x, centerY - midH),
                                end = Offset(x, centerY + midH),
                                strokeWidth = colWidth
                            )
                        }

                        if (lowH > 0.8f) {
                            val lowColor = when (proVariant) {
                                ProDarkVariant.BLACK_WHITE -> Color(0xFFA1A1AA).copy(alpha = alpha)
                                ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = alpha)
                                null -> if (waveformStyle == WaveformStyle.FREQUENCY_COLOURED) Color(0xFFFF3B30).copy(alpha = alpha) else Color(0xFFFF6600).copy(alpha = alpha)
                            }
                            drawLine(
                                color = lowColor,
                                start = Offset(x, centerY - lowH),
                                end = Offset(x, centerY + lowH),
                                strokeWidth = colWidth
                            )
                        }
                    }
                }

                // Viewport Zoom Window Overlay (Semi-transparent highlighted box)
                drawRect(
                    color = Color(0x24FFFFFF),
                    topLeft = Offset(windowLeft, 0f),
                    size = Size(windowRight - windowLeft, height)
                )
                drawRect(
                    color = Color(0x66FFFFFF),
                    topLeft = Offset(windowLeft, 0f),
                    size = Size(windowRight - windowLeft, height),
                    style = Stroke(width = 1f)
                )

                // Authoritative Playhead Needle
                val playheadColor = when (proVariant) {
                    ProDarkVariant.BLACK_WHITE -> Color.White
                    ProDarkVariant.BLACK_RED -> BloodRedPrimary
                    null -> Color(0xFFFF0055)
                }
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, height),
                    strokeWidth = 2f
                )
                // Top pointer chevron
                reusableTopPlayheadPath.apply {
                    rewind()
                    moveTo(playheadX - 3.5f, 0f)
                    lineTo(playheadX + 3.5f, 0f)
                    lineTo(playheadX, 4.5f)
                    close()
                }
                drawPath(reusableTopPlayheadPath, playheadColor)
            } else {
                // Flat baseline
                val baselineColor = when (proVariant) {
                    ProDarkVariant.BLACK_WHITE -> Color(0x33FFFFFF)
                    ProDarkVariant.BLACK_RED -> BloodRedPrimary.copy(alpha = 0.35f)
                    null -> Color(0x3300F0FF)
                }
                drawLine(
                    color = baselineColor,
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
    currentPositionProvider: () -> Long,
    durationMs: Long,
    visibleWindowSeconds: Float,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onToggleWaveformStyle: (() -> Unit)? = null,
    waveformShape: WaveformShape = WaveformShape.FULL,
    onToggleWaveformShape: (() -> Unit)? = null,
    isPro: Boolean = false,
    proVariant: ProDarkVariant? = null,
    accent: Color = DeckACyan,
    onToggleDebugHud: (() -> Unit)? = null,
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
                val bpmColor = if (isPro) accent else DeckACyan
                Surface(
                    shape = RoundedCornerShape(if (isPro) 2.dp else 4.dp),
                    color = bpmColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, bpmColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = if (track.bpm > 0) String.format(Locale.US, "%.1f BPM", track.bpm) else "AUTO BPM",
                        color = bpmColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Camelot Key Badge
                val keyColor = if (isPro) {
                    when (proVariant) {
                        ProDarkVariant.BLACK_WHITE -> Color(0xFFD4D4D8)
                        ProDarkVariant.BLACK_RED -> BloodRedPrimary
                        null -> Color(0xFF3B7FFF)
                    }
                } else DeckBPink
                Surface(
                    shape = RoundedCornerShape(if (isPro) 2.dp else 4.dp),
                    color = keyColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, keyColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "KEY ${track.musicalKey}",
                        color = keyColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Waveform Style Mode Badge (Clickable to switch between styles)
                val badgeColor = if (isPro) accent else when (waveformStyle) {
                    WaveformStyle.DETAILED -> DeckACyan
                    WaveformStyle.RETRO -> NeonAmber
                    WaveformStyle.CLASSIC_AMPLITUDE -> Color(0xFF64B5F6)
                    WaveformStyle.FREQUENCY_COLOURED -> Color(0xFFFF5252)
                    WaveformStyle.SPECTRUM_INSPIRED -> Color(0xFFE040FB)
                    WaveformStyle.DJ_OVERVIEW -> Color(0xFFFFD600)
                    WaveformStyle.MINIMAL -> Color(0xFFB0BEC5)
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeColor.copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        badgeColor.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            if (onToggleWaveformStyle != null) {
                                Modifier.clickable(onClick = onToggleWaveformStyle)
                            } else Modifier
                        )
                        .testTag("toggle_waveform_style_badge")
                ) {
                    Text(
                        text = waveformStyle.shortName.uppercase(),
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Waveform Shape Mode Badge (Clickable to switch between FULL and HALF)
                val shapeBadgeColor = if (isPro) accent else when (waveformShape) {
                    WaveformShape.FULL -> Color(0xFF00E5FF)
                    WaveformShape.HALF -> Color(0xFFFF9500)
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = shapeBadgeColor.copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        shapeBadgeColor.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            if (onToggleWaveformShape != null) {
                                Modifier.clickable(onClick = onToggleWaveformShape)
                            } else Modifier
                        )
                        .testTag("toggle_waveform_shape_badge")
                ) {
                    Text(
                        text = waveformShape.shortName.uppercase(),
                        color = shapeBadgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.then(
                        if (onToggleDebugHud != null) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { onToggleDebugHud() })
                            }
                        } else Modifier
                    )
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

private fun DrawScope.drawClassicAmplitudeScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Float,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer,
    proVariant: ProDarkVariant? = null,
    waveformShape: WaveformShape = WaveformShape.FULL
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val isHalf = waveformShape == WaveformShape.HALF

    val centerY = height / 2f
    val baselineY = if (isHalf) height - 5f else centerY
    val maxPeakHeight = if (isHalf) (height - 12f) * 0.92f else centerY * 0.90f

    // Draw baseline / center line
    drawLine(
        color = Color(0x3364B5F6),
        start = Offset(0f, baselineY),
        end = Offset(width, baselineY),
        strokeWidth = 1f
    )

    if (durationMs <= 0 || waveformData == null || waveformData.samplePoints <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val totalSamples = waveformData.samplePoints
    val msPerSample = durationMs.toFloat() / totalSamples.toFloat()

    val barWidth = 2.0f
    val stepPx = 2.5f
    val barsCount = (width / stepPx).toInt()

    for (b in 0 until barsCount) {
        val screenX = b * stepPx
        val targetMs = currentPositionMs + ((screenX - centerX) * msPerPixel)
        if (targetMs < 0 || targetMs > durationMs) continue

        val sampleIdx = (targetMs / msPerSample).toInt().coerceIn(0, totalSamples - 1)
        val peak = waveformData.peaks.getOrElse(sampleIdx) { 0f }
        val barHeight = (peak * maxPeakHeight).coerceAtLeast(1.5f)

        val isPast = screenX <= centerX
        val color = if (isPast) Color(0xFF64B5F6) else Color(0xFF90CAF9).copy(alpha = 0.55f)

        if (isHalf) {
            drawLine(
                color = color,
                start = Offset(screenX, baselineY - barHeight),
                end = Offset(screenX, baselineY),
                strokeWidth = barWidth
            )
        } else {
            drawLine(
                color = color,
                start = Offset(screenX, centerY - barHeight),
                end = Offset(screenX, centerY + barHeight),
                strokeWidth = barWidth
            )
        }
    }
}

private fun DrawScope.drawSpectrumInspiredScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Float,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer,
    proVariant: ProDarkVariant? = null,
    waveformShape: WaveformShape = WaveformShape.FULL
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val isHalf = waveformShape == WaveformShape.HALF

    val centerY = height / 2f
    val baselineY = if (isHalf) height - 5f else centerY
    val maxPeakHeight = if (isHalf) (height - 12f) * 0.92f else centerY * 0.90f

    // Draw baseline / center line
    drawLine(
        color = Color(0x33E040FB),
        start = Offset(0f, baselineY),
        end = Offset(width, baselineY),
        strokeWidth = 1f
    )

    if (durationMs <= 0 || waveformData == null || waveformData.samplePoints <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val totalSamples = waveformData.samplePoints
    val msPerSample = durationMs.toFloat() / totalSamples.toFloat()

    val stepPx = 3f
    val barsCount = (width / stepPx).toInt()

    for (b in 0 until barsCount) {
        val screenX = b * stepPx
        val targetMs = currentPositionMs + ((screenX - centerX) * msPerPixel)
        if (targetMs < 0 || targetMs > durationMs) continue

        val sampleIdx = (targetMs / msPerSample).toInt().coerceIn(0, totalSamples - 1)
        val low = waveformData.lowBand.getOrElse(sampleIdx) { 0f }
        val mid = waveformData.midBand.getOrElse(sampleIdx) { 0f }
        val high = waveformData.highBand.getOrElse(sampleIdx) { 0f }
        val peak = waveformData.peaks.getOrElse(sampleIdx) { 0f }

        val isPast = screenX <= centerX
        val alpha = if (isPast) 0.95f else 0.45f

        // Stacked spectral layers: Bass inner, Mids middle, Highs outer
        val lowH = (low * maxPeakHeight * 0.5f).coerceAtLeast(1f)
        val midH = (mid * maxPeakHeight * 0.75f).coerceAtLeast(lowH)
        val highH = (peak * maxPeakHeight).coerceAtLeast(midH)

        if (isHalf) {
            // Outer (Highs - Cyan/Purple)
            drawLine(
                color = Color(0xFFE040FB).copy(alpha = alpha * 0.8f),
                start = Offset(screenX, baselineY - highH),
                end = Offset(screenX, baselineY),
                strokeWidth = 2.2f
            )
            // Middle (Mids - Amber)
            drawLine(
                color = Color(0xFFFF9100).copy(alpha = alpha * 0.9f),
                start = Offset(screenX, baselineY - midH),
                end = Offset(screenX, baselineY),
                strokeWidth = 2.2f
            )
            // Inner (Lows - Deep Blue/Red)
            drawLine(
                color = Color(0xFF00E5FF).copy(alpha = alpha),
                start = Offset(screenX, baselineY - lowH),
                end = Offset(screenX, baselineY),
                strokeWidth = 2.2f
            )
        } else {
            // Outer (Highs - Cyan/Purple)
            drawLine(
                color = Color(0xFFE040FB).copy(alpha = alpha * 0.8f),
                start = Offset(screenX, centerY - highH),
                end = Offset(screenX, centerY + highH),
                strokeWidth = 2.2f
            )
            // Middle (Mids - Amber)
            drawLine(
                color = Color(0xFFFF9100).copy(alpha = alpha * 0.9f),
                start = Offset(screenX, centerY - midH),
                end = Offset(screenX, centerY + midH),
                strokeWidth = 2.2f
            )
            // Inner (Lows - Deep Blue/Red)
            drawLine(
                color = Color(0xFF00E5FF).copy(alpha = alpha),
                start = Offset(screenX, centerY - lowH),
                end = Offset(screenX, centerY + lowH),
                strokeWidth = 2.2f
            )
        }
    }
}

private fun DrawScope.drawDjOverviewScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Float,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer,
    proVariant: ProDarkVariant? = null,
    waveformShape: WaveformShape = WaveformShape.FULL
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val isHalf = waveformShape == WaveformShape.HALF

    val centerY = height / 2f
    val baselineY = if (isHalf) height - 5f else centerY
    val maxPeakHeight = if (isHalf) (height - 12f) * 0.94f else centerY * 0.92f

    // Draw baseline / center line
    drawLine(
        color = Color(0x33FFD600),
        start = Offset(0f, baselineY),
        end = Offset(width, baselineY),
        strokeWidth = 1f
    )

    if (durationMs <= 0 || waveformData == null || waveformData.samplePoints <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val totalSamples = waveformData.samplePoints
    val msPerSample = durationMs.toFloat() / totalSamples.toFloat()

    val stepPx = 2f
    val barsCount = (width / stepPx).toInt()

    for (b in 0 until barsCount) {
        val screenX = b * stepPx
        val targetMs = currentPositionMs + ((screenX - centerX) * msPerPixel)
        if (targetMs < 0 || targetMs > durationMs) continue

        val sampleIdx = (targetMs / msPerSample).toInt().coerceIn(0, totalSamples - 1)
        val peak = waveformData.peaks.getOrElse(sampleIdx) { 0f }
        val low = waveformData.lowBand.getOrElse(sampleIdx) { 0f }
        val barHeight = (peak * maxPeakHeight).coerceAtLeast(1.5f)

        val isPast = screenX <= centerX
        val color = if (isPast) {
            if (low > 0.5f) Color(0xFFFFD600) else Color(0xFF00E5FF)
        } else {
            Color(0xFF37474F)
        }

        if (isHalf) {
            drawLine(
                color = color,
                start = Offset(screenX, baselineY - barHeight),
                end = Offset(screenX, baselineY),
                strokeWidth = 1.8f
            )
        } else {
            drawLine(
                color = color,
                start = Offset(screenX, centerY - barHeight),
                end = Offset(screenX, centerY + barHeight),
                strokeWidth = 1.8f
            )
        }
    }
}

private fun DrawScope.drawMinimalScrollingWaveform(
    waveformData: WaveformData?,
    currentPositionMs: Float,
    durationMs: Long,
    visibleWindowSeconds: Float,
    trackBpm: Double,
    textMeasurer: TextMeasurer,
    proVariant: ProDarkVariant? = null,
    waveformShape: WaveformShape = WaveformShape.FULL
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val isHalf = waveformShape == WaveformShape.HALF

    val centerY = height / 2f
    val baselineY = if (isHalf) height - 5f else centerY
    val maxPeakHeight = if (isHalf) (height - 12f) * 0.85f else centerY * 0.70f

    // Clean center axis line
    drawLine(
        color = Color(0x22B0BEC5),
        start = Offset(0f, baselineY),
        end = Offset(width, baselineY),
        strokeWidth = 1f
    )

    if (durationMs <= 0 || waveformData == null || waveformData.samplePoints <= 0) return

    val msPerPixel = (visibleWindowSeconds * 1000f) / width
    val totalSamples = waveformData.samplePoints
    val msPerSample = durationMs.toFloat() / totalSamples.toFloat()

    val stepPx = 3f
    val barsCount = (width / stepPx).toInt()

    for (b in 0 until barsCount) {
        val screenX = b * stepPx
        val targetMs = currentPositionMs + ((screenX - centerX) * msPerPixel)
        if (targetMs < 0 || targetMs > durationMs) continue

        val sampleIdx = (targetMs / msPerSample).toInt().coerceIn(0, totalSamples - 1)
        val peak = waveformData.peaks.getOrElse(sampleIdx) { 0f }
        val barHeight = (peak * maxPeakHeight).coerceAtLeast(1.0f)

        val isPast = screenX <= centerX
        val color = if (isPast) Color(0xFFECEFF1) else Color(0xFF546E7A).copy(alpha = 0.5f)

        if (isHalf) {
            drawLine(
                color = color,
                start = Offset(screenX, baselineY - barHeight),
                end = Offset(screenX, baselineY),
                strokeWidth = 1.4f
            )
        } else {
            drawLine(
                color = color,
                start = Offset(screenX, centerY - barHeight),
                end = Offset(screenX, centerY + barHeight),
                strokeWidth = 1.4f
            )
        }
    }
}

