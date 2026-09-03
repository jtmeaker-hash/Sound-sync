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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Queue
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
import com.example.model.Track
import com.example.model.TrackFolder
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
fun FolderDetailScreen(
    folder: TrackFolder,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>, Boolean) -> Unit,
    onAddFolderToPlaylist: (List<Track>) -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onQueueTrack: (Track, Boolean) -> Unit,
    onInspectProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit
) {
    val formattedTotalDuration = remember(folder.totalDurationSeconds) {
        val min = folder.totalDurationSeconds / 60
        val sec = folder.totalDurationSeconds % 60
        if (min >= 60) {
            "${min / 60}h ${min % 60}m"
        } else {
            "$min min $sec sec"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("folder_detail_screen")
    ) {
        // Top Back Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceDark)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DeckACyan
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = folder.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Folder Header Card
            item {
                Surface(
                    color = DjSurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DjSurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = DeckACyan,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.name,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = folder.path,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "${folder.trackCount} tracks · $formattedTotalDuration",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = DeckACyan
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Action Buttons: Play All, Shuffle, Add to Playlist
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onPlayAll(folder.tracks, false) },
                                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = DjObsidian,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Play All",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DjObsidian
                                )
                            }

                            OutlinedButton(
                                onClick = { onPlayAll(folder.tracks, true) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = null,
                                    tint = DeckBPink,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Shuffle",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }

                            OutlinedButton(
                                onClick = { onAddFolderToPlaylist(folder.tracks) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = null,
                                    tint = NeonGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Playlist",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Track List Items
            itemsIndexed(folder.tracks, key = { _, track -> track.id }) { index, track ->
                val isAvailable = track.isAvailable
                val isCurrentlyPlaying = currentPlayingTrack?.id == track.id
                var showTrackMenu by remember { mutableStateOf(false) }

                Surface(
                    color = if (isCurrentlyPlaying) DjSurfaceElevated else DjSurfaceCard.copy(alpha = if (isAvailable) 1f else 0.45f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isCurrentlyPlaying) DeckACyan else DjSurfaceBorder.copy(alpha = if (isAvailable) 1f else 0.35f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clickable { onPlayTrack(track) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Track Number or Playing Equalizer
                        Box(
                            modifier = Modifier.width(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!isAvailable) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = "Disconnected",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else if (isCurrentlyPlaying) {
                                Icon(
                                    imageVector = Icons.Default.Equalizer,
                                    contentDescription = "Playing",
                                    tint = if (isPlaying) NeonGreen else DeckACyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Title and Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                fontSize = 14.sp,
                                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isAvailable) TextMuted else if (isCurrentlyPlaying) DeckACyan else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = track.artist,
                                    fontSize = 11.sp,
                                    color = if (!isAvailable) TextMuted.copy(alpha = 0.7f) else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )

                                if (!isAvailable) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "DISCONNECTED",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )
                                } else if (track.isAiTagged) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    MetadataProvenanceBadge(track = track)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // BPM / Key Badges
                        if (track.bpm > 0) {
                            Surface(
                                color = DjSurfaceDark,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "${track.bpm.toInt()} BPM",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = DeckBPink,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        if (track.camelotKey.isNotBlank()) {
                            Surface(
                                color = DjSurfaceDark,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = track.camelotKey,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonPurple,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Duration
                        val durationStr = remember(track.durationSeconds) {
                            val m = track.durationSeconds / 60
                            val s = track.durationSeconds % 60
                            String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
                        }
                        Text(
                            text = durationStr,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )

                        // Overflow Menu Button
                        Box {
                            IconButton(
                                onClick = { showTrackMenu = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showTrackMenu,
                                onDismissRequest = { showTrackMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Play Next") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Queue,
                                            contentDescription = null,
                                            tint = DeckACyan
                                        )
                                    },
                                    onClick = {
                                        showTrackMenu = false
                                        onQueueTrack(track, true)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to Queue") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.QueueMusic,
                                            contentDescription = null,
                                            tint = TextSecondary
                                        )
                                    },
                                    onClick = {
                                        showTrackMenu = false
                                        onQueueTrack(track, false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to Playlist") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PlaylistAdd,
                                            contentDescription = null,
                                            tint = NeonGreen
                                        )
                                    },
                                    onClick = {
                                        showTrackMenu = false
                                        onAddTrackToPlaylist(track)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Inspect Spectrogram") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = DeckBPink
                                        )
                                    },
                                    onClick = {
                                        showTrackMenu = false
                                        onInspectSpectrogram(track)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("File Properties & Tags") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = TextMuted
                                        )
                                    },
                                    onClick = {
                                        showTrackMenu = false
                                        onInspectProperties(track)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
