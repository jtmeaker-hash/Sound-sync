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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.SpotifyAuthState
import com.example.model.SpotifyPlaylistItem
import com.example.model.SpotifyTrackItem
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

val SpotifyGreen = Color(0xFF1DB954)

@Composable
fun SpotifyTab(
    authState: SpotifyAuthState,
    savedTracks: List<SpotifyTrackItem>,
    playlists: List<SpotifyPlaylistItem>,
    searchResults: List<SpotifyTrackItem>,
    isLoading: Boolean,
    currentTrack: Track?,
    isPlaying: Boolean,
    onConnectSpotify: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenConfigDialog: () -> Unit,
    onSearch: (String) -> Unit,
    onPlayTrack: (SpotifyTrackItem) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header with Auth & Connection Status
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DjSurfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (authState.userProfile?.avatarUrl != null) {
                        AsyncImage(
                            model = authState.userProfile.avatarUrl,
                            contentDescription = "Spotify Avatar",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, SpotifyGreen, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = SpotifyGreen.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SpotifyGreen),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(22.dp))
                            }
                        }
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = authState.userProfile?.displayName ?: if (authState.isConnected) "Spotify Connected" else "Spotify",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (authState.isConnected) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = SpotifyGreen.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SpotifyGreen)
                                ) {
                                    Text(
                                        text = authState.userProfile?.product?.uppercase() ?: "PREMIUM",
                                        color = SpotifyGreen,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (authState.isConnected) "${authState.userProfile?.followersCount ?: 0} Followers • Web API Integration" else "OAuth PKCE Authorization Flow",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenConfigDialog) {
                        Icon(Icons.Default.Settings, contentDescription = "Config", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    if (authState.isConnected) {
                        Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Disconnect", color = TextSecondary, fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = onConnectSpotify,
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Connect", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Sub-tabs: Liked Songs, Playlists, Search
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = DjSurfaceCard,
            contentColor = SpotifyGreen,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedSubTab]),
                    color = SpotifyGreen
                )
            },
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                text = { Text("LIKED SONGS (${savedTracks.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                text = { Text("PLAYLISTS (${playlists.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedSubTab == 2,
                onClick = { selectedSubTab = 2 },
                text = { Text("SEARCH", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 3.dp)
            }
        } else {
            when (selectedSubTab) {
                0 -> { // Liked Songs
                    if (savedTracks.isEmpty()) {
                        EmptySpotifyState(
                            title = if (authState.isConnected) "No Liked Songs Found" else "Connect Spotify to view Liked Songs",
                            subtitle = "Sync your Spotify Saved Library, control playback, and explore your collection.",
                            buttonText = if (!authState.isConnected) "Connect Spotify" else "Refresh Library",
                            onAction = if (!authState.isConnected) onConnectSpotify else onRefresh
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("spotify_saved_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            items(savedTracks, key = { it.id }) { track ->
                                SpotifyTrackCard(
                                    track = track,
                                    isCurrent = currentTrack?.id == "spotify_${track.id}",
                                    isPlaying = isPlaying && currentTrack?.id == "spotify_${track.id}",
                                    onPlay = { onPlayTrack(track) },
                                    onInspect = { onInspectSpectrogram(track.toAppTrack()) }
                                )
                            }
                        }
                    }
                }
                1 -> { // Playlists
                    if (playlists.isEmpty()) {
                        EmptySpotifyState(
                            title = "No Playlists Found",
                            subtitle = "Spotify user playlists will appear here once connected.",
                            buttonText = "Refresh Playlists",
                            onAction = onRefresh
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                SpotifyPlaylistCard(playlist = playlist)
                            }
                        }
                    }
                }
                2 -> { // Search
                    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                onSearch(it)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("spotify_search_input"),
                            placeholder = { Text("Search Spotify catalog...", color = TextMuted, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SpotifyGreen,
                                unfocusedBorderColor = DjSurfaceBorder,
                                focusedContainerColor = DjSurfaceCard,
                                unfocusedContainerColor = DjSurfaceCard,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        if (searchResults.isEmpty()) {
                            EmptySpotifyState(
                                title = if (searchQuery.isNotBlank()) "No Results Found" else "Search Over 100M Tracks on Spotify",
                                subtitle = "Search for tracks, remixes, albums, and artists.",
                                buttonText = "",
                                onAction = {}
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f).testTag("spotify_search_list"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 72.dp)
                            ) {
                                items(searchResults, key = { it.id }) { track ->
                                    SpotifyTrackCard(
                                        track = track,
                                        isCurrent = currentTrack?.id == "spotify_${track.id}",
                                        isPlaying = isPlaying && currentTrack?.id == "spotify_${track.id}",
                                        onPlay = { onPlayTrack(track) },
                                        onInspect = { onInspectSpectrogram(track.toAppTrack()) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpotifyTrackCard(
    track: SpotifyTrackItem,
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
            .testTag("spotify_track_card_${track.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) DjSurfaceElevated else DjSurfaceCard
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrent) SpotifyGreen else DjSurfaceBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Album Artwork Image
            if (track.albumArtUrl != null) {
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = "Album Artwork",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceDark,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Track metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    color = if (isCurrent) SpotifyGreen else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(track.artistName, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                    if (track.isExplicit) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = DjSurfaceDark,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, TextMuted)
                        ) {
                            Text("E", color = TextMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                        }
                    }
                }
            }

            // Duration & Actions
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onInspect,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Spectrogram Info", tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                    }

                    Surface(
                        shape = CircleShape,
                        color = if (isCurrent) SpotifyGreen else DjSurfaceDark,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onPlay() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = if (isCurrent) Color.Black else TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                val durSec = (track.durationMs / 1000).toInt()
                Text(
                    text = String.format(Locale.US, "%d:%02d", durSec / 60, durSec % 60),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun SpotifyPlaylistCard(playlist: SpotifyPlaylistItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (playlist.imageUrl != null) {
                AsyncImage(
                    model = playlist.imageUrl,
                    contentDescription = "Playlist Cover",
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceDark,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(24.dp))
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                Spacer(modifier = Modifier.height(2.dp))
                Text("By ${playlist.ownerName} • ${playlist.trackCount} Tracks", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun EmptySpotifyState(
    title: String,
    subtitle: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (buttonText.isNotBlank()) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(buttonText, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
