package com.example.model

enum class MusicPlatform(val displayName: String, val iconName: String, val colorHex: Long) {
    LOCAL("Local Storage", "folder", 0xFF05FFA1),
    SPOTIFY("Spotify", "library_music", 0xFF1DB954),
    SOUNDCLOUD("SoundCloud", "cloud", 0xFFFF5500),
    GOOGLE_DRIVE("Google Drive", "cloud_upload", 0xFF4285F4)
}

enum class StorageSourceType(val displayName: String, val defaultPath: String, val isRemovable: Boolean) {
    INTERNAL("Internal Audio", "/storage/emulated/0/Music", false),
    USB_SSD("USB-C SSD (Crucial X8)", "/mnt/media_rw/USB_DJ_VAULT/Tracks", true),
    SD_CARD("MicroSD Card (SanDisk 512G)", "/storage/0000-0000/DJ_Sets", true),
    DOWNLOADS("Downloads Folder", "/storage/emulated/0/Download", false),
    CLOUD_VAULT("Cloud Sync Cache", "/storage/emulated/0/SoundSync/CloudCache", false)
}

data class StorageSource(
    val id: String,
    val type: StorageSourceType,
    val label: String,
    val path: String,
    val isOnline: Boolean = true,
    val trackCount: Int = 0,
    val freeSpaceGb: Double = 128.4,
    val totalSpaceGb: Double = 512.0,
    val lastScanned: Long = System.currentTimeMillis()
)

enum class SyncState {
    LOCAL_ONLY,
    SYNCED,
    SYNCING,
    CLOUD_ONLY,
    MODIFIED_OFFLINE
}

enum class AudioQualityRating(val label: String, val description: String, val cutoffKhz: Float, val isLossless: Boolean) {
    STUDIO_LOSSLESS("24-bit Hi-Res FLAC", "No spectral cutoffs. Full frequencies up to 24kHz+", 24.0f, true),
    TRUE_LOSSLESS("16-bit Lossless FLAC", "Full dynamic range. Pure uncompressed acoustic frequency up to 22.05kHz", 22.0f, true),
    TRUE_320("True 320 kbps MP3", "Clean spectral ceiling at 20.5 kHz. High density high-end detail", 20.5f, false),
    TRUE_256("256 kbps AAC/MP3", "Standard broadcast cutoff at 19.0 kHz. Clean harmonics", 19.0f, false),
    SUSPICIOUS_UPSCALED("Fake 320k (Upscaled)", "Brickwall cutoff at ~16 kHz with zero high frequency. Transcoded from 128k!", 15.5f, false),
    LOW_128("128 kbps Low Quality", "Severe shelf cutoff at 15-16 kHz. Poor club audio fidelity", 15.0f, false)
}

data class SpectrogramAnalysis(
    val cutoffKhz: Float,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val bitrateKbps: Int = 320,
    val dynamicRangeDb: Float = 14.2f,
    val qualityRating: AudioQualityRating = AudioQualityRating.TRUE_320,
    val spectralSlices: List<FloatArray> = emptyList(), // FFT magnitude columns [time][freq_bin]
    val notes: String = "Clean audio spectrum verified."
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Single",
    val genre: String = "DJ Library",
    val subGenre: String = "Club",
    val bpm: Double = 0.0,
    val musicalKey: String = "", // Camelot key e.g. 8A (A Minor), 11B (A Major) or "" if unknown
    val durationSeconds: Int = 210,
    val bitrateKbps: Int = 320,
    val format: String = "MP3", // MP3, FLAC, WAV, AAC, AIFF
    val fileSizeMb: Double = 8.4,
    val filePath: String = "/Music/track.mp3",
    val directoryPath: String = "/Music",
    val isOfflineReady: Boolean = true,
    val syncState: SyncState = SyncState.SYNCED,
    val platforms: List<MusicPlatform> = listOf(MusicPlatform.LOCAL),
    val energyRating: Int = 7, // 1 to 10 scale for DJ set building
    val hotCues: List<Int> = listOf(0, 32, 64, 128), // Cue positions in beats/seconds
    val isAiTagged: Boolean = false,
    val qualityRating: AudioQualityRating = AudioQualityRating.TRUE_320,
    val dateAdded: Long = System.currentTimeMillis(),
    val crateId: String = "default",
    val sourceId: String = "internal",
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val storageRelativePath: String = "",
    val contentFingerprint: String = ""
) {
    val hasValidBpm: Boolean
        get() = bpm > 30.0 && bpm < 300.0

    val hasValidKey: Boolean
        get() = musicalKey.isNotBlank() && musicalKey != "—" && musicalKey != "-" && !musicalKey.equals("Unknown", ignoreCase = true)

    val bpmDisplay: String
        get() = if (hasValidBpm) String.format(java.util.Locale.US, "%.1f BPM", bpm) else "BPM —"

    val bpmIntDisplay: String
        get() = if (hasValidBpm) "${bpm.toInt()} BPM" else "BPM —"

    val bpmValueDisplay: String
        get() = if (hasValidBpm) String.format(java.util.Locale.US, "%.1f", bpm) else "—"

    val keyDisplay: String
        get() = if (hasValidKey) "KEY $musicalKey" else "KEY —"

    val keyShortDisplay: String
        get() = if (hasValidKey) musicalKey else "—"
}

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val totalDurationSeconds: Int,
    val tracks: List<Track>,
    val year: Int = 0,
    val artworkUri: String? = null
)

