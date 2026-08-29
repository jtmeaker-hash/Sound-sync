package com.example.sync

import com.example.model.AudioQualityRating
import com.example.model.DjCrate
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PlatformConnectionStatus(
    val platform: MusicPlatform,
    val isConnected: Boolean,
    val accountName: String,
    val syncedTracksCount: Int,
    val isSyncing: Boolean = false
)

data class CloudStorageQuota(
    val usedBytesMb: Double = 42800.0, // 42.8 GB
    val totalBytesMb: Double = 102400.0, // 100 GB
    val offlineCacheBytesMb: Double = 18400.0 // 18.4 GB
)

object CloudSyncManager {

    private val _platformStatuses = MutableStateFlow(
        listOf(
            PlatformConnectionStatus(MusicPlatform.LOCAL, true, "Internal Storage + SD Card", 48),
            PlatformConnectionStatus(MusicPlatform.BEATPORT, true, "dj_resident@pro.beatport", 34),
            PlatformConnectionStatus(MusicPlatform.SPOTIFY, true, "alex_music_crate", 82),
            PlatformConnectionStatus(MusicPlatform.SOUNDCLOUD, true, "DJ_Nova_Official", 19),
            PlatformConnectionStatus(MusicPlatform.GOOGLE_DRIVE, true, "drive.google.com/dj-masters", 65),
            PlatformConnectionStatus(MusicPlatform.DROPBOX, true, "Dropbox/RekordboxCrates", 52),
            PlatformConnectionStatus(MusicPlatform.BANDCAMP, false, "Not Connected", 0),
            PlatformConnectionStatus(MusicPlatform.TIDAL, false, "Not Connected", 0)
        )
    )
    val platformStatuses = _platformStatuses.asStateFlow()

    private val _storageQuota = MutableStateFlow(CloudStorageQuota())
    val storageQuota = _storageQuota.asStateFlow()

    private val _isGlobalSyncRunning = MutableStateFlow(false)
    val isGlobalSyncRunning = _isGlobalSyncRunning.asStateFlow()

