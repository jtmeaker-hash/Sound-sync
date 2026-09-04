package com.example.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Track
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
fun ArtistDetailScreen(
    artist: Artist,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onSelectAlbum: (Album) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAll: (List<Track>, Boolean) -> Unit,
    onAddTracksToPlaylist: (List<Track>) -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onQueueTrack: (Track, Boolean) -> Unit,
    onInspectProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit
) {
    val formattedDuration = remember(artist.totalDurationSeconds) {
        val min = artist.totalDurationSeconds / 60
        val sec = artist.totalDurationSeconds % 60
        "$min min $sec sec"
    }

    BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("artist_detail_screen")
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
                modifier = Modifier.testTag("artist_detail_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DeckACyan
                )
            }

            Text(
                text = "Artist Details",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Artist Header Card
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
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(DjSurfaceCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${artist.songCount} songs • ${artist.albumCount} albums • $formattedDuration",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Action Buttons Row (Play, Shuffle, Add All)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onPlayAll(artist.songs, false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckACyan,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("artist_play_all_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play All", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { onPlayAll(artist.songs, true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckBPink,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("artist_shuffle_button")
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Shuffle", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = { onAddTracksToPlaylist(artist.songs) },
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("artist_add_to_playlist_button")
            ) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Main List (Albums Carousel + All Songs)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Albums Section
            if (artist.albums.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "ALBUMS (${artist.albums.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(artist.albums, key = { it.id }) { album ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DjSurfaceDark,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clickable { onSelectAlbum(album) }
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(100.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(DjSurfaceCard),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Album,
                                                contentDescription = null,
                                                tint = DeckACyan,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = album.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${album.trackCount} tracks",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Songs Header
            item {
                Text(
                    text = "ALL SONGS (${artist.songs.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }

            // Song Rows
            itemsIndexed(artist.songs, key = { _, track -> track.id }) { index, track ->
                SongTrackRow(
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
