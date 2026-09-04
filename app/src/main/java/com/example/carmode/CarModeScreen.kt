package com.example.carmode

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.audio.DjAudioEngine
import com.example.audio.WaveformData
import com.example.model.Track
import com.example.ui.components.RekordboxWaveformView
import com.example.ui.theme.*
import java.util.Locale

/**
 * Dedicated Car Mode interface designed for glanceable, high-contrast, safe operation
 * while driving without Android Auto.
 */
@Composable
fun CarModeScreen(
    currentTrack: Track?,
    isPlaying: Boolean,
    waveformData: WaveformData?,
    audioEngine: DjAudioEngine,
    carModeManager: CarModeManager,
    playbackQueue: List<Track>,
    isFavorite: Boolean,
    isShuffleEnabled: Boolean = false,
    repeatMode: com.example.ui.RepeatMode = com.example.ui.RepeatMode.OFF,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onPlaySomething: () -> Unit,
    onExitCarMode: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onSelectQueueTrack: (Track) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isNightMode by carModeManager.isNightMode.collectAsState()
    val displayMode by carModeManager.displayMode.collectAsState()
    val smartShuffle by carModeManager.smartDrivingShuffle.collectAsState()
    val currentPositionMs by audioEngine.currentPositionMs.collectAsState()
    val durationMs = if ((currentTrack?.durationSeconds ?: 0) > 0) currentTrack!!.durationSeconds * 1000L else 0L

    var showQueuePreview by remember { mutableStateOf(false) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }

    // Color Palette optimized for driving readability and night anti-glare
    val bgColor = if (isNightMode) Color(0xFF07080A) else Color(0xFF111317)
    val cardColor = if (isNightMode) Color(0xFF0F1115) else Color(0xFF1B1E24)
    val accentColor = Color(0xFF1E6CFF)
    val textColor = Color(0xFFF1F5F9)
    val mutedColor = Color(0xFF94A3B8)

    LaunchedEffect(saveStatusMessage) {
        if (saveStatusMessage != null) {
            kotlinx.coroutines.delay(2500)
            saveStatusMessage = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .testTag("car_mode_screen")
    ) {
        if (isLandscape) {
            // Dedicated Landscape Dashboard Layout
            CarModeLandscapeLayout(
                track = currentTrack,
                isPlaying = isPlaying,
                waveformData = waveformData,
                displayMode = displayMode,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                isFavorite = isFavorite,
                isShuffle = isShuffleEnabled,
                smartShuffle = smartShuffle,
                repeatMode = repeatMode,
                cardColor = cardColor,
                accentColor = accentColor,
                textColor = textColor,
                mutedColor = mutedColor,
                onTogglePlayPause = onTogglePlayPause,
                onPreviousTrack = onPreviousTrack,
                onNextTrack = onNextTrack,
                onSeekToMs = onSeekToMs,
                onToggleFavorite = onToggleFavorite,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleDisplayMode = {
                    val next = when (displayMode) {
                        CarDisplayMode.ARTWORK -> CarDisplayMode.WAVEFORM
                        CarDisplayMode.WAVEFORM -> CarDisplayMode.DJ_DASHBOARD
                        CarDisplayMode.DJ_DASHBOARD -> CarDisplayMode.ARTWORK
                    }
                    carModeManager.setDisplayMode(next)
                },
                onOpenQueue = { showQueuePreview = true },
                onSaveForLater = {
                    currentTrack?.let { t ->
                        carModeManager.saveTrackForLater(t) { msg -> saveStatusMessage = msg }
                    }
                },
                onExit = onExitCarMode
            )
        } else {
            // High-legibility Portrait Car Mode Layout
            CarModePortraitLayout(
                track = currentTrack,
                isPlaying = isPlaying,
                waveformData = waveformData,
                displayMode = displayMode,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                isFavorite = isFavorite,
                isShuffle = isShuffleEnabled,
                smartShuffle = smartShuffle,
                repeatMode = repeatMode,
                cardColor = cardColor,
                accentColor = accentColor,
                textColor = textColor,
                mutedColor = mutedColor,
                onTogglePlayPause = onTogglePlayPause,
                onPreviousTrack = onPreviousTrack,
                onNextTrack = onNextTrack,
                onSeekToMs = onSeekToMs,
                onToggleFavorite = onToggleFavorite,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleDisplayMode = {
                    val next = when (displayMode) {
                        CarDisplayMode.ARTWORK -> CarDisplayMode.WAVEFORM
                        CarDisplayMode.WAVEFORM -> CarDisplayMode.DJ_DASHBOARD
                        CarDisplayMode.DJ_DASHBOARD -> CarDisplayMode.ARTWORK
                    }
                    carModeManager.setDisplayMode(next)
                },
                onOpenQueue = { showQueuePreview = true },
                onPlaySomething = onPlaySomething,
                onSaveForLater = {
                    currentTrack?.let { t ->
                        carModeManager.saveTrackForLater(t) { msg -> saveStatusMessage = msg }
                    }
                },
                onExit = onExitCarMode
            )
        }

        // Status Toast Badge
        AnimatedVisibility(
            visible = saveStatusMessage != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Surface(
                color = Color(0xFF1E6CFF),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = saveStatusMessage.orEmpty(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }

        // Queue Preview Sheet / Dialog
        if (showQueuePreview) {
            CarQueuePreviewDialog(
                currentTrack = currentTrack,
                queue = playbackQueue,
                onDismiss = { showQueuePreview = false },
                onSelectTrack = { t ->
                    onSelectQueueTrack(t)
                    showQueuePreview = false
                }
            )
        }
    }
}

// ── Portrait Layout ─────────────────────────────────────────────────────────

@Composable
private fun CarModePortraitLayout(
    track: Track?,
    isPlaying: Boolean,
    waveformData: WaveformData?,
    displayMode: CarDisplayMode,
    currentPositionMs: Long,
    durationMs: Long,
    isFavorite: Boolean,
    isShuffle: Boolean,
    smartShuffle: Boolean,
    repeatMode: com.example.ui.RepeatMode,
    cardColor: Color,
    accentColor: Color,
    textColor: Color,
    mutedColor: Color,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlaySomething: () -> Unit,
    onSaveForLater: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Car Mode top controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = cardColor),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Exit Car Mode", tint = textColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Exit", color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // Central Artwork / Waveform Toggle Pill
            Surface(
                color = cardColor,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B313C)),
                modifier = Modifier
                    .clickable { onToggleDisplayMode() }
                    .height(40.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (displayMode == CarDisplayMode.ARTWORK) Icons.Default.Album else Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(displayMode.label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            IconButton(
                onClick = onOpenQueue,
                modifier = Modifier
                    .size(44.dp)
                    .background(cardColor, CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = textColor, modifier = Modifier.size(22.dp))
            }
        }

        // 2. Large artwork / waveform / visualization area (starts strictly below top bar, responsive size)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            when (displayMode) {
                CarDisplayMode.ARTWORK -> {
                    Surface(
                        modifier = Modifier
                            .sizeIn(minWidth = 140.dp, minHeight = 140.dp, maxWidth = 260.dp, maxHeight = 260.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                        color = cardColor,
                        shadowElevation = 8.dp
                    ) {
                        if (track?.artworkUrl != null) {
                            AsyncImage(
                                model = track.artworkUrl,
                                contentDescription = "Cover",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = mutedColor, modifier = Modifier.size(72.dp))
                            }
                        }
                    }
                }
                CarDisplayMode.WAVEFORM, CarDisplayMode.DJ_DASHBOARD -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 220.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (displayMode == CarDisplayMode.DJ_DASHBOARD) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Text(
                                    text = if ((track?.bpm ?: 0.0) > 0) String.format(Locale.US, "%.1f BPM", track!!.bpm) else "--- BPM",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = track?.camelotKey?.ifBlank { track.musicalKey }?.ifBlank { "---" } ?: "---",
                                    color = Color(0xFF05FFA1),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Live Waveform Canvas
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardColor)
                        ) {
                            if (track != null) {
                                RekordboxWaveformView(
                                    track = track,
                                    waveformData = waveformData,
                                    currentPositionMs = currentPositionMs,
                                    durationMs = durationMs,
                                    isPlaying = isPlaying,
                                    onSeekToMs = onSeekToMs,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Large track title & Artist name
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track?.title ?: "No Track Playing",
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track?.artist ?: "Select music to begin",
                color = mutedColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        // 4. Large progress / seek control + Elapsed & Duration
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            Slider(
                value = progress,
                onValueChange = { frac -> onSeekToMs((frac * durationMs).toLong()) },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = Color(0xFF2B313C)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(currentPositionMs), color = mutedColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text(formatTime(durationMs), color = mutedColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // 5. Large previous / play-pause / next controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPreviousTrack,
                modifier = Modifier
                    .size(68.dp)
                    .background(cardColor, CircleShape)
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = textColor, modifier = Modifier.size(42.dp))
            }

            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .size(84.dp)
                    .background(accentColor, CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(52.dp)
                )
            }

            IconButton(
                onClick = onNextTrack,
                modifier = Modifier
                    .size(68.dp)
                    .background(cardColor, CircleShape)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = textColor, modifier = Modifier.size(42.dp))
            }
        }

        // 6. Favourite / Save for Later ("Car Finds") controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cardColor,
                modifier = Modifier
                    .height(44.dp)
                    .clickable { onToggleFavorite() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (isFavorite) Color(0xFFFF334B) else textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(if (isFavorite) "Favorited" else "Favorite", color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cardColor,
                modifier = Modifier
                    .height(44.dp)
                    .clickable { onSaveForLater() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = "Save for Later",
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text("Save to Car Finds", color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // 7. Secondary controls: Play Something, Shuffle, Repeat
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlaySomething,
                colors = ButtonDefaults.buttonColors(containerColor = cardColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play Something", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isShuffle) accentColor.copy(alpha = 0.25f) else cardColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isShuffle) accentColor else Color.Transparent),
                    modifier = Modifier
                        .size(42.dp)
                        .clickable { onToggleShuffle() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffle) accentColor else mutedColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (repeatMode != com.example.ui.RepeatMode.OFF) accentColor.copy(alpha = 0.25f) else cardColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (repeatMode != com.example.ui.RepeatMode.OFF) accentColor else Color.Transparent),
                    modifier = Modifier
                        .size(42.dp)
                        .clickable { onToggleRepeat() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (repeatMode == com.example.ui.RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode != com.example.ui.RepeatMode.OFF) accentColor else mutedColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Landscape Dashboard Layout ──────────────────────────────────────────────

@Composable
private fun CarModeLandscapeLayout(
    track: Track?,
    isPlaying: Boolean,
    waveformData: WaveformData?,
    displayMode: CarDisplayMode,
    currentPositionMs: Long,
    durationMs: Long,
    isFavorite: Boolean,
    isShuffle: Boolean,
    smartShuffle: Boolean,
    repeatMode: com.example.ui.RepeatMode,
    cardColor: Color,
    accentColor: Color,
    textColor: Color,
    mutedColor: Color,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onSaveForLater: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left Column: Artwork or Live Waveform
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            when (displayMode) {
                CarDisplayMode.ARTWORK -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight(0.9f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                        color = cardColor,
                        shadowElevation = 6.dp
                    ) {
                        if (track?.artworkUrl != null) {
                            AsyncImage(
                                model = track.artworkUrl,
                                contentDescription = "Cover",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = mutedColor, modifier = Modifier.size(64.dp))
                            }
                        }
                    }
                }
                CarDisplayMode.WAVEFORM, CarDisplayMode.DJ_DASHBOARD -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (displayMode == CarDisplayMode.DJ_DASHBOARD) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Text(
                                    text = if ((track?.bpm ?: 0.0) > 0) String.format(Locale.US, "%.1f BPM", track!!.bpm) else "--- BPM",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = track?.camelotKey?.ifBlank { track.musicalKey }?.ifBlank { "---" } ?: "---",
                                    color = Color(0xFF05FFA1),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(cardColor)
                        ) {
                        if (track != null) {
                            RekordboxWaveformView(
                                track = track,
                                waveformData = waveformData,
                                currentPositionMs = currentPositionMs,
                                durationMs = durationMs,
                                isPlaying = isPlaying,
                                onSeekToMs = onSeekToMs,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        }
                    }
                }
            }
        }

        // Center / Right Column: Metadata, Progress & Big Controls
        Column(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Mode toggle, Queue, Exit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = cardColor,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { onToggleDisplayMode() }
                ) {
                    Text(
                        text = displayMode.label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.size(40.dp).background(cardColor, CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = textColor, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = onExit,
                        modifier = Modifier.size(40.dp).background(cardColor, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", tint = textColor, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Track details
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = track?.title ?: "No Track Playing",
                    color = textColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track?.artist ?: "Unknown"} • ${track?.album ?: ""}",
                    color = mutedColor,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Slider(
                    value = progress,
                    onValueChange = { frac -> onSeekToMs((frac * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(currentPositionMs), color = mutedColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text(formatTime(durationMs), color = mutedColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Transport Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isFavorite) Color(0xFFFF334B) else textColor,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = onPreviousTrack,
                    modifier = Modifier.size(60.dp).background(cardColor, CircleShape)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = textColor, modifier = Modifier.size(36.dp))
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(76.dp).background(accentColor, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(46.dp)
                    )
                }

                IconButton(
                    onClick = onNextTrack,
                    modifier = Modifier.size(60.dp).background(cardColor, CircleShape)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = textColor, modifier = Modifier.size(36.dp))
                }

                IconButton(onClick = onSaveForLater, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = "Save for Later", tint = textColor, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

// ── Queue Preview Dialog ────────────────────────────────────────────────────

@Composable
private fun CarQueuePreviewDialog(
    currentTrack: Track?,
    queue: List<Track>,
    onDismiss: () -> Unit,
    onSelectTrack: (Track) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181B21),
        title = {
            Text("Upcoming Tracks", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (queue.isEmpty()) {
                    Text("Queue is empty.", color = Color(0xFF8E95A2), fontSize = 14.sp)
                } else {
                    queue.take(5).forEachIndexed { idx, track ->
                        val isCurrent = track.id == currentTrack?.id
                        Surface(
                            color = if (isCurrent) Color(0xFF1E6CFF).copy(alpha = 0.25f) else Color(0xFF22262F),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTrack(track) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (isCurrent) "▶" else "${idx + 1}",
                                    color = if (isCurrent) Color(0xFF1E6CFF) else Color(0xFF8E95A2),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        color = Color(0xFF8E95A2),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF1E6CFF), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    )
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