    fun togglePlatformConnection(platform: MusicPlatform) {
        val current = _platformStatuses.value.toMutableList()
        val index = current.indexOfFirst { it.platform == platform }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(
                isConnected = !item.isConnected,
                accountName = if (!item.isConnected) "Connected User" else "Not Connected",
                syncedTracksCount = if (!item.isConnected) 24 else 0
            )
            _platformStatuses.value = current
        }
    }

    /**
     * Generates initial sample DJ tracks demonstrating multiple genres, quality ratings,
     * fuzzy duplicate candidates, and multi-platform sync states.
     */
    fun getInitialSampleTracks(): List<Track> {
        return listOf(
            Track(
                id = "track_1",
                title = "Atmospheric Echoes (Extended Club Mix)",
                artist = "Nexus & Solis",
                album = "Neon Horizons EP",
                genre = "Melodic Techno",
                subGenre = "Hypnotic Peak",
                bpm = 126.0,
                musicalKey = "8A",
                durationSeconds = 384,
                bitrateKbps = 320,
                format = "MP3",
                fileSizeMb = 14.8,
                filePath = "/storage/emulated/0/Music/Techno/Atmospheric_Echoes_Extended.mp3",
                directoryPath = "/storage/emulated/0/Music/Techno",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.LOCAL, MusicPlatform.BEATPORT, MusicPlatform.GOOGLE_DRIVE),
                energyRating = 8,
                hotCues = listOf(0, 45, 120, 240),
                isAiTagged = true,
                qualityRating = AudioQualityRating.TRUE_320,
                crateId = "crate_peak",
                sourceId = "internal"
            ),
            Track(
                id = "track_1_dup",
                title = "01. Nexus - Atmospheric Echoes [12'' Master Rip]",
                artist = "Nexus",
                album = "Neon Horizons",
                genre = "Melodic Techno",
                subGenre = "Peak Time",
                bpm = 126.0,
                musicalKey = "8A",
                durationSeconds = 382,
                bitrateKbps = 128,
                format = "MP3",
                fileSizeMb = 5.9,
                filePath = "/storage/emulated/0/Download/Nexus_Atmospheric_Echoes_128k_rip.mp3",
                directoryPath = "/storage/emulated/0/Download",
                isOfflineReady = true,
                syncState = SyncState.LOCAL_ONLY,
                platforms = listOf(MusicPlatform.LOCAL),
                energyRating = 8,
                hotCues = listOf(0, 45),
                isAiTagged = false,
                qualityRating = AudioQualityRating.SUSPICIOUS_UPSCALED,
                crateId = "crate_peak",
                sourceId = "downloads"
            ),
            Track(
                id = "track_2",
                title = "Subterranean Pressure (Original Mix)",
                artist = "Klangwerk",
                album = "Subterranean",
                genre = "Peak Time Techno",
                subGenre = "Industrial Driving",
                bpm = 133.0,
                musicalKey = "11A",
                durationSeconds = 345,
                bitrateKbps = 1411,
                format = "FLAC",
                fileSizeMb = 48.2,
                filePath = "/mnt/media_rw/USB_DJ_VAULT/Tracks/Techno/Subterranean_Pressure.flac",
                directoryPath = "/mnt/media_rw/USB_DJ_VAULT/Tracks/Techno",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.USB_OTG, MusicPlatform.DROPBOX, MusicPlatform.BANDCAMP),
                energyRating = 9,
                hotCues = listOf(0, 30, 90, 210),
                isAiTagged = true,
                qualityRating = AudioQualityRating.STUDIO_LOSSLESS,
                crateId = "crate_peak",
                sourceId = "usb_ssd"
            ),
            Track(
                id = "track_3",
                title = "Solaris Dreams (Sunset Vocal Mix)",
                artist = "Maya Rivera",
                album = "Solaris Dreams",
                genre = "Afro House",
                subGenre = "Deep Organic",
                bpm = 122.0,
                musicalKey = "5B",
                durationSeconds = 290,
                bitrateKbps = 320,
                format = "MP3",
                fileSizeMb = 11.2,
                filePath = "/storage/emulated/0/Music/House/Solaris_Dreams_Sunset_Mix.mp3",
                directoryPath = "/storage/emulated/0/Music/House",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.SPOTIFY, MusicPlatform.SOUNDCLOUD, MusicPlatform.LOCAL),
                energyRating = 6,
                hotCues = listOf(0, 32, 64, 160),
                isAiTagged = true,
                qualityRating = AudioQualityRating.TRUE_320,
                crateId = "crate_warmup",
                sourceId = "internal"
            ),
            Track(
                id = "track_3_dup",
                title = "Solaris Dreams (Maya Rivera) [Clean Club Edit]",
                artist = "Maya Rivera",
                album = "Solaris",
                genre = "Afro House",
                subGenre = "Deep Organic",
                bpm = 122.0,
                musicalKey = "5B",
                durationSeconds = 289,
                bitrateKbps = 1411,
                format = "FLAC",
                fileSizeMb = 39.5,
                filePath = "/storage/emulated/0/SoundSync/CloudCache/House/Solaris_Dreams_Clean_Lossless.flac",
                directoryPath = "/storage/emulated/0/SoundSync/CloudCache/House",
                isOfflineReady = false,
                syncState = SyncState.CLOUD_ONLY,
                platforms = listOf(MusicPlatform.GOOGLE_DRIVE),
                energyRating = 6,
                hotCues = listOf(0, 32, 64, 160),
                isAiTagged = true,
                qualityRating = AudioQualityRating.TRUE_LOSSLESS,
                crateId = "crate_warmup",
                sourceId = "cloud_vault"
            ),
            Track(
                id = "track_4",
                title = "Velocity Shift",
                artist = "SubMatrix",
                album = "Neuro Grid",
                genre = "Drum & Bass",
                subGenre = "Liquid Roller",
                bpm = 174.0,
                musicalKey = "4A",
                durationSeconds = 278,
                bitrateKbps = 320,
                format = "MP3",
                fileSizeMb = 10.7,
                filePath = "/mnt/media_rw/USB_DJ_VAULT/Tracks/DnB/Velocity_Shift.mp3",
                directoryPath = "/mnt/media_rw/USB_DJ_VAULT/Tracks/DnB",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.SOUNDCLOUD, MusicPlatform.USB_OTG),
                energyRating = 9,
                hotCues = listOf(0, 22, 66, 154),
                isAiTagged = true,
                qualityRating = AudioQualityRating.TRUE_320,
                crateId = "crate_peak",
                sourceId = "usb_ssd"
            ),
            Track(
                id = "track_5",
                title = "Neon Boulevard (Retro 80s Edit)",
                artist = "CyberWaves",
                album = "Outrun City",
                genre = "Synthwave",
                subGenre = "Retrowave 80s",
                bpm = 118.0,
                musicalKey = "2B",
                durationSeconds = 260,
                bitrateKbps = 256,
                format = "AAC",
                fileSizeMb = 8.1,
                filePath = "/storage/0000-0000/DJ_Sets/Synthwave/Neon_Boulevard_80s.aac",
                directoryPath = "/storage/0000-0000/DJ_Sets/Synthwave",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.SD_CARD, MusicPlatform.SPOTIFY),
                energyRating = 7,
                hotCues = listOf(0, 30, 75, 140),
                isAiTagged = true,
                qualityRating = AudioQualityRating.TRUE_256,
                crateId = "crate_warmup",
                sourceId = "sd_card"
            ),
            Track(
                id = "track_6",
                title = "Groove Dimension (Extended Funky Mix)",
                artist = "Disco Knights ft. Leo",
                album = "Studio 54 Rebirth",
                genre = "Nu Disco",
                subGenre = "Funky Groove",
                bpm = 124.0,
                musicalKey = "7A",
                durationSeconds = 310,
                bitrateKbps = 320,
                format = "MP3",
                fileSizeMb = 12.0,
                filePath = "/storage/emulated/0/Music/Disco/Groove_Dimension_Funky.mp3",
                directoryPath = "/storage/emulated/0/Music/Disco",
                isOfflineReady = true,
                syncState = SyncState.SYNCED,
                platforms = listOf(MusicPlatform.BEATPORT, MusicPlatform.LOCAL),
                energyRating = 8,
                hotCues = listOf(0, 31, 62, 186),
                isAiTagged = true,
                qualityRating = AudioQualityRating.TRUE_320,
                crateId = "crate_warmup",
                sourceId = "internal"
            )
        )
    }

    fun getInitialCrates(): List<DjCrate> {
        return listOf(
            DjCrate("crate_all", "All Audio Tracks", "Complete synchronized DJ vault", 0xFF00F0FF, 100.0 to 180.0, 7),
            DjCrate("crate_peak", "Peak Time Weapons", "High energy bangers (126-174 BPM)", 0xFFFF2A6D, 126.0 to 175.0, 3),
            DjCrate("crate_warmup", "Warmup & Sunset Vibes", "Deep House, Afro, Nu-Disco grooves", 0xFFFFB800, 118.0 to 124.0, 3),
            DjCrate("crate_lossless", "Lossless Master Vault", "Studio 24-bit FLAC / WAV club masters", 0xFF05FFA1, 110.0 to 175.0, 2)
        )
    }
}