data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val songCount: Int,
    val totalDurationSeconds: Int,
    val albums: List<Album>,
    val songs: List<Track>
)

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceId: String? = null,
    val backingFileUri: String? = null,
    val backingRelativePath: String? = null,
    val isRockboxCompatible: Boolean = true,
    val isImported: Boolean = false,
    val trackCount: Int = 0,
    val totalDurationSeconds: Int = 0,
    val tracks: List<Track> = emptyList(),
    val missingTrackCount: Int = 0,
    val storageLocationLabel: String = "Internal Storage",
    val hasCrossStorageWarning: Boolean = false
)

data class FolderItem(
    val name: String,
    val path: String,
    val trackCount: Int = 0,
    val subFolderCount: Int = 0,
    val totalSizeMb: Double = 0.0
)

data class DjCrate(
    val id: String,
    val name: String,
    val description: String,
    val colorHex: Long = 0xFF00F0FF,
    val bpmRange: Pair<Double, Double> = 120.0 to 130.0,
    val trackCount: Int = 0
)

data class DuplicateMatch(
    val trackA: Track,
    val trackB: Track,
    val similarityScore: Int, // 0 to 100
    val reason: String,
    val recommendedAction: String // "Keep Track B (Higher Quality FLAC)", etc.
)

enum class FileOperationType(val label: String) {
    MOVE("Move"),
    COPY("Copy"),
    RENAME("Rename"),
    TRASH("Safe Trash"),
    AUTO_TAG("Batch Tag")
}

data class OperationJournalItem(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val operationType: FileOperationType,
    val affectedTracksCount: Int,
    val summary: String,
    val canUndo: Boolean = true,
    val isUndone: Boolean = false
)

enum class ExplorerSortOption(val displayName: String) {
    NAME_ASC("Name (A-Z)"),
    BPM_ASC("BPM (Low-High)"),
    BPM_DESC("BPM (High-Low)"),
    KEY("Camelot Key"),
    QUALITY("Audio Quality"),
    ENERGY_DESC("Energy (10-1)"),
    DATE_DESC("Date Added"),
    SIZE_DESC("File Size")
}

data class ScanSummaryResult(
    val discovered: Int,
    val imported: Int,
    val skipped: Int,
    val failed: Int
) {
    val totalProcessed: Int
        get() = imported + skipped + failed

    val userMessage: String
        get() {
            if (discovered == 0 && imported == 0 && skipped == 0) {
                return "No audio files found on storage."
            }
            if (imported == 0 && skipped > 0) {
                return if (failed > 0) {
                    "All $skipped tracks are already in your library and were skipped ($failed unreadable)."
                } else {
                    "All $skipped tracks are already in your library and were skipped."
                }
            }
            val parts = mutableListOf<String>()
            parts.add("$imported ${if (imported == 1) "track" else "tracks"} imported")
            if (skipped > 0) {
                parts.add("$skipped ${if (skipped == 1) "track" else "tracks"} already in library and skipped")
            }
            if (failed > 0) {
                parts.add("$failed ${if (failed == 1) "file" else "files"} could not be read")
            }
            return parts.joinToString("\n")
        }
}


