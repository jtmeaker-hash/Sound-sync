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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Album
import com.example.model.Track
import com.example.ui.components.MetadataProvenanceBadge
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AlbumDetailScreen(
    album: Album,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>, Boolean) -> Unit,
    onAddAlbumToPlaylist: (List<Track>) -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onQueueTrack: (Track, Boolean) -> Unit,
    onInspectProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit
) {
    val formattedTotalDuration = remember(album.totalDurationSeconds) {
        val min = album.totalDurationSeconds / 60
        val sec = album.totalDurationSeconds % 60
        "$min min $sec sec"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("album_detail_screen")
    ) {
        // Top Back Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("album_detail_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DeckACyan
                )
            }

            Text(
                text = "Album Details",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Album Header Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DjSurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DjSurfaceCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = album.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = album.artist,
                        fontSize = 14.sp,
                        color = DeckACyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${album.trackCount} songs • $formattedTotalDuration",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Action Buttons Row (Play, Shuffle, Add to Playlist)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onPlayAll(album.tracks, false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckACyan,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("album_play_all_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play All", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { onPlayAll(album.tracks, true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckBPink,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("album_shuffle_button")
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = { onAddAlbumToPlaylist(album.tracks) },
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("album_add_to_playlist_button")
            ) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tracks in Album List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
                AlbumTrackRow(
                    index = index + 1,
                    track = track,
                    isCurrent = currentPlayingTrack?.id == track.id,
                    isPlaying = isPlaying && currentPlayingTrack?.id == track.id,
                    onClick = { onPlayTrack(track) },
                    onAddToPlaylist = { onAddTrackToPlaylist(track) },
                    onQueueTrack = { playNext -> onQueueTrack(track, playNext) },
                    onInspectProperties = { onInspectProperties(track) },
                    onInspectSpectrogram = { onInspectSpectrogram(track) }
                )
            }
        }
    }
}

@Composable
private fun AlbumTrackRow(
    index: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
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

    val isAvailable = track.isAvailable

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent) DeckACyan.copy(alpha = 0.08f) else DjSurfaceDark.copy(alpha = if (isAvailable) 1f else 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrent) DeckACyan.copy(alpha = 0.5f) else DjSurfaceBorder.copy(alpha = if (isAvailable) 0.5f else 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("album_track_row_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Track / Disc Index
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!isAvailable) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Disconnected",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (isCurrent) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = if (track.trackNumber > 0) track.trackNumber.toString() else index.toString(),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (!isAvailable) TextMuted else if (isCurrent) DeckACyan else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isAvailable) {
                        Text(
                            text = "DISCONNECTED",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                    }
                    if (track.bpm > 0) {
                        Text(
                            text = "${track.bpm.toInt()} BPM",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DeckACyan
                        )
                    }
                    if (track.musicalKey.isNotBlank()) {
                        Text(
                            text = track.musicalKey,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DeckBPink
                        )
                    }
                    Text(
                        text = track.format,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    MetadataProvenanceBadge(track = track, compact = true)
                }
            }

            Text(
                text = formattedDuration,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("album_track_menu_${track.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DjSurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to Playlist", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = DeckACyan) },
                        onClick = {
                            showMenu = false
                            onAddToPlaylist()
                        }
                    )
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
                        text = { Text("Track Inspector", color = DeckACyan, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = DeckACyan) },
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
