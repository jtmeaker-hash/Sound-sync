package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.audio.WaveformData
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track
import com.example.model.WaveformStyle
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.SoundSyncTheme
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dedicated Full-Screen Now Playing Page.
 * Displays a clean, modern, immersive music playback experience:
 * - Header says strictly "Now Playing"
 * - No deck labels, no "Deck A", no multi-deck terminology
 * - No CUE / Hot Cue / loop buttons
 * - Track Title, Artist, Album, BPM, Musical Key
 * - Full interactive waveform with zoom / album artwork toggle
 * - Clean play/pause/skip transport controls
 */
@Composable
fun NowPlayingFullScreen(
    track: Track,
    displayMode: NowPlayingDisplayMode,
    waveformData: WaveformData?,
    isWaveformLoading: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    // EQ parameters
    eqEnabled: Boolean = true,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    onSetEqEnabled: (Boolean) -> Unit = {},
    onSetEqLow: (Float) -> Unit = {},
    onSetEqMid: (Float) -> Unit = {},
    onSetEqHigh: (Float) -> Unit = {},
    // Haas parameters
    haasEnabled: Boolean = false,
    haasAmount: Float = 0.5f,
    haasDelayMs: Float = 5f,
    onSetHaasEnabled: (Boolean) -> Unit = {},
    onSetHaasAmount: (Float) -> Unit = {},
    onSetHaasDelayMs: (Float) -> Unit = {},
    // Repeat & Shuffle
    isShuffleEnabled: Boolean = false,
    repeatMode: com.example.ui.RepeatMode = com.example.ui.RepeatMode.OFF,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    // Callbacks
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onSetDisplayMode: (NowPlayingDisplayMode) -> Unit,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onToggleWaveformStyle: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onOpenProperties: (Track) -> Unit = {},
    onOpenArtist: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Intercept back button to smoothly close the full-screen page
    BackHandler {
        onDismiss()
    }

    if (com.example.ui.theme.SoundSyncTheme.isPro) {
        ProNowPlayingFullScreenContent(
            track = track,
            displayMode = displayMode,
            waveformData = waveformData,
            isWaveformLoading = isWaveformLoading,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            isShuffleEnabled = isShuffleEnabled,
            repeatMode = repeatMode,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            onDismiss = onDismiss,
            onTogglePlayPause = onTogglePlayPause,
            onPreviousTrack = onPreviousTrack,
            onNextTrack = onNextTrack,
            onSeekToMs = onSeekToMs,
            onToggleDisplayMode = onToggleDisplayMode,
            onSetDisplayMode = onSetDisplayMode,
            waveformStyle = waveformStyle,
            onToggleWaveformStyle = onToggleWaveformStyle,
            onOpenSettings = onOpenSettings,
            onOpenProperties = onOpenProperties,
            onOpenArtist = onOpenArtist,
            modifier = modifier
        )
        return
    }

    val totalSec = if (durationMs > 0) (durationMs / 1000).toInt() else track.durationSeconds.coerceAtLeast(1)
    val curSec = (currentPositionMs / 1000).toInt().coerceIn(0, totalSec)
    val remainingSec = (totalSec - curSec).coerceAtLeast(0)

    val curMin = curSec / 60
    val curS = curSec % 60
    val curMsFrac = ((currentPositionMs % 1000) / 100).toInt()

    val remMin = remainingSec / 60
    val remS = remainingSec % 60

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("now_playing_full_screen"),
        color = DjObsidian
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // =========================================================================
            // TOP APP BAR / HEADER: Strictly "Now Playing"
            // =========================================================================
            Surface(
                color = DjSurfaceDark,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("close_now_playing_screen")
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Close Now Playing",
                            tint = TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = "Now Playing",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("now_playing_header_title")
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onOpenProperties(track) },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("now_playing_inspector_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Track Inspector",
                                tint = DeckACyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("now_playing_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Now Playing Settings",
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // SCROLLABLE FULL-SCREEN BODY
            // =========================================================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. TRACK TITLE & ARTIST
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.title,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("now_playing_title")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ArtistCreditText(
                        artistString = track.artist.ifBlank { "Unknown Artist" },
                        textColor = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        onOpenArtist = onOpenArtist
                    )
                    if (track.album.isNotBlank() && !track.album.equals("Unknown Album", ignoreCase = true)) {
                        Text(text = " • ", color = TextMuted, fontSize = 14.sp)
                        Text(
                            text = track.album,
                            color = TextMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // BPM & KEY BADGES
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // BPM Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DeckACyan.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = if (track.bpm > 0) String.format(Locale.US, "%.1f BPM", track.bpm) else "BPM —",
                            color = DeckACyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Key Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DeckBPink.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeckBPink.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = if (track.musicalKey.isNotBlank() && track.musicalKey != "—") "KEY ${track.musicalKey}" else "KEY —",
                            color = DeckBPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Audio Quality Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                    ) {
                        Text(
                            text = "${track.format.uppercase()} ${if (track.bitrateKbps > 0) "${track.bitrateKbps}K" else ""}".trim(),
                            color = if (track.qualityRating.isLossless) NeonGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. MAIN VISUAL: WAVEFORM OR ALBUM ARTWORK
                Crossfade(
                    targetState = displayMode,
                    label = "NowPlayingFullScreenCrossfade",
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
                            NowPlayingArtwork(
                                track = track,
                                isPlaying = isPlaying,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. TIME INDICATORS (Current Time & Remaining Time)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", curMin, curS),
                            color = DeckACyan,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("current_time_text")
                        )
                        Text(
                            text = String.format(Locale.US, ".%d", curMsFrac),
                            color = DeckACyan.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 1.dp)
                        )
                    }

                    Text(
                        text = "${track.format.uppercase()} • ${if (track.bitrateKbps > 0) "${track.bitrateKbps} kbps" else "Stereo"}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "-%02d:%02d", remMin, remS),
                            color = DeckBPink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("remaining_time_text")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 4. CLEAN PLAYBACK CONTROLS (Shuffle, Previous, Play/Pause, Next, Repeat)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Shuffle Button
                    IconButton(
                        onClick = onToggleShuffle,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("toggle_shuffle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = if (isShuffleEnabled) "Shuffle On" else "Shuffle Off",
                            tint = if (isShuffleEnabled) DeckACyan else TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Previous Track Button
                    Surface(
                        shape = CircleShape,
                        color = DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                        modifier = Modifier.size(56.dp)
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
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Center Play/Pause Button
                    Surface(
                        shape = CircleShape,
                        color = if (isPlaying) DeckBPink else DeckACyan,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .size(76.dp)
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
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    // Next Track Button
                    Surface(
                        shape = CircleShape,
                        color = DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                        modifier = Modifier.size(56.dp)
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
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Repeat Button
                    IconButton(
                        onClick = onToggleRepeat,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("toggle_repeat_button")
                    ) {
                        Icon(
                            imageVector = if (repeatMode == com.example.ui.RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat: $repeatMode",
                            tint = if (repeatMode != com.example.ui.RepeatMode.OFF) DeckACyan else TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 5. WAVEFORM ↔ ARTWORK TOGGLE BUTTON
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DjSurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Waveform Mode Button
                        val isWaveform = displayMode == NowPlayingDisplayMode.WAVEFORM
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isWaveform) DeckACyan else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
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
                                    modifier = Modifier.size(18.dp)
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

                        // Artwork Mode Button
                        val isArtwork = displayMode == NowPlayingDisplayMode.ARTWORK
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isArtwork) DeckBPink else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
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
                                    modifier = Modifier.size(18.dp)
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

                Spacer(modifier = Modifier.height(16.dp))

                // 6. AUDIO EFFECTS & SETTINGS ACTION
                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("open_audio_effects_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Audio Effects & Settings",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Clean Album Artwork View for Now Playing screen.
 * Loads actual embedded artwork from the track file asynchronously.
 * Shows a styled placeholder only when artwork truly isn't available.
 */
@Composable
private fun NowPlayingArtwork(
    track: Track,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var artworkBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastLoadedTrackId by remember { mutableStateOf<String?>(null) }

    // Load artwork asynchronously whenever the track changes
    LaunchedEffect(track.id, track.filePath) {
        // Reset state for new track immediately to avoid showing stale artwork
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

        // Only update if this is still the current track (avoid stale load races)
        if (lastLoadedTrackId == track.id) {
            artworkBitmap = bitmap?.asImageBitmap()
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C0E14))
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (artworkBitmap != null) {
            // Display actual album artwork with rounded container
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(2.dp, DeckACyan),
                shadowElevation = 12.dp,
                modifier = Modifier.size(240.dp)
            ) {
                androidx.compose.foundation.Image(
                    bitmap = artworkBitmap!!,
                    contentDescription = "${track.title} album artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                )
            }
        } else if (isLoading) {
            // Loading state: subtle pulsing indicator
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(2.dp, DeckACyan.copy(alpha = 0.4f)),
                shadowElevation = 8.dp,
                modifier = Modifier.size(180.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(Color(0xFF1B2A4A), Color(0xFF0B111F))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = DeckACyan.copy(alpha = 0.5f),
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
        } else {
            // Fallback placeholder: no artwork available for this track
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(2.dp, DeckACyan),
                shadowElevation = 12.dp,
                modifier = Modifier.size(180.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(Color(0xFF1B2A4A), Color(0xFF0B111F))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = DeckACyan,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (track.genre.isNotBlank() && track.genre != "Unknown") track.genre.uppercase() else "AUDIO TRACK",
                            color = DeckACyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.bitrateKbps}K ${track.format.uppercase()}",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Utilitarian workstation audio layout for Pro theme.
 * Inspired by Pioneer rekordbox workstation view:
 * - Workstation header with tabular BPM and Musical Key readouts
 * - Compact typography and audio spec badges
 * - Full-track overview scrubber + zoomed live detailed waveform
 * - Tabular monospaced elapsed and remaining time displays
 * - Compact restrained transport controls
 * - Detailed technical audio specs table
 */
@Composable
private fun ProNowPlayingFullScreenContent(
    track: Track,
    displayMode: NowPlayingDisplayMode,
    waveformData: WaveformData?,
    isWaveformLoading: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    isShuffleEnabled: Boolean,
    repeatMode: com.example.ui.RepeatMode,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onSetDisplayMode: (NowPlayingDisplayMode) -> Unit,
    waveformStyle: WaveformStyle,
    onToggleWaveformStyle: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenProperties: (Track) -> Unit,
    onOpenArtist: ((String) -> Unit)? = null,
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
    val remMsFrac = (((totalSec * 1000L - currentPositionMs).coerceAtLeast(0) % 1000) / 100).toInt()

    val theme = SoundSyncTheme.current

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("now_playing_full_screen"),
        color = theme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // =========================================================================
            // 1. PRO WORKSTATION TOP BAR
            // =========================================================================
            Surface(
                color = theme.surface,
                border = BorderStroke(0.5.dp, theme.divider),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("close_now_playing_screen")
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close Now Playing",
                                tint = theme.textPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "NOW PLAYING",
                                color = theme.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier.testTag("now_playing_header_title")
                            )
                            Text(
                                text = "WORKSTATION DECK",
                                color = theme.textMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Monospace DJ Readouts: BPM & Key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // BPM Readout
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = theme.surfaceSunken,
                            border = BorderStroke(0.5.dp, theme.divider)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "BPM",
                                    color = theme.textMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (track.bpm > 0) String.format(Locale.US, "%.2f", track.bpm) else "---.--",
                                    color = theme.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Musical Key Readout
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = theme.surfaceSunken,
                            border = BorderStroke(0.5.dp, theme.divider)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "KEY",
                                    color = theme.textMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                val keyText = when {
                                    track.camelotKey.isNotBlank() && track.camelotKey != "—" -> track.camelotKey
                                    track.musicalKey.isNotBlank() && track.musicalKey != "—" -> track.musicalKey
                                    else -> "---"
                                }
                                Text(
                                    text = keyText,
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        IconButton(
                            onClick = { onOpenProperties(track) },
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("now_playing_inspector_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Track Inspector",
                                tint = theme.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("now_playing_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Now Playing Settings",
                                tint = theme.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 2. SCROLLABLE WORKSTATION BODY
            // =========================================================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // TRACK TITLE & ARTIST BAR
                Surface(
                    color = theme.surface,
                    border = BorderStroke(0.5.dp, theme.divider),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = track.title.ifBlank { "Unknown Title" },
                                color = theme.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("now_playing_title")
                            )

                            // Format / Lossless tag
                            Surface(
                                shape = RoundedCornerShape(2.dp),
                                color = theme.surfaceRaised,
                                border = BorderStroke(0.5.dp, theme.divider),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = track.format.uppercase(),
                                    color = if (track.qualityRating.isLossless) Color(0xFF30D158) else theme.accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ArtistCreditText(
                                artistString = track.artist.ifBlank { "Unknown Artist" },
                                textColor = theme.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                onOpenArtist = onOpenArtist,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (track.album.isNotBlank() && !track.album.equals("Unknown Album", ignoreCase = true)) {
                                Text(text = "•", color = theme.textMuted, fontSize = 11.sp)
                                Text(
                                    text = track.album,
                                    color = theme.textMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // VIEW SELECTOR TABS: WAVEFORM / ARTWORK
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = theme.surfaceSunken,
                    border = BorderStroke(0.5.dp, theme.divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isWaveform = displayMode == NowPlayingDisplayMode.WAVEFORM
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = if (isWaveform) theme.surfaceRaised else Color.Transparent,
                            border = if (isWaveform) BorderStroke(0.5.dp, theme.accent) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
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
                                    contentDescription = "Waveform",
                                    tint = if (isWaveform) theme.accent else theme.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "WAVEFORM",
                                    color = if (isWaveform) theme.textPrimary else theme.textMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        val isArtwork = displayMode == NowPlayingDisplayMode.ARTWORK
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = if (isArtwork) theme.surfaceRaised else Color.Transparent,
                            border = if (isArtwork) BorderStroke(0.5.dp, theme.accent) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
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
                                    contentDescription = "Artwork",
                                    tint = if (isArtwork) theme.accent else theme.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "ARTWORK",
                                    color = if (isArtwork) theme.textPrimary else theme.textMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // MAIN VISUAL: WAVEFORM WORKSTATION OR ALBUM ARTWORK
                Crossfade(
                    targetState = displayMode,
                    label = "ProNowPlayingCrossfade",
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
                            NowPlayingArtwork(
                                track = track,
                                isPlaying = isPlaying,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // TIME INDICATORS (Elapsed & Remaining with fractional tenths)
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = theme.surfaceSunken,
                    border = BorderStroke(0.5.dp, theme.divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Elapsed
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "%02d:%02d", curMin, curS),
                                color = theme.accent,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("current_time_text")
                            )
                            Text(
                                text = String.format(Locale.US, ".%d", curMsFrac),
                                color = theme.accent.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = "${track.format.uppercase()} ${if (track.bitrateKbps > 0) "${track.bitrateKbps}K" else ""}".trim(),
                            color = theme.textMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        // Remaining
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "-%02d:%02d", remMin, remS),
                                color = if (remainingSec < 30) Color(0xFFFF3B30) else theme.textSecondary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("remaining_time_text")
                            )
                            Text(
                                text = String.format(Locale.US, ".%d", remMsFrac),
                                color = (if (remainingSec < 30) Color(0xFFFF3B30) else theme.textSecondary).copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // RESTRAINED PRO TRANSPORT CONTROLS
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = theme.surface,
                    border = BorderStroke(0.5.dp, theme.divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Shuffle Button
                        IconButton(
                            onClick = onToggleShuffle,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("toggle_shuffle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = if (isShuffleEnabled) "Shuffle On" else "Shuffle Off",
                                tint = if (isShuffleEnabled) theme.accent else theme.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Previous Track
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = theme.surfaceRaised,
                            border = BorderStroke(0.5.dp, theme.divider),
                            modifier = Modifier.size(42.dp)
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
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Play/Pause Workstation Button
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = if (isPlaying) Color(0xFF22262F) else theme.accent,
                            border = BorderStroke(1.dp, if (isPlaying) theme.accent else Color.Transparent),
                            modifier = Modifier
                                .size(width = 64.dp, height = 44.dp)
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
                                    tint = if (isPlaying) theme.accent else Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        // Next Track
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = theme.surfaceRaised,
                            border = BorderStroke(0.5.dp, theme.divider),
                            modifier = Modifier.size(42.dp)
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
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Repeat Button
                        IconButton(
                            onClick = onToggleRepeat,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("toggle_repeat_button")
                        ) {
                            Icon(
                                imageVector = if (repeatMode == com.example.ui.RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat: $repeatMode",
                                tint = if (repeatMode != com.example.ui.RepeatMode.OFF) theme.accent else theme.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // TECHNICAL AUDIO SPECS WORKSTATION TABLE
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = theme.surface,
                    border = BorderStroke(0.5.dp, theme.divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "AUDIO SPECIFICATIONS",
                                color = theme.textMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (track.isAvailable) "ONLINE" else "OFFLINE",
                                color = if (track.isAvailable) Color(0xFF30D158) else Color(0xFFFF3B30),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Grid of specs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("FORMAT", color = theme.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                Text(track.format.uppercase(), color = theme.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("BITRATE", color = theme.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                Text(if (track.bitrateKbps > 0) "${track.bitrateKbps} kbps" else "Lossless", color = theme.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("KEY / CAMELOT", color = theme.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                Text("${track.musicalKey.ifBlank { "—" }} / ${track.camelotKey.ifBlank { "—" }}", color = theme.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("QUALITY", color = theme.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                Text(track.qualityRating.label, color = if (track.qualityRating.isLossless) Color(0xFF30D158) else theme.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // QUICK ACTION: AUDIO EFFECTS & SETTINGS
                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary),
                    border = BorderStroke(0.5.dp, theme.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("open_audio_effects_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = theme.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Audio Effects & DSP Settings",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = theme.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Data class representing an artist segment (either a clickable artist name or an acoustic delimiter like " feat. " or ", ").
 */
data class ArtistSegment(val text: String, val isArtist: Boolean)

/**
 * Splits an artist string into individual artist tokens and connecting delimiters.
 * Handles common feature and collaboration markers: feat., ft., featuring, vs., with, &, ;, ,, /, and x.
 */
fun splitArtistSegments(artistString: String): List<ArtistSegment> {
    val trimmed = artistString.trim()
    if (trimmed.isBlank() || trimmed.equals("Unknown Artist", ignoreCase = true)) {
        return listOf(ArtistSegment(trimmed.ifBlank { "Unknown Artist" }, isArtist = false))
    }

    val delimiterRegex = Regex("""(?i)(\s*(?:\b(?:featuring|feat|ft|vs|with|x)(?:\.|\b)|&|;|,|\/)\s*)""")
    val matches = delimiterRegex.findAll(trimmed).toList()
    if (matches.isEmpty()) {
        return listOf(ArtistSegment(trimmed, isArtist = true))
    }

    val tokens = mutableListOf<ArtistSegment>()
    var currentIndex = 0

    for (match in matches) {
        if (match.range.first > currentIndex) {
            val artistPart = trimmed.substring(currentIndex, match.range.first).trim()
            if (artistPart.isNotEmpty()) {
                tokens.add(ArtistSegment(artistPart, isArtist = true))
            }
        }
        tokens.add(ArtistSegment(match.value, isArtist = false))
        currentIndex = match.range.last + 1
    }

    if (currentIndex < trimmed.length) {
        val artistPart = trimmed.substring(currentIndex).trim()
        if (artistPart.isNotEmpty()) {
            tokens.add(ArtistSegment(artistPart, isArtist = true))
        }
    }

    return tokens
}

/**
 * Returns a clean list of individual artist names from a collaborative artist tag.
 */
fun splitArtistNames(artistString: String): List<String> {
    return splitArtistSegments(artistString).filter { it.isArtist }.map { it.text }
}

/**
 * Renders an artist credit line with individual clickable artist tokens.
 */
@Composable
fun ArtistCreditText(
    artistString: String,
    textColor: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Medium,
    onOpenArtist: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val segments = remember(artistString) { splitArtistSegments(artistString) }

    if (segments.size <= 1) {
        val name = segments.firstOrNull()?.text ?: artistString.ifBlank { "Unknown Artist" }
        Text(
            text = name,
            color = textColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .testTag("now_playing_artist")
                .then(
                    if (onOpenArtist != null && name.isNotBlank() && !name.equals("Unknown Artist", ignoreCase = true)) {
                        Modifier.clickable { onOpenArtist(name) }
                    } else Modifier
                )
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.testTag("now_playing_artist")
        ) {
            segments.forEach { segment ->
                if (segment.isArtist) {
                    Text(
                        text = segment.text,
                        color = textColor,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .then(
                                if (onOpenArtist != null) {
                                    Modifier.clickable { onOpenArtist(segment.text) }
                                } else Modifier
                            )
                            .testTag("now_playing_artist_token_${segment.text}")
                    )
                } else {
                    Text(
                        text = segment.text,
                        color = textColor.copy(alpha = 0.65f),
                        fontSize = fontSize,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

