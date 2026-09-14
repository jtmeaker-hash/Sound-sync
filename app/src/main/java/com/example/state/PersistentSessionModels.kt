package com.example.state

import com.example.carmode.CarDisplayMode
import com.example.model.ExplorerSortOption
import com.example.model.Track
import com.example.model.WaveformStyle
import com.example.player.QueueRepeatMode
import com.example.player.SmartContinueMode
import com.example.storage.ScanStatus
import com.example.ui.theme.ProDarkVariant
import com.example.ui.theme.ProLibraryDensity
import com.example.ui.theme.ThemeMode

/**
 * Authoritative models for persistent application and playback session state (Upgrade 27).
 *
 * Designed to survive process death, task termination, and device restarts without
 * serializing live framework handles or stale callbacks.
 */

data class PersistentPlaybackSession(
    val currentTrack: Track? = null,
    val playbackPositionMs: Long = 0L,
    val wasPlaying: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

data class PersistentQueueSession(
    val currentTrack: Track? = null,
    val upcomingQueue: List<Track> = emptyList(),
    val playbackHistory: List<Track> = emptyList(),
    val forwardHistory: List<Track> = emptyList(),
    val isShuffleEnabled: Boolean = false,
    val shuffleSequenceTrackIds: List<String> = emptyList(),
    val shuffleIndex: Int = 0,
    val originalQueueTrackIds: List<String> = emptyList(),
    val repeatMode: QueueRepeatMode = QueueRepeatMode.OFF,
    val smartContinueMode: SmartContinueMode = SmartContinueMode.OFF,
    val historyCursor: Int = -1
)

data class PersistentLibraryUiSession(
    val sortOption: ExplorerSortOption = ExplorerSortOption.NAME_ASC,
    val sortAscending: Boolean = true,
    val searchQuery: String = "",
    val selectedCrateId: String = "crate_all",
    val selectedGenreFilter: String? = null,
    val selectedPlatformFilter: String? = null,
    val hideUnavailableTracks: Boolean = false,
    val currentDirectoryPath: String = "",
    val currentStorageSourceId: String = "all",
    val selectedTab: String = "LOCAL",
    val selectedLocalCategory: String = "SONGS",
    val selectedAlbumName: String? = null,
    val selectedArtistName: String? = null,
    val selectedPlaylistId: String? = null,
    val selectedFolderPath: String? = null,
    val scrollAnchorTrackId: String? = null,
    val scrollItemIndex: Int = 0,
    val scrollItemOffset: Int = 0
)

data class PersistentAppearanceSession(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val proDarkVariant: ProDarkVariant = ProDarkVariant.BLACK_WHITE,
    val libraryDensity: ProLibraryDensity = ProLibraryDensity.COMPACT,
    val waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    val isTrackGridView: Boolean = false,
    val isCarModeActive: Boolean = false,
    val carModeKeepAwake: Boolean = true,
    val carModeNightMode: Boolean = false,
    val carModeDisplayMode: CarDisplayMode = CarDisplayMode.ARTWORK,
    val carModeSmartShuffle: Boolean = true
)

data class PersistentScannerCheckpoint(
    val scanType: String = "METADATA_ANALYSIS",
    val status: ScanStatus = ScanStatus.IDLE,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val sourceId: String = "",
    val sourceUri: String = "",
    val activeDirectory: String = "",
    val lastProcessedTrackId: String = "",
    val lastProcessedFilePath: String = "",
    val processedCount: Int = 0,
    val totalDiscoveredCount: Int = 0,
    val completedSuccess: Int = 0,
    val completedSkipped: Int = 0,
    val failedCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class PersistentAppSession(
    val version: Int = CURRENT_SCHEMA_VERSION,
    val playback: PersistentPlaybackSession = PersistentPlaybackSession(),
    val queue: PersistentQueueSession = PersistentQueueSession(),
    val libraryUi: PersistentLibraryUiSession = PersistentLibraryUiSession(),
    val appearance: PersistentAppearanceSession = PersistentAppearanceSession(),
    val scannerCheckpoint: PersistentScannerCheckpoint? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
