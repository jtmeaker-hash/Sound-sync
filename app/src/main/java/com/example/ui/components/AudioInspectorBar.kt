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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.DjAudioEngine
import com.example.model.AudioQualityRating
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun AudioInspectorBar(
    audioEngine: DjAudioEngine,
    onOpenSpectrogram: (Track) -> Unit,
    onOpenProperties: (Track) -> Unit,
    onAutoTag: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTrack by audioEngine.currentTrack.collectAsState()
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val progress by audioEngine.playbackProgress.collectAsState()
    val currentSec by audioEngine.currentPositionSec.collectAsState()
    val waveformBars by audioEngine.waveformHeights.collectAsState()
    val activeLoop by audioEngine.activeLoopBars.collectAsState()

    val track = currentTrack ?: return

    val totalSec = track.durationSeconds
    val currentMin = currentSec / 60
    val currentSecRem = currentSec % 60
    val totalMin = totalSec / 60
    val totalSecRem = totalSec % 60
    val timeFormatted = String.format(Locale.US, "%02d:%02d / %02d:%02d", currentMin, currentSecRem, totalMin, totalSecRem)

    val keyColor = getKeyColor(track.musicalKey)
    val isLossless = track.qualityRating.isLossless
    val isSuspicious = track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_inspector_bar"),
        color = DjSurfaceDark,
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Top row: Track Details & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Track Info & Badges
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Camelot Key Pill
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = keyColor.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, keyColor)
                    ) {
                        Text(
                            text = track.musicalKey,
                            color = keyColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // BPM Pill
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DjSurfaceElevated
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f", track.bpm),
                            color = NeonAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    // Format Pill
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isLossless) NeonGreen.copy(alpha = 0.2f) else if (isSuspicious) NeonRed.copy(alpha = 0.2f) else DjSurfaceElevated,
                        border = if (isLossless) androidx.compose.foundation.BorderStroke(1.dp, NeonGreen) else if (isSuspicious) androidx.compose.foundation.BorderStroke(1.dp, NeonRed) else null
                    ) {
                        Text(
                            text = "${track.format} ${if (isLossless) "FLAC" else "${track.bitrateKbps}k"}",
                            color = if (isLossless) NeonGreen else if (isSuspicious) NeonRed else TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    // Title & Artist
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.artist} · ${track.genre}",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Quick Tools Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Spectrogram Button
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenSpectrogram(track) },
                        shape = RoundedCornerShape(6.dp),
                        color = DeckACyan.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = "Spectrogram", tint = DeckACyan, modifier = Modifier.size(13.dp))
                            Text("Spectrum", color = DeckACyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Properties & Metadata Button
                    IconButton(
                        onClick = { onOpenProperties(track) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Properties", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }

                    // Auto Tag AI Button
                    IconButton(
                        onClick = { onAutoTag(track) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Auto Tag", tint = NeonPurple, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Middle row: Seekable Waveform Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(DjObsidian, RoundedCornerShape(4.dp))
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val seekRatio = (offset.x / size.width).coerceIn(0f, 1f)
                            audioEngine.seekToRatio(seekRatio)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val seekRatio = (change.position.x / size.width).coerceIn(0f, 1f)
                            audioEngine.seekToRatio(seekRatio)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                    val w = size.width
                    val h = size.height
                    val barCount = waveformBars.size
                    val barWidth = (w / barCount) * 0.75f
                    val spacing = (w / barCount) * 0.25f

                    for (i in 0 until barCount) {
                        val barHeight = (waveformBars[i] * h * 0.9f).coerceAtLeast(3f)
                        val x = i * (barWidth + spacing)
                        val isPlayed = (i.toFloat() / barCount) <= progress

                        val color = if (isPlayed) {
                            DeckACyan
                        } else {
                            DjSurfaceBorder.copy(alpha = 0.7f)
                        }

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, (h - barHeight) / 2f),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }

                    // Playhead Line
                    val cursorX = progress * w
                    drawLine(
                        color = Color.White,
                        start = Offset(cursorX, 0f),
                        end = Offset(cursorX, h),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Bottom row: Timecode, Seek buttons, Loop & Play/Pause
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timecode
                Text(
                    text = timeFormatted,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                // Compact Playback Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Loop Toggle
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                val next = if (activeLoop == 0) 4 else 0
                                audioEngine.setLoop(next)
                            },
                        shape = RoundedCornerShape(4.dp),
                        color = if (activeLoop > 0) NeonAmber.copy(alpha = 0.25f) else DjSurfaceElevated,
                        border = if (activeLoop > 0) androidx.compose.foundation.BorderStroke(1.dp, NeonAmber) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Icons.Default.Repeat, contentDescription = null, tint = if (activeLoop > 0) NeonAmber else TextMuted, modifier = Modifier.size(11.dp))
                            Text(
                                text = if (activeLoop > 0) "4-BAR" else "LOOP",
                                color = if (activeLoop > 0) NeonAmber else TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Rewind -15s
                    IconButton(
                        onClick = {
                            val target = (progress - 0.05f).coerceAtLeast(0f)
                            audioEngine.seekToRatio(target)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }

                    // Play / Pause Circle Button
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) DeckBPink else DeckACyan)
                            .clickable { audioEngine.togglePlayPause() }
                            .testTag("preview_play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = DjObsidian,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Fast Forward +15s
                    IconButton(
                        onClick = {
                            val target = (progress + 0.05f).coerceAtMost(1f)
                            audioEngine.seekToRatio(target)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = "Forward", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun getKeyColor(key: String): Color {
    return when {
        key.startsWith("1") -> Color(0xFF00F0FF) // Cyan
        key.startsWith("2") -> Color(0xFF05FFA1) // Green
        key.startsWith("3") -> Color(0xFF70E000) // Lime
        key.startsWith("4") -> Color(0xFFFFB703) // Amber
        key.startsWith("5") -> Color(0xFFFB8500) // Orange
        key.startsWith("6") -> Color(0xFFFF2A6D) // Pink
        key.startsWith("7") -> Color(0xFFFF0055) // Red
        key.startsWith("8") -> Color(0xFFD90429) // Crimson
        key.startsWith("9") -> Color(0xFF7209B7) // Purple
        key.startsWith("10") -> Color(0xFF3A0CA3) // Indigo
        key.startsWith("11") -> Color(0xFF4361EE) // Blue
        key.startsWith("12") -> Color(0xFF4CC9F0) // Sky
        else -> DeckACyan
    }
}
