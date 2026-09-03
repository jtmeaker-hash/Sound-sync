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
import androidx.compose.material.icons.filled.Bookmark
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
import kotlinx.coroutines.launch
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import com.example.ui.components.AudioEffectsPanel
import com.example.ui.djtools.ClippingDetectorTool
import com.example.ui.djtools.DynamicRangeMeterTool
import com.example.ui.djtools.KeyConverterTool
import com.example.ui.djtools.MetronomeTool
import com.example.ui.djtools.RmsMeterTool
import com.example.ui.djtools.TapBpmTool
import com.example.ui.settings.AppearanceSettingsScreen
import com.example.ui.settings.GitHubSettingsScreen
import com.example.ui.settings.LibrarySettingsScreen
import com.example.ui.settings.PlaybackSettingsScreen
import com.example.ui.sidemenu.SideMenuDestination
import com.example.ui.sidemenu.SideNavigationDrawerContent
import com.example.ui.components.LocalMusicView
import com.example.ui.components.FilePropertiesDialog
import com.example.ui.components.NowPlayingFullScreen
import com.example.ui.components.NowPlayingModalSheet
import com.example.ui.components.NowPlayingSettingsSheet
import com.example.ui.components.OperationsAndCloudView
import com.example.ui.components.SaveSongFindDialog
import com.example.ui.components.SongFindsView
import com.example.ui.components.UpdateDialog
import android.app.Activity
import com.example.ui.components.SpectrogramAnalyzerView
import com.example.ui.components.StreamingView
import com.example.ui.library.LocalLibraryScreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.SoundCloudOrange
import com.example.ui.theme.SpotifyGreen
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
import com.example.ui.theme.ThemeMode

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
    val inspectingTrackForProperties by viewModel.inspectingTrackForProperties.collectAsState()

    // Song Finds States
    val songFinds by viewModel.songFinds.collectAsState()
    val pendingShare by viewModel.pendingShare.collectAsState()

    // Spotify States
    val selectedStreamingProvider by viewModel.selectedStreamingProvider.collectAsState()
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

    // Audio Engine Playback States (low-frequency only at root level)
    val playingTrack by viewModel.audioEngine.currentTrack.collectAsState()
    val isPlaying by viewModel.audioEngine.isPlaying.collectAsState()
    // High-frequency position state is collected inside the specific child composables
    // that display it (DjMiniPlayer, NowPlayingFullScreen, SpectrogramAnalyzerView)
    // to avoid causing the entire MainDjScreen to recompose on every position update.

    // EQ & Haas Audio Effect States
    val eqEnabled by viewModel.audioEngine.eqEnabled.collectAsState()
    val eqLow by viewModel.audioEngine.eqLow.collectAsState()
    val eqMid by viewModel.audioEngine.eqMid.collectAsState()
    val eqHigh by viewModel.audioEngine.eqHigh.collectAsState()
    val haasEnabled by viewModel.audioEngine.haasEnabled.collectAsState()
    val haasAmount by viewModel.audioEngine.haasAmount.collectAsState()
    val haasDelayMs by viewModel.audioEngine.haasDelayMs.collectAsState()

    // SoundSync In-App Update States
    val repeatMode by viewModel.repeatMode.collectAsState()
    val isShuffleEnabled by viewModel.isShuffleEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val updateLastCheckedTimestamp by viewModel.updateLastCheckedTimestamp.collectAsState()
    val isAutoUpdateCheckEnabled by viewModel.isAutoUpdateCheckEnabled.collectAsState()

    // Now Playing Display Mode & Waveform States
    val nowPlayingDisplayMode by viewModel.nowPlayingDisplayMode.collectAsState()
    val isNowPlayingExpanded by viewModel.isNowPlayingExpanded.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val isWaveformLoading by viewModel.isWaveformLoading.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val crossfadeSeconds by viewModel.crossfadeSeconds.collectAsState()
    var showNowPlayingSettings by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var activeSideDestination by remember { mutableStateOf<SideMenuDestination?>(null) }

    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
    BackHandler(enabled = activeSideDestination != null) {
        activeSideDestination = null
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SideNavigationDrawerContent(
                onSelectDestination = { dest ->
                    coroutineScope.launch { drawerState.close() }
                    activeSideDestination = dest
                },
                onCloseDrawer = {
                    coroutineScope.launch { drawerState.close() }
                }
            )
        }
    ) {
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
                        onOpenMenu = { coroutineScope.launch { drawerState.open() } },
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
                // Docked Mini-Player Bar (only displayed when full player is NOT expanded)
                if (!isNowPlayingExpanded) {
                    playingTrack?.let { track ->
                        PositionAwareMiniPlayer(
                            track = track,
                            displayMode = nowPlayingDisplayMode,
                            waveformData = waveformData,
                            isPlaying = isPlaying,
                            audioEngine = viewModel.audioEngine,
                            onTogglePlayPause = { viewModel.audioEngine.togglePlayPause() },
                            onPreviousTrack = { viewModel.previousTrack() },
                            onNextTrack = { viewModel.nextTrack() },
                            onSeekToMs = { ms -> viewModel.seekToMs(ms) },
                            onToggleDisplayMode = { viewModel.toggleNowPlayingDisplayMode() },
                            onOpenNowPlaying = { viewModel.openNowPlaying() }
                        )
                    }
                }

                // Bottom Navigation (Local, SoundCloud, Spotify, Spectrogram, Settings)
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
            when {
                isDriveBrowserOpen -> {
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
                        onPlayTrack = { viewModel.playDriveTrackFromListing(it) },
                        onDownloadTrack = { viewModel.downloadDriveTrack(it) },
                        onCancelDownload = { viewModel.cancelDriveDownload(it) },
                        onSyncEntireFolder = { viewModel.syncEntireDriveFolder() },
                        onConnectAccount = { viewModel.connectGoogleDrive(context as? Activity) },
                        onDisconnectAccount = { viewModel.disconnectGoogleDrive() },
                        onRefresh = { viewModel.refreshDriveFolder() }
                    )
                }
                activeSideDestination != null -> {
                    SideDestinationScreen(
                        destination = activeSideDestination!!,
                        viewModel = viewModel,
                        allTracks = allTracks,
                        playingTrack = playingTrack,
                        isPlaying = isPlaying,
                        scanServiceState = scanServiceState,
                        storageSources = storageSources,
                        operationJournal = operationJournal,
                        updateState = updateState,
                        updateLastCheckedTimestamp = updateLastCheckedTimestamp,
                        isAutoUpdateCheckEnabled = isAutoUpdateCheckEnabled,
                        themeMode = themeMode,
                        crossfadeSeconds = crossfadeSeconds,
                        repeatMode = repeatMode,
                        isShuffleEnabled = isShuffleEnabled,
                        eqEnabled = eqEnabled,
                        eqLow = eqLow,
                        eqMid = eqMid,
                        eqHigh = eqHigh,
                        haasEnabled = haasEnabled,
                        haasAmount = haasAmount,
                        haasDelayMs = haasDelayMs,
                        onPickSafFolder = onPickSafFolder,
                        onPickAudioFiles = onPickAudioFiles,
                        onClose = { activeSideDestination = null }
                    )
                }
                else -> {
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
                        DjTab.FINDS -> {
                            SongFindsView(
                                songFinds = songFinds,
                                onAddNewFind = { viewModel.openCreateSongFindDialog() },
                                onToggleCompleted = { id, completed -> viewModel.toggleSongFindCompleted(id, completed) },
                                onDeleteFind = { id -> viewModel.deleteSongFind(id) },
                                onClearCompleted = { viewModel.clearCompletedSongFinds() },
                                onSearchInLibrary = { query ->
                                    viewModel.selectTab(DjTab.LOCAL)
                                }
                            )
                        }
                        DjTab.STREAMING -> {
                            StreamingView(
                                activeProviderId = selectedStreamingProvider,
                                onSelectProvider = { viewModel.selectStreamingProvider(it) },
                                spotifyAuthState = spotifyAuthState,
                                spotifySavedTracks = spotifySavedTracks,
                                spotifyPlaylists = spotifyPlaylists,
                                spotifySearchResults = spotifySearchResults,
                                spotifyIsLoading = spotifyIsLoading,
                                onConnectSpotify = { viewModel.connectSpotify(context) },
                                onDisconnectSpotify = { viewModel.disconnectSpotify() },
                                onSearchSpotify = { viewModel.searchSpotify(it) },
                                onPlaySpotifyTrack = { viewModel.playSpotifyTrack(it) },
                                onRefreshSpotify = { viewModel.refreshSpotify() },
                                soundCloudAuthState = soundCloudAuthState,
                                soundCloudLikedTracks = soundCloudLikedTracks,
                                soundCloudPlaylists = soundCloudPlaylists,
                                soundCloudSearchResults = soundCloudSearchResults,
                                soundCloudIsLoading = soundCloudIsLoading,
                                onConnectSoundCloud = { viewModel.connectSoundCloud(context) },
                                onDisconnectSoundCloud = { viewModel.disconnectSoundCloud() },
                                onSearchSoundCloud = { viewModel.searchSoundCloud(it) },
                                onPlaySoundCloudTrack = { viewModel.playSoundCloudTrack(it) },
                                onRefreshSoundCloud = { viewModel.refreshSoundCloud() },
                                currentTrack = playingTrack,
                                isPlaying = isPlaying,
                                onInspectSpectrogram = { track ->
                                    viewModel.inspectTrackSpectrogram(track, showTab = true)
                                },
                                onOpenConfigDialog = { viewModel.openApiConfigDialog() }
                            )
                        }
                        DjTab.SPECTROGRAM -> {
                            PositionAwareSpectrogramTab(
                                analyzedTrack = analyzedTrack ?: playingTrack ?: allTracks.firstOrNull(),
                                spectrogramData = spectrogramData,
                                allTracks = allTracks,
                                isPlaying = isPlaying,
                                audioEngine = viewModel.audioEngine,
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
                    }
                }
            }

            // In-App Update Dialog / Prepare Update Confirmation
            UpdateDialog(
                updateState = updateState,
                onPrepareUpdate = { info -> viewModel.prepareUpdate(info) },
                onCancelPrepare = { viewModel.cancelPrepareUpdate() },
                onConfirmUpdateAndUninstall = {
                    viewModel.openReleaseAndUninstall(context)
                },
                onDismiss = {
                    val tagName = (updateState as? UpdateState.UpdateAvailable)?.info?.tagName
                        ?: (updateState as? UpdateState.PrepareUpdate)?.info?.tagName
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

            // Save Song Find Dialog
            pendingShare?.let { share ->
                SaveSongFindDialog(
                    pendingShare = share,
                    onSave = { url, title, platform, notes ->
                        viewModel.saveSongFind(url, title, platform, notes)
                    },
                    onDismiss = { viewModel.dismissSongFindDialog() }
                )
            }

            // Track metadata inspector
            inspectingTrackForProperties?.let { inspectedTrack ->
                FilePropertiesDialog(
                    track = inspectedTrack,
                    onDismiss = { viewModel.closeTrackProperties() },
                    onSave = { viewModel.saveTrackProperties(it) },
                    onAutoTag = { viewModel.autoTagSingleTrack(it) },
                    onInspectSpectrogram = {
                        viewModel.closeTrackProperties()
                        viewModel.inspectTrackSpectrogram(it, showTab = true)
                    },
                    onDelete = { viewModel.deleteTrack(it) }
                )
            }

            // Full-Screen Now Playing Page (position collected locally)
            AnimatedVisibility(
                visible = isNowPlayingExpanded && playingTrack != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                if (playingTrack != null) {
                    PositionAwareNowPlaying(
                        track = playingTrack!!,
                        displayMode = nowPlayingDisplayMode,
                        waveformData = waveformData,
                        isWaveformLoading = isWaveformLoading,
                        isPlaying = isPlaying,
                        audioEngine = viewModel.audioEngine,
                        eqEnabled = eqEnabled,
                        eqLow = eqLow,
                        eqMid = eqMid,
                        eqHigh = eqHigh,
                        onSetEqEnabled = { viewModel.audioEngine.setEqEnabled(it) },
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
                        isShuffleEnabled = isShuffleEnabled,
                        repeatMode = repeatMode,
                        onToggleShuffle = { viewModel.toggleShuffle() },
                        onToggleRepeat = { viewModel.toggleRepeatMode() },
                        onSeekToMs = { ms -> viewModel.seekToMs(ms) },
                        onToggleDisplayMode = { viewModel.toggleNowPlayingDisplayMode() },
                        onSetDisplayMode = { mode -> viewModel.setNowPlayingDisplayMode(mode) },
                        onOpenSettings = { showNowPlayingSettings = true },
                        onOpenProperties = { track -> viewModel.openTrackProperties(track) }
                    )
                }
            }

            if (showNowPlayingSettings) {
                NowPlayingSettingsSheet(
                    crossfadeSeconds = crossfadeSeconds,
                    onCrossfadeSecondsChange = { viewModel.setCrossfadeSeconds(it) },
                    eqEnabled = eqEnabled,
                    eqLow = eqLow,
                    eqMid = eqMid,
                    eqHigh = eqHigh,
                    onSetEqEnabled = { viewModel.audioEngine.setEqEnabled(it) },
                    onSetEqLow = { viewModel.audioEngine.setEq(it, eqMid, eqHigh) },
                    onSetEqMid = { viewModel.audioEngine.setEq(eqLow, it, eqHigh) },
                    onSetEqHigh = { viewModel.audioEngine.setEq(eqLow, eqMid, it) },
                    haasEnabled = haasEnabled,
                    haasAmount = haasAmount,
                    haasDelayMs = haasDelayMs,
                    onSetHaasEnabled = { viewModel.audioEngine.setHaasEnabled(it) },
                    onSetHaasAmount = { viewModel.audioEngine.setHaasAmount(it) },
                    onSetHaasDelayMs = { viewModel.audioEngine.setHaasDelayMs(it) },
                    onDismiss = { showNowPlayingSettings = false }
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
    onOpenMenu: () -> Unit,
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Hamburger Menu + App Branding & Active Tab Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onOpenMenu,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Navigation Menu",
                        tint = DeckACyan,
                        modifier = Modifier.size(24.dp)
                    )
                }

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
                                DjTab.FINDS -> NeonAmber.copy(alpha = 0.2f)
                                DjTab.STREAMING -> SpotifyGreen.copy(alpha = 0.2f)
                                DjTab.SPECTROGRAM -> NeonPurple.copy(alpha = 0.2f)
                            }
                        ) {
                            Text(
                                text = currentTab.title.uppercase(),
                                color = when (currentTab) {
                                    DjTab.LOCAL -> DeckACyan
                                    DjTab.FINDS -> NeonAmber
                                    DjTab.STREAMING -> SpotifyGreen
                                    DjTab.SPECTROGRAM -> NeonPurple
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
            Triple(DjTab.FINDS, "Finds", Icons.Default.Bookmark),
            Triple(DjTab.STREAMING, "Streaming", Icons.Default.Cloud),
            Triple(DjTab.SPECTROGRAM, "Spectrum", Icons.Default.GraphicEq)
        )

        tabs.forEach { (tab, title, icon) ->
            val isSelected = selectedTab == tab
            val tabColor = when (tab) {
                DjTab.LOCAL -> DeckACyan
                DjTab.FINDS -> NeonAmber
                DjTab.STREAMING -> SpotifyGreen
                DjTab.SPECTROGRAM -> DeckACyan
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

// ── Position-aware wrappers that collect high-frequency state locally ────
// These prevent the entire MainDjScreen from recomposing on every position update.

@Composable
private fun PositionAwareMiniPlayer(
    track: com.example.model.Track,
    displayMode: com.example.model.NowPlayingDisplayMode,
    waveformData: com.example.audio.WaveformData?,
    isPlaying: Boolean,
    audioEngine: com.example.audio.DjAudioEngine,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onOpenNowPlaying: () -> Unit
) {
    val currentPositionMs = audioEngine.currentPositionMs.collectAsState().value
    DjMiniPlayer(
        track = track,
        displayMode = displayMode,
        waveformData = waveformData,
        isPlaying = isPlaying,
        currentPositionMs = currentPositionMs,
        durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L,
        onTogglePlayPause = onTogglePlayPause,
        onPreviousTrack = onPreviousTrack,
        onNextTrack = onNextTrack,
        onSeekToMs = onSeekToMs,
        onToggleDisplayMode = onToggleDisplayMode,
        onOpenNowPlaying = onOpenNowPlaying
    )
}

@Composable
private fun PositionAwareNowPlaying(
    track: com.example.model.Track,
    displayMode: com.example.model.NowPlayingDisplayMode,
    waveformData: com.example.audio.WaveformData?,
    isWaveformLoading: Boolean,
    isPlaying: Boolean,
    audioEngine: com.example.audio.DjAudioEngine,
    eqEnabled: Boolean, eqLow: Float, eqMid: Float, eqHigh: Float,
    onSetEqEnabled: (Boolean) -> Unit, onSetEqLow: (Float) -> Unit,
    onSetEqMid: (Float) -> Unit, onSetEqHigh: (Float) -> Unit,
    haasEnabled: Boolean, haasAmount: Float, haasDelayMs: Float,
    onSetHaasEnabled: (Boolean) -> Unit, onSetHaasAmount: (Float) -> Unit,
    onSetHaasDelayMs: (Float) -> Unit,
    onDismiss: () -> Unit, onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit, onNextTrack: () -> Unit,
    isShuffleEnabled: Boolean = false,
    repeatMode: com.example.ui.RepeatMode = com.example.ui.RepeatMode.OFF,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onSeekToMs: (Long) -> Unit, onToggleDisplayMode: () -> Unit,
    onSetDisplayMode: (com.example.model.NowPlayingDisplayMode) -> Unit,
    onOpenSettings: () -> Unit, onOpenProperties: (com.example.model.Track) -> Unit
) {
    val currentPositionMs = audioEngine.currentPositionMs.collectAsState().value
    NowPlayingFullScreen(
        track = track,
        displayMode = displayMode,
        waveformData = waveformData,
        isWaveformLoading = isWaveformLoading,
        isPlaying = isPlaying,
        currentPositionMs = currentPositionMs,
        durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L,
        eqEnabled = eqEnabled, eqLow = eqLow, eqMid = eqMid, eqHigh = eqHigh,
        onSetEqEnabled = onSetEqEnabled, onSetEqLow = onSetEqLow,
        onSetEqMid = onSetEqMid, onSetEqHigh = onSetEqHigh,
        haasEnabled = haasEnabled, haasAmount = haasAmount, haasDelayMs = haasDelayMs,
        onSetHaasEnabled = onSetHaasEnabled, onSetHaasAmount = onSetHaasAmount,
        onSetHaasDelayMs = onSetHaasDelayMs,
        isShuffleEnabled = isShuffleEnabled,
        repeatMode = repeatMode,
        onToggleShuffle = onToggleShuffle,
        onToggleRepeat = onToggleRepeat,
        onDismiss = onDismiss, onTogglePlayPause = onTogglePlayPause,
        onPreviousTrack = onPreviousTrack, onNextTrack = onNextTrack,
        onSeekToMs = onSeekToMs, onToggleDisplayMode = onToggleDisplayMode,
        onSetDisplayMode = onSetDisplayMode, onOpenSettings = onOpenSettings,
        onOpenProperties = onOpenProperties
    )
}

@Composable
private fun PositionAwareSpectrogramTab(
    analyzedTrack: com.example.model.Track?,
    spectrogramData: com.example.model.SpectrogramAnalysis?,
    allTracks: List<com.example.model.Track>,
    isPlaying: Boolean,
    audioEngine: com.example.audio.DjAudioEngine,
    onSelectTrack: (com.example.model.Track) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekToRatio: (Float) -> Unit,
    onLoadToDeck: (com.example.model.Track) -> Unit,
    isLoading: Boolean,
    analysisProgressPercent: Int,
    errorMessage: String?,
    onRetryAnalysis: () -> Unit
) {
    val currentPositionSec = audioEngine.currentPositionSec.collectAsState().value
    val playbackProgress = audioEngine.playbackProgress.collectAsState().value
    SpectrogramAnalyzerView(
        analyzedTrack = analyzedTrack,
        spectrogramData = spectrogramData,
        allTracks = allTracks,
        isPlaying = isPlaying,
        currentPositionSec = currentPositionSec,
        playbackProgress = playbackProgress,
        onSelectTrack = onSelectTrack,
        onTogglePlayPause = onTogglePlayPause,
        onSeekToRatio = onSeekToRatio,
        onLoadToDeck = onLoadToDeck,
        isLoading = isLoading,
        analysisProgressPercent = analysisProgressPercent,
        errorMessage = errorMessage,
        onRetryAnalysis = onRetryAnalysis
    )
}

@Composable
private fun SideDestinationScreen(
    destination: SideMenuDestination,
    viewModel: MainDjViewModel,
    allTracks: List<com.example.model.Track>,
    playingTrack: com.example.model.Track?,
    isPlaying: Boolean,
    scanServiceState: com.example.service.AudioScanState,
    storageSources: List<com.example.model.StorageSource>,
    operationJournal: List<com.example.model.OperationJournalItem>,
    updateState: UpdateState,
    updateLastCheckedTimestamp: Long,
    isAutoUpdateCheckEnabled: Boolean,
    themeMode: com.example.ui.theme.ThemeMode,
    crossfadeSeconds: Int,
    repeatMode: RepeatMode,
    isShuffleEnabled: Boolean,
    eqEnabled: Boolean,
    eqLow: Float,
    eqMid: Float,
    eqHigh: Float,
    haasEnabled: Boolean,
    haasAmount: Float,
    haasDelayMs: Float,
    onPickSafFolder: () -> Unit,
    onPickAudioFiles: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DjObsidian)
    ) {
        // Top Bar for destination
        Surface(
            color = DjSurfaceDark,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = DeckACyan
                        )
                    }
                    Text(
                        text = destination.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            when (destination) {
                SideMenuDestination.Metronome -> {
                    MetronomeTool(modifier = Modifier.fillMaxSize().padding(14.dp))
                }
                SideMenuDestination.TapBpm -> {
                    TapBpmTool(modifier = Modifier.fillMaxSize().padding(14.dp))
                }
                SideMenuDestination.KeyConverter -> {
                    KeyConverterTool(modifier = Modifier.fillMaxSize().padding(14.dp))
                }
                SideMenuDestination.RmsMeter -> {
                    RmsMeterTool(
                        audioEngine = viewModel.audioEngine,
                        selectedTrack = playingTrack,
                        allTracks = allTracks,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SideMenuDestination.ClippingDetector -> {
                    ClippingDetectorTool(
                        audioEngine = viewModel.audioEngine,
                        selectedTrack = playingTrack,
                        allTracks = allTracks,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SideMenuDestination.DynamicRangeMeter -> {
                    DynamicRangeMeterTool(
                        selectedTrack = playingTrack,
                        allTracks = allTracks,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SideMenuDestination.Eq, SideMenuDestination.HaasSurround -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        AudioEffectsPanel(
                            eqEnabled = eqEnabled,
                            eqLow = eqLow,
                            eqMid = eqMid,
                            eqHigh = eqHigh,
                            onSetEqEnabled = { viewModel.audioEngine.setEqEnabled(it) },
                            onSetEqLow = { viewModel.audioEngine.setEq(it, eqMid, eqHigh) },
                            onSetEqMid = { viewModel.audioEngine.setEq(eqLow, it, eqHigh) },
                            onSetEqHigh = { viewModel.audioEngine.setEq(eqLow, eqMid, it) },
                            haasEnabled = haasEnabled,
                            haasAmount = haasAmount,
                            haasDelayMs = haasDelayMs,
                            onSetHaasEnabled = { viewModel.audioEngine.setHaasEnabled(it) },
                            onSetHaasAmount = { viewModel.audioEngine.setHaasAmount(it) },
                            onSetHaasDelayMs = { viewModel.audioEngine.setHaasDelayMs(it) }
                        )
                    }
                }
                SideMenuDestination.PlaybackSettings -> {
                    PlaybackSettingsScreen(
                        crossfadeSeconds = crossfadeSeconds,
                        onCrossfadeSecondsChange = { viewModel.setCrossfadeSeconds(it) },
                        repeatMode = repeatMode,
                        onToggleRepeat = { viewModel.toggleRepeatMode() },
                        isShuffleEnabled = isShuffleEnabled,
                        onToggleShuffle = { viewModel.toggleShuffle() }
                    )
                }
                SideMenuDestination.LibrarySettings -> {
                    LibrarySettingsScreen(
                        storageSources = storageSources,
                        operationJournal = operationJournal,
                        scanServiceState = scanServiceState,
                        metadataSettings = viewModel.metadataSettings.collectAsState().value,
                        focusMusicBrainz = false,
                        onSetEnrichmentEnabled = viewModel::setEnrichmentEnabled,
                        onSetMusicBrainzEnabled = viewModel::setMusicBrainzEnabled,
                        onSetBpmAnalysisEnabled = viewModel::setBpmAnalysisEnabled,
                        onSetKeyAnalysisEnabled = viewModel::setKeyAnalysisEnabled,
                        onSetWriteToFileEnabled = viewModel::setWriteToFileEnabled,
                        onSetShowProvenanceBadges = viewModel::setShowProvenanceBadges,
                        onSetConcurrency = viewModel::setEnrichmentConcurrency,
                        onSetBpmRange = viewModel::setBpmRange,
                        onTriggerSync = { viewModel.triggerCloudSync() },
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
                SideMenuDestination.MusicBrainzSettings -> {
                    LibrarySettingsScreen(
                        storageSources = storageSources,
                        operationJournal = operationJournal,
                        scanServiceState = scanServiceState,
                        metadataSettings = viewModel.metadataSettings.collectAsState().value,
                        focusMusicBrainz = true,
                        onSetEnrichmentEnabled = viewModel::setEnrichmentEnabled,
                        onSetMusicBrainzEnabled = viewModel::setMusicBrainzEnabled,
                        onSetBpmAnalysisEnabled = viewModel::setBpmAnalysisEnabled,
                        onSetKeyAnalysisEnabled = viewModel::setKeyAnalysisEnabled,
                        onSetWriteToFileEnabled = viewModel::setWriteToFileEnabled,
                        onSetShowProvenanceBadges = viewModel::setShowProvenanceBadges,
                        onSetConcurrency = viewModel::setEnrichmentConcurrency,
                        onSetBpmRange = viewModel::setBpmRange,
                        onTriggerSync = { viewModel.triggerCloudSync() },
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
                SideMenuDestination.AppearanceSettings -> {
                    AppearanceSettingsScreen(
                        themeMode = themeMode,
                        onSetThemeMode = { viewModel.setThemeMode(it) }
                    )
                }
                SideMenuDestination.GitHubUpdates -> {
                    GitHubSettingsScreen(
                        updateState = updateState,
                        lastCheckedTimestamp = updateLastCheckedTimestamp,
                        isAutoCheckEnabled = isAutoUpdateCheckEnabled,
                        onCheckForUpdates = { viewModel.checkForUpdates(isManual = true) },
                        onToggleAutoCheck = { viewModel.setAutoUpdateCheckEnabled(it) }
                    )
                }
            }
        }
    }
}

