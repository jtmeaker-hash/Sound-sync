package com.example.metadata

import com.example.model.Track

interface LocalAudioAnalyzer {
    suspend fun analyze(track: Track): AudioAnalysisResult
}

class MusicMetadataEnrichmentService(
    private val musicBrainzClient: MusicBrainzClient,
    private val audioAnalyzer: LocalAudioAnalyzer
) {
    suspend fun enrich(
        track: Track,
        musicBrainzEnabled: Boolean = true,
        bpmAnalysisEnabled: Boolean = true,
        keyAnalysisEnabled: Boolean = true
    ): EnrichedTrackMetadata {
        val identity = LocalTrackIdentity(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSeconds = track.durationSeconds,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            isrc = track.isrc,
            recordingId = track.musicBrainzRecordingId
        )
        val recording = if (musicBrainzEnabled) musicBrainzClient.findRecording(identity) else null
        val audio = if (bpmAnalysisEnabled || keyAnalysisEnabled) {
            audioAnalyzer.analyze(track)
        } else {
            AudioAnalysisResult()
        }
        // Respect per-analysis toggles: a disabled analyzer must not silently
        // supply its field from a stale embedded tag below.
        val audioBpm = if (bpmAnalysisEnabled) audio.bpm else null
        val audioKey = if (keyAnalysisEnabled) audio.musicalKey else null
        val audioCamelot = if (keyAnalysisEnabled) audio.camelotKey else null
        val release = if (recording != null) recording.releases else emptyList()
        val selectedRelease = release
            ?.sortedWith(
                compareByDescending<MusicBrainzRelease> { release ->
                    release.title.equals(track.album, ignoreCase = true)
                }
                    .thenByDescending { release -> release.trackNumber == track.trackNumber && release.discNumber == track.discNumber }
                    .thenByDescending { release -> release.title.equals(track.album, ignoreCase = true) }
                    .thenBy { it.date.orEmpty() }
                    .thenBy { it.title }
            )
            ?.firstOrNull()
        val artist = recording?.artistCredits?.joinToString(", ") { it.name }.orEmpty().ifBlank { track.artist }

        return EnrichedTrackMetadata(
            musicBrainzRecordingId = recording?.id ?: track.musicBrainzRecordingId,
            musicBrainzArtistId = recording?.artistCredits?.firstOrNull()?.artistId ?: track.musicBrainzArtistId,
            musicBrainzReleaseId = selectedRelease?.id ?: track.musicBrainzReleaseId,
            musicBrainzReleaseGroupId = selectedRelease?.releaseGroupId ?: track.musicBrainzReleaseGroupId,
            isrc = recording?.isrcs?.firstOrNull() ?: track.isrc,
            title = recording?.title?.ifBlank { track.title } ?: track.title,
            artist = artist,
            artistCredits = artist,
            album = selectedRelease?.title?.ifBlank { track.album } ?: track.album,
            albumArtist = artist,
            genre = recording?.tags?.firstOrNull() ?: track.genre.takeIf { it.isNotBlank() },
            tags = recording?.tags.orEmpty(),
            releaseDate = selectedRelease?.date ?: track.releaseDate,
            releaseYear = selectedRelease?.date?.take(4)?.toIntOrNull() ?: track.releaseYear,
            trackNumber = selectedRelease?.trackNumber ?: track.trackNumber,
            discNumber = selectedRelease?.discNumber ?: track.discNumber,
            recordLabel = selectedRelease?.label ?: track.recordLabel,
            barcode = selectedRelease?.barcode ?: track.barcode,
            releaseCountry = selectedRelease?.country,
            releaseStatus = selectedRelease?.status,
            disambiguation = recording?.disambiguation,
            artworkUrl = selectedRelease?.id?.let { "https://coverartarchive.org/release/$it/front-500" } ?: track.artworkUrl,
            // BPM/key are deliberately sourced only from local analysis or existing persisted values.
            bpm = audioBpm ?: track.bpm.takeIf { it in 30.0..300.0 },
            bpmConfidence = if (audioBpm != null) audio.bpmConfidence else track.bpmConfidence,
            bpmAnalysisVersion = audioBpm?.let { audio.analysisVersion } ?: track.bpmAnalysisVersion,
            bpmLastAnalyzed = audioBpm?.let { audio.analyzedAt } ?: track.bpmLastAnalyzed,
            musicalKey = audioKey ?: track.musicalKey.takeIf(String::isNotBlank),
            camelotKey = audioCamelot ?: track.camelotKey.takeIf(String::isNotBlank),
            keyConfidence = if (audioKey != null) audio.keyConfidence else track.keyConfidence,
            keyAnalysisVersion = audioKey?.let { audio.analysisVersion } ?: track.keyAnalysisVersion,
            keyLastAnalyzed = audioKey?.let { audio.analyzedAt } ?: track.keyLastAnalyzed,
            musicBrainzConfidence = if (recording != null) 1.0 else track.musicBrainzMatchConfidence,
            musicBrainzLastChecked = if (recording != null) System.currentTimeMillis() else track.musicBrainzLastChecked
        )
    }
}
