package com.example.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueueMusic
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Playlist
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: (Uri) -> Unit,
    onDiscoverPlaylists: () -> Unit,
    onRenamePlaylist: (Playlist, String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onExportToRockbox: (Playlist) -> Unit
) {
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    // File picker launcher for importing M3U/M3U8 playlists
    val m3uPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportPlaylist(uri)
        }
    }

    if (playlistToRename != null) {
        CreatePlaylistDialog(
            initialName = playlistToRename!!.name,
            confirmButtonText = "Rename",
            titleText = "Rename Playlist",
            onConfirm = { newName, _ ->
                onRenamePlaylist(playlistToRename!!, newName)
                playlistToRename = null
            },
            onDismiss = { playlistToRename = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlists_screen")
    ) {
        // Actions Header (+ New Playlist, Import M3U, Discover on Storage)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onCreatePlaylist,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeckACyan,
                    contentColor = DjObsidian
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("create_playlist_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Playlist", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = { m3uPickerLauncher.launch(arrayOf("*/*", "audio/x-mpegurl", "application/vnd.apple.mpegurl")) },
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(40.dp)
                    .testTag("import_m3u_button")
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import M3U", color = TextPrimary, fontSize = 12.sp)
            }

            IconButton(
                onClick = onDiscoverPlaylists,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceDark)
                    .testTag("discover_playlists_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FindInPage,
                    contentDescription = "Scan for M3U playlists on storage",
                    tint = DeckBPink,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (playlists.isEmpty()) {
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
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No playlists found",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Create custom DJ playlists or import Rockbox-compatible .m3u8 playlists from SD Card / USB storage.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Button(
                        onClick = onCreatePlaylist,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckACyan,
                            contentColor = DjObsidian
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create First Playlist", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistsScreenRow(
                        playlist = playlist,
                        onClick = { onSelectPlaylist(playlist) },
                        onRename = { playlistToRename = playlist },
                        onExportRockbox = { onExportToRockbox(playlist) },
                        onDelete = { onDeletePlaylist(playlist) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreenRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExportRockbox: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val formattedDuration = remember(playlist.totalDurationSeconds) {
        val min = playlist.totalDurationSeconds / 60
        val sec = playlist.totalDurationSeconds % 60
        "$min min $sec s"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("playlist_screen_row_${playlist.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = if (playlist.isRockboxCompatible) DeckACyan else TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${playlist.trackCount} tracks",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    if (playlist.totalDurationSeconds > 0) {
                        Text(text = "•", fontSize = 10.sp, color = TextMuted)
                        Text(
                            text = formattedDuration,
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (playlist.isRockboxCompatible) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonGreen.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "ROCKBOX M3U8",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonGreen,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (playlist.hasCrossStorageWarning) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonAmber.copy(alpha = 0.15f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(10.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Cross-Storage",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonAmber
                                )
                            }
                        }
                    }

                    if (playlist.missingTrackCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonAmber.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${playlist.missingTrackCount} missing",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonAmber,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("playlist_row_menu_${playlist.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DjSurfaceElevated)
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename Playlist", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = DeckACyan) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export to Rockbox (.m3u8)", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = NeonGreen) },
                        onClick = {
                            showMenu = false
                            onExportRockbox()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Playlist", color = DeckBPink) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = DeckBPink) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
