package com.example.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.FileOperationType
import com.example.model.Track
import com.example.ui.components.AddTrackDialog
import com.example.ui.components.AudioInspectorBar
import com.example.ui.components.AutoTagProgressDialog
import com.example.ui.components.DjFileExplorerView
import com.example.ui.components.DuplicateFinderSheet
import com.example.ui.components.FilePropertiesDialog
import com.example.ui.components.LibraryCrateView
import com.example.ui.components.OperationsAndCloudView
import com.example.ui.components.SafeBulkOperationDialog
import com.example.ui.components.SpectrogramAnalyzerView
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MainDjScreen(
    viewModel: MainDjViewModel = viewModel()
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val directoryTracks by viewModel.currentDirectoryTracks.collectAsState()
    val subFolders by viewModel.currentSubFolders.collectAsState()
    val storageSources by viewModel.storageSources.collectAsState()
    val currentSourceId by viewModel.currentStorageSourceId.collectAsState()
    val currentDirPath by viewModel.currentDirectoryPath.collectAsState()
    val selectedTrackIds by viewModel.selectedTrackIds.collectAsState()
    val sortOption by viewModel.explorerSortOption.collectAsState()
    val isDryRun by viewModel.isDryRunEnabled.collectAsState()
    val operationJournal by viewModel.operationJournal.collectAsState()

    val filteredTracks by viewModel.filteredTracks.collectAsState()
    val duplicateMatches by viewModel.duplicateMatches.collectAsState()
    val crates by viewModel.crates.collectAsState()
    val selectedCrateId by viewModel.selectedCrateId.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val analyzedTrack by viewModel.analyzedTrack.collectAsState()
    val spectrogramData by viewModel.spectrogramData.collectAsState()
    val isTagging by viewModel.isTaggingInProgress.collectAsState()
    val taggingMessage by viewModel.taggingProgressMessage.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    val playingTrack by viewModel.audioEngine.currentTrack.collectAsState()
    val isPlaying by viewModel.audioEngine.isPlaying.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var inspectingTrackForProperties by remember { mutableStateOf<Track?>(null) }
    var bulkOperationType by remember { mutableStateOf<FileOperationType?>(null) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(DjObsidian),
        containerColor = DjObsidian,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DjTopAppBar(
                totalTracks = allTracks.size,
                duplicatesCount = duplicateMatches.size,
                currentSourceLabel = storageSources.find { it.id == currentSourceId }?.label ?: "Storage",
                onSelectDuplicatesTab = { viewModel.selectTab(DjTab.DUPLICATES) }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Docked Audio Inspector & Mini Player (When a track is selected/playing)
                if (playingTrack != null) {
                    AudioInspectorBar(
                        audioEngine = viewModel.audioEngine,
                        onOpenSpectrogram = { track ->
                            viewModel.inspectTrackSpectrogram(track)
                            viewModel.selectTab(DjTab.SPECTROGRAM)
                        },
                        onOpenProperties = { track ->
                            inspectingTrackForProperties = track
                        },
                        onAutoTag = { track ->
                            viewModel.autoTagSingleTrack(track)
                        }
                    )
                }

                // Bottom Navigation
                DjBottomNavigationBar(
                    selectedTab = selectedTab,
                    duplicatesCount = duplicateMatches.size,
                    onTabSelected = { viewModel.selectTab(it) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DjObsidian)
        ) {
            when (selectedTab) {
                DjTab.EXPLORER -> {
                    DjFileExplorerView(
                        tracks = directoryTracks,
                        subFolders = subFolders,
                        storageSources = storageSources,
                        currentSourceId = currentSourceId,
                        currentPath = currentDirPath,
                        selectedTrackIds = selectedTrackIds,
                        searchQuery = searchQuery,
                        sortOption = sortOption,
                        isDryRun = isDryRun,
                        playingTrackId = playingTrack?.id,
                        isPlaying = isPlaying,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        onSelectSource = { viewModel.selectStorageSource(it) },
                        onNavigateToDir = { viewModel.navigateToDirectory(it) },
                        onNavigateUp = { viewModel.navigateUpDirectory() },
                        onToggleSelectTrack = { viewModel.toggleTrackSelection(it) },
                        onSelectAll = { viewModel.selectAllInCurrentDirectory() },
                        onClearSelection = { viewModel.clearSelection() },
                        onSortChange = { viewModel.setSortOption(it) },
                        onToggleDryRun = { viewModel.toggleDryRun() },
                        onPlayTrack = { viewModel.playTrack(it) },
                        onOpenProperties = { inspectingTrackForProperties = it },
                        onInspectSpectrogram = { track ->
                            viewModel.inspectTrackSpectrogram(track)
                            viewModel.selectTab(DjTab.SPECTROGRAM)
                        },
                        onAutoTagTrack = { viewModel.autoTagSingleTrack(it) },
                        onBulkMove = { bulkOperationType = FileOperationType.MOVE },
                        onBulkTrash = { bulkOperationType = FileOperationType.TRASH },
                        onBulkAutoTag = { viewModel.performBulkAutoTag() },
                        onMountSaf = { viewModel.mountSafDirectory() },
                        onOpenAddTrack = { showAddTrackDialog = true }
                    )
                }
                DjTab.LIBRARY -> {
                    LibraryCrateView(
                        tracks = filteredTracks,
                        crates = crates,
                        selectedCrateId = selectedCrateId,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        onSelectCrate = { viewModel.selectCrate(it) },
                        onLoadToDeck = { viewModel.playTrack(it) },
                        onInspectSpectrogram = { track ->
                            viewModel.inspectTrackSpectrogram(track)
                            viewModel.selectTab(DjTab.SPECTROGRAM)
                        },
                        onAutoTagSingle = { viewModel.autoTagSingleTrack(it) },
                        onAutoTagAll = { viewModel.runAutoTagAll() },
                        onDeleteTrack = { viewModel.deleteTrack(it) },
                        onOpenAddTrack = { showAddTrackDialog = true }
                    )
                }
                DjTab.SPECTROGRAM -> {
                    SpectrogramAnalyzerView(
                        analyzedTrack = analyzedTrack,
                        spectrogramData = spectrogramData,
                        allTracks = allTracks,
                        onSelectTrack = { viewModel.inspectTrackSpectrogram(it) },
                        onLoadToDeck = { viewModel.playTrack(it) }
                    )
                }
                DjTab.DUPLICATES -> {
                    DuplicateFinderSheet(
                        duplicateMatches = duplicateMatches,
                        onResolveKeepBest = { viewModel.resolveDuplicateKeepBest(it) },
                        onInspectSpectrogram = { track ->
                            viewModel.inspectTrackSpectrogram(track)
                            viewModel.selectTab(DjTab.SPECTROGRAM)
                        },
                        onLoadToDeck = { viewModel.playTrack(it) }
                    )
                }
                DjTab.OPERATIONS -> {
                    OperationsAndCloudView(
                        storageSources = storageSources,
                        operationJournal = operationJournal,
                        onTriggerSync = { viewModel.triggerCloudSync() },
                        onExportRekordbox = { viewModel.exportRekordboxXml() },
                        onUndoOperation = { viewModel.undoJournalOperation(it) },
                        onMountSaf = { viewModel.mountSafDirectory() }
                    )
                }
            }

            // Dialogs
            if (showAddTrackDialog) {
                AddTrackDialog(
                    onDismiss = { showAddTrackDialog = false },
                    onAddTrack = { title, artist, genre, bpm, key, format, bitrate ->
                        viewModel.addNewTrack(title, artist, genre, bpm, key, format, bitrate)
                    }
                )
            }

            if (inspectingTrackForProperties != null) {
                FilePropertiesDialog(
                    track = inspectingTrackForProperties!!,
                    onDismiss = { inspectingTrackForProperties = null },
                    onSave = { updated ->
                        viewModel.updateTrackMetadata(updated)
                        inspectingTrackForProperties = null
                    },
                    onAutoTag = { track ->
                        viewModel.autoTagSingleTrack(track)
                    },
                    onInspectSpectrogram = { track ->
                        inspectingTrackForProperties = null
                        viewModel.inspectTrackSpectrogram(track)
                        viewModel.selectTab(DjTab.SPECTROGRAM)
                    },
                    onDelete = { track ->
                        viewModel.deleteTrack(track)
                    }
                )
            }

            if (bulkOperationType != null) {
                SafeBulkOperationDialog(
                    operationType = bulkOperationType!!,
                    affectedCount = selectedTrackIds.size,
                    currentPath = currentDirPath,
                    initialDryRun = isDryRun,
                    onDismiss = { bulkOperationType = null },
                    onConfirm = { targetDir, dryRun ->
                        when (bulkOperationType) {
                            FileOperationType.MOVE -> viewModel.performBulkMove(targetDir)
                            FileOperationType.TRASH -> viewModel.performBulkTrash()
                            else -> {}
                        }
                    }
                )
            }

            if (isTagging) {
                AutoTagProgressDialog(message = taggingMessage)
            }
        }
    }
}

@Composable
private fun DjTopAppBar(
    totalTracks: Int,
    duplicatesCount: Int,
    currentSourceLabel: String,
    onSelectDuplicatesTab: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("dj_top_app_bar"),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand & Logo
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(DeckACyan, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(
                        text = "SOUNDSYNC PRO",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Hybrid DJ File Explorer & Lab",
                        color = DeckACyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Quick Status Chips
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Storage Source Pill
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceElevated
                ) {
                    Text(
                        text = currentSourceLabel.uppercase(),
                        color = DeckACyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                // Total Tracks Count
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceElevated
                ) {
                    Text(
                        text = "$totalTracks TRACKS",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                // Duplicates alert badge
                if (duplicatesCount > 0) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onSelectDuplicatesTab),
                        shape = RoundedCornerShape(6.dp),
                        color = DeckBPink.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeckBPink)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(12.dp))
                            Text(
                                text = "$duplicatesCount DUPS",
                                color = DeckBPink,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DjBottomNavigationBar(
    selectedTab: DjTab,
    duplicatesCount: Int,
    onTabSelected: (DjTab) -> Unit
) {
    NavigationBar(
        modifier = Modifier.testTag("dj_bottom_nav_bar"),
        containerColor = DjSurfaceDark,
        tonalElevation = 8.dp
    ) {
        val tabs = listOf(
            Triple(DjTab.EXPLORER, "Explorer", Icons.Default.FolderOpen),
            Triple(DjTab.LIBRARY, "Crates", Icons.AutoMirrored.Filled.QueueMusic),
            Triple(DjTab.SPECTROGRAM, "Spectrum", Icons.Default.GraphicEq),
            Triple(DjTab.DUPLICATES, "Duplicates", Icons.Default.ContentCopy),
            Triple(DjTab.OPERATIONS, "Storage", Icons.Default.Storage)
        )

        tabs.forEach { (tab, title, icon) ->
            val isSelected = selectedTab == tab

            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = {
                    if (tab == DjTab.DUPLICATES && duplicatesCount > 0) {
                        BadgedBox(badge = {
                            Badge(containerColor = DeckBPink, contentColor = Color.White) {
                                Text("$duplicatesCount")
                            }
                        }) {
                            Icon(icon, contentDescription = title)
                        }
                    } else {
                        Icon(icon, contentDescription = title)
                    }
                },
                label = {
                    Text(
                        text = title,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DeckACyan,
                    selectedTextColor = DeckACyan,
                    indicatorColor = DeckACyan.copy(alpha = 0.15f),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
            )
        }
    }
}
