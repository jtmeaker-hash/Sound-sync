package com.example.metadata

import com.example.model.Track

interface LocalAudioAnalyzer {
    suspend fun analyze(track: Track): AudioAnalysisResult
}

class MusicMetadataEnrichmentService(
    private val musicBrainzClient: MusicBrainzClient,
    private val audioAnalyzer: LocalAudioAnalyzer
) {
    suspend fun enrich(track: Track): EnrichedTrackMetadata {
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
        val recording = musicBrainzClient.findRecording(identity)
        val audio = audioAnalyzer.analyze(track)
        val release = recording?.releases
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
            musicBrainzReleaseId = release?.id ?: track.musicBrainzReleaseId,
            musicBrainzReleaseGroupId = release?.releaseGroupId ?: track.musicBrainzReleaseGroupId,
            isrc = recording?.isrcs?.firstOrNull() ?: track.isrc,
            title = recording?.title?.ifBlank { track.title } ?: track.title,
            artist = artist,
            artistCredits = artist,
            album = release?.title?.ifBlank { track.album } ?: track.album,
            albumArtist = artist,
            genre = recording?.tags?.firstOrNull() ?: track.genre.takeIf { it.isNotBlank() },
            tags = recording?.tags.orEmpty(),
            releaseDate = release?.date ?: track.releaseDate,
            releaseYear = release?.date?.take(4)?.toIntOrNull() ?: track.releaseYear,
            trackNumber = release?.trackNumber ?: track.trackNumber,
            discNumber = release?.discNumber ?: track.discNumber,
            recordLabel = release?.label ?: track.recordLabel,
            barcode = release?.barcode ?: track.barcode,
            releaseCountry = release?.country,
            releaseStatus = release?.status,
            disambiguation = recording?.disambiguation,
            artworkUrl = release?.id?.let { "https://coverartarchive.org/release/$it/front-500" } ?: track.artworkUrl,
            // BPM/key are deliberately sourced only from local analysis or existing persisted values.
            bpm = audio.bpm ?: track.bpm.takeIf { it in 30.0..300.0 },
            bpmConfidence = if (audio.bpm != null) audio.bpmConfidence else track.bpmConfidence,
            bpmAnalysisVersion = audio.bpm?.let { audio.analysisVersion } ?: track.bpmAnalysisVersion,
            bpmLastAnalyzed = audio.bpm?.let { audio.analyzedAt } ?: track.bpmLastAnalyzed,
            musicalKey = audio.musicalKey ?: track.musicalKey.takeIf(String::isNotBlank),
            camelotKey = audio.camelotKey ?: track.camelotKey.takeIf(String::isNotBlank),
            keyConfidence = if (audio.musicalKey != null) audio.keyConfidence else track.keyConfidence,
            keyAnalysisVersion = audio.musicalKey?.let { audio.analysisVersion } ?: track.keyAnalysisVersion,
            keyLastAnalyzed = audio.musicalKey?.let { audio.analyzedAt } ?: track.keyLastAnalyzed,
            musicBrainzConfidence = if (recording != null) 1.0 else track.musicBrainzMatchConfidence,
            musicBrainzLastChecked = if (recording != null) System.currentTimeMillis() else track.musicBrainzLastChecked
        )
    }
}
