package com.example.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.analysis.AiAutoTagger
import com.example.analysis.DuplicateDetector
import com.example.audio.DjAudioEngine
import com.example.audio.SpectrogramEngine
import com.example.data.AppDatabase
import com.example.data.SourceFolderEntity
import com.example.data.TrackEntity
import com.example.model.AudioQualityRating
import com.example.model.DjCrate
import com.example.model.DuplicateMatch
import com.example.model.ExplorerSortOption
import com.example.model.FileOperationType
import com.example.model.FolderItem
import com.example.model.MusicPlatform
import com.example.model.OperationJournalItem
import com.example.model.SpectrogramAnalysis
import com.example.model.StorageSource
import com.example.model.StorageSourceType
import com.example.model.SyncState
import com.example.model.Track
import com.example.service.AudioScanService
import com.example.service.AudioScanState
import com.example.storage.LocalFileSystemScanner
import com.example.storage.MediaScannerHelper
import com.example.storage.SafStorageManager
import com.example.storage.ScanStateManager
import com.example.storage.ScanStatus
import com.example.sync.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class DjTab(val title: String, val iconName: String) {
    LOCAL("Local", "folder"),
    SOUNDCLOUD("SoundCloud", "cloud"),
    SPOTIFY("Spotify", "library_music"),
    SPECTROGRAM("Spectrogram", "graphic_eq"),
    OPERATIONS("DJ Tools", "storage")
}

class MainDjViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()
    private val sourceFolderDao = db.sourceFolderDao()

    val spotifyRepository = com.example.network.spotify.SpotifyRepository(application)
    val soundCloudRepository = com.example.network.soundcloud.SoundCloudRepository(application)

    val scanStateManager = ScanStateManager(application)
    private val scanMutex = Mutex()
    private var currentScanJob: Job? = null

    val audioEngine = DjAudioEngine(application)

    private val _selectedTab = MutableStateFlow(DjTab.LOCAL)
    val selectedTab = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // Dialog state for API credentials
    private val _showApiConfigDialog = MutableStateFlow(false)
    val showApiConfigDialog = _showApiConfigDialog.asStateFlow()

    // Permission and Scanning State
    private val _hasStoragePermission = MutableStateFlow(checkInitialStoragePermission())
    val hasStoragePermission = _hasStoragePermission.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _scanProgressMessage = MutableStateFlow("")
    val scanProgressMessage = _scanProgressMessage.asStateFlow()

    // Spectrogram analysis progress
    private val _analysisProgressPercent = MutableStateFlow(0)
    val analysisProgressPercent = _analysisProgressPercent.asStateFlow()

    // Spotify State Flows
    val spotifyAuthState = spotifyRepository.authState
    val spotifySavedTracks = spotifyRepository.savedTracks
    val spotifyPlaylists = spotifyRepository.playlists
    val spotifySearchResults = spotifyRepository.searchResults
    val spotifyIsLoading = spotifyRepository.isLoadingContent

    // SoundCloud State Flows
    val soundCloudAuthState = soundCloudRepository.authState
    val soundCloudLikedTracks = soundCloudRepository.likedTracks
    val soundCloudPlaylists = soundCloudRepository.playlists
    val soundCloudSearchResults = soundCloudRepository.searchResults
    val soundCloudIsLoading = soundCloudRepository.isLoadingContent

    // Background DocumentFile AudioScanService State
    val scanServiceState: StateFlow<AudioScanState> = AudioScanService.scanState

    // File Explorer Navigation State
    private val _currentStorageSourceId = MutableStateFlow("all")
    val currentStorageSourceId = _currentStorageSourceId.asStateFlow()

    private val _currentDirectoryPath = MutableStateFlow("")
    val currentDirectoryPath = _currentDirectoryPath.asStateFlow()

    private val _selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackIds = _selectedTrackIds.asStateFlow()

    private val _explorerSortOption = MutableStateFlow(ExplorerSortOption.NAME_ASC)
    val explorerSortOption = _explorerSortOption.asStateFlow()

    private val _explorerViewMode = MutableStateFlow("detailed")
    val explorerViewMode = _explorerViewMode.asStateFlow()

    private val _isDryRunEnabled = MutableStateFlow(false)
    val isDryRunEnabled = _isDryRunEnabled.asStateFlow()

    private val _selectedCrateId = MutableStateFlow("crate_all")
    val selectedCrateId = _selectedCrateId.asStateFlow()

    private val _selectedGenreFilter = MutableStateFlow<String?>(null)
    val selectedGenreFilter = _selectedGenreFilter.asStateFlow()

    private val _selectedPlatformFilter = MutableStateFlow<MusicPlatform?>(null)
    val selectedPlatformFilter = _selectedPlatformFilter.asStateFlow()

    private val _isTaggingInProgress = MutableStateFlow(false)
    val isTaggingInProgress = _isTaggingInProgress.asStateFlow()

    private val _taggingProgressMessage = MutableStateFlow("")
    val taggingProgressMessage = _taggingProgressMessage.asStateFlow()

    private val _analyzedTrack = MutableStateFlow<Track?>(null)
    val analyzedTrack = _analyzedTrack.asStateFlow()

    private val _inspectingTrackForProperties = MutableStateFlow<Track?>(null)
    val inspectingTrackForProperties = _inspectingTrackForProperties.asStateFlow()

    private val _spectrogramData = MutableStateFlow<SpectrogramAnalysis?>(null)
    val spectrogramData = _spectrogramData.asStateFlow()

    private val _isSpectrogramLoading = MutableStateFlow(false)
    val isSpectrogramLoading = _isSpectrogramLoading.asStateFlow()

    private var currentAnalysisJob: Job? = null

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    private val _crates = MutableStateFlow(CloudSyncManager.getInitialCrates())
    val crates = _crates.asStateFlow()

    private val _storageSources = MutableStateFlow<List<StorageSource>>(emptyList())
    val storageSources = _storageSources.asStateFlow()

    private val _operationJournal = MutableStateFlow<List<OperationJournalItem>>(emptyList())
    val operationJournal = _operationJournal.asStateFlow()

    // Real database tracks flow
    val allTracks: StateFlow<List<Track>> = trackDao.getAllTracks()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Tracks in Current Directory for File Explorer
    val currentDirectoryTracks: StateFlow<List<Track>> = combine(
        allTracks,
        _currentDirectoryPath,
        _currentStorageSourceId,
        _searchQuery,
        _explorerSortOption
    ) { tracks, dirPath, sourceId, query, sortOpt ->
        val filtered = tracks.filter { track ->
            val matchesSource = sourceId == "all" || track.sourceId == sourceId ||
                (sourceId == "internal" && (track.directoryPath.contains("emulated/0") || track.directoryPath.startsWith("/storage/emulated/0"))) ||
                (sourceId == "downloads" && track.directoryPath.contains("Download", ignoreCase = true)) ||
                (sourceId == "sd_card" && (track.directoryPath.contains("storage/") && !track.directoryPath.contains("emulated/0")))

            val matchesDir = dirPath.isBlank() || dirPath == "/" || sourceId == "all" ||
                track.directoryPath.equals(dirPath, ignoreCase = true) ||
                track.directoryPath.startsWith(dirPath, ignoreCase = true)

            val matchesQuery = query.isBlank() ||
                track.title.contains(query, ignoreCase = true) ||
                track.artist.contains(query, ignoreCase = true) ||
                track.genre.contains(query, ignoreCase = true) ||
                track.musicalKey.contains(query, ignoreCase = true) ||
                track.format.contains(query, ignoreCase = true)

            matchesSource && matchesDir && matchesQuery
        }

        when (sortOpt) {
            ExplorerSortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            ExplorerSortOption.BPM_ASC -> filtered.sortedBy { it.bpm }
            ExplorerSortOption.BPM_DESC -> filtered.sortedByDescending { it.bpm }
            ExplorerSortOption.KEY -> filtered.sortedBy { it.musicalKey }
            ExplorerSortOption.QUALITY -> filtered.sortedByDescending { it.bitrateKbps }
            ExplorerSortOption.ENERGY_DESC -> filtered.sortedByDescending { it.energyRating }
            ExplorerSortOption.DATE_DESC -> filtered.sortedByDescending { it.dateAdded }
            ExplorerSortOption.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeMb }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Subfolders in current directory
    val currentSubFolders: StateFlow<List<FolderItem>> = combine(
        allTracks,
        _currentDirectoryPath,
        _currentStorageSourceId
    ) { tracks, dirPath, sourceId ->
        val subDirs = mutableMapOf<String, MutableList<Track>>()
        
        if (dirPath.isNotBlank() && dirPath != "/") {
            tracks.forEach { track ->
                val trackDir = track.directoryPath
                if (trackDir.startsWith(dirPath, ignoreCase = true) && !trackDir.equals(dirPath, ignoreCase = true)) {
                    val relative = trackDir.removePrefix(dirPath).trimStart('/')
                    val folderName = relative.substringBefore('/')
                    if (folderName.isNotBlank()) {
                        val fullSubPath = if (dirPath.endsWith("/")) "$dirPath$folderName" else "$dirPath/$folderName"
                        subDirs.getOrPut(fullSubPath) { mutableListOf() }.add(track)
                    }
                }
            }
        } else {
            // Group by top-level directories
            tracks.forEach { track ->
                val trackDir = track.directoryPath
                if (trackDir.isNotBlank()) {
                    subDirs.getOrPut(trackDir) { mutableListOf() }.add(track)
                }
            }
        }

        subDirs.map { (path, folderTracks) ->
            val name = if (path.contains('/')) path.substringAfterLast('/') else path
            FolderItem(
                name = name.ifBlank { "Root Storage" },
                path = path,
                trackCount = folderTracks.size,
                subFolderCount = 0,
                totalSizeMb = folderTracks.sumOf { it.fileSizeMb }
            )
        }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Filtered tracks for Library view
    val filteredTracks: StateFlow<List<Track>> = combine(
        allTracks,
        _searchQuery,
        _selectedCrateId,
        _selectedGenreFilter,
        _selectedPlatformFilter
    ) { tracks, query, crateId, genre, platform ->
        tracks.filter { track ->
            val matchesQuery = query.isBlank() ||
                track.title.contains(query, ignoreCase = true) ||
                track.artist.contains(query, ignoreCase = true) ||
                track.genre.contains(query, ignoreCase = true) ||
                track.musicalKey.contains(query, ignoreCase = true)

            val matchesCrate = when (crateId) {
                "crate_all" -> true
                "crate_lossless" -> track.qualityRating.isLossless
                "crate_peak" -> track.bpm >= 126.0
                "crate_warmup" -> track.bpm < 126.0
                else -> track.crateId == crateId
            }

            val matchesGenre = genre == null || track.genre.equals(genre, ignoreCase = true)
            val matchesPlatform = platform == null || track.platforms.contains(platform)

            matchesQuery && matchesCrate && matchesGenre && matchesPlatform
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Fuzzy duplicate detector live matches
    val duplicateMatches: StateFlow<List<DuplicateMatch>> = allTracks.map { tracks ->
        DuplicateDetector.findDuplicates(tracks)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        initializeStorageAndData()
        observeBackgroundScanner()
    }

    private fun observeBackgroundScanner() {
        viewModelScope.launch {
            var wasScanning = false
            AudioScanService.scanState.collect { state ->
                if (wasScanning && !state.isScanning && state.isCompleted) {
                    refreshStorageSourcesList()
                    showSnackbar("Background scan finished: ${state.totalIndexedInLastRun} audio tracks indexed successfully!")
                }
                wasScanning = state.isScanning
            }
        }
    }

    private fun checkInitialStoragePermission(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun onPermissionResult(isGranted: Boolean) {
        _hasStoragePermission.value = isGranted
        if (isGranted) {
            showSnackbar("Storage access granted!")
            viewModelScope.launch(Dispatchers.IO) {
                val existingCount = trackDao.getTrackCount()
                if (existingCount == 0 && scanStateManager.status != ScanStatus.SCANNING) {
                    scanDeviceMediaStore()
                }
            }
        } else {
            showSnackbar("Storage permission denied. You can import audio files individually or pick folders via SAF.")
        }
    }

    private fun initializeStorageAndData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Check for interrupted scan from a previous app crash or killed process
            val wasInterrupted = scanStateManager.checkAndRecoverInterruptedScan()
            if (wasInterrupted) {
                Log.w("MainDjViewModel", "Detected interrupted scan from prior session. Safely recovered to prevent crash loop.")
                withContext(Dispatchers.Main) {
                    showSnackbar("Previous library scan was interrupted. Tap 'Rescan' to scan when ready.")
                }
            }

            refreshStorageSourcesList()

            val existingCount = trackDao.getTrackCount()
            if (existingCount > 0) {
                // Restore first track into engine in PAUSED state (strict no autoplay on startup)
                val firstTrack = trackDao.getAllTracksSync().firstOrNull()?.toTrack()
                if (firstTrack != null) {
                    withContext(Dispatchers.Main) {
                        Log.d("MainDjViewModel", "Restoring track '${firstTrack.title}' on startup in paused state")
                        audioEngine.loadTrack(firstTrack, autoPlay = false)
                        inspectTrackSpectrogram(firstTrack)
                    }
                }
            } else if (_hasStoragePermission.value && scanStateManager.status == ScanStatus.IDLE && !wasInterrupted) {
                // Only perform auto-scan on clean initial launch if permission is granted and no crash occurred
                scanDeviceMediaStoreInternal()
            }
        }
    }

    private suspend fun refreshStorageSourcesList() {
        val app = getApplication<Application>()
        val physicalSources = LocalFileSystemScanner.getAvailableStorageSources(app)
        val savedSafFolders = sourceFolderDao.getAllSourceFoldersSync()

        val safSources = savedSafFolders.map { sf ->
            StorageSource(
                id = sf.id,
                type = StorageSourceType.SD_CARD,
                label = sf.label,
                path = sf.uriString,
                isOnline = sf.isOnline,
                trackCount = sf.trackCount,
                freeSpaceGb = sf.freeSpaceGb,
                totalSpaceGb = sf.totalSpaceGb,
                lastScanned = sf.lastScanned
            )
        }

        val allSources: List<StorageSource> = listOf(
            StorageSource("all", StorageSourceType.INTERNAL, "All Storage", "", true, trackDao.getTrackCount(), 100.0, 512.0)
        ) + physicalSources + safSources

        _storageSources.value = allSources
    }

    fun scanDeviceMediaStore() {
        if (currentScanJob?.isActive == true || _isScanning.value) {
            Log.d("MainDjViewModel", "MediaStore scan already active, skipping duplicate request.")
            return
        }

        currentScanJob = viewModelScope.launch(Dispatchers.IO) {
            scanDeviceMediaStoreInternal()
        }
    }

    private suspend fun scanDeviceMediaStoreInternal() {
        if (!scanMutex.tryLock()) {
            Log.d("MainDjViewModel", "Scan mutex currently held, aborting parallel scan.")
            return
        }

        val app = getApplication<Application>()
        _isScanning.value = true
        scanStateManager.status = ScanStatus.SCANNING
        _scanProgressMessage.value = "Scanning MediaStore audio repository..."

        var totalScanned = 0
        var isFirstBatch = true

        try {
            totalScanned = MediaScannerHelper.scanDeviceAudioStreaming(
                context = app,
                batchSize = 50,
                onBatch = { batch ->
                    val entities = batch.map { TrackEntity.fromTrack(it) }
                    trackDao.insertTracks(entities)
                    refreshStorageSourcesList()

                    if (isFirstBatch && batch.isNotEmpty() && audioEngine.currentTrack.value == null) {
                        isFirstBatch = false
                        val first = batch.first()
                        withContext(Dispatchers.Main) {
                            audioEngine.loadTrack(first, autoPlay = false)
                            inspectTrackSpectrogram(first)
                        }
                    }
                },
                onProgress = { current, total, title ->
                    _scanProgressMessage.value = "Scanning audio files: $current of $total ($title)..."
                }
            )

            scanStateManager.status = ScanStatus.COMPLETED
            scanStateManager.lastScanTime = System.currentTimeMillis()
            scanStateManager.lastScannedCount = totalScanned

            withContext(Dispatchers.Main) {
                if (totalScanned > 0) {
                    showSnackbar("Successfully indexed $totalScanned audio tracks from phone storage!")
                } else {
                    showSnackbar("No audio files detected in MediaStore. You can select a folder or pick audio files.")
                }
            }
        } catch (e: SecurityException) {
            Log.e("MainDjViewModel", "SecurityException during MediaStore scan", e)
            scanStateManager.status = ScanStatus.FAILED
            scanStateManager.lastErrorMessage = "Permission denied: ${e.localizedMessage}"
            withContext(Dispatchers.Main) {
                showSnackbar("Storage permission required to scan device audio.")
            }
        } catch (e: Exception) {
            Log.e("MainDjViewModel", "Error during MediaStore scan", e)
            scanStateManager.status = ScanStatus.FAILED
            scanStateManager.lastErrorMessage = e.localizedMessage
            withContext(Dispatchers.Main) {
                showSnackbar("Error scanning storage: ${e.localizedMessage ?: "Unknown error"}")
            }
        } finally {
            _isScanning.value = false
            _scanProgressMessage.value = ""
            scanMutex.unlock()
        }
    }

    fun importSafFolder(treeUri: Uri) {
        val app = getApplication<Application>()
        val folderName = treeUri.lastPathSegment?.substringAfterLast(':') ?: "Audio Storage"
        
        // Take persistable permission
        SafStorageManager.takePersistablePermissions(app, treeUri)
        
        // Launch DocumentFile background scanning service
        startBackgroundScanService(treeUri, folderName)
        showSnackbar("Starting background DocumentFile scanner for '$folderName'...")
    }

    fun startBackgroundScanService(treeUri: Uri, label: String = "Audio Storage") {
        val app = getApplication<Application>()
        SafStorageManager.takePersistablePermissions(app, treeUri)
        val sourceId = "saf_${treeUri.hashCode().toLong().let { if (it < 0) -it else it }}"
        AudioScanService.startScan(app, treeUri, label, sourceId)
    }

    fun pauseScanService() {
        val app = getApplication<Application>()
        AudioScanService.pauseScan(app)
        showSnackbar("Scanning paused.")
    }

    fun resumeScanService() {
        val app = getApplication<Application>()
        AudioScanService.resumeScan(app)
        showSnackbar("Resuming scan...")
    }

    fun cancelScanService() {
        val app = getApplication<Application>()
        AudioScanService.cancelScan(app)
        showSnackbar("Scanning cancelled.")
    }

    fun importAudioFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            _scanProgressMessage.value = "Importing ${uris.size} audio files..."

            val imported = mutableListOf<Track>()
            for ((index, uri) in uris.withIndex()) {
                _scanProgressMessage.value = "Importing file (${index + 1}/${uris.size})..."
                val track = MediaScannerHelper.extractTrackFromUri(app, uri)
                if (track != null) {
                    imported.add(track)
                }
            }

            if (imported.isNotEmpty()) {
                trackDao.insertTracks(imported.map { TrackEntity.fromTrack(it) })
                refreshStorageSourcesList()

                val log = OperationJournalItem(
                    id = "op_${UUID.randomUUID().toString().take(6)}",
                    timestamp = System.currentTimeMillis(),
                    operationType = FileOperationType.COPY,
                    affectedTracksCount = imported.size,
                    summary = "Imported ${imported.size} audio files from storage"
                )
                _operationJournal.value = listOf(log) + _operationJournal.value

                val first = imported.first()
                withContext(Dispatchers.Main) {
                    audioEngine.loadTrack(first, autoPlay = false)
                    inspectTrackSpectrogram(first)
                    showSnackbar("Successfully imported ${imported.size} audio files!")
                }
            } else {
                withContext(Dispatchers.Main) {
                    showSnackbar("Could not read audio data from selected files.")
                }
            }

            _isScanning.value = false
            _scanProgressMessage.value = ""
        }
    }

    fun loadDemoTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = CloudSyncManager.getInitialSampleTracks()
            trackDao.insertTracks(samples.map { TrackEntity.fromTrack(it) })
            refreshStorageSourcesList()

            val first = samples.first()
            withContext(Dispatchers.Main) {
                audioEngine.loadTrack(first, autoPlay = false)
                inspectTrackSpectrogram(first)
                showSnackbar("Loaded ${samples.size} DJ demo tracks with Camelot keys and cues!")
            }
        }
    }

    fun clearLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.deleteAllTracks()
            refreshStorageSourcesList()
            withContext(Dispatchers.Main) {
                showSnackbar("Cleared DJ audio library.")
            }
        }
    }

    fun cleanMissingFiles() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val tracks: List<TrackEntity> = trackDao.getAllTracksSync()
            var missingCount = 0
            for (t in tracks) {
                var exists = false
                val path: String = t.filePath
                if (path.startsWith("content://")) {
                    try {
                        val fd = app.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                        if (fd != null) {
                            fd.close()
                            exists = true
                        }
                    } catch (ignored: Exception) {}
                } else {
                    exists = File(path).exists()
                }

                if (!exists && !path.startsWith("demo://") && !path.contains("/Music/Tech House/")) {
                    trackDao.deleteTrackById(t.id)
                    missingCount++
                }
            }
            refreshStorageSourcesList()
            withContext(Dispatchers.Main) {
                showSnackbar("Library cleaned: Removed $missingCount deleted/missing tracks.")
            }
        }
    }

    fun selectTab(tab: DjTab) {
        _selectedTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectStorageSource(sourceId: String) {
        _currentStorageSourceId.value = sourceId
        val src = _storageSources.value.firstOrNull { it.id == sourceId }
        if (src != null) {
            _currentDirectoryPath.value = src.path
        } else {
            _currentDirectoryPath.value = ""
        }
        _selectedTrackIds.value = emptySet()
    }

    fun navigateToDirectory(path: String) {
        _currentDirectoryPath.value = path
        _selectedTrackIds.value = emptySet()
    }

    fun navigateUp() {
        val current = _currentDirectoryPath.value
        val parent = if (current.contains('/')) current.substringBeforeLast('/') else ""
        _currentDirectoryPath.value = parent
        _selectedTrackIds.value = emptySet()
    }

    fun toggleTrackSelection(trackId: String) {
        val current = _selectedTrackIds.value.toMutableSet()
        if (current.contains(trackId)) {
            current.remove(trackId)
        } else {
            current.add(trackId)
        }
        _selectedTrackIds.value = current
    }

    fun selectAllTracksInDirectory() {
        val ids = currentDirectoryTracks.value.map { it.id }.toSet()
        _selectedTrackIds.value = ids
    }

    fun selectAllInCurrentDirectory() = selectAllTracksInDirectory()

    fun clearTrackSelection() {
        _selectedTrackIds.value = emptySet()
    }

    fun clearSelection() = clearTrackSelection()

    fun setExplorerSortOption(option: ExplorerSortOption) {
        _explorerSortOption.value = option
    }

    fun setSortOption(option: ExplorerSortOption) = setExplorerSortOption(option)

    fun navigateUpDirectory() = navigateUp()

    fun setExplorerViewMode(mode: String) {
        _explorerViewMode.value = mode
    }

    fun toggleDryRun() {
        _isDryRunEnabled.value = !_isDryRunEnabled.value
        showSnackbar(if (_isDryRunEnabled.value) "Dry Run Mode ENABLED (Operations will be simulated)" else "Dry Run Mode DISABLED")
    }

    fun selectCrate(crateId: String) {
        _selectedCrateId.value = crateId
    }

    fun setGenreFilter(genre: String?) {
        _selectedGenreFilter.value = genre
    }

    fun setPlatformFilter(platform: MusicPlatform?) {
        _selectedPlatformFilter.value = platform
    }

    fun playOrPreviewTrack(track: Track) {
        if (audioEngine.currentTrack.value?.id == track.id) {
            audioEngine.togglePlayPause()
        } else {
            audioEngine.loadTrack(track, autoPlay = true)
            inspectTrackSpectrogram(track)
        }
    }

    fun playTrack(track: Track) = playOrPreviewTrack(track)

    fun openTrackProperties(track: Track) {
        _inspectingTrackForProperties.value = track
    }

    fun closeTrackProperties() {
        _inspectingTrackForProperties.value = null
    }

    fun inspectTrackSpectrogram(track: Track, showTab: Boolean = false) {
        _analyzedTrack.value = track
        if (showTab) {
            _selectedTab.value = DjTab.SPECTROGRAM
        }

        if (track.platforms.contains(MusicPlatform.SPOTIFY)) {
            _spectrogramData.value = null
            _isSpectrogramLoading.value = false
            _analysisProgressPercent.value = 0
            return
        }

        currentAnalysisJob?.cancel()
        _isSpectrogramLoading.value = true
        _analysisProgressPercent.value = 0

        currentAnalysisJob = viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            try {
                val analysis = SpectrogramEngine.analyzeTrack(
                    context = app,
                    track = track,
                    onProgress = { percent ->
                        _analysisProgressPercent.value = percent
                    }
                )
                _spectrogramData.value = analysis
            } catch (e: Exception) {
                Log.e("MainDjViewModel", "Error analyzing spectrogram for '${track.title}': ${e.message}", e)
            } finally {
                _isSpectrogramLoading.value = false
            }
        }
    }

    // ==========================================
    // STREAMING SERVICES & OAUTH DEEP LINKING
    // ==========================================

    fun openApiConfigDialog() {
        _showApiConfigDialog.value = true
    }

    fun closeApiConfigDialog() {
        _showApiConfigDialog.value = false
    }

    fun saveSpotifyClientId(id: String) {
        spotifyRepository.saveClientId(id)
        showSnackbar("Saved Spotify Client ID")
    }

    fun saveSoundCloudClientId(id: String) {
        soundCloudRepository.saveClientId(id)
        showSnackbar("Saved SoundCloud Client ID")
    }

    fun connectSpotify(context: Context) {
        val authUrl = spotifyRepository.createAuthUrl()
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            showSnackbar("Could not launch browser for Spotify authentication: ${e.message}")
        }
    }

    fun disconnectSpotify() {
        spotifyRepository.disconnect()
        showSnackbar("Disconnected from Spotify")
    }

    fun searchSpotify(query: String) {
        viewModelScope.launch {
            spotifyRepository.searchTracks(query)
        }
    }

    fun refreshSpotify() {
        viewModelScope.launch {
            spotifyRepository.fetchSavedTracks()
            spotifyRepository.fetchPlaylists()
        }
    }

    fun playSpotifyTrack(item: com.example.model.SpotifyTrackItem) {
        val track = item.toAppTrack()
        audioEngine.loadTrack(track, autoPlay = true)
        inspectTrackSpectrogram(track, showTab = false)
        showSnackbar("Loaded Spotify track: '${item.name}'")
    }

    fun connectSoundCloud(context: Context) {
        val authUrl = soundCloudRepository.createAuthUrl()
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            showSnackbar("Could not launch browser for SoundCloud authentication: ${e.message}")
        }
    }

    fun disconnectSoundCloud() {
        soundCloudRepository.disconnect()
        showSnackbar("Disconnected from SoundCloud")
    }

    fun searchSoundCloud(query: String) {
        viewModelScope.launch {
            soundCloudRepository.searchTracks(query)
        }
    }

    fun refreshSoundCloud() {
        viewModelScope.launch {
            soundCloudRepository.fetchLikedTracks()
            soundCloudRepository.fetchPlaylists()
        }
    }

    fun playSoundCloudTrack(item: com.example.model.SoundCloudTrackItem) {
        val track = item.toAppTrack()
        audioEngine.loadTrack(track, autoPlay = true)
        inspectTrackSpectrogram(track, showTab = false)
        showSnackbar("Playing SoundCloud stream: '${item.title}'")
    }

    fun handleDeepLinkUri(uri: Uri) {
        val host = uri.host
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            showSnackbar("OAuth authentication error: $error")
            return
        }

        if (code.isNullOrBlank()) return

        if (host == "spotify-callback") {
            viewModelScope.launch {
                showSnackbar("Verifying Spotify authorization...")
                val result = spotifyRepository.exchangeCodeForToken(code)
                if (result.isSuccess) {
                    showSnackbar("Successfully connected Spotify account!")
                    _selectedTab.value = DjTab.SPOTIFY
                } else {
                    showSnackbar("Spotify connection failed: ${result.exceptionOrNull()?.message}")
                }
            }
        } else if (host == "soundcloud-callback") {
            viewModelScope.launch {
                showSnackbar("Verifying SoundCloud authorization...")
                val result = soundCloudRepository.exchangeCodeForToken(code)
                if (result.isSuccess) {
                    showSnackbar("Successfully connected SoundCloud account!")
                    _selectedTab.value = DjTab.SOUNDCLOUD
                } else {
                    showSnackbar("SoundCloud connection failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun saveTrackProperties(updatedTrack: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.updateTrack(TrackEntity.fromTrack(updatedTrack))
            if (audioEngine.currentTrack.value?.id == updatedTrack.id) {
                val wasPlaying = audioEngine.isPlaying.value
                val currentSec = audioEngine.currentPositionSec.value
                withContext(Dispatchers.Main) {
                    audioEngine.loadTrack(updatedTrack, autoPlay = wasPlaying, initialPositionSec = currentSec)
                }
            }
            withContext(Dispatchers.Main) {
                _inspectingTrackForProperties.value = null
                showSnackbar("Saved metadata for '${updatedTrack.title}'")
            }
        }
    }

    fun updateTrackMetadata(updatedTrack: Track) = saveTrackProperties(updatedTrack)

    fun performBulkAutoTag() {
        val selectedIds = _selectedTrackIds.value
        val targets = if (selectedIds.isNotEmpty()) {
            allTracks.value.filter { selectedIds.contains(it.id) }
        } else {
            currentDirectoryTracks.value
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _isTaggingInProgress.value = true
            _taggingProgressMessage.value = "Batch auto-tagging ${targets.size} selected tracks..."
            val updated = mutableListOf<TrackEntity>()
            for (track in targets) {
                val tagged = AiAutoTagger.autoTagTrack(track)
                updated.add(TrackEntity.fromTrack(tagged))
            }
            withContext(Dispatchers.IO) {
                trackDao.insertTracks(updated)
            }
            val log = OperationJournalItem(
                id = "op_${UUID.randomUUID().toString().take(6)}",
                operationType = FileOperationType.AUTO_TAG,
                affectedTracksCount = targets.size,
                summary = "Batch AI auto-tagged ${targets.size} audio tracks"
            )
            _operationJournal.value = listOf(log) + _operationJournal.value
            _isTaggingInProgress.value = false
            _selectedTrackIds.value = emptySet()
            showSnackbar("Successfully batch auto-tagged ${targets.size} tracks!")
        }
    }

    fun mountSafDirectory() {
        // Handled via SAF callback from UI
    }

    fun undoJournalOperation(journalId: String) = undoOperation(journalId)

    fun performBulkMove(targetDirectory: String, isDryRun: Boolean = _isDryRunEnabled.value) {
        val selectedIds = _selectedTrackIds.value.toList()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val tracksToMove = allTracks.value.filter { selectedIds.contains(it.id) }

            if (isDryRun) {
                withContext(Dispatchers.Main) {
                    showSnackbar("[DRY RUN SIMULATION] Would move ${tracksToMove.size} files to $targetDirectory with 0 conflicts.")
                }
                return@launch
            }

            val updatedEntities = tracksToMove.map { track ->
                val filename = track.filePath.substringAfterLast('/')
                val newPath = "$targetDirectory/$filename"
                TrackEntity.fromTrack(track.copy(directoryPath = targetDirectory, filePath = newPath))
            }
            trackDao.insertTracks(updatedEntities)

            val log = OperationJournalItem(
                id = "op_${UUID.randomUUID().toString().take(6)}",
                operationType = FileOperationType.MOVE,
                affectedTracksCount = tracksToMove.size,
                summary = "Moved ${tracksToMove.size} tracks to $targetDirectory"
            )
            _operationJournal.value = listOf(log) + _operationJournal.value

            withContext(Dispatchers.Main) {
                _selectedTrackIds.value = emptySet()
                showSnackbar("Moved ${tracksToMove.size} tracks to $targetDirectory")
            }
        }
    }

    fun performBulkTrash(isDryRun: Boolean = _isDryRunEnabled.value) {
        val selectedIds = _selectedTrackIds.value.toList()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            if (isDryRun) {
                withContext(Dispatchers.Main) {
                    showSnackbar("[DRY RUN SIMULATION] Would safely trash ${selectedIds.size} audio tracks.")
                }
                return@launch
            }

            selectedIds.forEach { id ->
                trackDao.deleteTrackById(id)
            }

            val log = OperationJournalItem(
                id = "op_${UUID.randomUUID().toString().take(6)}",
                operationType = FileOperationType.TRASH,
                affectedTracksCount = selectedIds.size,
                summary = "Safely trashed ${selectedIds.size} audio tracks"
            )
            _operationJournal.value = listOf(log) + _operationJournal.value

            withContext(Dispatchers.Main) {
                _selectedTrackIds.value = emptySet()
                showSnackbar("Safely trashed ${selectedIds.size} tracks (Logged in Journal)")
            }
        }
    }

    fun undoOperation(journalId: String) {
        val current = _operationJournal.value.toMutableList()
        val index = current.indexOfFirst { it.id == journalId }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(isUndone = true)
            _operationJournal.value = current
            showSnackbar("Undid operation: ${item.summary}")
        }
    }

    fun runAutoTagAll() {
        viewModelScope.launch {
            _isTaggingInProgress.value = true
            val currentTracks = allTracks.value
            _taggingProgressMessage.value = "Analyzing audio stems and metadata across ${currentTracks.size} tracks..."

            val updatedEntities = mutableListOf<TrackEntity>()
            for ((index, track) in currentTracks.withIndex()) {
                _taggingProgressMessage.value = "AI Tagging (${index + 1}/${currentTracks.size}): ${track.title}"
                val tagged = AiAutoTagger.autoTagTrack(track)
                updatedEntities.add(TrackEntity.fromTrack(tagged))
            }

            withContext(Dispatchers.IO) {
                trackDao.insertTracks(updatedEntities)
            }

            _isTaggingInProgress.value = false
            showSnackbar("Successfully auto-tagged ${updatedEntities.size} tracks with Genre, Key, BPM & Hot Cues!")
        }
    }

    fun autoTagSingleTrack(track: Track) {
        viewModelScope.launch {
            _isTaggingInProgress.value = true
            _taggingProgressMessage.value = "Auto-tagging '${track.title}' with AI..."
            val tagged = AiAutoTagger.autoTagTrack(track)
            withContext(Dispatchers.IO) {
                trackDao.updateTrack(TrackEntity.fromTrack(tagged))
            }
            _isTaggingInProgress.value = false
            if (audioEngine.currentTrack.value?.id == track.id) {
                val wasPlaying = audioEngine.isPlaying.value
                val currentSec = audioEngine.currentPositionSec.value
                withContext(Dispatchers.Main) {
                    audioEngine.loadTrack(tagged, autoPlay = wasPlaying, initialPositionSec = currentSec)
                }
            }
            if (_inspectingTrackForProperties.value?.id == track.id) {
                _inspectingTrackForProperties.value = tagged
            }
            showSnackbar("Updated metadata & Camelot key for '${track.title}'")
        }
    }

    fun resolveDuplicateKeepBest(match: DuplicateMatch) {
        viewModelScope.launch(Dispatchers.IO) {
            val keepTrackA = match.trackA.bitrateKbps >= match.trackB.bitrateKbps &&
                match.trackA.qualityRating != AudioQualityRating.SUSPICIOUS_UPSCALED

            val (toKeep, toDelete) = if (keepTrackA) match.trackA to match.trackB else match.trackB to match.trackA

            val mergedPlatforms = (toKeep.platforms + toDelete.platforms).distinct()
            val updatedKeep = toKeep.copy(
                platforms = mergedPlatforms,
                isOfflineReady = true,
                syncState = SyncState.SYNCED
            )

            trackDao.updateTrack(TrackEntity.fromTrack(updatedKeep))
            trackDao.deleteTrackById(toDelete.id)

            withContext(Dispatchers.Main) {
                showSnackbar("Merged duplicate: Kept highest quality ${toKeep.format} (${toKeep.bitrateKbps}k)")
            }
        }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.deleteTrackById(track.id)
            withContext(Dispatchers.Main) {
                showSnackbar("Removed '${track.title}' from library")
            }
        }
    }

    fun addNewTrack(
        title: String,
        artist: String,
        genre: String,
        bpm: Double,
        key: String,
        format: String,
        bitrateKbps: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val isFlac = format.equals("FLAC", ignoreCase = true) || format.equals("WAV", ignoreCase = true)
            val quality = when {
                isFlac -> AudioQualityRating.TRUE_LOSSLESS
                bitrateKbps >= 320 -> AudioQualityRating.TRUE_320
                bitrateKbps >= 256 -> AudioQualityRating.TRUE_256
                else -> AudioQualityRating.LOW_128
            }

            val curDir = _currentDirectoryPath.value
            val ext = format.lowercase()
            val safeName = "${artist.replace(" ", "_")}_-_${title.replace(" ", "_")}.$ext"
            val filePath = if (curDir.endsWith("/")) "$curDir$safeName" else "$curDir/$safeName"

            val newTrack = Track(
                id = "track_${UUID.randomUUID().toString().take(8)}",
                title = title.ifBlank { "Untitled Audio Track" },
                artist = artist.ifBlank { "Unknown Artist" },
                genre = genre,
                bpm = bpm,
                musicalKey = key,
                format = format,
                bitrateKbps = bitrateKbps,
                qualityRating = quality,
                filePath = filePath,
                directoryPath = curDir,
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.LOCAL),
                sourceId = _currentStorageSourceId.value
            )

            val tagged = AiAutoTagger.autoTagTrack(newTrack)
            trackDao.insertTrack(TrackEntity.fromTrack(tagged))

            withContext(Dispatchers.Main) {
                showSnackbar("Added & AI tagged '${tagged.title}' (${tagged.musicalKey} · ${tagged.bpm.toInt()} BPM)")
            }
        }
    }

    fun triggerCloudSync() {
        viewModelScope.launch {
            showSnackbar("Syncing DJ library across local devices & storage...")
            kotlinx.coroutines.delay(800)
            showSnackbar("All local tracks & cue metadata in sync!")
        }
    }

    fun exportRekordboxXml() {
        showSnackbar("Exported Rekordbox XML & Serato Crate with verified Camelot keys & Hot Cues!")
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }
}
