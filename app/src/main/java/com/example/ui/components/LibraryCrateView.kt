package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.DjCrate
import com.example.model.MusicPlatform
import com.example.model.SyncState
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
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun LibraryCrateView(
    tracks: List<Track>,
    crates: List<DjCrate>,
    selectedCrateId: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSelectCrate: (String) -> Unit,
    onLoadToDeck: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onAutoTagSingle: (Track) -> Unit,
    onAutoTagAll: () -> Unit,
    onDeleteTrack: (Track) -> Unit,
    onPickAudioFiles: () -> Unit,
    onPickSafFolder: () -> Unit,
    onScanMediaStore: () -> Unit,
    onLoadDemoTracks: () -> Unit,
    onOpenAddTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(DjObsidian)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Search Bar & AI Auto-Tag Quick Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("search_track_input"),
                    placeholder = { Text("Search tracks, artists, keys (e.g. 8A), BPM...", color = TextMuted, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DeckACyan,
                        unfocusedBorderColor = DjSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DjSurfaceCard,
                        unfocusedContainerColor = DjSurfaceCard
                    )
                )

                Button(
                    onClick = onAutoTagAll,
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("auto_tag_all_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("AI TAG ALL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }

            // DJ Crates Selector Carousel
            LazyRow(
                modifier = Modifier.fillMaxWidth().testTag("crates_carousel"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(crates) { crate ->
                    val isSelected = crate.id == selectedCrateId
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectCrate(crate.id) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(crate.colorHex).copy(alpha = 0.25f) else DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(crate.colorHex) else DjSurfaceBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(crate.colorHex), CircleShape)
                            )
                            Text(
                                text = crate.name,
                                color = if (isSelected) Color(crate.colorHex) else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Track List Header Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tracks.size} AUDIO TRACKS IN CRATE",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "CAMELOT / BPM / SPECTRUM READY",
                    color = DeckACyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Track List
            if (tracks.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = DjSurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Audio Tracks in this Crate", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Scan device music or import audio files from your storage", color = TextSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onScanMediaStore,
                                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Scan Storage", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }

                            Button(
                                onClick = onPickAudioFiles,
                                colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pick Files", fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("track_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tracks, key = { it.id }) { track ->
                        TrackRowItem(
                            track = track,
                            onLoadToDeck = { onLoadToDeck(track) },
                            onInspectSpectrogram = { onInspectSpectrogram(track) },
                            onAutoTag = { onAutoTagSingle(track) },
                            onDelete = { onDeleteTrack(track) }
                        )
                    }
                }
            }
        }

        // Floating Action Button to Add / Import New Stem/Track
        FloatingActionButton(
            onClick = onOpenAddTrack,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_track_fab"),
            containerColor = DeckACyan,
            contentColor = DjObsidian
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Audio File")
        }
    }
}

@Composable
private fun TrackRowItem(
    track: Track,
    onLoadToDeck: () -> Unit,
    onInspectSpectrogram: () -> Unit,
    onAutoTag: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoadToDeck)
            .testTag("track_row_${track.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Camelot Key Block
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(DeckBPink.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .border(1.dp, DeckBPink, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (track.hasValidKey) track.musicalKey else "—",
                        color = DeckBPink,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Track Title, Artist, Genre & Platform badges
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = track.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )

                Text(
                    text = "${track.artist} • ${track.genre}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )

                // Tags row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QualityPill(track.qualityRating)

                    // Sync & Offline badge
                    if (track.isOfflineReady) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Default.OfflinePin, contentDescription = "Offline Ready", tint = NeonGreen, modifier = Modifier.size(12.dp))
                            Text("Offline", color = NeonGreen, fontSize = 9.sp)
                        }
                    }

                    if (track.isAiTagged) {
                        Text("AI Tagged", color = NeonPurple, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // BPM & Duration
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text(
                    text = if (track.hasValidBpm) "${track.bpm.toInt()} BPM" else "BPM —",
                    color = DeckACyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
                val durationStr = String.format(Locale.US, "%02d:%02d", track.durationSeconds / 60, track.durationSeconds % 60)
                Text(
                    text = durationStr,
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Context Menu Button
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = TextSecondary)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DjSurfaceDark)
                ) {
                    DropdownMenuItem(
                        text = { Text("Play / Preview", color = DeckACyan, fontSize = 12.sp) },
                        onClick = {
                            showMenu = false
                            onLoadToDeck()
                        },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DeckACyan) }
                    )
                    DropdownMenuItem(
                        text = { Text("Analyze Spectrogram", color = TextPrimary, fontSize = 12.sp) },
                        onClick = {
                            showMenu = false
                            onInspectSpectrogram()
                        },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckBPink) }
                    )
                    DropdownMenuItem(
                        text = { Text("AI Auto-Tag Metadata", color = NeonPurple, fontSize = 12.sp) },
                        onClick = {
                            showMenu = false
                            onAutoTag()
                        },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonPurple) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Track", color = NeonRed, fontSize = 12.sp) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = NeonRed) }
                    )
                }
            }
        }
    }
}
