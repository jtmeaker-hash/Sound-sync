package com.example.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Playlist
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    allAvailableTracks: List<Track>,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>, Boolean) -> Unit,
    onAddTracksToPlaylist: (List<Track>) -> Unit,
    onRemoveTrack: (position: Int) -> Unit,
    onReorderTrack: (fromPos: Int, toPos: Int) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onExportToRockbox: () -> Unit,
    onQueueTrack: (Track, Boolean) -> Unit,
    onInspectProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddTracksSheet by remember { mutableStateOf(false) }

    val formattedTotalDuration = remember(playlist.totalDurationSeconds) {
        val min = playlist.totalDurationSeconds / 60
        val sec = playlist.totalDurationSeconds % 60
        "$min min $sec s"
    }

    if (showRenameDialog) {
        CreatePlaylistDialog(
            initialName = playlist.name,
            confirmButtonText = "Rename",
            titleText = "Rename Playlist",
            onConfirm = { newName, _ ->
                onRenamePlaylist(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showAddTracksSheet) {
        SelectTracksForPlaylistSheet(
            availableTracks = allAvailableTracks,
            existingTrackIds = playlist.tracks.map { it.id }.toSet(),
            onAddTracks = { selected ->
                onAddTracksToPlaylist(selected)
                showAddTracksSheet = false
            },
            onDismiss = { showAddTracksSheet = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlist_detail_screen")
    ) {
        // Top Back & Options Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("playlist_detail_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = DeckACyan
                    )
                }

                Text(
                    text = "Playlist",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Row {
                IconButton(
                    onClick = { showRenameDialog = true },
                    modifier = Modifier.testTag("playlist_detail_rename_button")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextSecondary, modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = onExportToRockbox,
                    modifier = Modifier.testTag("playlist_detail_export_button")
                ) {
                    Icon(Icons.Default.DriveFileMove, contentDescription = "Export to Rockbox", tint = NeonGreen, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Playlist Header Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DjSurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DjSurfaceCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = DeckACyan,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${playlist.trackCount} tracks • $formattedTotalDuration",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                        if (playlist.backingRelativePath != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Path: ${playlist.backingRelativePath}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NeonGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Cross storage or missing track warning banner
                if (playlist.hasCrossStorageWarning || playlist.missingTrackCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NeonAmber.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonAmber.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (playlist.hasCrossStorageWarning)
                                    "Tracks span multiple storage devices. Rockbox requires tracks on the same drive."
                                else "${playlist.missingTrackCount} track(s) in this playlist could not be found.",
                                fontSize = 11.sp,
                                color = NeonAmber
                            )
                        }
                    }
                }
            }
        }

        // Action Toolbar (Play All, Shuffle, + Add Tracks)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onPlayAll(playlist.tracks, false) },
                enabled = playlist.tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckACyan,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("playlist_play_all_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play All", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { onPlayAll(playlist.tracks, true) },
                enabled = playlist.tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckBPink,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("playlist_shuffle_button")
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { showAddTracksSheet = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DjSurfaceElevated,
                    contentColor = DeckACyan
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("playlist_add_tracks_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Songs", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Tracks in Playlist List
        if (playlist.tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "This playlist is empty",
                        fontSize = 15.sp,
                        color = TextSecondary
                    )
                    Button(
                        onClick = { showAddTracksSheet = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckACyan,
                            contentColor = DjObsidian
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Songs to Playlist", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(playlist.tracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                    PlaylistTrackRow(
                        position = index,
                        totalCount = playlist.tracks.size,
                        track = track,
                        isCurrent = currentPlayingTrack?.id == track.id,
                        isPlaying = isPlaying && currentPlayingTrack?.id == track.id,
                        onClick = { onPlayTrack(track) },
                        onMoveUp = { onReorderTrack(index, index - 1) },
                        onMoveDown = { onReorderTrack(index, index + 1) },
                        onRemove = { onRemoveTrack(index) },
                        onQueueTrack = { playNext -> onQueueTrack(track, playNext) },
                        onInspectProperties = { onInspectProperties(track) },
                        onInspectSpectrogram = { onInspectSpectrogram(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    position: Int,
    totalCount: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onQueueTrack: (Boolean) -> Unit,
    onInspectProperties: () -> Unit,
    onInspectSpectrogram: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val formattedDuration = remember(track.durationSeconds) {
        val min = track.durationSeconds / 60
        val sec = track.durationSeconds % 60
        String.format("%d:%02d", min, sec)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent) DeckACyan.copy(alpha = 0.08f) else DjSurfaceDark,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("playlist_track_row_${track.id}_$position")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = "${position + 1}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }

            // Move Up / Down Reordering Buttons
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = position > 0,
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("move_up_${position}")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move Up",
                        tint = if (position > 0) TextSecondary else TextMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = position < totalCount - 1,
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("move_down_${position}")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move Down",
                        tint = if (position < totalCount - 1) TextSecondary else TextMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Title & Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrent) DeckACyan else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (track.bpm > 0) {
                        Text(
                            text = "${track.bpm.toInt()} BPM",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DeckACyan
                        )
                    }
                }
            }

            Text(
                text = formattedDuration,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )

            // Remove Button
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("remove_track_from_playlist_$position")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove from playlist",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("playlist_track_menu_$position")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DjSurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null, tint = DeckBPink) },
                        onClick = {
                            showMenu = false
                            onQueueTrack(true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Queue, contentDescription = null, tint = TextSecondary) },
                        onClick = {
                            showMenu = false
                            onQueueTrack(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Analyse Spectrogram", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonPurple) },
                        onClick = {
                            showMenu = false
                            onInspectSpectrogram()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Track Details", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary) },
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
