package com.example.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import com.example.ui.components.LibrarySearchBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.material3.TextButton
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
import com.example.ui.theme.SoundSyncTheme
import com.example.ui.theme.LocalLibraryDensity
import com.example.ui.theme.ProLibraryDensity
import java.util.Locale

enum class SongSortMode(val label: String) {
    TITLE_ASC("Title (A-Z)"),
    TITLE_DESC("Title (Z-A)"),
    ARTIST_ASC("Artist (A-Z)"),
    ARTIST_DESC("Artist (Z-A)"),
    ALBUM_ASC("Album (A-Z)"),
    BPM_DESC("BPM (High-Low)"),
    BPM_ASC("BPM (Low-High)"),
    KEY_ASC("Key (A-Z)"),
    KEY_DESC("Key (Z-A)"),
    DURATION_DESC("Duration (Long-Short)"),
    DURATION_ASC("Duration (Short-Long)"),
    DATE_DESC("Recently Added");

    fun sort(tracks: List<Track>): List<Track> {
        return when (this) {
            TITLE_ASC -> tracks.sortedBy { it.title.lowercase() }
            TITLE_DESC -> tracks.sortedByDescending { it.title.lowercase() }
            ARTIST_ASC -> tracks.sortedBy { it.artist.lowercase() }
            ARTIST_DESC -> tracks.sortedByDescending { it.artist.lowercase() }
            ALBUM_ASC -> tracks.sortedBy { it.album.lowercase() }
            BPM_DESC -> tracks.sortedByDescending { it.bpm }
            BPM_ASC -> tracks.sortedBy { it.bpm }
            KEY_ASC -> tracks.sortedBy { it.camelotKey.ifBlank { it.musicalKey }.lowercase() }
            KEY_DESC -> tracks.sortedByDescending { it.camelotKey.ifBlank { it.musicalKey }.lowercase() }
            DURATION_DESC -> tracks.sortedByDescending { it.durationSeconds }
            DURATION_ASC -> tracks.sortedBy { it.durationSeconds }
            DATE_DESC -> tracks.sortedByDescending { it.dateAdded }
        }
    }
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
    onStartScan: () -> Unit,
    onBulkEditTracks: ((List<Track>) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SongSortMode.TITLE_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(setOf<String>()) }

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

