package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.FileOperationType
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track
import com.example.model.UpdateState
import com.example.ui.components.ApiConfigDialog
import com.example.ui.components.DjMiniPlayer
import com.example.ui.components.GoogleDriveBrowserView
import com.example.ui.components.LocalMusicView
import com.example.ui.components.NowPlayingFullScreen
import com.example.ui.components.NowPlayingModalSheet
import com.example.ui.components.OperationsAndCloudView
import com.example.ui.components.UpdateDialog
import android.app.Activity
import com.example.ui.components.SoundCloudTab
import com.example.ui.components.SoundCloudOrange
import com.example.ui.components.SpectrogramAnalyzerView
import com.example.ui.components.SpotifyGreen
import com.example.ui.components.SpotifyTab
import com.example.ui.library.LocalLibraryScreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isFolderExplorerOpen by viewModel.isFolderExplorerOpen.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val storageSources by viewModel.storageSources.collectAsState()
    val currentSourceId by viewModel.currentStorageSourceId.collectAsState()
    val operationJournal by viewModel.operationJournal.collectAsState()

    val hasStoragePermission by viewModel.hasStoragePermission.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgressMessage by viewModel.scanProgressMessage.collectAsState()
    val scanServiceState by viewModel.scanServiceState.collectAsState()

    val analyzedTrack by viewModel.analyzedTrack.collectAsState()
    val spectrogramData by viewModel.spectrogramData.collectAsState()
    val isSpectrogramLoading by viewModel.isSpectrogramLoading.collectAsState()
    val spectrogramErrorMessage by viewModel.spectrogramErrorMessage.collectAsState()
    val analysisProgressPercent by viewModel.analysisProgressPercent.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val showApiConfigDialog by viewModel.showApiConfigDialog.collectAsState()

    // Spotify States
    val spotifyAuthState by viewModel.spotifyAuthState.collectAsState()
    val spotifySavedTracks by viewModel.spotifySavedTracks.collectAsState()
    val spotifyPlaylists by viewModel.spotifyPlaylists.collectAsState()
    val spotifySearchResults by viewModel.spotifySearchResults.collectAsState()
    val spotifyIsLoading by viewModel.spotifyIsLoading.collectAsState()

    // SoundCloud States
    val soundCloudAuthState by viewModel.soundCloudAuthState.collectAsState()
    val soundCloudLikedTracks by viewModel.soundCloudLikedTracks.collectAsState()
    val soundCloudPlaylists by viewModel.soundCloudPlaylists.collectAsState()
    val soundCloudSearchResults by viewModel.soundCloudSearchResults.collectAsState()
    val soundCloudIsLoading by viewModel.soundCloudIsLoading.collectAsState()

    // Google Drive States
    val isDriveBrowserOpen by viewModel.isDriveBrowserOpen.collectAsState()
    val driveAuthState by viewModel.driveAuthState.collectAsState()
    val driveListing by viewModel.driveListing.collectAsState()
    val driveBreadcrumbs by viewModel.driveBreadcrumbs.collectAsState()
    val driveIsLoading by viewModel.driveIsLoading.collectAsState()
    val driveSyncStatusMap by viewModel.driveSyncStatusMap.collectAsState()
    val driveDownloadProgressMap by viewModel.driveDownloadProgressMap.collectAsState()

    // Audio Engine Playback States
    val playingTrack by viewModel.audioEngine.currentTrack.collectAsState()
    val isPlaying by viewModel.audioEngine.isPlaying.collectAsState()
    val currentPosSec by viewModel.audioEngine.currentPositionSec.collectAsState()
    val playbackProgress by viewModel.audioEngine.playbackProgress.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()

    // EQ & Haas Audio Effect States
    val eqLow by viewModel.audioEngine.eqLow.collectAsState()
    val eqMid by viewModel.audioEngine.eqMid.collectAsState()
    val eqHigh by viewModel.audioEngine.eqHigh.collectAsState()
    val haasEnabled by viewModel.audioEngine.haasEnabled.collectAsState()
    val haasAmount by viewModel.audioEngine.haasAmount.collectAsState()
    val haasDelayMs by viewModel.audioEngine.haasDelayMs.collectAsState()

    // SoundSync In-App Update States
    val updateState by viewModel.updateState.collectAsState()
    val updateLastCheckedTimestamp by viewModel.updateLastCheckedTimestamp.collectAsState()
    val isAutoUpdateCheckEnabled by viewModel.isAutoUpdateCheckEnabled.collectAsState()

    // Now Playing Display Mode & Waveform States
    val nowPlayingDisplayMode by viewModel.nowPlayingDisplayMode.collectAsState()
    val isNowPlayingExpanded by viewModel.isNowPlayingExpanded.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val isWaveformLoading by viewModel.isWaveformLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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
                    currentTab = selectedTab,
                    isScanning = isScanning,
                    onOpenConfig = { viewModel.openApiConfigDialog() },
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
                // Docked Mini-Player Bar (When a track is loaded/playing)
                playingTrack?.let { track ->
                    DjMiniPlayer(
                        track = track,
                        displayMode = nowPlayingDisplayMode,
                        waveformData = waveformData,
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L,
                        onTogglePlayPause = { viewModel.audioEngine.togglePlayPause() },
                        onPreviousTrack = { viewModel.previousTrack() },
                        onNextTrack = { viewModel.nextTrack() },
                        onSeekToMs = { ms -> viewModel.seekToMs(ms) },
                        onToggleDisplayMode = { viewModel.toggleNowPlayingDisplayMode() },
                        onOpenNowPlaying = { viewModel.openNowPlaying() }
                    )
                }

                // Bottom Navigation (Local, SoundCloud, Spotify, Spectrogram, DJ Tools)
                DjBottomNavigationBar(
                    selectedTab = selectedTab,
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
                DjTab.LOCAL -> {
                    if (isFolderExplorerOpen) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                color = DjSurfaceDark,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { viewModel.toggleFolderExplorer(false) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back to Library",
                                            tint = DeckACyan
                                        )
                                    }
                                    Text(
                                        text = "Folder & Storage Explorer",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }

                            LocalMusicView(
                                tracks = allTracks,
                                currentTrack = playingTrack,
                                isPlaying = isPlaying,
                                isScanning = isScanning,
                                scanProgressText = scanProgressMessage,
                                onScanMediaStore = { viewModel.scanDeviceMediaStore() },
                                onPickSafFolder = onPickSafFolder,
                                onPickAudioFiles = onPickAudioFiles,
                                onLoadTrack = { viewModel.playTrack(it) },
                                onInspectSpectrogram = { track ->
                                    viewModel.inspectTrackSpectrogram(track, showTab = true)
                                }
                            )
                        }
                    } else {
                        LocalLibraryScreen(
                            viewModel = viewModel,
                            onOpenFolderExplorer = { viewModel.toggleFolderExplorer(true) }
                        )
                    }
                }
                DjTab.SOUNDCLOUD -> {
                    SoundCloudTab(
                        authState = soundCloudAuthState,
                        likedTracks = soundCloudLikedTracks,
                        playlists = soundCloudPlaylists,
                        searchResults = soundCloudSearchResults,
                        isLoading = soundCloudIsLoading,
                        currentTrack = playingTrack,
                        isPlaying = isPlaying,
                        onConnectSoundCloud = { viewModel.connectSoundCloud(context) },
                        onDisconnect = { viewModel.disconnectSoundCloud() },
                        onOpenConfigDialog = { viewModel.openApiConfigDialog() },
                        onSearch = { viewModel.searchSoundCloud(it) },
                        onPlayTrack = { viewModel.playSoundCloudTrack(it) },
                        onInspectSpectrogram = { track ->
                            viewModel.inspectTrackSpectrogram(track, showTab = true)
                        },
                        onRefresh = { viewModel.refreshSoundCloud() }
                    )
                }
                DjTab.SPOTIFY -> {
                    SpotifyTab(
                        authState = spotifyAuthState,
                        savedTracks = spotifySavedTracks,
                        playlists = spotifyPlaylists,
                        searchResults = spotifySearchResults,
                        isLoading = spotifyIsLoading,
                        currentTrack = playingTrack,
                        isPlaying = isPlaying,
                        onConnectSpotify = { viewModel.connectSpotify(context) },
                        onDisconnect = { viewModel.disconnectSpotify() },
                        onOpenConfigDialog = { viewModel.openApiConfigDialog() },
                        onSearch = { viewModel.searchSpotify(it) },
                        onPlayTrack = { viewModel.playSpotifyTrack(it) },
                        onInspectSpectrogram = { track ->
                            viewModel.inspectTrackSpectrogram(track, showTab = true)
                        },
                        onRefresh = { viewModel.refreshSpotify() }
                    )
                }
                DjTab.SPECTROGRAM -> {
                    SpectrogramAnalyzerView(
                        analyzedTrack = analyzedTrack ?: playingTrack ?: allTracks.firstOrNull(),
                        spectrogramData = spectrogramData,
                        allTracks = allTracks,
                        isPlaying = isPlaying,
                        currentPositionSec = currentPosSec,
                        playbackProgress = playbackProgress,
                        onSelectTrack = { viewModel.inspectTrackSpectrogram(it, showTab = false) },
                        onTogglePlayPause = { viewModel.audioEngine.togglePlayPause() },
                        onSeekToRatio = { ratio ->
                            viewModel.audioEngine.seekToFraction(ratio)
                        },
                        onLoadToDeck = { viewModel.playTrack(it) },
                        isLoading = isSpectrogramLoading,
                        analysisProgressPercent = analysisProgressPercent,
                        errorMessage = spectrogramErrorMessage,
                        onRetryAnalysis = { viewModel.retrySpectrogramAnalysis() }
                    )
                }
                DjTab.OPERATIONS -> {
                    if (isDriveBrowserOpen) {
                        GoogleDriveBrowserView(
                            authState = driveAuthState,
                            listing = driveListing,
                            breadcrumbs = driveBreadcrumbs,
                            syncStatusMap = driveSyncStatusMap,
                            downloadProgressMap = driveDownloadProgressMap,
                            isLoading = driveIsLoading,
                            currentPlayingTrack = playingTrack,
                            isPlaying = isPlaying,
                            onBack = { viewModel.closeGoogleDriveBrowser() },
                            onNavigateBreadcrumb = { viewModel.navigateDriveBreadcrumb(it) },
                            onOpenFolder = { id, name -> viewModel.openDriveFolder(id, name) },
                            onPlayTrack = { viewModel.playDriveTrack(it) },
                            onDownloadTrack = { viewModel.downloadDriveTrack(it) },
                            onCancelDownload = { viewModel.cancelDriveDownload(it) },
                            onSyncEntireFolder = { viewModel.syncEntireDriveFolder() },
                            onConnectAccount = { viewModel.connectGoogleDrive(context as? Activity) },
                            onDisconnectAccount = { viewModel.disconnectGoogleDrive() },
                            onRefresh = { viewModel.refreshDriveFolder() }
                        )
                    } else {
                        OperationsAndCloudView(
                            storageSources = storageSources,
                            operationJournal = operationJournal,
                            scanServiceState = scanServiceState,
                            updateState = updateState,
                            lastCheckedTimestamp = updateLastCheckedTimestamp,
                            isAutoCheckEnabled = isAutoUpdateCheckEnabled,
                            onCheckForUpdates = { viewModel.checkForUpdates(isManual = true) },
                            onToggleAutoCheck = { viewModel.setAutoUpdateCheckEnabled(it) },
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
                            onCancelScan = { viewModel.cancelScanService() },
                            onOpenGoogleDrive = { viewModel.openGoogleDriveBrowser() },
                            onConnectGoogleDrive = { viewModel.connectGoogleDrive(context as? Activity) },
                            onDisconnectGoogleDrive = { viewModel.disconnectGoogleDrive() }
                        )
                    }
                }
            }

            // In-App Update Dialog / Progress / Install Sheet
            val activity = context as? Activity
            UpdateDialog(
                updateState = updateState,
                onStartDownload = { info -> viewModel.startUpdateDownload(info) },
                onCancelDownload = { viewModel.cancelUpdateDownload() },
                onInstallApk = { apkFile, info ->
                    activity?.let { act -> viewModel.installUpdateApk(act, apkFile, info) }
                },
                onDismiss = {
                    val tagName = (updateState as? UpdateState.UpdateAvailable)?.info?.tagName
                    viewModel.dismissUpdateDialog(tagName)
                },
                onRetry = { viewModel.checkForUpdates(isManual = true) }
            )

            // API Configuration Dialog
            if (showApiConfigDialog) {
                ApiConfigDialog(
                    initialSpotifyClientId = viewModel.spotifyRepository.getStoredClientId(),
                    initialSoundCloudClientId = viewModel.soundCloudRepository.getStoredClientId(),
                    onSaveSpotifyClientId = { viewModel.saveSpotifyClientId(it) },
                    onSaveSoundCloudClientId = { viewModel.saveSoundCloudClientId(it) },
                    onDismiss = { viewModel.closeApiConfigDialog() }
                )
            }

            // Full-Screen Now Playing Page
            AnimatedVisibility(
                visible = isNowPlayingExpanded && playingTrack != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                if (playingTrack != null) {
                    NowPlayingFullScreen(
                        track = playingTrack!!,
                        displayMode = nowPlayingDisplayMode,
                        waveformData = waveformData,
                        isWaveformLoading = isWaveformLoading,
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        durationMs = if (playingTrack!!.durationSeconds > 0) playingTrack!!.durationSeconds * 1000L else 0L,
                        eqLow = eqLow,
                        eqMid = eqMid,
                        eqHigh = eqHigh,
                        onSetEqLow = { viewModel.audioEngine.setEq(it, eqMid, eqHigh) },
                        onSetEqMid = { viewModel.audioEngine.setEq(eqLow, it, eqHigh) },
                        onSetEqHigh = { viewModel.audioEngine.setEq(eqLow, eqMid, it) },
                        haasEnabled = haasEnabled,
                        haasAmount = haasAmount,
                        haasDelayMs = haasDelayMs,
                        onSetHaasEnabled = { viewModel.audioEngine.setHaasEnabled(it) },
                        onSetHaasAmount = { viewModel.audioEngine.setHaasAmount(it) },
                        onSetHaasDelayMs = { viewModel.audioEngine.setHaasDelayMs(it) },
                        onDismiss = { viewModel.closeNowPlaying() },
                        onTogglePlayPause = { viewModel.audioEngine.togglePlayPause() },
                        onPreviousTrack = { viewModel.previousTrack() },
                        onNextTrack = { viewModel.nextTrack() },
                        onSeekToMs = { ms -> viewModel.seekToMs(ms) },
                        onToggleDisplayMode = { viewModel.toggleNowPlayingDisplayMode() },
                        onSetDisplayMode = { mode -> viewModel.setNowPlayingDisplayMode(mode) },
                        onOpenProperties = { track -> viewModel.openTrackProperties(track) }
                    )
                }
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isPaused) {
                    Icon(Icons.Default.Pause, contentDescription = "Paused", tint = NeonAmber, modifier = Modifier.size(14.dp))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = DeckACyan,
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = message,
                    color = if (isPaused) NeonAmber else DeckACyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            if (isBackgroundService) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isPaused) {
                        IconButton(onClick = onResume, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = NeonGreen, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        IconButton(onClick = onPause, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = NeonAmber, modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel", tint = NeonRed, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DjTopAppBar(
    totalTracks: Int,
    currentTab: DjTab,
    isScanning: Boolean,
    onOpenConfig: () -> Unit,
    onRescan: () -> Unit
) {
    Surface(
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // App Branding & Active Tab Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeckACyan.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(18.dp))
                    }
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "SOUNDSYNC",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (currentTab) {
                                DjTab.LOCAL -> DeckACyan.copy(alpha = 0.2f)
                                DjTab.SOUNDCLOUD -> SoundCloudOrange.copy(alpha = 0.2f)
                                DjTab.SPOTIFY -> SpotifyGreen.copy(alpha = 0.2f)
                                DjTab.SPECTROGRAM -> NeonPurple.copy(alpha = 0.2f)
                                DjTab.OPERATIONS -> TextMuted.copy(alpha = 0.2f)
                            }
                        ) {
                            Text(
                                text = currentTab.title.uppercase(),
                                color = when (currentTab) {
                                    DjTab.LOCAL -> DeckACyan
                                    DjTab.SOUNDCLOUD -> SoundCloudOrange
                                    DjTab.SPOTIFY -> SpotifyGreen
                                    DjTab.SPECTROGRAM -> NeonPurple
                                    DjTab.OPERATIONS -> TextPrimary
                                },
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "$totalTracks Local Tracks • DJ & Streaming Suite",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            // Action Icons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onOpenConfig, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "API Config", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isScanning, onClick = onRescan),
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), color = DeckACyan, strokeWidth = 1.5.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = TextSecondary, modifier = Modifier.size(12.dp))
                        }
                        Text(
                            text = "SCAN",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DjBottomNavigationBar(
    selectedTab: DjTab,
    onTabSelected: (DjTab) -> Unit
) {
    NavigationBar(
        modifier = Modifier.testTag("dj_bottom_nav_bar"),
        containerColor = DjSurfaceDark,
        tonalElevation = 8.dp
    ) {
        val tabs = listOf(
            Triple(DjTab.LOCAL, "Local", Icons.Default.FolderOpen),
            Triple(DjTab.SOUNDCLOUD, "SoundCloud", Icons.Default.Cloud),
            Triple(DjTab.SPOTIFY, "Spotify", Icons.Default.LibraryMusic),
            Triple(DjTab.SPECTROGRAM, "Spectrum", Icons.Default.GraphicEq),
            Triple(DjTab.OPERATIONS, "DJ Tools", Icons.Default.Storage)
        )

        tabs.forEach { (tab, title, icon) ->
            val isSelected = selectedTab == tab
            val tabColor = when (tab) {
                DjTab.LOCAL -> DeckACyan
                DjTab.SOUNDCLOUD -> SoundCloudOrange
                DjTab.SPOTIFY -> SpotifyGreen
                DjTab.SPECTROGRAM -> DeckACyan
                DjTab.OPERATIONS -> DeckACyan
            }

            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(icon, contentDescription = title)
                },
                label = {
                    Text(
                        text = title,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = tabColor,
                    selectedTextColor = tabColor,
                    indicatorColor = tabColor.copy(alpha = 0.15f),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
            )
        }
    }
}
