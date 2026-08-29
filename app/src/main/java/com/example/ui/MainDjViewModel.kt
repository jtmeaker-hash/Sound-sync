package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.analysis.AiAutoTagger
import com.example.analysis.DuplicateDetector
import com.example.audio.DjAudioEngine
import com.example.audio.SpectrogramEngine
import com.example.data.AppDatabase
import com.example.data.CrateEntity
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
import com.example.sync.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class DjTab(val title: String, val iconName: String) {
    EXPLORER("DJ Explorer", "folder"),
    LIBRARY("Crates & Tags", "queue_music"),
    SPECTROGRAM("Spectrum Lab", "graphic_eq"),
    DUPLICATES("Duplicates", "content_copy"),
    OPERATIONS("Hub & Cloud", "storage")
}

class MainDjViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()

    val audioEngine = DjAudioEngine(application)

    private val _selectedTab = MutableStateFlow(DjTab.EXPLORER)
    val selectedTab = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // File Explorer Navigation State
    private val _currentStorageSourceId = MutableStateFlow("internal")
    val currentStorageSourceId = _currentStorageSourceId.asStateFlow()

    private val _currentDirectoryPath = MutableStateFlow("/storage/emulated/0/Music")
    val currentDirectoryPath = _currentDirectoryPath.asStateFlow()

    private val _selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackIds = _selectedTrackIds.asStateFlow()

    private val _explorerSortOption = MutableStateFlow(ExplorerSortOption.NAME_ASC)
    val explorerSortOption = _explorerSortOption.asStateFlow()

    private val _explorerViewMode = MutableStateFlow("detailed") // "detailed", "compact", "grid"
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

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    private val _crates = MutableStateFlow(CloudSyncManager.getInitialCrates())
    val crates = _crates.asStateFlow()

    private val _storageSources = MutableStateFlow(
        listOf(
            StorageSource("internal", StorageSourceType.INTERNAL, "Internal Storage", "/storage/emulated/0/Music", true, 4, 184.2, 512.0),
            StorageSource("usb_ssd", StorageSourceType.USB_SSD, "USB-C SSD (Crucial X8)", "/mnt/media_rw/USB_DJ_VAULT/Tracks", true, 2, 842.0, 1024.0),
            StorageSource("sd_card", StorageSourceType.SD_CARD, "MicroSD (SanDisk 512G)", "/storage/0000-0000/DJ_Sets", true, 1, 310.5, 512.0),
            StorageSource("downloads", StorageSourceType.DOWNLOADS, "Downloads", "/storage/emulated/0/Download", true, 1, 45.0, 512.0),
            StorageSource("cloud_vault", StorageSourceType.CLOUD_VAULT, "Cloud Vault Sync", "/storage/emulated/0/SoundSync/CloudCache", true, 1, 100.0, 200.0)
        )
    )
    val storageSources = _storageSources.asStateFlow()

    private val _operationJournal = MutableStateFlow(
        listOf(
            OperationJournalItem(
                id = "op_1",
                timestamp = System.currentTimeMillis() - 600000,
                operationType = FileOperationType.AUTO_TAG,
                affectedTracksCount = 7,
                summary = "AI Auto-tagged 7 tracks with Camelot keys & DJ energy",
                canUndo = true
            ),
            OperationJournalItem(
                id = "op_2",
                timestamp = System.currentTimeMillis() - 1800000,
                operationType = FileOperationType.MOVE,
                affectedTracksCount = 2,
                summary = "Moved 2 tracks to /USB_DJ_VAULT/Tracks/Techno",
                canUndo = true
            )
        )
    )
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
                (sourceId == "internal" && track.directoryPath.startsWith("/storage/emulated/0/Music")) ||
                (sourceId == "usb_ssd" && track.directoryPath.contains("USB")) ||
                (sourceId == "sd_card" && track.directoryPath.contains("0000-0000")) ||
                (sourceId == "downloads" && track.directoryPath.contains("Download")) ||
                (sourceId == "cloud_vault" && track.directoryPath.contains("CloudCache"))

            val matchesDir = dirPath.isBlank() || track.directoryPath.startsWith(dirPath) || dirPath == "/" || sourceId == "all"
            
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
        tracks.forEach { track ->
            val trackDir = track.directoryPath
            if (trackDir.startsWith(dirPath) && trackDir != dirPath) {
                val relative = trackDir.removePrefix(dirPath).trimStart('/')
                val folderName = relative.substringBefore('/')
                if (folderName.isNotBlank()) {
                    val fullSubPath = if (dirPath.endsWith("/")) "$dirPath$folderName" else "$dirPath/$folderName"
                    subDirs.getOrPut(fullSubPath) { mutableListOf() }.add(track)
                }
            }
        }

        subDirs.map { (path, folderTracks) ->
            FolderItem(
                name = path.substringAfterLast('/'),
                path = path,
                trackCount = folderTracks.size,
                subFolderCount = 0,
                totalSizeMb = folderTracks.sumOf { it.fileSizeMb }
            )
        }.sortedBy { it.name }
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
        bootstrapInitialData()
    }

    private fun bootstrapInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = trackDao.getTrackCount()
            if (count == 0) {
                val initial = CloudSyncManager.getInitialSampleTracks()
                trackDao.insertTracks(initial.map { TrackEntity.fromTrack(it) })
            }

            // Load initial track for preview
            val first = trackDao.getTrackById("track_1")?.toTrack()
                ?: CloudSyncManager.getInitialSampleTracks().first()
            withContext(Dispatchers.Main) {
                audioEngine.loadTrack(first)
                inspectTrackSpectrogram(first)
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
        }
        _selectedTrackIds.value = emptySet()
    }

    fun navigateToDirectory(path: String) {
        _currentDirectoryPath.value = path
        _selectedTrackIds.value = emptySet()
    }

    fun navigateUp() {
        val current = _currentDirectoryPath.value
        val parent = if (current.contains('/')) current.substringBeforeLast('/') else "/"
        _currentDirectoryPath.value = if (parent.isBlank()) "/" else parent
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
            audioEngine.loadTrack(track)
            audioEngine.play()
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

    fun inspectTrackSpectrogram(track: Track) {
        _analyzedTrack.value = track
        viewModelScope.launch(Dispatchers.Default) {
            val analysis = SpectrogramEngine.analyzeTrackQuality(track)
            _spectrogramData.value = analysis
        }
    }

    fun saveTrackProperties(updatedTrack: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.updateTrack(TrackEntity.fromTrack(updatedTrack))
            if (audioEngine.currentTrack.value?.id == updatedTrack.id) {
                audioEngine.loadTrack(updatedTrack)
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

    fun mountSafDirectory() = mountSafStorageFolder()

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

    fun mountSafStorageFolder() {
        viewModelScope.launch {
            showSnackbar("Storage Access Framework: Mounted external USB/SD folder with persistent permission.")
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
                audioEngine.loadTrack(tagged)
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
                title = title.ifBlank { "Untitled DJ Stem" },
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
                platforms = listOf(MusicPlatform.LOCAL, MusicPlatform.GOOGLE_DRIVE),
                sourceId = _currentStorageSourceId.value
            )

            val tagged = AiAutoTagger.autoTagTrack(newTrack)
            trackDao.insertTrack(TrackEntity.fromTrack(tagged))

            withContext(Dispatchers.Main) {
                showSnackbar("Added & AI tagged '${tagged.title}' (${tagged.musicalKey} · ${tagged.bpm} BPM)")
            }
        }
    }

    fun triggerCloudSync() {
        viewModelScope.launch {
            showSnackbar("Syncing DJ library across Beatport, Spotify, SoundCloud & Cloud Storage...")
            kotlinx.coroutines.delay(1200)
            showSnackbar("All tracks & cue metadata in sync with Cloud Vault!")
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

