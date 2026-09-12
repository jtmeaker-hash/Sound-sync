package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.Track
import com.example.ui.theme.SoundSyncTheme
import com.example.util.AlbumArtHelper
import java.util.Locale

/**
 * High-performance, responsive track grid card component.
 * Displays album artwork with prominent visual indicators and compact DJ metadata.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackGridCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueTrack: (playNext: Boolean) -> Unit,
    onInspectProperties: () -> Unit,
    onInspectSpectrogram: () -> Unit,
    onMixWithThis: (() -> Unit)? = null,
    onInspectQuality: (() -> Unit)? = null,
    onOpenLyrics: (() -> Unit)? = null,
    onOpenTrackIntelligence: (() -> Unit)? = null,
    onOpenPlaybackIssueSheet: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val theme = SoundSyncTheme.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val formattedDuration = remember(track.durationSeconds) {
        val min = track.durationSeconds / 60
        val sec = track.durationSeconds % 60
        String.format(Locale.US, "%d:%02d", min, sec)
    }

    val isAvailable = track.isAvailable

    // Synchronous memory-cache check for instantaneous rendering
    var artworkBitmap by remember(track.id, track.filePath, track.artworkCachePath, track.artworkUrl) {
        mutableStateOf(AlbumArtHelper.getCachedArtwork(track, 320))
    }

    // Asynchronous decode if not immediately cached
    LaunchedEffect(track.id, track.filePath, track.artworkCachePath, track.artworkUrl) {
        if (artworkBitmap == null) {
            val loaded = AlbumArtHelper.getArtworkForTrack(context, track, 320)
            artworkBitmap = loaded
        }
    }

    val cardCorner = theme.cornerMedium.coerceAtLeast(10.dp)

    Card(
        shape = RoundedCornerShape(cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) theme.selectedSurface
                             else if (isCurrent) theme.playingSurface
                             else theme.surfaceRaised.copy(alpha = if (isAvailable) 1f else 0.5f)
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else if (isCurrent) 1.dp else 0.5.dp,
            color = if (isSelected) theme.accent
                    else if (isCurrent) theme.accent.copy(alpha = 0.7f)
                    else theme.divider.copy(alpha = if (isAvailable) 0.6f else 0.25f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cardCorner))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection
            )
            .testTag("track_grid_card_${track.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── TOP: Cover Artwork Container (1:1 aspect ratio) ─────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = cardCorner, topEnd = cardCorner))
                    .background(theme.surfaceSunken)
            ) {
                // Actual Artwork Image or Vinyl Fallback
                if (artworkBitmap != null) {
                    Image(
                        bitmap = artworkBitmap!!.asImageBitmap(),
                        contentDescription = "${track.title} artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(theme.surfaceSunken),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = theme.textMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Disconnected Scrim Overlay
                if (!isAvailable) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "Disconnected",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "OFFLINE",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Top-Left: Audio Quality / Format Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        border = if (track.qualityRating.isLossless) BorderStroke(0.5.dp, Color(0xFF00E676)) else null
                    ) {
                        Text(
                            text = if (track.qualityRating.isLossless) "LOSSLESS" else track.format.ifBlank { "AUDIO" },
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (track.qualityRating.isLossless) Color(0xFF00E676) else Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // Top-Right: Multi-Select Checkbox
                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelection() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = theme.accent,
                                checkmarkColor = Color.White,
                                uncheckedColor = Color.White.copy(alpha = 0.8f)
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Bottom-Left: Playing / Current Indicator
                if (isCurrent && isAvailable) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = theme.accent
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Playing" else "Paused",
                                    tint = theme.onAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = if (isPlaying) "PLAYING" else "PAUSED",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    color = theme.onAccent
                                )
                            }
                        }
                    }
                }

                // Bottom-Right: Duration Pill
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = formattedDuration,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // ── BOTTOM: Track Info & DJ Details ─────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Title
                Text(
                    text = track.title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (!isAvailable) theme.textDisabled else if (isCurrent) theme.accent else theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Artist
                Text(
                    text = track.artist.ifBlank { "Unknown Artist" },
                    fontSize = 11.sp,
                    color = if (!isAvailable) theme.textDisabled else theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Album (if distinct)
                if (track.album.isNotBlank() && track.album != "Single" && track.album != "Unknown Album") {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = track.album,
                        fontSize = 10.sp,
                        color = theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Row: DJ Metadata Pills & More Actions Menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left DJ Pills (BPM & Key)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (track.bpm > 0) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = theme.surfaceSunken,
                                border = BorderStroke(0.5.dp, theme.divider)
                            ) {
                                Text(
                                    text = "${track.bpm.toInt()} BPM",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (track.musicalKey.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = theme.surfaceSunken,
                                border = BorderStroke(0.5.dp, theme.divider)
                            ) {
                                Text(
                                    text = track.camelotKey.ifBlank { track.musicalKey },
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (track.hasPlaybackIssue) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Playback Issue",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        MetadataProvenanceBadge(track = track, compact = true)
                    }

                    // Right: Context Menu Button
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("track_grid_menu_${track.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = theme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(theme.surfaceRaised)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add to Playlist", color = theme.textPrimary) },
                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = theme.accent) },
                                onClick = {
                                    showMenu = false
                                    onAddToPlaylist()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Play Next", color = theme.textPrimary) },
                                leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null, tint = theme.accent) },
                                onClick = {
                                    showMenu = false
                                    onQueueTrack(true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Queue", color = theme.textPrimary) },
                                leadingIcon = { Icon(Icons.Default.Queue, contentDescription = null, tint = theme.textSecondary) },
                                onClick = {
                                    showMenu = false
                                    onQueueTrack(false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Analyse Spectrogram", color = theme.textPrimary) },
                                leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = theme.textSecondary) },
                                onClick = {
                                    showMenu = false
                                    onInspectSpectrogram()
                                }
                            )
                            if (onMixWithThis != null) {
                                DropdownMenuItem(
                                    text = { Text("Mix With This", color = theme.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = theme.accent) },
                                    onClick = {
                                        showMenu = false
                                        onMixWithThis()
                                    }
                                )
                            }
                            if (onInspectQuality != null) {
                                DropdownMenuItem(
                                    text = { Text("Inspect Quality", color = theme.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF00E676)) },
                                    onClick = {
                                        showMenu = false
                                        onInspectQuality()
                                    }
                                )
                            }
                            if (onOpenLyrics != null) {
                                DropdownMenuItem(
                                    text = { Text("Lyrics", color = theme.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, tint = theme.accent) },
                                    onClick = {
                                        showMenu = false
                                        onOpenLyrics()
                                    }
                                )
                            }
                            if (onOpenTrackIntelligence != null) {
                                DropdownMenuItem(
                                    text = { Text("Track Intelligence", color = theme.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = theme.accent) },
                                    onClick = {
                                        showMenu = false
                                        onOpenTrackIntelligence()
                                    }
                                )
                            }
                            if (track.hasPlaybackIssue && onOpenPlaybackIssueSheet != null) {
                                DropdownMenuItem(
                                    text = { Text("Diagnose & Repair Issue", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        onOpenPlaybackIssueSheet(track)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Properties", color = theme.textPrimary) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = theme.textSecondary) },
                                onClick = {
                                    showMenu = false
                                    onInspectProperties()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
