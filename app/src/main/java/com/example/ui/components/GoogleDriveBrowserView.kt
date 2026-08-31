package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.model.Track
import com.example.network.drive.DriveAuthState
import com.example.network.drive.DriveBreadcrumb
import com.example.network.drive.DriveFileItem
import com.example.network.drive.DriveFolderListing
import com.example.network.drive.DriveSyncStatus
import com.example.ui.theme.DeckACyan
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

@Composable
fun GoogleDriveBrowserView(
    authState: DriveAuthState,
    listing: DriveFolderListing,
    breadcrumbs: List<DriveBreadcrumb>,
    syncStatusMap: Map<String, DriveSyncStatus>,
    downloadProgressMap: Map<String, Int>,
    isLoading: Boolean,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onNavigateBreadcrumb: (String) -> Unit,
    onOpenFolder: (String, String) -> Unit,
    onPlayTrack: (DriveFileItem) -> Unit,
    onDownloadTrack: (DriveFileItem) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSyncEntireFolder: () -> Unit,
    onConnectAccount: () -> Unit,
    onDisconnectAccount: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchOpen by remember { mutableStateOf(false) }

    val filteredItems = remember(listing.items, searchQuery) {
        if (searchQuery.isBlank()) {
            listing.items
        } else {
            val q = searchQuery.trim().lowercase()
            listing.items.filter {
                it.name.lowercase().contains(q) ||
                    it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q)
            }
        }
    }

    val audioTracksCount = remember(filteredItems) { filteredItems.count { !it.isFolder } }
    val foldersCount = remember(filteredItems) { filteredItems.count { it.isFolder } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .testTag("google_drive_browser_view")
    ) {
        // Top Header Bar
        Surface(
            color = DjSurfaceDark,
            border = BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(34.dp).testTag("drive_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = DeckACyan
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF4285F4).copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF4285F4).copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color(0xFF4285F4),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Google Drive Audio Browser",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (authState.isConnected) "Connected: ${authState.userEmail}" else "Not Connected",
                                color = if (authState.isConnected) NeonGreen else TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { isSearchOpen = !isSearchOpen },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Search Bar Expandable
                AnimatedVisibility(visible = isSearchOpen) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter Drive folder by title, artist...", fontSize = 11.sp, color = TextMuted) },
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DjSurfaceCard,
                                unfocusedContainerColor = DjSurfaceCard,
                                focusedBorderColor = Color(0xFF4285F4),
                                unfocusedBorderColor = DjSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        )
                    }
                }

                // Breadcrumbs Bar
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    breadcrumbs.forEachIndexed { index, crumb ->
                        val isLast = index == breadcrumbs.lastIndex
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !isLast) { onNavigateBreadcrumb(crumb.folderId) },
                            color = if (isLast) Color(0xFF4285F4).copy(alpha = 0.2f) else DjSurfaceElevated,
                            shape = RoundedCornerShape(4.dp),
                            border = if (isLast) BorderStroke(1.dp, Color(0xFF4285F4)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (index == 0) {
                                    Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF4285F4), modifier = Modifier.size(12.dp))
                                } else {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(12.dp))
                                }
                                Text(
                                    text = crumb.folderName,
                                    color = if (isLast) TextPrimary else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        if (!isLast) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.padding(horizontal = 4.dp).size(8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Action Sub-Bar: Sync Entire Folder Action + Stats
        Surface(
            color = DjSurfaceCard,
            border = BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$foldersCount folders • $audioTracksCount audio tracks",
                    color = TextSecondary,
                    fontSize = 10.sp
                )

                if (audioTracksCount > 0) {
                    Button(
                        onClick = onSyncEntireFolder,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4), contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(28.dp).testTag("sync_entire_folder_button")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync Entire Folder", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Account Auth Not Connected Banner (if disconnected)
        if (!authState.isConnected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF4285F4).copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = Color(0xFF4285F4),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Connect Google Drive Account",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Browse your Google Drive audio library directly, stream club tracks with zero local storage footprint, or download high-fidelity FLAC/WAV masters for offline DJ sets.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(
                        onClick = onConnectAccount,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("connect_google_drive_button")
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect Google Account", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Main File / Folder List
        if (isLoading && filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF4285F4), strokeWidth = 2.dp)
                    Text("Loading Google Drive contents...", color = TextSecondary, fontSize = 12.sp)
                }
            }
        } else if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                    Text("No audio files found in this folder", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("SoundSync supports MP3, FLAC, WAV, M4A/AAC, OGG, Opus, AIFF", color = TextMuted, fontSize = 10.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("drive_items_list"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    if (item.isFolder) {
                        DriveFolderRow(
                            folder = item,
                            onClick = { onOpenFolder(item.id, item.name) }
                        )
                    } else {
                        val syncStatus = syncStatusMap[item.id] ?: item.syncStatus
                        val progress = downloadProgressMap[item.id] ?: item.downloadProgressPercent
                        val isCurrentPlaying = currentPlayingTrack?.id == "gdrive_${item.id}"

                        DriveAudioTrackRow(
                            item = item,
                            syncStatus = syncStatus,
                            downloadProgress = progress,
                            isPlaying = isPlaying && isCurrentPlaying,
                            isCurrentTrack = isCurrentPlaying,
                            onPlay = { onPlayTrack(item) },
                            onDownload = { onDownloadTrack(item) },
                            onCancelDownload = { onCancelDownload(item.id) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
private fun DriveFolderRow(
    folder: DriveFileItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("drive_folder_${folder.id}"),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(NeonAmber.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .border(1.dp, NeonAmber.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = NeonAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = folder.name,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Google Drive Folder",
                        color = TextSecondary,
                        fontSize = 9.5.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Open Folder",
                tint = TextMuted,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun DriveAudioTrackRow(
    item: DriveFileItem,
    syncStatus: DriveSyncStatus,
    downloadProgress: Int,
    isPlaying: Boolean,
    isCurrentTrack: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val isLossless = item.extension == "FLAC" || item.extension == "WAV" || item.extension == "AIFF"
    val formatBadgeColor = if (isLossless) DeckACyan else NeonPurple

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("drive_track_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentTrack) DjSurfaceElevated else DjSurfaceDark
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (isCurrentTrack) DeckACyan else DjSurfaceBorder
        )
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Play / Pause Button
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            if (isCurrentTrack) DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                            CircleShape
                        )
                        .border(
                            1.dp,
                            if (isCurrentTrack) DeckACyan else DjSurfaceBorder,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isCurrentTrack) DeckACyan else TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Title & Artist Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayTitle,
                        color = if (isCurrentTrack) DeckACyan else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = item.displayArtist,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("•", color = TextMuted, fontSize = 9.sp)
                        Text(
                            text = "${item.formattedDuration} · ${item.formattedSize}",
                            color = TextMuted,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Format Pill
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = formatBadgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.5.dp, formatBadgeColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = item.extension,
                        color = formatBadgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Sync Status & Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                when (syncStatus) {
                    DriveSyncStatus.CLOUD_ONLY -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color(0xFF4285F4),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Cloud only • Streamable",
                                color = Color(0xFF4285F4),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    DriveSyncStatus.DOWNLOADING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.size(12.dp),
                                color = NeonAmber,
                                strokeWidth = 1.5.dp
                            )
                            Text(
                                text = "Downloading $downloadProgress%",
                                color = NeonAmber,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    DriveSyncStatus.SYNCED -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = NeonGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Synced • Offline Ready",
                                color = NeonGreen,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    DriveSyncStatus.UPDATED_REMOTELY -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = NeonPurple,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Remote File Updated",
                                color = NeonPurple,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    DriveSyncStatus.ERROR -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SyncProblem,
                                contentDescription = null,
                                tint = NeonRed,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Sync Error • Tap to retry",
                                color = NeonRed,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Action Button (Download / Sync / Cancel)
                when (syncStatus) {
                    DriveSyncStatus.DOWNLOADING -> {
                        IconButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Download", tint = NeonAmber, modifier = Modifier.size(14.dp))
                        }
                    }
                    DriveSyncStatus.CLOUD_ONLY, DriveSyncStatus.UPDATED_REMOTELY, DriveSyncStatus.ERROR -> {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onDownload),
                            color = DjSurfaceElevated,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF4285F4))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = Color(0xFF4285F4),
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = if (syncStatus == DriveSyncStatus.ERROR) "Retry Sync" else "Sync",
                                    color = Color(0xFF4285F4),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    DriveSyncStatus.SYNCED -> {
                        // Already synced badge
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonGreen.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(Icons.Default.CloudDone, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(10.dp))
                                Text("Offline", color = NeonGreen, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Progress indicator bar when downloading
            if (syncStatus == DriveSyncStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
                    color = NeonAmber,
                    trackColor = DjSurfaceElevated
                )
            }
        }
    }
}
