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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.audio.WaveformData
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track
import com.example.model.WaveformStyle
import com.example.ui.theme.SoundSyncTheme
import com.example.ui.theme.DjMonoBpm
import com.example.ui.theme.DjMonoKey
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
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onToggleWaveformStyle: (() -> Unit)? = null,
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

    val theme = SoundSyncTheme.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.cornerMedium))
            .background(theme.surface)
            .border(1.dp, theme.divider, RoundedCornerShape(theme.cornerMedium))
            .padding(14.dp)
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
                    color = theme.textPrimary,
                    fontSize = 17.sp,
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
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("now_playing_artist")
                    )
                    Text(text = "•", color = theme.textMuted, fontSize = 11.sp)
                    Text(
                        text = track.album,
                        color = theme.textMuted,
                        fontSize = 11.sp,
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
                    shape = RoundedCornerShape(theme.cornerSmall),
                    color = theme.surfaceRaised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
                ) {
                    Text(
                        text = if (track.hasValidBpm) "${String.format(Locale.US, "%.1f", track.bpm)} BPM" else "— BPM",
                        color = theme.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(theme.cornerSmall),
                    color = theme.surfaceRaised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
                ) {
                    Text(
                        text = if (track.hasValidKey) (track.camelotKey.ifBlank { track.musicalKey }) else "— KEY",
                        color = theme.textPrimary,
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
                        waveformStyle = waveformStyle,
                        onToggleWaveformStyle = onToggleWaveformStyle,
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
                    color = theme.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("current_time_text")
                )
                Text(
                    text = String.format(Locale.US, ".%d", curMsFrac),
                    color = theme.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }

            // Audio Quality Tag
            Surface(
                shape = RoundedCornerShape(theme.cornerSmall),
                color = theme.surfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider)
            ) {
                Text(
                    text = "${track.format.uppercase()} ${track.bitrateKbps}K",
                    color = if (track.qualityRating.isLossless) Color(0xFF00E676) else theme.textSecondary,
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
                    color = theme.accent,
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
            horizontalArrangement = Arrangement.Center
        ) {
            // Previous Track Button
            Surface(
                shape = CircleShape,
                color = theme.surfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider),
                modifier = Modifier.size(52.dp)
            ) {
                IconButton(
                    onClick = onPreviousTrack,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("previous_track_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = theme.textPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Big Center Play / Pause Button
            Surface(
                shape = CircleShape,
                color = if (isPlaying) theme.accentHover else theme.accent,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(66.dp)
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
                        tint = theme.onAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Next Track Button
            Surface(
                shape = CircleShape,
                color = theme.surfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider),
                modifier = Modifier.size(52.dp)
            ) {
                IconButton(
                    onClick = onNextTrack,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("next_track_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = theme.textPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // 5. WAVEFORM ↔ ARTWORK TOGGLE BUTTON
        // =========================================================================
        Surface(
            shape = RoundedCornerShape(theme.cornerMedium),
            color = theme.surfaceSunken,
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider),
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
                    shape = RoundedCornerShape(theme.cornerSmall),
                    color = if (isWaveform) theme.accent else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
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
                            tint = if (isWaveform) theme.onAccent else theme.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "WAVEFORM",
                            color = if (isWaveform) theme.onAccent else theme.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Artwork Toggle Segment
                val isArtwork = displayMode == NowPlayingDisplayMode.ARTWORK
                Surface(
                    shape = RoundedCornerShape(theme.cornerSmall),
                    color = if (isArtwork) theme.accent else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
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
                            tint = if (isArtwork) theme.onAccent else theme.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ARTWORK",
                            color = if (isArtwork) theme.onAccent else theme.textSecondary,
                            fontSize = 11.sp,
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
 * Loads actual embedded artwork from the track file asynchronously.
 */
@Composable
private fun AlbumArtworkDisplay(
    track: Track,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val theme = SoundSyncTheme.current
    val context = LocalContext.current
    var artworkBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastLoadedTrackId by remember { mutableStateOf<String?>(null) }

    // Load artwork asynchronously whenever the track changes
    LaunchedEffect(track.id, track.filePath) {
        if (lastLoadedTrackId != track.id) {
            artworkBitmap = null
            isLoading = true
        }
        lastLoadedTrackId = track.id

        val bitmap = withContext(Dispatchers.IO) {
            try {
                AlbumArtHelper.getArtworkForTrack(context, track, 512)
            } catch (e: Exception) {
                null
            }
        }

        // Only update if this is still the current track
        if (lastLoadedTrackId == track.id) {
            artworkBitmap = bitmap?.asImageBitmap()
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(theme.cornerMedium))
            .background(theme.surfaceSunken)
            .border(1.dp, theme.divider, RoundedCornerShape(theme.cornerMedium)),
        contentAlignment = Alignment.Center
    ) {
        if (artworkBitmap != null) {
            // Ambient vinyl groove canvas behind artwork
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = min(size.width, size.height) * 0.44f
                drawCircle(
                    color = theme.surface,
                    radius = maxR,
                    center = center
                )
                for (r in listOf(0.9f, 0.82f, 0.74f, 0.66f, 0.58f)) {
                    drawCircle(
                        color = theme.divider.copy(alpha = 0.5f),
                        radius = maxR * r,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                    )
                }
                drawCircle(
                    color = if (isPlaying) theme.accent.copy(alpha = 0.4f) else theme.divider,
                    radius = maxR,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                )
            }

            // Center: actual album artwork
            Surface(
                shape = RoundedCornerShape(theme.cornerSmall),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider),
                shadowElevation = 4.dp,
                modifier = Modifier.size(180.dp)
            ) {
                androidx.compose.foundation.Image(
                    bitmap = artworkBitmap!!,
                    contentDescription = "${track.title} album artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(theme.cornerSmall))
                )
            }
        } else {
            // Ambient vinyl groove canvas (placeholder mode)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = min(size.width, size.height) * 0.44f
                drawCircle(
                    color = theme.surface,
                    radius = maxR,
                    center = center
                )
                for (r in listOf(0.9f, 0.82f, 0.74f, 0.66f, 0.58f)) {
                    drawCircle(
                        color = theme.divider.copy(alpha = 0.5f),
                        radius = maxR * r,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                    )
                }
                drawCircle(
                    color = if (isPlaying) theme.accent.copy(alpha = 0.4f) else theme.divider,
                    radius = maxR,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                )
            }

            // Placeholder badge
            Surface(
                shape = RoundedCornerShape(theme.cornerSmall),
                color = theme.surfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.divider),
                shadowElevation = 4.dp,
                modifier = Modifier.size(130.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(theme.surfaceRaised),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        if (isLoading) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = theme.textMuted,
                                modifier = Modifier.size(36.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = theme.accent,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = track.genre.uppercase(),
                                color = theme.accent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "${track.bitrateKbps}K ${track.format}",
                                color = theme.textSecondary,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
