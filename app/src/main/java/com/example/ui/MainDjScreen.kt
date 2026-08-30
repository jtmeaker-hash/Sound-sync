package com.example.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MainDjScreen(
    viewModel: MainDjViewModel = viewModel(),
    onRequestStoragePermission: () -> Unit = {},
    onPickSafFolder: () -> Unit = {},
    onPickAudioFiles: () -> Unit = {}
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

    val hasStoragePermission by viewModel.hasStoragePermission.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgressMessage by viewModel.scanProgressMessage.collectAsState()
    val scanServiceState by viewModel.scanServiceState.collectAsState()

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
            Column(modifier = Modifier.fillMaxWidth()) {
                DjTopAppBar(
                    totalTracks = allTracks.size,
                    duplicatesCount = duplicateMatches.size,
                    currentSourceLabel = storageSources.find { it.id == currentSourceId }?.label ?: "Storage",
                    isScanning = isScanning,
                    onSelectDuplicatesTab = { viewModel.selectTab(DjTab.DUPLICATES) },
                    onRescan = { viewModel.scanDeviceMediaStore() }
                )

                // Storage Permission Request Banner (if not granted)
                if (!hasStoragePermission) {
                    PermissionRequestBanner(onRequestPermission = onRequestStoragePermission)
                }

                // Scanning Progress Banner (foreground MediaStore or DocumentFile Background Service)
                AnimatedVisibility(visible = isScanning || scanServiceState.isScanning || scanServiceState.isPaused) {
                    val msg = when {
                        scanServiceState.isPaused -> "DocumentFile Scan Paused: ${scanServiceState.filesIndexed} tracks indexed"
                        scanServiceState.isScanning -> "DocumentFile Scan: [${scanServiceState.filesIndexed}/${scanServiceState.filesDiscovered}] ${scanServiceState.currentFile.ifBlank { scanServiceState.currentDirectory }}"
                        else -> scanProgressMessage
                    }
                    ScanningProgressBanner(
                        message = msg,
                        isPaused = scanServiceState.isPaused,
                        isBackgroundService = scanServiceState.isScanning || scanServiceState.isPaused,
                        onPause = { viewModel.pauseScanService() },
                        onResume = { viewModel.resumeScanService() },
                        onCancel = { viewModel.cancelScanService() }
                    )
                }
            }
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
                        isScanning = isScanning,
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
                        onMountSaf = onPickSafFolder,
                        onPickAudioFiles = onPickAudioFiles,
                        onScanMediaStore = { viewModel.scanDeviceMediaStore() },
                        onLoadDemoTracks = { viewModel.loadDemoTracks() },
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
                        onPickAudioFiles = onPickAudioFiles,
                        onPickSafFolder = onPickSafFolder,
                        onScanMediaStore = { viewModel.scanDeviceMediaStore() },
                        onLoadDemoTracks = { viewModel.loadDemoTracks() },
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
                        scanServiceState = scanServiceState,
                        onTriggerSync = { viewModel.triggerCloudSync() },
                        onExportRekordbox = { viewModel.exportRekordboxXml() },
                        onUndoOperation = { viewModel.undoJournalOperation(it) },
                        onMountSaf = onPickSafFolder,
                        onPickAudioFiles = onPickAudioFiles,
                        onScanMediaStore = { viewModel.scanDeviceMediaStore() },
                        onCleanMissingFiles = { viewModel.cleanMissingFiles() },
                        onLoadDemoTracks = { viewModel.loadDemoTracks() },
                        onClearLibrary = { viewModel.clearLibrary() },
                        onPauseScan = { viewModel.pauseScanService() },
                        onResumeScan = { viewModel.resumeScanService() },
                        onCancelScan = { viewModel.cancelScanService() }
                    )
                }
            }

            // Dialogs
            if (showAddTrackDialog) {
                AddTrackDialog(
                    onDismiss = { showAddTrackDialog = false },
                    onPickRealFiles = {
                        showAddTrackDialog = false
                        onPickAudioFiles()
                    },
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
private fun PermissionRequestBanner(
    onRequestPermission: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NeonAmber.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonAmber)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(20.dp))
                Column {
                    Text(
                        text = "STORAGE PERMISSION REQUIRED",
                        color = NeonAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Grant audio storage access to scan device tracks automatically.",
                        color = TextPrimary,
                        fontSize = 10.sp
                    )
                }
            }

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = NeonAmber, contentColor = DjObsidian),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("grant_permission_button")
            ) {
                Text("Grant", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ScanningProgressBanner(
    message: String,
    isPaused: Boolean = false,
    isBackgroundService: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isPaused) NeonAmber.copy(alpha = 0.15f) else DeckACyan.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isPaused) NeonAmber else DeckACyan)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isPaused) {
                    Icon(Icons.Default.Pause, contentDescription = "Paused", tint = NeonAmber, modifier = Modifier.size(16.dp))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = DeckACyan,
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = message.ifBlank { "Indexing audio files & stems from storage..." },
                    color = if (isPaused) NeonAmber else DeckACyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            if (isBackgroundService) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isPaused) {
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onResume),
                            color = NeonAmber,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Resume", color = DjObsidian, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    } else {
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onPause),
                            color = DjSurfaceElevated,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Pause", color = NeonAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }

                    Surface(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onCancel),
                        color = DjSurfaceElevated,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Stop", color = NeonRed, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DjTopAppBar(
    totalTracks: Int,
    duplicatesCount: Int,
    currentSourceLabel: String,
    isScanning: Boolean,
    onSelectDuplicatesTab: () -> Unit,
    onRescan: () -> Unit
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
                        text = "Real Android Audio Storage & DJ Lab",
                        color = DeckACyan,
                        fontSize = 9.5.sp,
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

                // Total Tracks Count / Rescan Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isScanning, onClick = onRescan),
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceElevated
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), color = DeckACyan, strokeWidth = 1.5.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = TextSecondary, modifier = Modifier.size(11.dp))
                        }
                        Text(
                            text = "$totalTracks TRACKS",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
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
