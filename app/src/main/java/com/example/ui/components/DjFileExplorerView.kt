package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.ExplorerSortOption
import com.example.model.FileOperationType
import com.example.model.FolderItem
import com.example.model.StorageSource
import com.example.model.StorageSourceType
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
fun DjFileExplorerView(
    tracks: List<Track>,
    subFolders: List<FolderItem>,
    storageSources: List<StorageSource>,
    currentSourceId: String,
    currentPath: String,
    selectedTrackIds: Set<String>,
    searchQuery: String,
    sortOption: ExplorerSortOption,
    isDryRun: Boolean,
    isScanning: Boolean,
    playingTrackId: String?,
    isPlaying: Boolean,
    onSearchChange: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onNavigateToDir: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onToggleSelectTrack: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onSortChange: (ExplorerSortOption) -> Unit,
    onToggleDryRun: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onOpenProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onAutoTagTrack: (Track) -> Unit,
    onBulkMove: () -> Unit,
    onBulkTrash: () -> Unit,
    onBulkAutoTag: () -> Unit,
    onMountSaf: () -> Unit,
    onPickAudioFiles: () -> Unit,
    onScanMediaStore: () -> Unit,
    onLoadDemoTracks: () -> Unit,
    onOpenAddTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .testTag("dj_file_explorer_view")
    ) {
        // 1. Storage Source Selector Bar (All, Internal, SD Card, USB, SAF Folders)
        StorageSourceSelectorBar(
            sources = storageSources,
            currentSourceId = currentSourceId,
            onSelectSource = onSelectSource,
            onMountSaf = onMountSaf
        )

        // 2. Search & Action Bar
        ExplorerSearchBar(
            searchQuery = searchQuery,
            onSearchChange = onSearchChange,
            sortOption = sortOption,
            isDryRun = isDryRun,
            onToggleDryRun = onToggleDryRun,
            onOpenSort = { showSortMenu = true },
            onPickAudioFiles = onPickAudioFiles,
            onOpenAddTrack = onOpenAddTrack
        )

        // Sort Dropdown
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false },
            modifier = Modifier.background(DjSurfaceDark)
        ) {
            ExplorerSortOption.values().forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.displayName,
                            color = if (option == sortOption) DeckACyan else TextPrimary,
                            fontWeight = if (option == sortOption) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    onClick = {
                        onSortChange(option)
                        showSortMenu = false
                    }
                )
            }
        }

        // 3. Breadcrumb & Navigation Bar
        BreadcrumbNavigationBar(
            currentPath = currentPath,
            onNavigateUp = onNavigateUp,
            onNavigateToDir = onNavigateToDir,
            totalTracks = tracks.size,
            selectedCount = selectedTrackIds.size,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection
        )

        // 4. Bulk Action Toolbar (when tracks are selected)
        if (selectedTrackIds.isNotEmpty()) {
            BulkActionsBar(
                selectedCount = selectedTrackIds.size,
                isDryRun = isDryRun,
                onBulkMove = onBulkMove,
                onBulkAutoTag = onBulkAutoTag,
                onBulkTrash = onBulkTrash,
                onClearSelection = onClearSelection
            )
        }

        // 5. Main Explorer Content: Subfolders + Tracks List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Subfolders Section (if any)
            if (subFolders.isNotEmpty()) {
                item {
                    Text(
                        text = "DIRECTORIES (${subFolders.size})",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(subFolders) { folder ->
                    FolderRowItem(
                        folder = folder,
                        onClick = { onNavigateToDir(folder.path) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "AUDIO FILES (${tracks.size})",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Audio Tracks List
            if (tracks.isEmpty() && subFolders.isEmpty()) {
                item {
                    EmptyFolderPlaceholder(
                        currentPath = currentPath,
                        onScanMediaStore = onScanMediaStore,
                        onMountSaf = onMountSaf,
                        onPickAudioFiles = onPickAudioFiles,
                        onLoadDemoTracks = onLoadDemoTracks,
                        onOpenAddTrack = onOpenAddTrack
                    )
                }
            } else {
                items(tracks, key = { it.id }) { track ->
                    val isSelected = selectedTrackIds.contains(track.id)
                    val isCurrentlyPlaying = playingTrackId == track.id && isPlaying

                    DjTrackFileRow(
                        track = track,
                        isSelected = isSelected,
                        isPlaying = isCurrentlyPlaying,
                        onToggleSelect = { onToggleSelectTrack(track.id) },
                        onPlay = { onPlayTrack(track) },
                        onOpenProperties = { onOpenProperties(track) },
                        onInspectSpectrogram = { onInspectSpectrogram(track) },
                        onAutoTag = { onAutoTagTrack(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageSourceSelectorBar(
    sources: List<StorageSource>,
    currentSourceId: String,
    onSelectSource: (String) -> Unit,
    onMountSaf: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(sources) { source ->
                val isSelected = source.id == currentSourceId
                val icon = when (source.type) {
                    StorageSourceType.INTERNAL -> Icons.Default.Folder
                    StorageSourceType.USB_SSD -> Icons.Default.Usb
                    StorageSourceType.SD_CARD -> Icons.Default.SdCard
                    StorageSourceType.DOWNLOADS -> Icons.Default.Download
                    StorageSourceType.CLOUD_VAULT -> Icons.Default.Cloud
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectSource(source.id) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, DeckACyan) else null
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) DeckACyan else TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = source.label,
                            color = if (isSelected) DeckACyan else TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "(${source.trackCount})",
                            color = if (isSelected) DeckACyan else TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Mount external SAF folder button
            item {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onMountSaf)
                        .testTag("mount_saf_button"),
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(13.dp))
                        Text("+ Pick Folder", color = NeonAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerSearchBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortOption: ExplorerSortOption,
    isDryRun: Boolean,
    onToggleDryRun: () -> Unit,
    onOpenSort: () -> Unit,
    onPickAudioFiles: () -> Unit,
    onOpenAddTrack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Filter tracks, key:8A, bpm:128...", fontSize = 11.sp, color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary, modifier = Modifier.size(16.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            },
            modifier = Modifier.weight(1f).height(44.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = DeckACyan,
                unfocusedBorderColor = DjSurfaceBorder,
                unfocusedContainerColor = DjSurfaceDark,
                focusedContainerColor = DjSurfaceDark
            ),
            shape = RoundedCornerShape(8.dp)
        )

        // Sort Button
        Surface(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenSort),
            shape = RoundedCornerShape(8.dp),
            color = DjSurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = DeckACyan, modifier = Modifier.size(16.dp))
                Text(sortOption.displayName.substringBefore(" "), color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Dry Run Toggle Pill
        Surface(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleDryRun),
            shape = RoundedCornerShape(8.dp),
            color = if (isDryRun) NeonAmber.copy(alpha = 0.2f) else DjSurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isDryRun) NeonAmber else DjSurfaceBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("DRY", color = if (isDryRun) NeonAmber else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }

        // Pick Audio Files Button
        Surface(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPickAudioFiles)
                .testTag("pick_files_button"),
            shape = RoundedCornerShape(8.dp),
            color = DjSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
        ) {
            Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AudioFile, contentDescription = "Pick Audio Files", tint = DeckACyan, modifier = Modifier.size(18.dp))
            }
        }

        // Add File/Track Button
        Surface(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenAddTrack)
                .testTag("add_track_button"),
            shape = RoundedCornerShape(8.dp),
            color = DeckACyan
        ) {
            Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = "Add Track", tint = DjObsidian, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BreadcrumbNavigationBar(
    currentPath: String,
    onNavigateUp: () -> Unit,
    onNavigateToDir: (String) -> Unit,
    totalTracks: Int,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit
) {
    val segments = currentPath.split("/").filter { it.isNotBlank() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DjSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Up One Level & Path Segments
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = DeckACyan, modifier = Modifier.size(15.dp))
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (segments.isEmpty()) "ALL AUDIO" else "/",
                        color = if (segments.isEmpty()) DeckACyan else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (segments.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onNavigateToDir("") }
                    )

                    var accPath = ""
                    segments.forEachIndexed { index, seg ->
                        accPath += "/$seg"
                        val thisPath = accPath
                        val isLast = index == segments.size - 1

                        Text(
                            text = seg,
                            color = if (isLast) DeckACyan else TextSecondary,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { onNavigateToDir(thisPath) }
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                        )

                        if (!isLast) {
                            Text(text = ">", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Selection & Stats
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (selectedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeckBPink.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeckBPink)
                    ) {
                        Text(
                            text = "$selectedCount SEL",
                            color = DeckBPink,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "$totalTracks files",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun BulkActionsBar(
    selectedCount: Int,
    isDryRun: Boolean,
    onBulkMove: () -> Unit,
    onBulkAutoTag: () -> Unit,
    onBulkTrash: () -> Unit,
    onClearSelection: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "$selectedCount selected",
                    color = DeckACyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isDryRun) {
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = NeonAmber.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonAmber)
                    ) {
                        Text("DRY RUN ON", color = NeonAmber, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Move Button
                Button(
                    onClick = onBulkMove,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Move", fontSize = 10.sp)
                }

                // Batch Tag
                Button(
                    onClick = onBulkAutoTag,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple.copy(alpha = 0.3f), contentColor = NeonPurple),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Batch Tag", fontSize = 10.sp)
                }

                // Safe Trash
                Button(
                    onClick = onBulkTrash,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed.copy(alpha = 0.3f), contentColor = NeonRed),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Trash", fontSize = 10.sp)
                }

                // Clear
                IconButton(onClick = onClearSelection, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderRowItem(
    folder: FolderItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(20.dp))
                Column {
                    Text(
                        text = folder.name,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${folder.trackCount} audio tracks · ${String.format(Locale.US, "%.1f", folder.totalSizeMb)} MB",
                        color = TextSecondary,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun DjTrackFileRow(
    track: Track,
    isSelected: Boolean,
    isPlaying: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onOpenProperties: () -> Unit,
    onInspectSpectrogram: () -> Unit,
    onAutoTag: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val isLossless = track.qualityRating.isLossless
    val isSuspicious = track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED
    val keyColor = getKeyColor(track.musicalKey)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = if (isPlaying) DeckACyan.copy(alpha = 0.08f) else if (isSelected) DjSurfaceElevated else DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isPlaying) DeckACyan else if (isSelected) DeckBPink else DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Selection Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = DeckBPink,
                    uncheckedColor = DjSurfaceBorder,
                    checkmarkColor = Color.White
                ),
                modifier = Modifier.size(24.dp)
            )

            // Play / Pause Preview Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) DeckBPink else DjSurfaceElevated)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = if (isPlaying) DjObsidian else DeckACyan,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Track Details (Title, Artist, Genre, File path)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenProperties)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = track.title,
                        color = if (isPlaying) DeckACyan else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSuspicious) {
                        Icon(Icons.Default.Warning, contentDescription = "Fake transcode alert", tint = NeonRed, modifier = Modifier.size(12.dp))
                    }
                }

                Text(
                    text = "${track.artist} · ${track.genre}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Path / Size details
                Text(
                    text = "${track.format} · ${String.format(Locale.US, "%.1f", track.fileSizeMb)} MB",
                    color = TextMuted,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // DJ Metrics Badges: Key, BPM, Quality Format
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Camelot Key Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = keyColor.copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, keyColor)
                ) {
                    Text(
                        text = if (track.hasValidKey) track.musicalKey else "—",
                        color = keyColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // BPM
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = DjSurfaceElevated
                ) {
                    Text(
                        text = if (track.hasValidBpm) String.format(Locale.US, "%.0f", track.bpm) else "—",
                        color = NeonAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // Format & Bitrate
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isLossless) NeonGreen.copy(alpha = 0.2f) else if (isSuspicious) NeonRed.copy(alpha = 0.2f) else DjSurfaceElevated,
                    border = if (isLossless) androidx.compose.foundation.BorderStroke(1.dp, NeonGreen) else if (isSuspicious) androidx.compose.foundation.BorderStroke(1.dp, NeonRed) else null
                ) {
                    Text(
                        text = if (isLossless) "FLAC" else "${track.bitrateKbps}k",
                        color = if (isLossless) NeonGreen else if (isSuspicious) NeonRed else TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Context Menu Button
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextMuted, modifier = Modifier.size(16.dp))
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DjSurfaceDark)
                ) {
                    DropdownMenuItem(
                        text = { Text("Inspect Properties & Tags", fontSize = 11.sp, color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(15.dp)) },
                        onClick = {
                            onOpenProperties()
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Open Spectrogram", fontSize = 11.sp, color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(15.dp)) },
                        onClick = {
                            onInspectSpectrogram()
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("AI Auto-Tag", fontSize = 11.sp, color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(15.dp)) },
                        onClick = {
                            onAutoTag()
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderPlaceholder(
    currentPath: String,
    onScanMediaStore: () -> Unit,
    onMountSaf: () -> Unit,
    onPickAudioFiles: () -> Unit,
    onLoadDemoTracks: () -> Unit,
    onOpenAddTrack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(44.dp))
            Text("Ready to Index Real Audio Files", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                text = "Connect real audio files on your phone storage, SD card, or USB drive:",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Default
            )

            // Direct Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Scan Phone MediaStore
                Button(
                    onClick = onScanMediaStore,
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("empty_scan_device_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Entire Device Audio (MediaStore)", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }

                // 2. Pick SAF Folder
                Button(
                    onClick = onMountSaf,
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = NeonAmber),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonAmber),
                    modifier = Modifier.fillMaxWidth().testTag("empty_pick_folder_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Specific Folder from Storage (SAF)", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }

                // 3. Pick Individual Audio Files
                Button(
                    onClick = onPickAudioFiles,
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth().testTag("empty_pick_files_button")
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick Audio Files (.mp3, .flac, .wav, .m4a)", fontWeight = FontWeight.Normal, fontSize = 11.5.sp)
                }

                // 4. Load Demo Tracks (Optional)
                OutlinedButton(
                    onClick = onLoadDemoTracks,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("empty_load_demo_button")
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Load Example Demo Tracks (Optional)", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun getKeyColor(key: String): Color {
    return when {
        key.startsWith("1") -> Color(0xFF00F0FF)
        key.startsWith("2") -> Color(0xFF05FFA1)
        key.startsWith("3") -> Color(0xFF70E000)
        key.startsWith("4") -> Color(0xFFFFB703)
        key.startsWith("5") -> Color(0xFFFB8500)
        key.startsWith("6") -> Color(0xFFFF2A6D)
        key.startsWith("7") -> Color(0xFFFF0055)
        key.startsWith("8") -> Color(0xFFD90429)
        key.startsWith("9") -> Color(0xFF7209B7)
        key.startsWith("10") -> Color(0xFF3A0CA3)
        key.startsWith("11") -> Color(0xFF4361EE)
        key.startsWith("12") -> Color(0xFF4CC9F0)
        else -> DeckACyan
    }
}
