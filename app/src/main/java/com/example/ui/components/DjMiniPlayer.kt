package com.example.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.WaveformData
import com.example.model.MusicPlatform
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Docked Rekordbox DJ Player bar at bottom of SoundSync.
 * Shows track info, live scrolling waveform preview or album artwork, playback controls, and expands into full player on tap.
 */
@Composable
fun DjMiniPlayer(
    track: Track,
    displayMode: NowPlayingDisplayMode,
    waveformData: WaveformData?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit = {},
    onNextTrack: () -> Unit = {},
    onSeekToMs: (Long) -> Unit = {},
    onToggleDisplayMode: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val platformColor = when {
        track.platforms.contains(MusicPlatform.SPOTIFY) -> Color(0xFF1DB954)
        track.platforms.contains(MusicPlatform.SOUNDCLOUD) -> Color(0xFFFF5500)
        else -> DeckACyan
    }

    val safeDurationMs = if (durationMs > 0) durationMs else (track.durationSeconds.coerceAtLeast(1) * 1000L)
    val progressFrac = if (safeDurationMs > 0) (currentPositionMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val curSec = (currentPositionMs / 1000).toInt()
    val totalSec = (safeDurationMs / 1000).toInt()
    val curM = curSec / 60
    val curS = curSec % 60
    val durM = totalSec / 60
    val durS = totalSec % 60

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clickable { onOpenNowPlaying() }
            .testTag("dj_mini_player"),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            // Live Mini Waveform / Progress Strip
            MiniWaveformProgressStrip(
                waveformData = waveformData,
                currentPositionMs = currentPositionMs,
                durationMs = safeDurationMs,
                onSeekFraction = { frac ->
                    onSeekToMs((safeDurationMs * frac).toLong())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Visual Thumbnail (Waveform Icon vs Album Art Icon)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (displayMode == NowPlayingDisplayMode.WAVEFORM) DeckACyan.copy(alpha = 0.2f) else DeckBPink.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (displayMode == NowPlayingDisplayMode.WAVEFORM) DeckACyan else DeckBPink),
                    modifier = Modifier
                        .size(38.dp)
                        .clickable { onToggleDisplayMode() }
                        .testTag("mini_player_toggle_mode")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Crossfade(targetState = displayMode, label = "mini_thumb_crossfade") { mode ->
                            if (mode == NowPlayingDisplayMode.WAVEFORM) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Waveform Mode Active (Tap to toggle)",
                                    tint = DeckACyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Album,
                                    contentDescription = "Artwork Mode Active (Tap to toggle)",
                                    tint = DeckBPink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Track Info & Badges
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenNowPlaying() },
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = track.title.ifBlank { "Unknown Title" },
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = track.artist.ifBlank { "Unknown Artist" },
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text("•", color = TextMuted, fontSize = 9.sp)
                        Text(
                            text = String.format(Locale.US, "%d:%02d / %d:%02d", curM, curS, durM, durS),
                            color = DeckACyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (track.hasValidKey) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = DeckBPink.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = track.camelotKey.ifBlank { track.musicalKey },
                                    color = DeckBPink,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                // Controls: Previous | Play/Pause | Next | Expand
                IconButton(
                    onClick = onPreviousTrack,
                    modifier = Modifier.size(32.dp).testTag("mini_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (isPlaying) DeckBPink else DeckACyan,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onTogglePlayPause() }
                        .testTag("mini_play_pause_button")
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

                IconButton(
                    onClick = onNextTrack,
                    modifier = Modifier.size(32.dp).testTag("mini_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Expand Button
                IconButton(
                    onClick = onOpenNowPlaying,
                    modifier = Modifier.size(32.dp).testTag("expand_now_playing_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Open Full Player",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Top mini waveform scrubber strip on the mini player.
 */
@Composable
private fun MiniWaveformProgressStrip(
    waveformData: WaveformData?,
    currentPositionMs: Long,
    durationMs: Long,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF090B10))
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeekFraction(fraction)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            val playheadX = progress * width

            if (waveformData != null && waveformData.samplePoints > 0) {
                val peaks = waveformData.peaks
                val step = max(1f, width / peaks.size.toFloat())

                for (i in peaks.indices) {
                    val x = i * step
                    val peak = peaks[i]
                    val barH = max(1f, peak * (centerY * 0.9f))
                    val isPast = x <= playheadX
                    val color = if (isPast) DeckACyan else Color(0xFF222B3D)

                    drawLine(
                        color = color,
                        start = Offset(x, centerY - barH),
                        end = Offset(x, centerY + barH),
                        strokeWidth = max(1f, step - 0.5f)
                    )
                }

                // Center/Playhead Marker
                drawLine(
                    color = Color(0xFFFF0055),
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, height),
                    strokeWidth = 2f
                )
            } else {
                // Standard progress fill
                drawRect(
                    color = Color(0xFF141924),
                    size = Size(width, height)
                )
                drawRect(
                    color = DeckACyan,
                    size = Size(playheadX, height)
                )
            }
        }
    }
}
