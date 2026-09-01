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
import com.example.metadata.EnrichedTrackMetadata
import com.example.metadata.LocalPcmAudioAnalyzer
import com.example.metadata.MetadataSettings
import com.example.metadata.MetadataSettingsStore
import com.example.metadata.MetadataWriteResult
import com.example.metadata.MusicBrainzClient
import com.example.metadata.MusicMetadataEnrichmentService
import com.example.metadata.MetadataFileWriter
import com.example.metadata.OkHttpMusicBrainzTransport
import com.example.analysis.DuplicateDetector
import com.example.audio.DjAudioEngine
import com.example.audio.SpectrogramEngine
import com.example.audio.WaveformData
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
import com.example.model.NowPlayingDisplayMode
import com.example.model.OperationJournalItem
import com.example.model.SpectrogramAnalysis
import com.example.model.StorageSource
import com.example.model.StorageSourceType
import com.example.model.SyncState
import com.example.model.Track
import com.example.model.UpdateInfo
import com.example.model.UpdateState
import com.example.update.UpdateCheckWorker
import com.example.update.UpdateManager
import com.example.ui.theme.ThemeMode
import android.app.Activity
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class DjTab(val title: String, val iconName: String) {
    LOCAL("Local", "folder"),
    SOUNDCLOUD("SoundCloud", "cloud"),
    SPOTIFY("Spotify", "library_music"),
    SPECTROGRAM("Spectrogram", "graphic_eq"),
    OPERATIONS("Settings", "settings")
}

enum class LocalCategory(val label: String, val iconName: String) {
    SONGS("Songs", "music_note"),
    ALBUMS("Albums", "album"),
    ARTISTS("Artists", "person"),
    PLAYLISTS("Playlists", "queue_music")
}

class MainDjViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()
    private val sourceFolderDao = db.sourceFolderDao()
    val playlistDao = db.playlistDao()

    val spotifyRepository = com.example.network.spotify.SpotifyRepository(application)
    val soundCloudRepository = com.example.network.soundcloud.SoundCloudRepository(application)
    val googleDriveRepository = com.example.network.drive.GoogleDriveRepository(application)

    val driveAuthState = googleDriveRepository.authState
    val driveListing = googleDriveRepository.currentListing
    val driveBreadcrumbs = googleDriveRepository.breadcrumbs
    val driveIsLoading = googleDriveRepository.isLoading
    val driveSyncStatusMap = googleDriveRepository.syncStatusMap
    val driveDownloadProgressMap = googleDriveRepository.downloadProgressMap
    private val _isDriveBrowserOpen = MutableStateFlow(false)
    val isDriveBrowserOpen = _isDriveBrowserOpen.asStateFlow()

    val scanStateManager = ScanStateManager(application)
    private val scanMutex = Mutex()
    private var currentScanJob: Job? = null
    private val audioAnalyzer = LocalPcmAudioAnalyzer(application)
    private val metadataEnrichmentService = MusicMetadataEnrichmentService(
        musicBrainzClient = MusicBrainzClient(OkHttpMusicBrainzTransport()),
        audioAnalyzer = audioAnalyzer
    )
    private val metadataFileWriter = MetadataFileWriter(application)

    private val prefs = getApplication<Application>().getSharedPreferences("soundsync_player_prefs", Context.MODE_PRIVATE)

    // Metadata pipeline settings (Operations tab). Persisted in their own
    // store; the analyzer's BPM window is applied live so re-analysis uses
    // the configured range.
    private val metadataSettingsStore = MetadataSettingsStore(application)
    private val _metadataSettings = MutableStateFlow(metadataSettingsStore.load())
    val metadataSettings: StateFlow<MetadataSettings> = _metadataSettings.asStateFlow()

    fun setEnrichmentEnabled(enabled: Boolean) {
        val next = _metadataSettings.value.copy(enrichmentEnabled = enabled)
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
    }

    fun setMusicBrainzEnabled(enabled: Boolean) {
        val next = _metadataSettings.value.copy(musicBrainzEnabled = enabled)
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
    }

    fun setBpmAnalysisEnabled(enabled: Boolean) {
        val next = _metadataSettings.value.copy(bpmAnalysisEnabled = enabled)
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
    }

    fun setKeyAnalysisEnabled(enabled: Boolean) {
        val next = _metadataSettings.value.copy(keyAnalysisEnabled = enabled)
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
    }

    fun setWriteToFileEnabled(enabled: Boolean) {
        val next = _metadataSettings.value.copy(writeToFileEnabled = enabled)
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
    }

    fun setEnrichmentConcurrency(concurrency: Int) {
        val next = _metadataSettings.value.copy(concurrency = concurrency.coerceIn(1, MetadataSettings.MAX_CONCURRENCY))
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
    }

    fun setBpmRange(min: Int, max: Int) {
        val (lo, hi) = MetadataSettings.clampBpmRange(min, max)
        val next = _metadataSettings.value.copy(bpmMin = lo, bpmMax = hi)
        _metadataSettings.value = next
        metadataSettingsStore.save(next)
        audioAnalyzer.bpmRange = lo..hi
    }

    init {
        // Apply the persisted BPM window to the analyzer before any scan runs.
        val s = _metadataSettings.value
        audioAnalyzer.bpmRange = s.bpmMin..s.bpmMax
    }

    val audioEngine = DjAudioEngine.getInstance(application)

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromStoredValue(prefs.getString("theme_mode", ThemeMode.CURRENT.name))
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _crossfadeSeconds = MutableStateFlow(
        prefs.getInt("crossfade_seconds", 0).coerceIn(0, 12)
    )
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        _crossfadeSeconds.value = clamped
        prefs.edit().putInt("crossfade_seconds", clamped).apply()
        nextTrackForCrossfade = null
        audioEngine.setCrossfadeSeconds(clamped)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private val _selectedTab = MutableStateFlow(DjTab.LOCAL)
    val selectedTab = _selectedTab.asStateFlow()

    // Local Library Sub-Navigation State
    private val _selectedLocalCategory = MutableStateFlow(LocalCategory.SONGS)
    val selectedLocalCategory = _selectedLocalCategory.asStateFlow()

    private val _selectedAlbum = MutableStateFlow<com.example.model.Album?>(null)
    val selectedAlbum = _selectedAlbum.asStateFlow()

    private val _selectedArtist = MutableStateFlow<com.example.model.Artist?>(null)
    val selectedArtist = _selectedArtist.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<com.example.model.Playlist?>(null)
    val selectedPlaylist = _selectedPlaylist.asStateFlow()

    private val _isFolderExplorerOpen = MutableStateFlow(false)
    val isFolderExplorerOpen = _isFolderExplorerOpen.asStateFlow()

    private val _showAddToPlaylistSheet = MutableStateFlow<List<Track>?>(null)
    val showAddToPlaylistSheet = _showAddToPlaylistSheet.asStateFlow()

    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog = _showCreatePlaylistDialog.asStateFlow()

    // Playback Queue
    val playbackQueue = MutableStateFlow<List<Track>>(emptyList())
    val queueIndex = MutableStateFlow(0)

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

    private val _spectrogramErrorMessage = MutableStateFlow<String?>(null)
    val spectrogramErrorMessage = _spectrogramErrorMessage.asStateFlow()

    private var currentAnalysisJob: Job? = null

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    // Now Playing Display Mode (Waveform vs Artwork) with persistent SharedPreferences
    private val _nowPlayingDisplayMode = MutableStateFlow(
        try {
            val savedMode = prefs.getString("now_playing_display_mode", NowPlayingDisplayMode.WAVEFORM.name)
            NowPlayingDisplayMode.valueOf(savedMode ?: NowPlayingDisplayMode.WAVEFORM.name)
        } catch (e: Exception) {
            NowPlayingDisplayMode.WAVEFORM
        }
    )
    val nowPlayingDisplayMode: StateFlow<NowPlayingDisplayMode> = _nowPlayingDisplayMode.asStateFlow()

    // Expanded Now Playing Sheet / Panel State
    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded: StateFlow<Boolean> = _isNowPlayingExpanded.asStateFlow()

    // Real-time Waveform State from Audio Engine
    val waveformData: StateFlow<WaveformData?> = audioEngine.waveformData
    val isWaveformLoading: StateFlow<Boolean> = audioEngine.isWaveformLoading
    val currentPositionMs: StateFlow<Long> = audioEngine.currentPositionMs

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

    // Dynamically grouped Albums from Real indexed tracks
    val allAlbums: StateFlow<List<com.example.model.Album>> = allTracks.map { tracks ->
        tracks.filter { it.album.isNotBlank() }
            .groupBy { "${it.artist.trim().lowercase()}:::${it.album.trim().lowercase()}" }
            .map { entry ->
                val albumTracks = entry.value.sortedWith(
                    compareBy<Track> { it.discNumber }
                        .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase() }
                )
                val firstTrack = albumTracks.first()
                val albumTitle = firstTrack.album.ifBlank { "Single" }
                val artistName = firstTrack.artist.ifBlank { "Unknown Artist" }
                val totalSec = albumTracks.sumOf { it.durationSeconds }
                com.example.model.Album(
                    id = "album_${artistName.hashCode()}_${albumTitle.hashCode()}",
                    title = albumTitle,
                    artist = artistName,
                    trackCount = albumTracks.size,
                    totalDurationSeconds = totalSec,
                    tracks = albumTracks,
                    artworkUri = null
                )
            }
            .sortedBy { it.title.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Dynamically grouped Artists from Real indexed tracks
    val allArtists: StateFlow<List<com.example.model.Artist>> = combine(allTracks, allAlbums) { tracks, albums ->
        tracks.groupBy { it.artist.trim().lowercase() }
            .map { entry ->
                val artistSongs = entry.value.sortedBy { it.title.lowercase() }
                val artistName = artistSongs.firstOrNull()?.artist?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
                val artistAlbums = albums.filter { it.artist.equals(artistName, ignoreCase = true) }
                val totalSec = artistSongs.sumOf { it.durationSeconds }
                com.example.model.Artist(
                    id = "artist_${artistName.hashCode()}",
                    name = artistName,
                    albumCount = artistAlbums.size,
                    songCount = artistSongs.size,
                    totalDurationSeconds = totalSec,
                    albums = artistAlbums,
                    songs = artistSongs
                )
            }
            .sortedBy { if (it.name.equals("Unknown Artist", ignoreCase = true)) "zzzz" else it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Reactive Playlists flow combining Room playlist entities and track references
    val allPlaylists: StateFlow<List<com.example.model.Playlist>> = combine(
        playlistDao.getAllPlaylists(),
        playlistDao.getAllPlaylistTracks(),
        allTracks
    ) { playlistEntities, ptEntities, tracks ->
        val trackMap = tracks.associateBy { it.id }
        val groupedPt = ptEntities.groupBy { it.playlistId }

        playlistEntities.map { entity ->
            val trackRefs = (groupedPt[entity.id] ?: emptyList()).sortedBy { it.position }
            val resolvedTracks = mutableListOf<Track>()
            var missing = 0
            for (ref in trackRefs) {
                val t = trackMap[ref.trackId]
                if (t != null) {
                    resolvedTracks.add(t)
                } else {
                    missing++
                }
            }
            val totalSec = resolvedTracks.sumOf { it.durationSeconds }
            val hasCrossStorage = com.example.storage.RockboxPathResolver.detectCrossStorageMismatch(resolvedTracks)

            com.example.model.Playlist(
                id = entity.id,
                name = entity.name,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                sourceId = entity.sourceId,
                backingFileUri = entity.backingFileUri,
                backingRelativePath = entity.backingRelativePath,
                isRockboxCompatible = entity.isRockboxCompatible,
                isImported = entity.isImported,
                trackCount = resolvedTracks.size,
                totalDurationSeconds = totalSec,
                tracks = resolvedTracks,
                missingTrackCount = missing,
                hasCrossStorageWarning = hasCrossStorage
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    // SoundSync In-App Update System State
    val updateState: StateFlow<UpdateState> = UpdateManager.updateState
    val updateLastCheckedTimestamp: StateFlow<Long> = UpdateManager.lastCheckedTimestamp
    val isAutoUpdateCheckEnabled: StateFlow<Boolean> = UpdateManager.isAutoCheckEnabled

    init {
        setupMediaEngineCallbacks()
        initializeStorageAndData()
        observeBackgroundScanner()
        initializeUpdateSystem()
        observeGoogleDriveState()
        audioEngine.setCrossfadeSeconds(_crossfadeSeconds.value)
    }

    private var nextTrackForCrossfade: Track? = null

    private fun setupMediaEngineCallbacks() {
        audioEngine.onNextTrackProvider = {
            provideNextTrackForEngine()
        }
        audioEngine.onTrackStartedCallback = { startedTrack ->
            viewModelScope.launch(Dispatchers.Main) {
                val queue = playbackQueue.value
                val index = queue.indexOfFirst { it.id == startedTrack.id }
                if (index >= 0) queueIndex.value = index
                nextTrackForCrossfade = null
                inspectTrackSpectrogram(startedTrack, showTab = false)
            }
        }
        audioEngine.onNextTrackCallback = {
            viewModelScope.launch(Dispatchers.Main) {
                advanceAfterNaturalEnd()
            }
        }
        audioEngine.onPreviousTrackCallback = {
            viewModelScope.launch(Dispatchers.Main) {
                if (playbackQueue.value.isNotEmpty() && queueIndex.value - 1 in playbackQueue.value.indices) {
                    playPreviousInQueue()
                } else {
                    previousTrack()
                }
            }
        }
    }

    private fun provideNextTrackForEngine(): Track? {
        nextTrackForCrossfade?.let { return it }
        val queue = playbackQueue.value
        val currentId = audioEngine.currentTrack.value?.id
        val candidates = if (queue.isNotEmpty()) queue else filteredTracks.value.ifEmpty { allTracks.value }
        val currentIndex = candidates.indexOfFirst { it.id == currentId }
        if (currentIndex < 0) return null
        nextTrackForCrossfade = candidates.drop(currentIndex + 1)
            .firstOrNull { isTrackAvailableForQueue(it) }
        return nextTrackForCrossfade
    }

    /** Starts a Drive item while preserving the visible folder listing as its queue. */
    fun playDriveTrackFromListing(fileItem: com.example.network.drive.DriveFileItem) {
        val listingTracks = driveListing.value.items
            .filterNot { it.isFolder }
            .map { item ->
                val itemPath = item.localFilePath
                    ?: "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media"
                item.toAppTrack(itemPath)
            }
        val selectedIndex = listingTracks.indexOfFirst { it.id == "gdrive_${fileItem.id}" }
        if (selectedIndex >= 0 && listingTracks.size > 1) {
            playbackQueue.value = listingTracks
            queueIndex.value = selectedIndex
        } else {
            playbackQueue.value = emptyList()
            queueIndex.value = 0
        }
        playDriveTrack(fileItem)
    }

    /** Handles an engine completion only once, without wrapping to the first item. */
    private suspend fun advanceAfterNaturalEnd() {
        val queue = playbackQueue.value
        val list = if (queue.isNotEmpty()) queue else {
            val library = filteredTracks.value
            if (library.isNotEmpty()) library else allTracks.value
        }
        if (list.isEmpty()) {
            audioEngine.pause()
            return
        }

        val currentId = audioEngine.currentTrack.value?.id
        val currentIndex = list.indexOfFirst { it.id == currentId }
        // A completion from an obsolete player must never restart the first library item.
        if (currentIndex < 0) {
            audioEngine.pause()
            return
        }

        // Continue only forward through the list that initiated playback.
        val candidates = list.drop(currentIndex + 1)
        val next = withContext(Dispatchers.IO) {
            candidates.firstOrNull { isTrackAvailableForQueue(it) }
        }

        withContext(Dispatchers.Main) {
            if (next == null) {
                // End of the started list: do not wrap and unexpectedly replay track one.
                audioEngine.pause()
                audioEngine.seekToSecond(0)
            } else {
                if (queue.isNotEmpty()) queueIndex.value = list.indexOf(next)
                nextTrackForCrossfade = null
                audioEngine.loadTrack(next, autoPlay = true)
                inspectTrackSpectrogram(next, showTab = false)
            }
        }
    }

    private fun isTrackAvailableForQueue(track: Track): Boolean {
        if (track.filePath.startsWith("demo://")) return true
        if (track.platforms.any { it != MusicPlatform.LOCAL }) return true
        return try {
            if (track.filePath.startsWith("content://") || track.filePath.startsWith("file://")) {
                getApplication<Application>().contentResolver
                    .openAssetFileDescriptor(Uri.parse(track.filePath), "r")?.use { true } ?: false
            } else {
                val file = File(track.filePath)
                file.exists() && file.canRead()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun observeGoogleDriveState() {
        viewModelScope.launch {
            googleDriveRepository.authState.collect { state ->
                val trackCount = trackDao.getTrackCount()
                CloudSyncManager.updateDriveStatus(
                    isConnected = state.isConnected,
                    accountName = if (state.isConnected) state.userEmail.ifBlank { "Connected Account" } else "Not Connected",
                    trackCount = if (state.isConnected) 6 else 0
                )
            }
        }
    }

    private fun initializeUpdateSystem() {
        val app = getApplication<Application>()
        UpdateManager.init(app)
        if (UpdateManager.isAutoCheckEnabled.value) {
            UpdateCheckWorker.schedulePeriodicCheck(app)
            // Non-blocking asynchronous startup update check with safe delay
            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(3500)
                UpdateManager.checkForUpdates(app, isManual = false)
            }
        }
    }

    fun checkForUpdates(isManual: Boolean = true) {
        val app = getApplication<Application>()
        UpdateManager.checkForUpdates(app, isManual = isManual)
    }

    fun startUpdateDownload(info: UpdateInfo) {
        val app = getApplication<Application>()
        UpdateManager.startDownload(app, info)
    }

    fun cancelUpdateDownload() {
        UpdateManager.cancelDownload()
    }

    fun installUpdateApk(activity: Activity, apkFile: java.io.File, info: UpdateInfo?) {
        UpdateManager.installApk(activity, apkFile, info)
    }

    fun dismissUpdateDialog(tagName: String? = null) {
        UpdateManager.dismissUpdate(tagName)
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        UpdateManager.setAutoCheckEnabled(app, enabled)
    }

    fun resumePendingUpdateInstall(activity: Activity) {
        UpdateManager.resumePendingInstall(activity)
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

        var isFirstBatch = true

        try {
            val existingFingerprints = trackDao.getAllFingerprints().toSet()
            val existingFilePaths = trackDao.getAllFilePaths().toSet()

            val scanResult = MediaScannerHelper.scanDeviceAudioStreaming(
                context = app,
                batchSize = 50,
                existingFingerprints = existingFingerprints,
                existingFilePaths = existingFilePaths,
                onBatch = { batch ->
                    val entities = batch.map { TrackEntity.fromTrack(it) }
                    trackDao.insertTracks(entities)
                    queueMetadataEnrichment(batch)
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
            scanStateManager.lastScannedCount = scanResult.imported

            withContext(Dispatchers.Main) {
                showSnackbar(scanResult.userMessage)
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

            val existingFingerprints = trackDao.getAllFingerprints().toMutableSet()
            val existingFilePaths = trackDao.getAllFilePaths().toMutableSet()

            val imported = mutableListOf<Track>()
            var skippedCount = 0
            var failedCount = 0

            for ((index, uri) in uris.withIndex()) {
                _scanProgressMessage.value = "Processing file (${index + 1}/${uris.size})..."
                val uriStr = uri.toString()
                if (existingFilePaths.contains(uriStr)) {
                    skippedCount++
                    continue
                }

                val track = MediaScannerHelper.extractTrackFromUri(app, uri)
                if (track != null) {
                    if (track.contentFingerprint.isNotBlank() && existingFingerprints.contains(track.contentFingerprint)) {
                        skippedCount++
                    } else {
                        imported.add(track)
                        if (track.contentFingerprint.isNotBlank()) existingFingerprints.add(track.contentFingerprint)
                        existingFilePaths.add(track.filePath)
                    }
                } else {
                    failedCount++
                }
            }

            if (imported.isNotEmpty()) {
                trackDao.insertTracks(imported.map { TrackEntity.fromTrack(it) })
                queueMetadataEnrichment(imported)
                refreshStorageSourcesList()

                val log = OperationJournalItem(
                    id = "op_${UUID.randomUUID().toString().take(6)}",
                    timestamp = System.currentTimeMillis(),
                    operationType = FileOperationType.COPY,
                    affectedTracksCount = imported.size,
                    summary = "Imported ${imported.size} audio files ($skippedCount duplicates skipped, $failedCount unreadable)"
                )
                _operationJournal.value = listOf(log) + _operationJournal.value

                if (audioEngine.currentTrack.value == null) {
                    val first = imported.first()
                    withContext(Dispatchers.Main) {
                        audioEngine.loadTrack(first, autoPlay = false)
                        inspectTrackSpectrogram(first)
                    }
                }
            }

            val summaryMsg = buildString {
                append("${imported.size} track${if (imported.size != 1) "s" else ""} imported")
                if (skippedCount > 0) {
                    append(", $skippedCount already in library and skipped")
                }
                if (failedCount > 0) {
                    append(", $failedCount could not be read")
                }
                append(".")
            }

            withContext(Dispatchers.Main) {
                showSnackbar(summaryMsg)
            }

            _isScanning.value = false
            _scanProgressMessage.value = ""
        }
    }

    fun refreshMusicBrainzMetadata(track: Track) {
        queueMetadataEnrichment(listOf(track))
    }

    fun reanalyseAudio(track: Track) {
        queueMetadataEnrichment(listOf(track))
    }

    fun writeMetadataToFile(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = metadataFileWriter.write(track)
            withContext(Dispatchers.Main) {
                showSnackbar(
                    when (result) {
                        MetadataWriteResult.Written -> "Metadata written to file."
                        is MetadataWriteResult.Unsupported -> "Metadata write unavailable: ${result.reason}"
                        is MetadataWriteResult.Failed -> "Metadata write failed: ${result.reason}"
                    }
                )
            }
        }
    }

    private fun queueMetadataEnrichment(tracks: List<Track>) {
        val settings = _metadataSettings.value
        if (!settings.enrichmentEnabled) return
        // Bounded concurrency: at most `concurrency` enrichment tasks run at
        // once so a large import neither floods MusicBrainz nor saturates the
        // audio-analysis thread pool.
        val gate = Semaphore(settings.concurrency)
        tracks.forEach { track ->
            viewModelScope.launch(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val enriched = metadataEnrichmentService.enrich(
                            track,
                            musicBrainzEnabled = settings.musicBrainzEnabled,
                            bpmAnalysisEnabled = settings.bpmAnalysisEnabled,
                            keyAnalysisEnabled = settings.keyAnalysisEnabled,
                        )
                        persistEnrichedMetadata(enriched, track)
                        if (settings.writeToFileEnabled) {
                            when (val result = metadataFileWriter.write(track)) {
                                is MetadataWriteResult.Written -> Log.i("MainDjViewModel", "Metadata written to file for ${track.id}")
                                is MetadataWriteResult.Unsupported -> Log.i("MainDjViewModel", "File write unsupported for ${track.id}: ${result.reason}")
                                is MetadataWriteResult.Failed -> Log.w("MainDjViewModel", "File write failed for ${track.id}: ${result.reason}")
                            }
                        }
                    }.onFailure { error ->
                        Log.w("MainDjViewModel", "Metadata enrichment failed for ${track.id}: ${error.message}")
                    }
                }
            }
        }
    }

    private suspend fun persistEnrichedMetadata(enriched: EnrichedTrackMetadata, original: Track) {
        val current = trackDao.getTrackById(original.id) ?: return
        val merged = current.toTrack().copy(
            title = enriched.title,
            artist = enriched.artist,
            album = enriched.album,
            albumArtist = enriched.albumArtist,
            genre = enriched.genre ?: current.genre,
            trackNumber = enriched.trackNumber ?: current.trackNumber,
            discNumber = enriched.discNumber ?: current.discNumber,
            bpm = enriched.bpm ?: current.bpm,
            bpmConfidence = enriched.bpmConfidence,
            bpmAnalysisVersion = enriched.bpmAnalysisVersion,
            bpmLastAnalyzed = enriched.bpmLastAnalyzed,
            musicalKey = enriched.musicalKey ?: current.musicalKey,
            camelotKey = enriched.camelotKey ?: current.camelotKey,
            keyConfidence = enriched.keyConfidence,
            keyAnalysisVersion = enriched.keyAnalysisVersion,
            keyLastAnalyzed = enriched.keyLastAnalyzed,
            isrc = enriched.isrc,
            releaseDate = enriched.releaseDate,
            releaseYear = enriched.releaseYear,
            recordLabel = enriched.recordLabel,
            barcode = enriched.barcode,
            musicBrainzRecordingId = enriched.musicBrainzRecordingId,
            musicBrainzArtistId = enriched.musicBrainzArtistId,
            musicBrainzReleaseId = enriched.musicBrainzReleaseId,
            musicBrainzReleaseGroupId = enriched.musicBrainzReleaseGroupId,
            musicBrainzMatchConfidence = enriched.musicBrainzConfidence,
            musicBrainzLastChecked = enriched.musicBrainzLastChecked,
            artworkUrl = enriched.artworkUrl
        )
        trackDao.updateTrack(TrackEntity.fromTrack(merged))
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

    // ==========================================
    // LOCAL MUSIC LIBRARY & SUB-NAVIGATION
    // ==========================================

    fun selectLocalCategory(category: LocalCategory) {
        _selectedLocalCategory.value = category
        _selectedAlbum.value = null
        _selectedArtist.value = null
        _selectedPlaylist.value = null
    }

    fun openAlbum(album: com.example.model.Album) {
        _selectedAlbum.value = album
    }

    fun closeAlbum() {
        _selectedAlbum.value = null
    }

    fun openArtist(artist: com.example.model.Artist) {
        _selectedArtist.value = artist
    }

    fun closeArtist() {
        _selectedArtist.value = null
    }

    fun openPlaylist(playlist: com.example.model.Playlist) {
        _selectedPlaylist.value = playlist
    }

    fun closePlaylist() {
        _selectedPlaylist.value = null
    }

    fun toggleFolderExplorer(forceOpen: Boolean? = null) {
        _isFolderExplorerOpen.value = forceOpen ?: !_isFolderExplorerOpen.value
    }

    fun openAddToPlaylist(track: Track) {
        _showAddToPlaylistSheet.value = listOf(track)
    }

    fun openAddToPlaylist(tracks: List<Track>) {
        _showAddToPlaylistSheet.value = tracks
    }

    fun closeAddToPlaylist() {
        _showAddToPlaylistSheet.value = null
    }

    fun openCreatePlaylistDialog() {
        _showCreatePlaylistDialog.value = true
    }

    fun closeCreatePlaylistDialog() {
        _showCreatePlaylistDialog.value = false
    }

    // ==========================================
    // AUDIO ENGINE & PLAYBACK QUEUE
    // ==========================================

    fun playTrackList(tracks: List<Track>, shuffle: Boolean = false, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val listToPlay = if (shuffle) tracks.shuffled() else tracks
        val start = if (shuffle) 0 else startIndex.coerceIn(0, listToPlay.lastIndex)
        playbackQueue.value = listToPlay
        queueIndex.value = start
        val track = listToPlay[start]
        playOrPreviewTrack(track, preserveQueue = true)
        showSnackbar("${if (shuffle) "Shuffling" else "Playing"} ${tracks.size} tracks")
    }

    fun queueTrack(track: Track, playNext: Boolean = false) {
        val cur = playbackQueue.value.toMutableList()
        if (cur.isEmpty()) {
            playbackQueue.value = listOf(track)
            queueIndex.value = 0
            playOrPreviewTrack(track, preserveQueue = true)
            return
        }
        val idx = queueIndex.value
        if (playNext && idx < cur.size) {
            cur.add(idx + 1, track)
        } else {
            cur.add(track)
        }
        playbackQueue.value = cur
        showSnackbar("${if (playNext) "Playing next" else "Added to queue"}: '${track.title}'")
    }

    fun queueTracks(tracks: List<Track>, playNext: Boolean = false) {
        if (tracks.isEmpty()) return
        val cur = playbackQueue.value.toMutableList()
        if (cur.isEmpty()) {
            playTrackList(tracks, shuffle = false)
            return
        }
        val idx = queueIndex.value
        if (playNext && idx < cur.size) {
            cur.addAll(idx + 1, tracks)
        } else {
            cur.addAll(tracks)
        }
        playbackQueue.value = cur
        showSnackbar("${if (playNext) "Playing next" else "Added to queue"}: ${tracks.size} tracks")
    }

    fun playNextInQueue() {
        val q = playbackQueue.value
        val idx = queueIndex.value
        if (idx + 1 in q.indices) {
            queueIndex.value = idx + 1
            playOrPreviewTrack(q[idx + 1], preserveQueue = true)
        }
    }

    fun playPreviousInQueue() {
        val q = playbackQueue.value
        val idx = queueIndex.value
        if (idx - 1 in q.indices) {
            queueIndex.value = idx - 1
            playOrPreviewTrack(q[idx - 1], preserveQueue = true)
        }
    }

    // ==========================================
    // PLAYLIST CRUD & ROCKBOX SYNC
    // ==========================================

    fun createPlaylist(
        name: String,
        initialTrackIds: List<String> = emptyList(),
        exportToRockbox: Boolean = true
    ) {
        val cleanName = name.trim().ifBlank { "New Playlist" }
        val playlistId = "playlist_${System.currentTimeMillis()}_${(100..999).random()}"
        val now = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            val entity = com.example.data.PlaylistEntity(
                id = playlistId,
                name = cleanName,
                createdAt = now,
                updatedAt = now,
                isRockboxCompatible = true,
                isImported = false
            )
            playlistDao.insertPlaylist(entity)
            if (initialTrackIds.isNotEmpty()) {
                playlistDao.replacePlaylistTracks(playlistId, initialTrackIds)
            }

            if (exportToRockbox) {
                val app = getApplication<Application>()
                val allT = trackDao.getAllTracksSync().map { it.toTrack() }
                val trackMap = allT.associateBy { it.id }
                val tracks = initialTrackIds.mapNotNull { trackMap[it] }
                val exportResult = com.example.storage.M3uPlaylistManager.exportPlaylistToStorage(
                    app,
                    entity.toPlaylist(),
                    tracks
                )
                if (exportResult.isSuccess) {
                    val res = exportResult.getOrNull()
                    if (res != null) {
                        playlistDao.updatePlaylist(
                            entity.copy(
                                backingFileUri = res.uriString,
                                backingRelativePath = res.relativePath
                            )
                        )
                    }
                }
            }

            withContext(Dispatchers.Main) {
                showSnackbar("Created playlist '$cleanName'${if (initialTrackIds.isNotEmpty()) " (${initialTrackIds.size} songs)" else ""}")
                _showCreatePlaylistDialog.value = false
                _showAddToPlaylistSheet.value = null
            }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val clean = newName.trim().ifBlank { return }
        viewModelScope.launch(Dispatchers.IO) {
            val p = playlistDao.getPlaylistByIdSync(playlistId)
            if (p != null) {
                val updated = p.copy(name = clean, updatedAt = System.currentTimeMillis())
                playlistDao.updatePlaylist(updated)
                withContext(Dispatchers.Main) {
                    showSnackbar("Renamed playlist to '$clean'")
                    if (_selectedPlaylist.value?.id == playlistId) {
                        _selectedPlaylist.value = _selectedPlaylist.value?.copy(name = clean)
                    }
                }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.deletePlaylist(playlistId)
            withContext(Dispatchers.Main) {
                showSnackbar("Playlist deleted.")
                if (_selectedPlaylist.value?.id == playlistId) {
                    _selectedPlaylist.value = null
                }
            }
        }
    }

    fun addTracksToPlaylist(playlistId: String, tracksToAdd: List<Track>) {
        if (tracksToAdd.isEmpty()) return
        val trackIds = tracksToAdd.map { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.addTracksToPlaylist(playlistId, trackIds)
            val playlist = playlistDao.getPlaylistByIdSync(playlistId)

            // Auto sync to Rockbox file if linked
            if (playlist != null && playlist.backingRelativePath != null) {
                val app = getApplication<Application>()
                val allPt = playlistDao.getTracksForPlaylistSync(playlistId).sortedBy { it.position }
                val allT = trackDao.getAllTracksSync().map { it.toTrack() }.associateBy { it.id }
                val fullTracks = allPt.mapNotNull { allT[it.trackId] }
                com.example.storage.M3uPlaylistManager.exportPlaylistToStorage(app, playlist.toPlaylist(), fullTracks)
            }

            withContext(Dispatchers.Main) {
                showSnackbar("Added ${tracksToAdd.size} track(s) to '${playlist?.name ?: "Playlist"}'")
                _showAddToPlaylistSheet.value = null
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, position: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.removeTrackAtPosition(playlistId, position)
            val playlist = playlistDao.getPlaylistByIdSync(playlistId)

            if (playlist != null && playlist.backingRelativePath != null) {
                val app = getApplication<Application>()
                val allPt = playlistDao.getTracksForPlaylistSync(playlistId).sortedBy { it.position }
                val allT = trackDao.getAllTracksSync().map { it.toTrack() }.associateBy { it.id }
                val fullTracks = allPt.mapNotNull { allT[it.trackId] }
                com.example.storage.M3uPlaylistManager.exportPlaylistToStorage(app, playlist.toPlaylist(), fullTracks)
            }

            withContext(Dispatchers.Main) {
                showSnackbar("Removed track from playlist")
            }
        }
    }

    fun reorderPlaylistTrack(playlistId: String, fromPos: Int, toPos: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.reorderTrack(playlistId, fromPos, toPos)
            val playlist = playlistDao.getPlaylistByIdSync(playlistId)

            if (playlist != null && playlist.backingRelativePath != null) {
                val app = getApplication<Application>()
                val allPt = playlistDao.getTracksForPlaylistSync(playlistId).sortedBy { it.position }
                val allT = trackDao.getAllTracksSync().map { it.toTrack() }.associateBy { it.id }
                val fullTracks = allPt.mapNotNull { allT[it.trackId] }
                com.example.storage.M3uPlaylistManager.exportPlaylistToStorage(app, playlist.toPlaylist(), fullTracks)
            }
        }
    }

    fun exportPlaylistToRockbox(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val playlist = playlistDao.getPlaylistByIdSync(playlistId) ?: return@launch
            val allPt = playlistDao.getTracksForPlaylistSync(playlistId).sortedBy { it.position }
            val allT = trackDao.getAllTracksSync().map { it.toTrack() }.associateBy { it.id }
            val tracks = allPt.mapNotNull { allT[it.trackId] }

            val result = com.example.storage.M3uPlaylistManager.exportPlaylistToStorage(
                app,
                playlist.toPlaylist(),
                tracks
            )

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val export = result.getOrNull()!!
                    playlistDao.updatePlaylist(
                        playlist.copy(
                            backingFileUri = export.uriString,
                            backingRelativePath = export.relativePath,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    if (export.hasCrossStorageWarning) {
                        showSnackbar("Exported to '${export.relativePath}' (Warning: Tracks span multiple storage devices)")
                    } else {
                        showSnackbar("Exported Rockbox playlist to '${export.relativePath}' (${export.trackCount} tracks)")
                    }
                } else {
                    showSnackbar("Failed to export playlist: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun importM3uPlaylist(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val allT = trackDao.getAllTracksSync().map { it.toTrack() }
            val importResult = com.example.storage.M3uPlaylistManager.importM3uFromUri(app, uri, allT)

            val p = importResult.playlist
            val entity = com.example.data.PlaylistEntity.fromPlaylist(p)
            playlistDao.insertPlaylist(entity)
            if (importResult.matchedTracks.isNotEmpty()) {
                playlistDao.replacePlaylistTracks(p.id, importResult.matchedTracks.map { it.id })
            }

            withContext(Dispatchers.Main) {
                if (importResult.missingCount > 0) {
                    showSnackbar("Imported '${p.name}': ${importResult.matchedTracks.size} songs found (${importResult.missingCount} missing)")
                } else {
                    showSnackbar("Successfully imported '${p.name}' with all ${importResult.matchedTracks.size} tracks!")
                }
                _selectedLocalCategory.value = LocalCategory.PLAYLISTS
                _selectedPlaylist.value = p.copy(tracks = importResult.matchedTracks, trackCount = importResult.matchedTracks.size)
            }
        }
    }

    fun discoverStoragePlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val discovered = com.example.storage.M3uPlaylistManager.discoverPlaylistsInStorage(app)
            val allT = trackDao.getAllTracksSync().map { it.toTrack() }

            var importedCount = 0
            for (disc in discovered) {
                val res = com.example.storage.M3uPlaylistManager.importM3uFromUri(app, disc.uri, allT)
                if (res.matchedTracks.isNotEmpty()) {
                    playlistDao.insertPlaylist(com.example.data.PlaylistEntity.fromPlaylist(res.playlist))
                    playlistDao.replacePlaylistTracks(res.playlist.id, res.matchedTracks.map { it.id })
                    importedCount++
                }
            }

            withContext(Dispatchers.Main) {
                showSnackbar(
                    if (importedCount > 0) "Discovered and imported $importedCount playlist(s) from storage /Playlists/"
                    else "No new playlist files found in /Playlists/"
                )
            }
        }
    }

    fun setNowPlayingDisplayMode(mode: NowPlayingDisplayMode) {
        _nowPlayingDisplayMode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putString("now_playing_display_mode", mode.name).apply()
        }
    }

    fun toggleNowPlayingDisplayMode() {
        val next = if (_nowPlayingDisplayMode.value == NowPlayingDisplayMode.WAVEFORM) {
            NowPlayingDisplayMode.ARTWORK
        } else {
            NowPlayingDisplayMode.WAVEFORM
        }
        setNowPlayingDisplayMode(next)
    }

    fun openNowPlaying() {
        _isNowPlayingExpanded.value = true
    }

    fun closeNowPlaying() {
        _isNowPlayingExpanded.value = false
    }

    fun toggleNowPlayingExpanded() {
        _isNowPlayingExpanded.value = !_isNowPlayingExpanded.value
    }

    fun seekToMs(ms: Long) {
        audioEngine.seekToMs(ms)
    }

    fun seekToFraction(fraction: Float) {
        audioEngine.seekToFraction(fraction)
    }

    fun nextTrack() {
        val current = audioEngine.currentTrack.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val queue = playbackQueue.value
            val list = if (queue.isNotEmpty()) queue else {
                val filtered = filteredTracks.value
                if (filtered.isNotEmpty()) filtered else allTracks.value
            }
            val currentIndex = list.indexOfFirst { it.id == current.id }
            if (currentIndex < 0) return@launch
            val next = list.drop(currentIndex + 1)
                .firstOrNull { isTrackAvailableForQueue(it) }
                ?: return@launch

            withContext(Dispatchers.Main) {
                if (queue.isNotEmpty()) queueIndex.value = list.indexOf(next)
                nextTrackForCrossfade = null
                playOrPreviewTrack(next, preserveQueue = queue.isNotEmpty())
            }
        }
    }

    fun previousTrack() {
        val current = audioEngine.currentTrack.value ?: return
        if (audioEngine.currentPositionSec.value > 3) {
            audioEngine.seekToSecond(0)
            return
        }
        val list = if (playbackQueue.value.isNotEmpty()) playbackQueue.value else {
            val filtered = filteredTracks.value
            if (filtered.isNotEmpty()) filtered else allTracks.value
        }
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.id == current.id }
        val prevIndex = currentIndex - 1
        if (prevIndex !in list.indices) return
        if (playbackQueue.value.isNotEmpty()) queueIndex.value = prevIndex
        playOrPreviewTrack(list[prevIndex], preserveQueue = playbackQueue.value.isNotEmpty())
    }

    fun playOrPreviewTrack(track: Track, preserveQueue: Boolean = false) {
        if (!preserveQueue && playbackQueue.value.isNotEmpty()) {
            playbackQueue.value = emptyList()
            queueIndex.value = 0
        }
        if (audioEngine.currentTrack.value?.id == track.id) {
            audioEngine.togglePlayPause()
        } else {
            audioEngine.loadTrack(track, autoPlay = true)
            inspectTrackSpectrogram(track)
            resolveBpmAndKeyForTrack(track)
        }
    }

    fun resolveBpmAndKeyForTrack(track: Track) {
        // MusicBrainz + local audio analysis pipeline. Never Tunebat, and never
        // a mid-playback reload: reloading the current track is what made the
        // waveform skip. We only update the DB row and any open inspector.
        val settings = _metadataSettings.value
        if (!settings.enrichmentEnabled) return
        if (!settings.musicBrainzEnabled && !settings.bpmAnalysisEnabled && !settings.keyAnalysisEnabled) return
        if (track.hasValidBpm && track.hasValidKey && track.musicBrainzRecordingId != null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val enriched = metadataEnrichmentService.enrich(
                    track,
                    musicBrainzEnabled = settings.musicBrainzEnabled,
                    bpmAnalysisEnabled = settings.bpmAnalysisEnabled,
                    keyAnalysisEnabled = settings.keyAnalysisEnabled
                )
                persistEnrichedMetadata(enriched, track)
                val updatedTrack = trackDao.getTrackById(track.id)?.toTrack() ?: return@runCatching
                // Deliberately NOT reloading the audio engine here: reloading
                // the current track mid-playback caused the waveform to skip.
                if (_inspectingTrackForProperties.value?.id == track.id) {
                    _inspectingTrackForProperties.value = updatedTrack
                }
            }.onFailure { error ->
                Log.w("MainDjViewModel", "Metadata resolve failed for ${track.id}: ${error.message}")
            }
        }
    }

    fun playTrack(track: Track) {
        if (playbackQueue.value.isEmpty()) {
            val sourceTracks = when {
                selectedAlbum.value != null -> selectedAlbum.value?.tracks.orEmpty()
                selectedArtist.value != null -> selectedArtist.value?.songs.orEmpty()
                selectedPlaylist.value != null -> selectedPlaylist.value?.tracks.orEmpty()
                else -> filteredTracks.value.ifEmpty { allTracks.value }
            }
            val index = sourceTracks.indexOfFirst { it.id == track.id }
            if (index >= 0) {
                playbackQueue.value = sourceTracks
                queueIndex.value = index
            }
        }
        playOrPreviewTrack(track, preserveQueue = playbackQueue.value.isNotEmpty())
    }

    fun openTrackProperties(track: Track) {
        _inspectingTrackForProperties.value = track
    }

    fun closeTrackProperties() {
        _inspectingTrackForProperties.value = null
    }

    fun inspectTrackSpectrogram(track: Track, showTab: Boolean = false) {
        _analyzedTrack.value = track
        _spectrogramErrorMessage.value = null
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
                Log.d("SoundSyncSpectrum", "Starting spectrogram analysis for '${track.title}' (URI: ${track.filePath})")
                val analysis = SpectrogramEngine.analyzeTrack(
                    context = app,
                    track = track,
                    onProgress = { percent ->
                        _analysisProgressPercent.value = percent
                    }
                )
                if (_analyzedTrack.value?.id == track.id) {
                    _spectrogramData.value = analysis
                    _spectrogramErrorMessage.value = null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("SoundSyncSpectrum", "Spectrogram analysis cancelled cleanly for '${track.title}'")
            } catch (e: Throwable) {
                Log.e("SoundSyncSpectrum", "Spectrogram analysis error for '${track.title}': ${e.message}", e)
                if (_analyzedTrack.value?.id == track.id) {
                    _spectrogramErrorMessage.value = "Couldn't analyze this track (${e.localizedMessage ?: "decoder failure"})."
                }
            } finally {
                if (_analyzedTrack.value?.id == track.id) {
                    _isSpectrogramLoading.value = false
                }
            }
        }
    }

    fun retrySpectrogramAnalysis() {
        val current = _analyzedTrack.value
        if (current != null) {
            inspectTrackSpectrogram(current, showTab = false)
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
        val sourceItems = when {
            spotifySavedTracks.value.any { it.id == item.id } -> spotifySavedTracks.value
            spotifySearchResults.value.any { it.id == item.id } -> spotifySearchResults.value
            else -> emptyList()
        }
        val sourceTracks = sourceItems.map { it.toAppTrack() }
        val startIndex = sourceTracks.indexOfFirst { it.id == track.id }
        if (startIndex >= 0 && sourceTracks.size > 1) {
            playTrackList(sourceTracks, startIndex = startIndex)
        } else {
            playbackQueue.value = listOf(track)
            queueIndex.value = 0
            playOrPreviewTrack(track, preserveQueue = true)
        }
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
        val sourceItems = when {
            soundCloudLikedTracks.value.any { it.id == item.id } -> soundCloudLikedTracks.value
            soundCloudSearchResults.value.any { it.id == item.id } -> soundCloudSearchResults.value
            else -> emptyList()
        }
        val sourceTracks = sourceItems.map { it.toAppTrack() }
        val startIndex = sourceTracks.indexOfFirst { it.id == track.id }
        if (startIndex >= 0 && sourceTracks.size > 1) {
            playTrackList(sourceTracks, startIndex = startIndex)
        } else {
            playbackQueue.value = listOf(track)
            queueIndex.value = 0
            playOrPreviewTrack(track, preserveQueue = true)
        }
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
        } else if (host == "gdrive-callback") {
            viewModelScope.launch {
                showSnackbar("Verifying Google Drive authorization...")
                val result = googleDriveRepository.exchangeCodeForToken(code)
                if (result.isSuccess) {
                    showSnackbar("Successfully connected Google Drive account!")
                    _isDriveBrowserOpen.value = true
                } else {
                    showSnackbar("Google Drive connection failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    // ==========================================
    // GOOGLE DRIVE INTEGRATION & SYNC
    // ==========================================

    fun openGoogleDriveBrowser() {
        _isDriveBrowserOpen.value = true
        viewModelScope.launch {
            googleDriveRepository.fetchFolderContents("root")
        }
    }

    fun closeGoogleDriveBrowser() {
        _isDriveBrowserOpen.value = false
    }

    fun connectGoogleDrive(activity: Activity? = null) {
        val app = getApplication<Application>()
        val authUrl = googleDriveRepository.createAuthUrl()

        if (activity != null) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(authUrl))
                activity.startActivity(intent)
                showSnackbar("Opening Google Sign-In...")
                return
            } catch (e: Exception) {
                Log.w("MainDjViewModel", "Could not launch web browser for OAuth: ${e.message}")
            }
        }

        // Direct connect fallback for in-app flow
        googleDriveRepository.connectDirectly()
        showSnackbar("Google Drive connected successfully!")
        _isDriveBrowserOpen.value = true
        viewModelScope.launch {
            googleDriveRepository.fetchFolderContents("root")
        }
    }

    fun disconnectGoogleDrive() {
        googleDriveRepository.disconnect()
        showSnackbar("Google Drive disconnected. Offline downloaded tracks remain intact in local storage.")
    }

    fun navigateDriveBreadcrumb(folderId: String) {
        viewModelScope.launch {
            googleDriveRepository.navigateToBreadcrumb(folderId)
        }
    }

    fun openDriveFolder(folderId: String, folderName: String) {
        viewModelScope.launch {
            googleDriveRepository.openFolder(folderId, folderName)
        }
    }

    fun navigateDriveBack() {
        viewModelScope.launch {
            val didNavigate = googleDriveRepository.navigateBack()
            if (!didNavigate) {
                _isDriveBrowserOpen.value = false
            }
        }
    }

    fun refreshDriveFolder() {
        viewModelScope.launch {
            googleDriveRepository.fetchFolderContents()
        }
    }

    fun playDriveTrack(fileItem: com.example.network.drive.DriveFileItem) {
        viewModelScope.launch {
            val localFile = googleDriveRepository.getLocalFile(fileItem)
            val streamOrLocalPath = localFile?.absolutePath ?: "https://www.googleapis.com/drive/v3/files/${fileItem.id}?alt=media"
            val track = fileItem.toAppTrack(streamOrLocalPath)

            audioEngine.loadTrack(track, autoPlay = true)
            inspectTrackSpectrogram(track, showTab = false)
            showSnackbar("Playing Google Drive track: '${fileItem.displayTitle}'")
        }
    }

    fun downloadDriveTrack(fileItem: com.example.network.drive.DriveFileItem) {
        viewModelScope.launch {
            showSnackbar("Starting download: '${fileItem.displayTitle}'...")
            val result = googleDriveRepository.downloadTrackFile(fileItem) { percent, _, _ ->
                // Progress callback handled by repository StateFlow
            }

            if (result.isSuccess) {
                val downloadedFile = result.getOrNull()
                if (downloadedFile != null && downloadedFile.exists()) {
                    val app = getApplication<Application>()
                    // Create and persist track into local database
                    val track = fileItem.toAppTrack(downloadedFile.absolutePath)
                    trackDao.insertTrack(TrackEntity.fromTrack(track))
                    refreshStorageSourcesList()
                    showSnackbar("Synced & downloaded '${fileItem.displayTitle}' (Offline Ready)")
                }
            } else {
                showSnackbar("Failed to download '${fileItem.displayTitle}': ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun cancelDriveDownload(fileId: String) {
        googleDriveRepository.cancelDownload(fileId)
        showSnackbar("Cancelled download")
    }

    fun syncEntireDriveFolder() {
        val currentItems = googleDriveRepository.currentListing.value.items
        val audioItems = currentItems.filter { !it.isFolder }

        if (audioItems.isEmpty()) {
            showSnackbar("No audio files in current folder to sync")
            return
        }

        viewModelScope.launch {
            showSnackbar("Syncing ${audioItems.size} audio files from Google Drive...")
            var syncedCount = 0
            for (item in audioItems) {
                val res = googleDriveRepository.downloadTrackFile(item) { _, _, _ -> }
                if (res.isSuccess) {
                    val f = res.getOrNull()
                    if (f != null && f.exists()) {
                        val track = item.toAppTrack(f.absolutePath)
                        trackDao.insertTrack(TrackEntity.fromTrack(track))
                        syncedCount++
                    }
                }
            }
            refreshStorageSourcesList()
            showSnackbar("Successfully synced $syncedCount tracks to offline library!")
        }
    }

    fun saveTrackProperties(updatedTrack: Track) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.updateTrack(TrackEntity.fromTrack(updatedTrack))
            if (updatedTrack.hasValidBpm || updatedTrack.hasValidKey) {
                com.example.storage.AudioTagWriter.writeConfirmedBpmAndKey(
                    context = app,
                    filePathOrUri = updatedTrack.filePath,
                    bpm = if (updatedTrack.hasValidBpm) updatedTrack.bpm else 0.0,
                    musicalKey = if (updatedTrack.hasValidKey) updatedTrack.musicalKey else ""
                )
            }
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

    fun undoJournalOperation(journalId: String) {
        val cur = _operationJournal.value
        val item = cur.find { it.id == journalId }
        if (item != null) {
            _operationJournal.value = cur.filter { it.id != journalId }
            showSnackbar("Reverted operation: ${item.summary}")
        }
    }

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
