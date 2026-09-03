package com.example.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.LocalCategory
import com.example.ui.MainDjViewModel
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun LocalLibraryScreen(
    viewModel: MainDjViewModel,
    onOpenFolderExplorer: () -> Unit
) {
    val selectedCategory by viewModel.selectedLocalCategory.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val allAlbums by viewModel.allAlbums.collectAsState()
    val allArtists by viewModel.allArtists.collectAsState()
    val allPlaylists by viewModel.allPlaylists.collectAsState()
    val allFolders by viewModel.allFolders.collectAsState()
    val hideUnavailableTracks by viewModel.hideUnavailableTracks.collectAsState()

    val selectedAlbum by viewModel.selectedAlbum.collectAsState()
    val selectedArtist by viewModel.selectedArtist.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()

    val currentPlayingTrack by viewModel.audioEngine.currentTrack.collectAsState()
    val isPlaying by viewModel.audioEngine.isPlaying.collectAsState()

    val showAddToPlaylistSheet by viewModel.showAddToPlaylistSheet.collectAsState()
    val showCreatePlaylistDialog by viewModel.showCreatePlaylistDialog.collectAsState()

    // Add to Playlist bottom sheet
    if (showAddToPlaylistSheet != null) {
        AddToPlaylistSheet(
            tracksToAdd = showAddToPlaylistSheet!!,
            playlists = allPlaylists,
            onSelectPlaylist = { playlist ->
                viewModel.addTracksToPlaylist(playlist.id, showAddToPlaylistSheet!!)
            },
            onCreateNewPlaylist = {
                viewModel.openCreatePlaylistDialog()
            },
            onDismiss = { viewModel.closeAddToPlaylist() }
        )
    }

    // Create Playlist dialog
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            initialName = "",
            confirmButtonText = "Create",
            titleText = "New Playlist",
            onConfirm = { name, exportToRockbox ->
                val initialTracks = showAddToPlaylistSheet?.map { it.id } ?: emptyList()
                viewModel.createPlaylist(name, initialTracks, exportToRockbox)
            },
            onDismiss = { viewModel.closeCreatePlaylistDialog() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DjObsidian)
            .testTag("local_library_screen")
    ) {
        // Navigation: If in detail view, show detail screen
        when {
            selectedAlbum != null -> {
                AlbumDetailScreen(
                    album = selectedAlbum!!,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    onBack = { viewModel.closeAlbum() },
                    onPlayTrack = { track -> viewModel.playTrack(track) },
                    onPlayAll = { tracks, shuffle -> viewModel.playTrackList(tracks, shuffle) },
                    onAddAlbumToPlaylist = { tracks -> viewModel.openAddToPlaylist(tracks) },
                    onAddTrackToPlaylist = { track -> viewModel.openAddToPlaylist(track) },
                    onQueueTrack = { track, playNext -> viewModel.queueTrack(track, playNext) },
                    onInspectProperties = { track -> viewModel.openTrackProperties(track) },
                    onInspectSpectrogram = { track -> viewModel.inspectTrackSpectrogram(track, showTab = true) }
                )
            }
            selectedArtist != null -> {
                ArtistDetailScreen(
                    artist = selectedArtist!!,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    onBack = { viewModel.closeArtist() },
                    onSelectAlbum = { album -> viewModel.openAlbum(album) },
                    onPlayTrack = { track -> viewModel.playTrack(track) },
                    onPlayAll = { tracks, shuffle -> viewModel.playTrackList(tracks, shuffle) },
                    onAddTracksToPlaylist = { tracks -> viewModel.openAddToPlaylist(tracks) },
                    onAddTrackToPlaylist = { track -> viewModel.openAddToPlaylist(track) },
                    onQueueTrack = { track, playNext -> viewModel.queueTrack(track, playNext) },
                    onInspectProperties = { track -> viewModel.openTrackProperties(track) },
                    onInspectSpectrogram = { track -> viewModel.inspectTrackSpectrogram(track, showTab = true) }
                )
            }
            selectedPlaylist != null -> {
                // Find latest playlist instance from allPlaylists flow to ensure reactive updates
                val livePlaylist = allPlaylists.firstOrNull { it.id == selectedPlaylist!!.id } ?: selectedPlaylist!!
                PlaylistDetailScreen(
                    playlist = livePlaylist,
                    allAvailableTracks = allTracks,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    onBack = { viewModel.closePlaylist() },
                    onPlayTrack = { track -> viewModel.playTrack(track) },
                    onPlayAll = { tracks, shuffle -> viewModel.playTrackList(tracks, shuffle) },
                    onAddTracksToPlaylist = { tracks -> viewModel.addTracksToPlaylist(livePlaylist.id, tracks) },
                    onRemoveTrack = { pos -> viewModel.removeTrackFromPlaylist(livePlaylist.id, pos) },
                    onReorderTrack = { from, to -> viewModel.reorderPlaylistTrack(livePlaylist.id, from, to) },
                    onRenamePlaylist = { newName -> viewModel.renamePlaylist(livePlaylist.id, newName) },
                    onDeletePlaylist = { viewModel.deletePlaylist(livePlaylist.id) },
                    onExportToRockbox = { viewModel.exportPlaylistToRockbox(livePlaylist.id) },
                    onQueueTrack = { track, playNext -> viewModel.queueTrack(track, playNext) },
                    onInspectProperties = { track -> viewModel.openTrackProperties(track) },
                    onInspectSpectrogram = { track -> viewModel.inspectTrackSpectrogram(track, showTab = true) }
                )
            }
            selectedFolder != null -> {
                FolderDetailScreen(
                    folder = selectedFolder!!,
                    currentPlayingTrack = currentPlayingTrack,
                    isPlaying = isPlaying,
                    onBack = { viewModel.closeFolder() },
                    onPlayTrack = { track -> viewModel.playTrack(track) },
                    onPlayAll = { tracks, shuffle -> viewModel.playTrackList(tracks, shuffle) },
                    onAddFolderToPlaylist = { tracks -> viewModel.openAddToPlaylist(tracks) },
                    onAddTrackToPlaylist = { track -> viewModel.openAddToPlaylist(track) },
                    onQueueTrack = { track, playNext -> viewModel.queueTrack(track, playNext) },
                    onInspectProperties = { track -> viewModel.openTrackProperties(track) },
                    onInspectSpectrogram = { track -> viewModel.inspectTrackSpectrogram(track, showTab = true) }
                )
            }
            else -> {
                // Category Selector Bar
                CategorySelectorBar(
                    selectedCategory = selectedCategory,
                    songCount = allTracks.size,
                    albumCount = allAlbums.size,
                    artistCount = allArtists.size,
                    playlistCount = allPlaylists.size,
                    folderCount = allFolders.size,
                    onSelectCategory = { cat -> viewModel.selectLocalCategory(cat) },
                    onOpenFolderExplorer = onOpenFolderExplorer
                )

                // Category Screen Content
                when (selectedCategory) {
                    LocalCategory.SONGS -> {
                        SongsScreen(
                            tracks = allTracks,
                            currentPlayingTrack = currentPlayingTrack,
                            isPlaying = isPlaying,
                            hideUnavailableTracks = hideUnavailableTracks,
                            onToggleHideUnavailable = { viewModel.toggleHideUnavailableTracks() },
                            onPlayTrack = { track -> viewModel.playTrack(track) },
                            onPlayAll = { tracks, shuffle -> viewModel.playTrackList(tracks, shuffle) },
                            onAddToPlaylist = { track -> viewModel.openAddToPlaylist(track) },
                            onQueueTrack = { track, playNext -> viewModel.queueTrack(track, playNext) },
                            onInspectProperties = { track -> viewModel.openTrackProperties(track) },
                            onInspectSpectrogram = { track -> viewModel.inspectTrackSpectrogram(track, showTab = true) },
                            onStartScan = { viewModel.scanDeviceMediaStore() },
                            onBulkEditTracks = { tracks -> viewModel.openBulkEditor(tracks) }
                        )
                    }
                    LocalCategory.ALBUMS -> {
                        AlbumsScreen(
                            albums = allAlbums,
                            onSelectAlbum = { album -> viewModel.openAlbum(album) }
                        )
                    }
                    LocalCategory.ARTISTS -> {
                        ArtistsScreen(
                            artists = allArtists,
                            onSelectArtist = { artist -> viewModel.openArtist(artist) }
                        )
                    }
                    LocalCategory.PLAYLISTS -> {
                        PlaylistsScreen(
                            playlists = allPlaylists,
                            onSelectPlaylist = { playlist -> viewModel.openPlaylist(playlist) },
                            onCreatePlaylist = { viewModel.openCreatePlaylistDialog() },
                            onImportPlaylist = { uri -> viewModel.importM3uPlaylist(uri) },
                            onDiscoverPlaylists = { viewModel.discoverStoragePlaylists() },
                            onRenamePlaylist = { playlist, newName -> viewModel.renamePlaylist(playlist.id, newName) },
                            onDeletePlaylist = { playlist -> viewModel.deletePlaylist(playlist.id) },
                            onExportToRockbox = { playlist -> viewModel.exportPlaylistToRockbox(playlist.id) }
                        )
                    }
                    LocalCategory.FOLDERS -> {
                        FoldersScreen(
                            folders = allFolders,
                            onSelectFolder = { folder -> viewModel.openFolder(folder) },
                            onPlayFolder = { folder, shuffle -> viewModel.playTrackList(folder.tracks, shuffle) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySelectorBar(
    selectedCategory: LocalCategory,
    songCount: Int,
    albumCount: Int,
    artistCount: Int,
    playlistCount: Int,
    folderCount: Int,
    onSelectCategory: (LocalCategory) -> Unit,
    onOpenFolderExplorer: () -> Unit
) {
    Surface(
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LocalCategory.entries.forEach { category ->
                    val isSelected = selectedCategory == category
                    val count = when (category) {
                        LocalCategory.SONGS -> songCount
                        LocalCategory.ALBUMS -> albumCount
                        LocalCategory.ARTISTS -> artistCount
                        LocalCategory.PLAYLISTS -> playlistCount
                        LocalCategory.FOLDERS -> folderCount
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) DeckACyan else DjSurfaceDark,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clickable { onSelectCategory(category) }
                            .testTag("category_tab_${category.name.lowercase()}")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = when (category) {
                                    LocalCategory.SONGS -> Icons.Default.MusicNote
                                    LocalCategory.ALBUMS -> Icons.Default.Album
                                    LocalCategory.ARTISTS -> Icons.Default.Person
                                    LocalCategory.PLAYLISTS -> Icons.Default.QueueMusic
                                    LocalCategory.FOLDERS -> Icons.Default.Folder
                                },
                                contentDescription = null,
                                tint = if (isSelected) DjObsidian else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = category.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) DjObsidian else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Folder Explorer Toggle Icon
            IconButton(
                onClick = onOpenFolderExplorer,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceElevated)
                    .testTag("open_folder_explorer_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Folder Explorer",
                    tint = DeckACyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
