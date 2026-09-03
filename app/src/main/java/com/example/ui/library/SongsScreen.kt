package com.example.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.example.model.AudioQualityRating
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

enum class SongSortMode(val label: String) {
    TITLE_ASC("Title (A-Z)"),
    ARTIST_ASC("Artist (A-Z)"),
    ALBUM_ASC("Album (A-Z)"),
    BPM_DESC("BPM (High-Low)"),
    DATE_DESC("Recently Added")
}

@Composable
fun SongsScreen(
    tracks: List<Track>,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    hideUnavailableTracks: Boolean = false,
    onToggleHideUnavailable: () -> Unit = {},
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>, Boolean) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onQueueTrack: (Track, Boolean) -> Unit,
    onInspectProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onStartScan: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SongSortMode.TITLE_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredTracks = remember(tracks, searchQuery, sortMode, hideUnavailableTracks) {
        val q = searchQuery.trim().lowercase()
        val base = tracks.filter { track ->
            val matchesAvailability = !hideUnavailableTracks || track.isAvailable
            val matchesQuery = q.isBlank() ||
                track.title.lowercase().contains(q) ||
                track.artist.lowercase().contains(q) ||
                track.album.lowercase().contains(q) ||
                track.format.lowercase().contains(q)
            matchesAvailability && matchesQuery
        }

        when (sortMode) {
            SongSortMode.TITLE_ASC -> base.sortedBy { it.title.lowercase() }
            SongSortMode.ARTIST_ASC -> base.sortedBy { it.artist.lowercase() }
            SongSortMode.ALBUM_ASC -> base.sortedBy { it.album.lowercase() }
            SongSortMode.BPM_DESC -> base.sortedByDescending { it.bpm }
            SongSortMode.DATE_DESC -> base.sortedByDescending { it.dateAdded }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("songs_screen")
    ) {
        // Search & Filter Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search songs, artists, albums...", fontSize = 13.sp, color = TextMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DeckACyan,
                    unfocusedBorderColor = DjSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = DeckACyan,
                    unfocusedContainerColor = DjSurfaceDark,
                    focusedContainerColor = DjSurfaceDark
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("songs_search_input")
            )

            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DjSurfaceDark)
                        .testTag("songs_sort_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort Songs",
                        tint = DeckACyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    modifier = Modifier.background(DjSurfaceElevated)
                ) {
                    SongSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mode.label,
                                    color = if (sortMode == mode) DeckACyan else TextPrimary,
                                    fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                sortMode = mode
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Action Toolbar (Play All, Shuffle All, Count, Filter)
        if (filteredTracks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${filteredTracks.size} tracks",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )

                    FilterChip(
                        selected = hideUnavailableTracks,
                        onClick = onToggleHideUnavailable,
                        label = {
                            Text(
                                text = if (hideUnavailableTracks) "Playable Only" else "Show All",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (hideUnavailableTracks) Icons.Default.Check else Icons.Default.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = DjSurfaceCard,
                            labelColor = TextSecondary,
                            selectedContainerColor = DeckACyan.copy(alpha = 0.2f),
                            selectedLabelColor = DeckACyan,
                            selectedLeadingIconColor = DeckACyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = hideUnavailableTracks,
                            borderColor = DjSurfaceBorder,
                            selectedBorderColor = DeckACyan,
                            borderWidth = 1.dp
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp).testTag("filter_unavailable_tracks_chip")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPlayAll(filteredTracks, false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckACyan.copy(alpha = 0.15f),
                            contentColor = DeckACyan
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("play_all_songs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onPlayAll(filteredTracks, true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckBPink.copy(alpha = 0.15f),
                            contentColor = DeckBPink
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("shuffle_all_songs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Shuffle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Track List / Empty State
        if (filteredTracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) "No songs match '$searchQuery'" else "No local tracks indexed yet",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    if (searchQuery.isBlank()) {
                        Button(
                            onClick = onStartScan,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeckACyan,
                                contentColor = DjObsidian
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("empty_scan_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan Device Storage", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(filteredTracks, key = { it.id }) { track ->
                    SongTrackRow(
                        track = track,
                        isCurrent = currentPlayingTrack?.id == track.id,
                        isPlaying = isPlaying && currentPlayingTrack?.id == track.id,
                        onClick = { onPlayTrack(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onQueueTrack = { playNext -> onQueueTrack(track, playNext) },
                        onInspectProperties = { onInspectProperties(track) },
                        onInspectSpectrogram = { onInspectSpectrogram(track) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongTrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueTrack: (playNext: Boolean) -> Unit,
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
        shape = RoundedCornerShape(10.dp),
        color = if (isCurrent) DeckACyan.copy(alpha = 0.08f) else DjSurfaceDark.copy(alpha = if (isAvailable) 1f else 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrent) DeckACyan.copy(alpha = 0.5f) else DjSurfaceBorder.copy(alpha = if (isAvailable) 0.5f else 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
            .testTag("song_track_row_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork / Format Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isCurrent) DeckACyan.copy(alpha = 0.2f)
                        else DjSurfaceCard
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!isAvailable) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Disconnected",
                        tint = TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                } else if (isCurrent) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        tint = DeckACyan,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = track.format,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (track.format == "FLAC" || track.format == "WAV") NeonGreen else DeckACyan
                        )
                        if (track.bitrateKbps > 0) {
                            Text(
                                text = "${track.bitrateKbps}k",
                                fontSize = 9.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (!isAvailable) TextMuted else if (isCurrent) DeckACyan else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        fontSize = 12.sp,
                        color = if (!isAvailable) TextMuted.copy(alpha = 0.7f) else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (track.album.isNotBlank() && track.album != "Single") {
                        Text(text = "•", fontSize = 10.sp, color = TextMuted)
                        Text(
                            text = track.album,
                            fontSize = 11.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // DJ Pills (BPM, Key, Quality, Disconnected)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isAvailable) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DjSurfaceElevated.copy(alpha = 0.7f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "DISCONNECTED",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted
                                )
                            }
                        }
                    }

                    if (track.bpm > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DjSurfaceElevated
                        ) {
                            Text(
                                text = "${track.bpm.toInt()} BPM",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeckACyan,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (track.musicalKey.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DjSurfaceElevated
                        ) {
                            Text(
                                text = track.musicalKey,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeckBPink,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (track.qualityRating == AudioQualityRating.TRUE_LOSSLESS) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonGreen.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "LOSSLESS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NeonGreen,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    MetadataProvenanceBadge(track = track, compact = true)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Duration & Menu
            Text(
                text = formattedDuration,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("track_menu_button_${track.id}")
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
