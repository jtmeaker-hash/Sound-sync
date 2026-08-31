package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.ExplorerSortOption
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun LocalMusicView(
    tracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    isScanning: Boolean,
    scanProgressText: String,
    onScanMediaStore: () -> Unit,
    onPickSafFolder: () -> Unit,
    onPickAudioFiles: () -> Unit,
    onLoadTrack: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedSort by remember { mutableStateOf(ExplorerSortOption.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredTracks = remember(tracks, searchQuery, selectedSort) {
        val query = searchQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) {
            tracks
        } else {
            tracks.filter {
                it.title.lowercase().contains(query) ||
                it.artist.lowercase().contains(query) ||
                it.album.lowercase().contains(query) ||
                it.genre.lowercase().contains(query) ||
                it.format.lowercase().contains(query)
            }
        }

        when (selectedSort) {
            ExplorerSortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            ExplorerSortOption.BPM_ASC -> filtered.sortedBy { it.bpm }
            ExplorerSortOption.BPM_DESC -> filtered.sortedByDescending { it.bpm }
            ExplorerSortOption.KEY -> filtered.sortedBy { it.musicalKey }
            ExplorerSortOption.QUALITY -> filtered.sortedByDescending { it.qualityRating.cutoffKhz }
            ExplorerSortOption.ENERGY_DESC -> filtered.sortedByDescending { it.energyRating }
            ExplorerSortOption.DATE_DESC -> filtered.sortedByDescending { it.dateAdded }
            ExplorerSortOption.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeMb }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Search & Sort Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("local_search_input"),
                placeholder = { Text("Search title, artist, genre...", color = TextMuted, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(18.dp))
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DeckACyan,
                    unfocusedBorderColor = DjSurfaceBorder,
                    focusedContainerColor = DjSurfaceCard,
                    unfocusedContainerColor = DjSurfaceCard,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            // Sort Menu Button
            Box {
                Surface(
                    modifier = Modifier
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showSortMenu = true },
                    shape = RoundedCornerShape(10.dp),
                    color = DjSurfaceCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort", tint = DeckACyan, modifier = Modifier.size(18.dp))
                        Text(selectedSort.displayName.split(" ")[0], color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    modifier = Modifier.background(DjSurfaceElevated)
                ) {
                    ExplorerSortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName, color = TextPrimary, fontSize = 13.sp) },
                            onClick = {
                                selectedSort = option
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Action Toolbar (Rescan, Import SAF Folder, Import Files)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onScanMediaStore,
                enabled = !isScanning,
                modifier = Modifier.weight(1f).height(38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckACyan.copy(alpha = 0.2f),
                    contentColor = DeckACyan
                ),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(color = DeckACyan, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scanning...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Device", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = onPickSafFolder,
                modifier = Modifier.weight(1f).height(38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DjSurfaceElevated,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Folder", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onPickAudioFiles,
                modifier = Modifier.weight(1f).height(38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DjSurfaceElevated,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Files", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Scanning Status Alert Banner
        if (isScanning) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DjSurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = DeckACyan, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Text(
                        text = scanProgressText.ifBlank { "Indexing music files in background..." },
                        color = DeckACyan,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Track Count & Stats Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOCAL TRACKS (${filteredTracks.size})",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
            val losslessCount = tracks.count { it.qualityRating.isLossless }
            if (losslessCount > 0) {
                Text(
                    text = "$losslessCount LOSSLESS FLAC/WAV",
                    color = NeonGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Track List / Empty State
        if (filteredTracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "No tracks matched '$searchQuery'" else "No local music found on device",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Scan your internal audio, or select a folder/audio files to populate your local DJ vault.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (searchQuery.isBlank()) {
                        Button(
                            onClick = onScanMediaStore,
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Start Device Scan", color = DjObsidian, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("local_tracks_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 72.dp)
            ) {
                items(filteredTracks, key = { it.id }) { track ->
                    val isCurrent = track.id == currentTrack?.id
                    LocalTrackCard(
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        onPlay = { onLoadTrack(track) },
                        onInspect = { onInspectSpectrogram(track) }
                    )
                }
            }
        }
    }
}

@Composable
fun LocalTrackCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPlay() }
            .testTag("track_card_${track.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) DjSurfaceElevated else DjSurfaceCard
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrent) DeckACyan else DjSurfaceBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Play / Status Indicator Icon Button
            Surface(
                shape = CircleShape,
                color = if (isCurrent) DeckACyan else DjSurfaceDark,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { onPlay() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = if (isCurrent) DjObsidian else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Title, Artist, Bitrate Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isCurrent) DeckACyan else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text("•", color = TextMuted, fontSize = 10.sp)
                    Text(
                        text = "${track.format} ${track.bitrateKbps}k",
                        color = if (track.qualityRating.isLossless) NeonGreen else TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // BPM, Key & Quality Pill
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    QualityPill(track.qualityRating)
                    IconButton(
                        onClick = onInspect,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Inspect Spectrogram",
                            tint = DeckACyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                val durM = track.durationSeconds / 60
                val durS = track.durationSeconds % 60
                Text(
                    text = if (track.hasValidBpm) String.format(Locale.US, "%d:%02d • %.0f BPM", durM, durS, track.bpm) else String.format(Locale.US, "%d:%02d • BPM —", durM, durS),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
