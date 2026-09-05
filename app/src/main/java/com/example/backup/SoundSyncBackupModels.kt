package com.example.backup

import com.example.data.SongFindEntity
import com.example.data.TrackEntity
import com.example.model.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versionable, portable backup schema for SoundSync.
 * Contains user-generated and expensive analysis-generated data
 * so an app uninstall/reinstall does NOT lose Song Finds or metadata scans.
 */
data class SoundSyncBackup(
    val backupVersion: Int = CURRENT_BACKUP_VERSION,
    val appVersion: String = "1.0.0",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val songFinds: List<SongFindBackupItem> = emptyList(),
    val tracks: List<TrackBackupItem> = emptyList()
) {
    companion object {
        const val CURRENT_BACKUP_VERSION = 1
    }
}

data class SongFindBackupItem(
    val id: String,
    val url: String,
    val title: String,
    val sourceAppName: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false
) {
    fun toEntity(): SongFindEntity = SongFindEntity(
        id = id,
        url = url,
        title = title,
        sourceAppName = sourceAppName,
        notes = notes,
        createdAt = createdAt,
        isCompleted = isCompleted
    )

    companion object {
        fun fromEntity(entity: SongFindEntity): SongFindBackupItem = SongFindBackupItem(
            id = entity.id,
            url = entity.url,
            title = entity.title,
            sourceAppName = entity.sourceAppName,
            notes = entity.notes,
            createdAt = entity.createdAt,
            isCompleted = entity.isCompleted
        )

        fun fromJson(json: JSONObject): SongFindBackupItem = SongFindBackupItem(
            id = json.optString("id"),
            url = json.optString("url"),
            title = json.optString("title"),
            sourceAppName = json.optString("sourceAppName", "Web Find"),
            notes = json.optString("notes", ""),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            isCompleted = json.optBoolean("isCompleted", false)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("sourceAppName", sourceAppName)
        put("notes", notes)
        put("createdAt", createdAt)
        put("isCompleted", isCompleted)
    }
}

data class TrackBackupItem(
    val id: String,
    val title: String,
    val artist: String,
    val originalArtist: String? = null,
    val resolvedArtist: String? = null,
    val metadataSource: String? = null,
    val metadataConfidence: Double = 0.0,
    val album: String = "Single",
    val albumArtist: String = "",
    val genre: String = "DJ Library",
    val subGenre: String = "Club",
    val bpm: Double = 0.0,
    val bpmConfidence: Double = 0.0,
    val bpmAnalysisVersion: String? = null,
    val bpmLastAnalyzed: Long? = null,
    val musicalKey: String = "",
    val camelotKey: String = "",
    val keyConfidence: Double = 0.0,
    val keyAnalysisVersion: String? = null,
    val keyLastAnalyzed: Long? = null,
    val durationSeconds: Int = 0,
    val bitrateKbps: Int = 320,
    val format: String = "MP3",
    val fileSizeMb: Double = 0.0,
    val filePath: String = "",
    val storageRelativePath: String = "",
    val contentFingerprint: String = "",
    val isOfflineReady: Boolean = true,
    val syncState: String = "SYNCED",
    val platformsString: String = "LOCAL",
    val energyRating: Int = 7,
    val hotCuesString: String = "0,32,64,128",
    val isAiTagged: Boolean = false,
    val qualityRating: String = "TRUE_320",
    val dateAdded: Long = System.currentTimeMillis(),
    val crateId: String = "crate_all",
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val barcode: String? = null,
    val isrc: String? = null,
    val appleTrackId: Long? = null,
    val appleCollectionId: Long? = null,
    val appleArtistId: Long? = null,
    val theAudioDbAlbumId: String? = null,
    val theAudioDbArtistId: String? = null,
    val artworkSource: String? = null,
    val artworkCachePath: String? = null,
    val metadataScanState: String = "NOT_SCANNED",
    val metadataScanTimestamp: Long? = null,
    val userConfirmedMetadata: Boolean = false,
    val artworkUrl: String? = null,
    val rating: Int = 0,
    val customTags: String = "",
    val notes: String = "",
    val composer: String = "",
    val isManualBpm: Boolean = false,
    val isManualKey: Boolean = false,
    val analysisState: String = "NOT_ANALYSED",
    val analysisVersion: Int = 1,
    val lastAnalysedAt: Long? = null,
    val analysisFailureReason: String? = null,
    val analysisRetryCount: Int = 0,
    val fileModifiedTimestamp: Long = 0L
) {
    fun toEntity(): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        subGenre = subGenre,
        bpm = bpm,
        bpmConfidence = bpmConfidence,
        bpmAnalysisVersion = bpmAnalysisVersion,
        bpmLastAnalyzed = bpmLastAnalyzed,
        musicalKey = musicalKey,
        camelotKey = camelotKey,
        keyConfidence = keyConfidence,
        keyAnalysisVersion = keyAnalysisVersion,
        keyLastAnalyzed = keyLastAnalyzed,
        durationSeconds = durationSeconds,
        bitrateKbps = bitrateKbps,
        format = format,
        fileSizeMb = fileSizeMb,
        filePath = filePath,
        isOfflineReady = isOfflineReady,
        syncState = syncState,
        platformsString = platformsString,
        energyRating = energyRating,
        hotCuesString = hotCuesString,
        isAiTagged = isAiTagged,
        qualityRating = qualityRating,
        dateAdded = dateAdded,
        crateId = crateId,
        trackNumber = trackNumber,
        discNumber = discNumber,
        albumArtist = albumArtist,
        releaseDate = releaseDate,
        releaseYear = releaseYear,
        recordLabel = recordLabel,
        barcode = barcode,
        isrc = isrc,
        appleTrackId = appleTrackId,
        appleCollectionId = appleCollectionId,
        appleArtistId = appleArtistId,
        theAudioDbAlbumId = theAudioDbAlbumId,
        theAudioDbArtistId = theAudioDbArtistId,
        artworkSource = artworkSource,
        artworkCachePath = artworkCachePath,
        metadataScanState = metadataScanState,
        metadataScanTimestamp = metadataScanTimestamp,
        userConfirmedMetadata = userConfirmedMetadata,
        artworkUrl = artworkUrl,
        storageRelativePath = storageRelativePath,
        contentFingerprint = contentFingerprint,
        rating = rating,
        customTags = customTags,
        notes = notes,
        composer = composer,
        isManualBpm = isManualBpm,
        isManualKey = isManualKey,
        analysisState = analysisState,
        analysisVersion = analysisVersion,
        lastAnalysedAt = lastAnalysedAt,
        analysisFailureReason = analysisFailureReason,
        analysisRetryCount = analysisRetryCount,
        fileModifiedTimestamp = fileModifiedTimestamp,
        originalArtist = originalArtist,
        resolvedArtist = resolvedArtist,
        metadataSource = metadataSource,
        metadataConfidence = metadataConfidence
    )

    companion object {
        fun fromEntity(entity: TrackEntity): TrackBackupItem = TrackBackupItem(
            id = entity.id,
            title = entity.title,
            artist = entity.artist,
            originalArtist = entity.originalArtist,
            resolvedArtist = entity.resolvedArtist,
            metadataSource = entity.metadataSource,
            metadataConfidence = entity.metadataConfidence,
            album = entity.album,
            albumArtist = entity.albumArtist,
            genre = entity.genre,
            subGenre = entity.subGenre,
            bpm = entity.bpm,
            bpmConfidence = entity.bpmConfidence,
            bpmAnalysisVersion = entity.bpmAnalysisVersion,
            bpmLastAnalyzed = entity.bpmLastAnalyzed,
            musicalKey = entity.musicalKey,
            camelotKey = entity.camelotKey,
            keyConfidence = entity.keyConfidence,
            keyAnalysisVersion = entity.keyAnalysisVersion,
            keyLastAnalyzed = entity.keyLastAnalyzed,
            durationSeconds = entity.durationSeconds,
            bitrateKbps = entity.bitrateKbps,
            format = entity.format,
            fileSizeMb = entity.fileSizeMb,
            filePath = entity.filePath,
            storageRelativePath = entity.storageRelativePath,
            contentFingerprint = entity.contentFingerprint,
            isOfflineReady = entity.isOfflineReady,
            syncState = entity.syncState,
            platformsString = entity.platformsString,
            energyRating = entity.energyRating,
            hotCuesString = entity.hotCuesString,
            isAiTagged = entity.isAiTagged,
            qualityRating = entity.qualityRating,
            dateAdded = entity.dateAdded,
            crateId = entity.crateId,
            trackNumber = entity.trackNumber,
            discNumber = entity.discNumber,
            releaseDate = entity.releaseDate,
            releaseYear = entity.releaseYear,
            recordLabel = entity.recordLabel,
            barcode = entity.barcode,
            isrc = entity.isrc,
            appleTrackId = entity.appleTrackId,
            appleCollectionId = entity.appleCollectionId,
            appleArtistId = entity.appleArtistId,
            theAudioDbAlbumId = entity.theAudioDbAlbumId,
            theAudioDbArtistId = entity.theAudioDbArtistId,
            artworkSource = entity.artworkSource,
            artworkCachePath = entity.artworkCachePath,
            metadataScanState = entity.metadataScanState,
            metadataScanTimestamp = entity.metadataScanTimestamp,
            userConfirmedMetadata = entity.userConfirmedMetadata,
            artworkUrl = entity.artworkUrl,
            rating = entity.rating,
            customTags = entity.customTags,
            notes = entity.notes,
            composer = entity.composer,
            isManualBpm = entity.isManualBpm,
            isManualKey = entity.isManualKey,
            analysisState = entity.analysisState,
            analysisVersion = entity.analysisVersion,
            lastAnalysedAt = entity.lastAnalysedAt,
            analysisFailureReason = entity.analysisFailureReason,
            analysisRetryCount = entity.analysisRetryCount,
            fileModifiedTimestamp = entity.fileModifiedTimestamp
        )

        fun fromJson(json: JSONObject): TrackBackupItem = TrackBackupItem(
            id = json.optString("id"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            originalArtist = json.optString("originalArtist").takeIf(String::isNotBlank),
            resolvedArtist = json.optString("resolvedArtist").takeIf(String::isNotBlank),
            metadataSource = json.optString("metadataSource").takeIf(String::isNotBlank),
            metadataConfidence = json.optDouble("metadataConfidence", 0.0),
            album = json.optString("album", "Single"),
            albumArtist = json.optString("albumArtist", ""),
            genre = json.optString("genre", "DJ Library"),
            subGenre = json.optString("subGenre", "Club"),
            bpm = json.optDouble("bpm", 0.0),
            bpmConfidence = json.optDouble("bpmConfidence", 0.0),
            bpmAnalysisVersion = json.optString("bpmAnalysisVersion").takeIf(String::isNotBlank),
            bpmLastAnalyzed = json.optLong("bpmLastAnalyzed").takeIf { it > 0 },
            musicalKey = json.optString("musicalKey", ""),
            camelotKey = json.optString("camelotKey", ""),
            keyConfidence = json.optDouble("keyConfidence", 0.0),
            keyAnalysisVersion = json.optString("keyAnalysisVersion").takeIf(String::isNotBlank),
            keyLastAnalyzed = json.optLong("keyLastAnalyzed").takeIf { it > 0 },
            durationSeconds = json.optInt("durationSeconds", 0),
            bitrateKbps = json.optInt("bitrateKbps", 320),
            format = json.optString("format", "MP3"),
            fileSizeMb = json.optDouble("fileSizeMb", 0.0),
            filePath = json.optString("filePath", ""),
            storageRelativePath = json.optString("storageRelativePath", ""),
            contentFingerprint = json.optString("contentFingerprint", ""),
            isOfflineReady = json.optBoolean("isOfflineReady", true),
            syncState = json.optString("syncState", "SYNCED"),
            platformsString = json.optString("platformsString", "LOCAL"),
            energyRating = json.optInt("energyRating", 7),
            hotCuesString = json.optString("hotCuesString", "0,32,64,128"),
            isAiTagged = json.optBoolean("isAiTagged", false),
            qualityRating = json.optString("qualityRating", "TRUE_320"),
            dateAdded = json.optLong("dateAdded", System.currentTimeMillis()),
            crateId = json.optString("crateId", "crate_all"),
            trackNumber = json.optInt("trackNumber", 0),
            discNumber = json.optInt("discNumber", 1),
            releaseDate = json.optString("releaseDate").takeIf(String::isNotBlank),
            releaseYear = json.optInt("releaseYear").takeIf { it > 0 },
            recordLabel = json.optString("recordLabel").takeIf(String::isNotBlank),
            barcode = json.optString("barcode").takeIf(String::isNotBlank),
            appleTrackId = json.optLong("appleTrackId").takeIf { it > 0 },
            appleCollectionId = json.optLong("appleCollectionId").takeIf { it > 0 },
            appleArtistId = json.optLong("appleArtistId").takeIf { it > 0 },
            theAudioDbAlbumId = json.optString("theAudioDbAlbumId").takeIf(String::isNotBlank),
            theAudioDbArtistId = json.optString("theAudioDbArtistId").takeIf(String::isNotBlank),
            artworkSource = json.optString("artworkSource").takeIf(String::isNotBlank),
            artworkCachePath = json.optString("artworkCachePath").takeIf(String::isNotBlank),
            metadataScanState = json.optString("metadataScanState", "NOT_SCANNED"),
            metadataScanTimestamp = json.optLong("metadataScanTimestamp").takeIf { it > 0 },
            userConfirmedMetadata = json.optBoolean("userConfirmedMetadata", false),
            artworkUrl = json.optString("artworkUrl").takeIf(String::isNotBlank),
            rating = json.optInt("rating", 0),
            customTags = json.optString("customTags", ""),
            notes = json.optString("notes", ""),
            composer = json.optString("composer", ""),
            isManualBpm = json.optBoolean("isManualBpm", false),
            isManualKey = json.optBoolean("isManualKey", false),
            analysisState = json.optString("analysisState", "NOT_ANALYSED"),
            analysisVersion = json.optInt("analysisVersion", 1),
            lastAnalysedAt = json.optLong("lastAnalysedAt").takeIf { it > 0 },
            analysisFailureReason = json.optString("analysisFailureReason").takeIf(String::isNotBlank),
            analysisRetryCount = json.optInt("analysisRetryCount", 0),
            fileModifiedTimestamp = json.optLong("fileModifiedTimestamp", 0L)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("originalArtist", originalArtist)
        put("resolvedArtist", resolvedArtist)
        put("metadataSource", metadataSource)
        put("metadataConfidence", metadataConfidence)
        put("album", album)
        put("albumArtist", albumArtist)
        put("genre", genre)
        put("subGenre", subGenre)
        put("bpm", bpm)
        put("bpmConfidence", bpmConfidence)
        put("bpmAnalysisVersion", bpmAnalysisVersion)
        put("bpmLastAnalyzed", bpmLastAnalyzed)
        put("musicalKey", musicalKey)
        put("camelotKey", camelotKey)
        put("keyConfidence", keyConfidence)
        put("keyAnalysisVersion", keyAnalysisVersion)
        put("keyLastAnalyzed", keyLastAnalyzed)
        put("durationSeconds", durationSeconds)
        put("bitrateKbps", bitrateKbps)
        put("format", format)
        put("fileSizeMb", fileSizeMb)
        put("filePath", filePath)
        put("storageRelativePath", storageRelativePath)
        put("contentFingerprint", contentFingerprint)
        put("isOfflineReady", isOfflineReady)
        put("syncState", syncState)
        put("platformsString", platformsString)
        put("energyRating", energyRating)
        put("hotCuesString", hotCuesString)
        put("isAiTagged", isAiTagged)
        put("qualityRating", qualityRating)
        put("dateAdded", dateAdded)
        put("crateId", crateId)
        put("trackNumber", trackNumber)
        put("discNumber", discNumber)
        put("releaseDate", releaseDate)
        put("releaseYear", releaseYear)
        put("recordLabel", recordLabel)
        put("barcode", barcode)
        put("isrc", isrc)
        put("appleTrackId", appleTrackId)
        put("appleCollectionId", appleCollectionId)
        put("appleArtistId", appleArtistId)
        put("theAudioDbAlbumId", theAudioDbAlbumId)
        put("theAudioDbArtistId", theAudioDbArtistId)
        put("artworkSource", artworkSource)
        put("artworkCachePath", artworkCachePath)
        put("metadataScanState", metadataScanState)
        put("metadataScanTimestamp", metadataScanTimestamp)
        put("userConfirmedMetadata", userConfirmedMetadata)
        put("artworkUrl", artworkUrl)
        put("rating", rating)
        put("customTags", customTags)
        put("notes", notes)
        put("composer", composer)
        put("isManualBpm", isManualBpm)
        put("isManualKey", isManualKey)
        put("analysisState", analysisState)
        put("analysisVersion", analysisVersion)
        put("lastAnalysedAt", lastAnalysedAt)
        put("analysisFailureReason", analysisFailureReason)
        put("analysisRetryCount", analysisRetryCount)
        put("fileModifiedTimestamp", fileModifiedTimestamp)
    }
}

data class BackupSummary(
    val lastBackupTimestamp: Long?,
    val status: String,
    val songFindCount: Int,
    val trackCount: Int,
    val backupLocation: String,
    val isAutoBackupEnabled: Boolean
)

sealed class RestoreResult {
    data class Success(
        val tracksRestored: Int,
        val tracksMatched: Int,
        val songFindsRestored: Int,
        val message: String
    ) : RestoreResult()

    data class Error(val message: String, val cause: Throwable? = null) : RestoreResult()
}

sealed class ValidationResult {
    data class Valid(val backup: SoundSyncBackup) : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}
