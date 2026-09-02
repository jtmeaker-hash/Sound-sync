package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.SyncState
import com.example.model.Track

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["contentFingerprint"]),
        Index(value = ["filePath"])
    ]
)
data class TrackEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val subGenre: String,
    val bpm: Double,
    val bpmConfidence: Double = 0.0,
    val bpmAnalysisVersion: String? = null,
    val bpmLastAnalyzed: Long? = null,
    val musicalKey: String,
    val camelotKey: String = "",
    val keyConfidence: Double = 0.0,
    val keyAnalysisVersion: String? = null,
    val keyLastAnalyzed: Long? = null,
    val durationSeconds: Int,
    val bitrateKbps: Int,
    val format: String,
    val fileSizeMb: Double,
    val filePath: String,
    val isOfflineReady: Boolean,
    val syncState: String, // from SyncState enum
    val platformsString: String, // comma separated platform names
    val energyRating: Int,
    val hotCuesString: String, // comma separated integers
    val isAiTagged: Boolean,
    val qualityRating: String, // from AudioQualityRating enum
    val dateAdded: Long,
    val crateId: String,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val albumArtist: String = "",
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val barcode: String? = null,
    val isrc: String? = null,
    val musicBrainzRecordingId: String? = null,
    val musicBrainzArtistId: String? = null,
    val musicBrainzReleaseId: String? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val musicBrainzMatchConfidence: Double = 0.0,
    val musicBrainzLastChecked: Long? = null,
    val artworkUrl: String? = null,
    val storageRelativePath: String = "",
    val contentFingerprint: String = ""
) {
    fun toTrack(): Track {
        val syncEnum = try { SyncState.valueOf(syncState) } catch (e: Exception) { SyncState.LOCAL_ONLY }
        val qualityEnum = try { AudioQualityRating.valueOf(qualityRating) } catch (e: Exception) { AudioQualityRating.TRUE_320 }
        val platformList = platformsString.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { name ->
                try { MusicPlatform.valueOf(name.trim()) } catch (e: Exception) { null }
            }
        val cues = hotCuesString.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.trim().toIntOrNull() }

        val dir = if (filePath.contains("/")) filePath.substringBeforeLast("/") else "/Music"
        val resolvedStoragePath = if (storageRelativePath.isNotBlank()) {
            storageRelativePath
        } else {
            val p = filePath.removePrefix("file://")
            when {
                p.contains("/storage/emulated/0/") -> p.substringAfter("/storage/emulated/0/").trimStart('/')
                p.contains("/storage/") -> p.substringAfter("/storage/").substringAfter("/").trimStart('/')
                p.startsWith("/") -> p.trimStart('/')
                else -> p
            }
        }

        return Track(
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
            directoryPath = dir,
            isOfflineReady = isOfflineReady,
            syncState = syncEnum,
            platforms = if (platformList.isEmpty()) listOf(MusicPlatform.LOCAL) else platformList,
            energyRating = energyRating,
            hotCues = if (cues.isEmpty()) listOf(0, 32, 64, 128) else cues,
            isAiTagged = isAiTagged,
            qualityRating = qualityEnum,
            dateAdded = dateAdded,
            crateId = crateId,
            sourceId = "internal",
            trackNumber = trackNumber,
            discNumber = discNumber,
            albumArtist = albumArtist,
            releaseDate = releaseDate,
            releaseYear = releaseYear,
            recordLabel = recordLabel,
            barcode = barcode,
            isrc = isrc,
            musicBrainzRecordingId = musicBrainzRecordingId,
            musicBrainzArtistId = musicBrainzArtistId,
            musicBrainzReleaseId = musicBrainzReleaseId,
            musicBrainzReleaseGroupId = musicBrainzReleaseGroupId,
            musicBrainzMatchConfidence = musicBrainzMatchConfidence,
            musicBrainzLastChecked = musicBrainzLastChecked,
            artworkUrl = artworkUrl,
            storageRelativePath = resolvedStoragePath,
            contentFingerprint = contentFingerprint
        )
    }

    companion object {
        fun fromTrack(track: Track): TrackEntity {
            return TrackEntity(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                genre = track.genre,
                subGenre = track.subGenre,
                bpm = track.bpm,
                bpmConfidence = track.bpmConfidence,
                bpmAnalysisVersion = track.bpmAnalysisVersion,
                bpmLastAnalyzed = track.bpmLastAnalyzed,
                musicalKey = track.musicalKey,
                camelotKey = track.camelotKey,
                keyConfidence = track.keyConfidence,
                keyAnalysisVersion = track.keyAnalysisVersion,
                keyLastAnalyzed = track.keyLastAnalyzed,
                durationSeconds = track.durationSeconds,
                bitrateKbps = track.bitrateKbps,
                format = track.format,
                fileSizeMb = track.fileSizeMb,
                filePath = track.filePath,
                isOfflineReady = track.isOfflineReady,
                syncState = track.syncState.name,
                platformsString = track.platforms.joinToString(",") { it.name },
                energyRating = track.energyRating,
                hotCuesString = track.hotCues.joinToString(","),
                isAiTagged = track.isAiTagged,
                qualityRating = track.qualityRating.name,
                dateAdded = track.dateAdded,
                crateId = track.crateId,
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
                albumArtist = track.albumArtist,
                releaseDate = track.releaseDate,
                releaseYear = track.releaseYear,
                recordLabel = track.recordLabel,
                barcode = track.barcode,
                isrc = track.isrc,
                musicBrainzRecordingId = track.musicBrainzRecordingId,
                musicBrainzArtistId = track.musicBrainzArtistId,
                musicBrainzReleaseId = track.musicBrainzReleaseId,
                musicBrainzReleaseGroupId = track.musicBrainzReleaseGroupId,
                musicBrainzMatchConfidence = track.musicBrainzMatchConfidence,
                musicBrainzLastChecked = track.musicBrainzLastChecked,
                artworkUrl = track.artworkUrl,
                storageRelativePath = track.storageRelativePath,
                contentFingerprint = track.contentFingerprint
            )
        }
    }
}

@Entity(tableName = "crates")
data class CrateEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val colorHex: Long,
    val minBpm: Double,
    val maxBpm: Double
)
