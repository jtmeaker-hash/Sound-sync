package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.WaveformData
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track
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
import kotlin.math.max
import kotlin.math.min

/**
 * Full Now Playing player component following the SoundSync DJ hierarchy:
 * 1. Track title / artist
 * 2. [ Waveform OR Album Artwork ]
 * 3. Current time <---------> Remaining/Total time
 * 4. Previous | Play/Pause | Next
 * 5. [ Waveform ↔ Artwork toggle ]
 */
@Composable
fun NowPlayingView(
    track: Track,
    displayMode: NowPlayingDisplayMode,
    waveformData: WaveformData?,
    isWaveformLoading: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onSetDisplayMode: (NowPlayingDisplayMode) -> Unit,
    onCueJump: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalSec = if (durationMs > 0) (durationMs / 1000).toInt() else track.durationSeconds.coerceAtLeast(1)
    val curSec = (currentPositionMs / 1000).toInt().coerceIn(0, totalSec)
    val remainingSec = (totalSec - curSec).coerceAtLeast(0)

    val curMin = curSec / 60
    val curS = curSec % 60
    val curMsFrac = ((currentPositionMs % 1000) / 100).toInt()

    val remMin = remainingSec / 60
    val remS = remainingSec % 60

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DjSurfaceDark)
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("now_playing_view"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // =========================================================================
        // 1. TRACK TITLE / ARTIST & DJ BADGES
        // =========================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("now_playing_title")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("now_playing_artist")
                    )
                    Text(text = "•", color = TextMuted, fontSize = 12.sp)
                    Text(
                        text = track.album,
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Key and BPM Header Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DeckACyan.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (track.bpm > 0) String.format(Locale.US, "%.1f", track.bpm) else "126.0",
                        color = DeckACyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DeckBPink.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckBPink.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = track.musicalKey,
                        color = DeckBPink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =========================================================================
        // 2. MAIN VISUAL AREA [ WAVEFORM OR ALBUM ARTWORK ]
        // =========================================================================
        Crossfade(
            targetState = displayMode,
            label = "NowPlayingVisualCrossfade",
            modifier = Modifier.fillMaxWidth()
        ) { mode ->
            when (mode) {
                NowPlayingDisplayMode.WAVEFORM -> {
                    RekordboxWaveformView(
                        track = track,
                        waveformData = waveformData,
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        durationMs = durationMs,
                        onSeekToMs = onSeekToMs,
                        isLoading = isWaveformLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NowPlayingDisplayMode.ARTWORK -> {
                    AlbumArtworkDisplay(
                        track = track,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // =========================================================================
        // 3. CURRENT TIME <--------------------------> REMAINING / TOTAL TIME
        // =========================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Current Time (with milliseconds fractional indicator)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%02d:%02d", curMin, curS),
                    color = DeckACyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("current_time_text")
                )
                Text(
                    text = String.format(Locale.US, ".%d", curMsFrac),
                    color = DeckACyan.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }

            // Audio Quality Tag
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Text(
                    text = "${track.format.uppercase()} ${track.bitrateKbps}K",
                    color = if (track.qualityRating.isLossless) NeonGreen else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Remaining Time
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "-%02d:%02d", remMin, remS),
                    color = DeckBPink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("remaining_time_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // =========================================================================
        // 4. PLAYBACK CONTROLS: PREVIOUS | PLAY/PAUSE | NEXT
        // =========================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Hot Cue A Jump
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                modifier = Modifier
                    .size(42.dp)
                    .clickable {
                        val cueSec = track.hotCues.firstOrNull() ?: 0
                        onSeekToMs(cueSec * 1000L)
                    }
                    .testTag("cue_a_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "CUE",
                        color = NeonAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Previous Track Button
            Surface(
                shape = CircleShape,
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = onPreviousTrack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("previous_track_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Big Center Play / Pause Button (Glowing DJ Deck style)
            Surface(
                shape = CircleShape,
                color = if (isPlaying) DeckBPink else DeckACyan,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .size(64.dp)
                    .clickable { onTogglePlayPause() }
                    .testTag("play_pause_button")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Next Track Button
            Surface(
                shape = CircleShape,
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = onNextTrack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("next_track_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Loop 4-Bars Toggle
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                modifier = Modifier
                    .size(42.dp)
                    .clickable {
                        // Quick 4-bar loop
                    }
                    .testTag("loop_4_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "4 BAR",
                        color = DeckACyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // 5. WAVEFORM ↔ ARTWORK TOGGLE BUTTON
        // =========================================================================
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DjObsidian,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Waveform Toggle Segment
                val isWaveform = displayMode == NowPlayingDisplayMode.WAVEFORM
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isWaveform) DeckACyan else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clickable { onSetDisplayMode(NowPlayingDisplayMode.WAVEFORM) }
                        .testTag("toggle_waveform_mode")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Waveform View",
                            tint = if (isWaveform) Color.Black else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "WAVEFORM",
                            color = if (isWaveform) Color.Black else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Artwork Toggle Segment
                val isArtwork = displayMode == NowPlayingDisplayMode.ARTWORK
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isArtwork) DeckBPink else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clickable { onSetDisplayMode(NowPlayingDisplayMode.ARTWORK) }
                        .testTag("toggle_artwork_mode")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = "Album Artwork View",
                            tint = if (isArtwork) Color.Black else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ARTWORK",
                            color = if (isArtwork) Color.Black else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * High-definition vinyl album art display with DJ deck styling.
 */
@Composable
private fun AlbumArtworkDisplay(
    track: Track,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C0E14))
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Ambient vinyl groove canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = min(size.width, size.height) * 0.44f

            // Outer Vinyl Disc
            drawCircle(
                color = Color(0xFF141722),
                radius = maxR,
                center = center
            )

            // Grooves
            for (r in listOf(0.9f, 0.82f, 0.74f, 0.66f, 0.58f)) {
                drawCircle(
                    color = Color(0xFF1C2233),
                    radius = maxR * r,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
            }

            // Glowing Outer Rim
            drawCircle(
                color = if (isPlaying) DeckACyan.copy(alpha = 0.4f) else Color(0x2200F0FF),
                radius = maxR,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )
        }

        // Center Album Art Badge
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DjSurfaceCard,
            border = androidx.compose.foundation.BorderStroke(2.dp, DeckACyan),
            shadowElevation = 12.dp,
            modifier = Modifier.size(130.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF1B2A4A), Color(0xFF0B111F))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.genre.uppercase(),
                        color = DeckACyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "${track.bitrateKbps}K ${track.format}",
                        color = TextSecondary,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