        sortMode.sort(base)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("songs_screen")
    ) {
        // Multi-Select Header Toolbar
        if (selectedTrackIds.isNotEmpty()) {
            Surface(
                color = DjSurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { selectedTrackIds = emptySet() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection", tint = TextPrimary, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = "${selectedTrackIds.size} selected",
                            color = DeckACyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                if (selectedTrackIds.size == filteredTracks.size) {
                                    selectedTrackIds = emptySet()
                                } else {
                                    selectedTrackIds = filteredTracks.map { it.id }.toSet()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (selectedTrackIds.size == filteredTracks.size) "Deselect All" else "Select All",
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }

                        Button(
                            onClick = {
                                val selectedList = filteredTracks.filter { it.id in selectedTrackIds }
                                onBulkEditTracks?.invoke(selectedList)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Bulk Edit", color = DjObsidian, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Search & Filter Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibrarySearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholderText = "Search songs, artists, albums...",
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                testTag = "songs_search_input"
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
            val isPro = SoundSyncTheme.isPro
            if (isPro) {
                ProSongsTableHeader(
                    sortMode = sortMode,
                    onSortChange = { sortMode = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isPro) 4.dp else 12.dp),
                verticalArrangement = if (isPro) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(filteredTracks, key = { _, track -> track.id }) { index, track ->
                    val isSelected = selectedTrackIds.contains(track.id)
                    SongTrackRow(
                        track = track,
                        index = index,
                        isCurrent = currentPlayingTrack?.id == track.id,
                        isPlaying = isPlaying && currentPlayingTrack?.id == track.id,
                        isSelectionMode = selectedTrackIds.isNotEmpty(),
                        isSelected = isSelected,
                        onToggleSelection = {
                            selectedTrackIds = if (isSelected) {
                                selectedTrackIds - track.id
                            } else {
                                selectedTrackIds + track.id
                            }
                        },
                        onClick = {
                            if (selectedTrackIds.isNotEmpty()) {
                                selectedTrackIds = if (isSelected) selectedTrackIds - track.id else selectedTrackIds + track.id
                            } else {
                                onPlayTrack(track)
                            }
                        },
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SongTrackRow(
    track: Track,
    index: Int? = null,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueTrack: (playNext: Boolean) -> Unit,
    onInspectProperties: () -> Unit,
    onInspectSpectrogram: () -> Unit
) {
    if (SoundSyncTheme.isPro) {
        ProSongTrackRow(
            track = track,
            index = index,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            onToggleSelection = onToggleSelection,
            onClick = onClick,
            onAddToPlaylist = onAddToPlaylist,
            onQueueTrack = onQueueTrack,
            onInspectProperties = onInspectProperties,
            onInspectSpectrogram = onInspectSpectrogram
        )
        return
    }

    var showMenu by remember { mutableStateOf(false) }

    val formattedDuration = remember(track.durationSeconds) {
        val min = track.durationSeconds / 60
        val sec = track.durationSeconds % 60
        String.format("%d:%02d", min, sec)
    }

    val isAvailable = track.isAvailable

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) DeckACyan.copy(alpha = 0.15f)
                else if (isCurrent) DeckACyan.copy(alpha = 0.08f)
                else DjSurfaceDark.copy(alpha = if (isAvailable) 1f else 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) DeckACyan
            else if (isCurrent) DeckACyan.copy(alpha = 0.5f)
            else DjSurfaceBorder.copy(alpha = if (isAvailable) 0.5f else 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (isSelectionMode) {
                        onToggleSelection()
                    } else {
                        onToggleSelection()
                    }
                }
            )
            .testTag("song_track_row_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Multi-select Checkbox
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = DeckACyan,
                        checkmarkColor = DjObsidian,
                        uncheckedColor = TextMuted
                    ),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
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

                // DJ Pills (BPM, Key, Quality, Disconnected, Provenance)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                    color = TextMuted,
                                    maxLines = 1,
                                    softWrap = false
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
                                maxLines = 1,
                                softWrap = false,
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
                                maxLines = 1,
                                softWrap = false,
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
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    MetadataProvenanceBadge(track = track, compact = true)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Duration & Menu
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedDuration,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    maxLines = 1
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
}

/**
 * Sortable desktop column header bar for Pro theme library browser.
 * Inspired by Pioneer rekordbox workstation library columns:
 * - # (Track index / chronological)
 * - Art (Album art thumbnail)
 * - Title (Sortable A-Z / Z-A)
 * - Artist (Sortable A-Z / Z-A)
 * - BPM (Sortable High-Low / Low-High)
 * - Key (Sortable Musical / Camelot Key)
 * - Time (Sortable Duration)
 * - Quality (Format & Lossless)
 */
@Composable
private fun ProSongsTableHeader(
    sortMode: SongSortMode,
    onSortChange: (SongSortMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = SoundSyncTheme.current
    Surface(
        color = theme.surfaceSunken,
        border = BorderStroke(0.5.dp, theme.divider),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // # column
            Text(
                text = "#",
                color = if (sortMode == SongSortMode.DATE_DESC) theme.accent else theme.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .width(28.dp)
                    .clickable { onSortChange(SongSortMode.DATE_DESC) }
            )

            // Artwork spacer
            Spacer(modifier = Modifier.width(30.dp))

            // Title Column (sortable)
            Row(
                modifier = Modifier
                    .weight(2.4f)
                    .clickable {
                        onSortChange(if (sortMode == SongSortMode.TITLE_ASC) SongSortMode.TITLE_DESC else SongSortMode.TITLE_ASC)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TITLE",
                    color = if (sortMode == SongSortMode.TITLE_ASC || sortMode == SongSortMode.TITLE_DESC) theme.accent else theme.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                if (sortMode == SongSortMode.TITLE_ASC) Text(" ▲", color = theme.accent, fontSize = 9.sp)
                if (sortMode == SongSortMode.TITLE_DESC) Text(" ▼", color = theme.accent, fontSize = 9.sp)
            }

            // Artist Column (sortable)
            Row(
                modifier = Modifier
                    .weight(1.8f)
                    .clickable {
                        onSortChange(if (sortMode == SongSortMode.ARTIST_ASC) SongSortMode.ARTIST_DESC else SongSortMode.ARTIST_ASC)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ARTIST",
                    color = if (sortMode == SongSortMode.ARTIST_ASC || sortMode == SongSortMode.ARTIST_DESC) theme.accent else theme.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                if (sortMode == SongSortMode.ARTIST_ASC) Text(" ▲", color = theme.accent, fontSize = 9.sp)
                if (sortMode == SongSortMode.ARTIST_DESC) Text(" ▼", color = theme.accent, fontSize = 9.sp)
            }

            // BPM Column (sortable)
            Row(
                modifier = Modifier
                    .width(46.dp)
                    .clickable {
                        onSortChange(if (sortMode == SongSortMode.BPM_DESC) SongSortMode.BPM_ASC else SongSortMode.BPM_DESC)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "BPM",
                    color = if (sortMode == SongSortMode.BPM_DESC || sortMode == SongSortMode.BPM_ASC) theme.accent else theme.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (sortMode == SongSortMode.BPM_DESC) Text("▼", color = theme.accent, fontSize = 8.sp)
                if (sortMode == SongSortMode.BPM_ASC) Text("▲", color = theme.accent, fontSize = 8.sp)
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Key Column (sortable)
            Row(
                modifier = Modifier
                    .width(36.dp)
                    .clickable {
                        onSortChange(if (sortMode == SongSortMode.KEY_ASC) SongSortMode.KEY_DESC else SongSortMode.KEY_ASC)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "KEY",
                    color = if (sortMode == SongSortMode.KEY_ASC || sortMode == SongSortMode.KEY_DESC) theme.accent else theme.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (sortMode == SongSortMode.KEY_ASC) Text("▲", color = theme.accent, fontSize = 8.sp)
                if (sortMode == SongSortMode.KEY_DESC) Text("▼", color = theme.accent, fontSize = 8.sp)
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Time Column (sortable)
            Row(
                modifier = Modifier
                    .width(42.dp)
                    .clickable {
                        onSortChange(if (sortMode == SongSortMode.DURATION_DESC) SongSortMode.DURATION_ASC else SongSortMode.DURATION_DESC)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "TIME",
                    color = if (sortMode == SongSortMode.DURATION_DESC || sortMode == SongSortMode.DURATION_ASC) theme.accent else theme.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (sortMode == SongSortMode.DURATION_DESC) Text("▼", color = theme.accent, fontSize = 8.sp)
                if (sortMode == SongSortMode.DURATION_ASC) Text("▲", color = theme.accent, fontSize = 8.sp)
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Quality Column
            Text(
                text = "QUAL",
                color = theme.textMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(36.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // ⋮ Menu spacer
            Spacer(modifier = Modifier.width(30.dp))
        }
    }
}

/**
 * Rectangular, high-density desktop row for Pro theme.
 * Supports Compact (38dp) and Comfortable (48dp) row density modes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProSongTrackRow(
    track: Track,
    index: Int?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueTrack: (playNext: Boolean) -> Unit,
    onInspectProperties: () -> Unit,
    onInspectSpectrogram: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val theme = SoundSyncTheme.current
    val density = LocalLibraryDensity.current

    val rowHeight = if (density == ProLibraryDensity.COMPACT) 38.dp else 48.dp
    val thumbSize = if (density == ProLibraryDensity.COMPACT) 26.dp else 34.dp
    val titleSize = if (density == ProLibraryDensity.COMPACT) 12.sp else 13.5.sp
    val subSize = if (density == ProLibraryDensity.COMPACT) 10.sp else 11.sp

    val formattedDuration = remember(track.durationSeconds) {
        val min = track.durationSeconds / 60
        val sec = track.durationSeconds % 60
        String.format(Locale.US, "%d:%02d", min, sec)
    }

    val isAvailable = track.isAvailable

    val rowBg = when {
        isSelected -> theme.selectedSurface
        isCurrent -> theme.playingSurface
        else -> Color.Transparent
    }

    Surface(
        shape = RoundedCornerShape(theme.cornerSmall),
        color = rowBg,
        border = if (isSelected) BorderStroke(0.5.dp, theme.accent) else null,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection
            )
            .testTag("song_track_row_${track.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Subtle blue left edge indicator for current playing track or selected track
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (isCurrent || isSelected) theme.accent else Color.Transparent)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Multi-select Checkbox
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = theme.accent,
                            checkmarkColor = Color.White,
                            uncheckedColor = theme.textMuted
                        ),
                        modifier = Modifier
                            .size(26.dp)
                            .padding(end = 4.dp)
                    )
                }

                // # / Play Status indicator (width 28dp)
                Box(
                    modifier = Modifier.width(28.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isCurrent) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Playing" else "Paused",
                            tint = theme.accent,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = if (index != null) "${index + 1}" else "",
                            color = theme.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Artwork Thumbnail (size thumbSize)
                Box(
                    modifier = Modifier
                        .size(thumbSize)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isCurrent) theme.surfaceRaised else theme.surfaceSunken)
                        .border(0.5.dp, theme.divider, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isAvailable) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Unavailable",
                            tint = theme.textDisabled,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            text = track.format.take(3),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (track.qualityRating.isLossless) Color(0xFF30D158) else theme.accent,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Title (weight 2.4f)
                Text(
                    text = track.title,
                    fontSize = titleSize,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (!isAvailable) theme.textDisabled else if (isCurrent) theme.accent else theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2.4f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Artist (weight 1.8f)
                Text(
                    text = track.artist,
                    fontSize = subSize,
                    color = if (!isAvailable) theme.textDisabled else theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.8f)
                )

                // BPM (width 46dp)
                Text(
                    text = if (track.bpm > 0) String.format(Locale.US, "%.1f", track.bpm) else "—",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) theme.accent else theme.textSecondary,
                    modifier = Modifier.width(46.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Key (width 36dp)
                val keyDisplay = track.camelotKey.ifBlank { track.musicalKey.ifBlank { "—" } }
                Text(
                    text = keyDisplay,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.width(36.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Time (width 42dp)
                Text(
                    text = formattedDuration,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    modifier = Modifier.width(42.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Quality Badge (width 36dp)
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = theme.surfaceRaised,
                    border = BorderStroke(0.5.dp, theme.divider),
                    modifier = Modifier.width(36.dp)
                ) {
                    Text(
                        text = if (track.qualityRating.isLossless) "FLAC" else if (track.bitrateKbps > 0) "${track.bitrateKbps}" else track.format,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (track.qualityRating.isLossless) Color(0xFF30D158) else theme.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }

                // ⋮ Options Menu Button
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("track_menu_button_${track.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = theme.textMuted,
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
                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFFA855F7)) },
                            onClick = {
                                showMenu = false
                                onInspectSpectrogram()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Track Inspector", color = theme.accent, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = theme.accent) },
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
