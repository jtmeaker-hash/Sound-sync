package com.example.metadata

/** The single authoritative result consumed by persistence and UI. */
data class EnrichedTrackMetadata(
    val musicBrainzRecordingId: String? = null,
    val musicBrainzArtistId: String? = null,
    val musicBrainzReleaseId: String? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val isrc: String? = null,
    val title: String,
    val artist: String,
    val artistCredits: String = artist,
    val album: String,
    val albumArtist: String = "",
    val genre: String? = null,
    val tags: List<String> = emptyList(),
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val recordLabel: String? = null,
    val barcode: String? = null,
    val releaseCountry: String? = null,
    val releaseStatus: String? = null,
    val disambiguation: String? = null,
    val artworkUrl: String? = null,
    val bpm: Double? = null,
    val bpmConfidence: Double = 0.0,
    val bpmAnalysisVersion: String? = null,
    val bpmLastAnalyzed: Long? = null,
    val musicalKey: String? = null,
    val camelotKey: String? = null,
    val keyConfidence: Double = 0.0,
    val keyAnalysisVersion: String? = null,
    val keyLastAnalyzed: Long? = null,
    val musicBrainzConfidence: Double = 0.0,
    val musicBrainzLastChecked: Long? = null
)

data class MusicBrainzRecording(
    val id: String,
    val title: String,
    val lengthMs: Long? = null,
    val disambiguation: String? = null,
    val artistCredits: List<MusicBrainzArtistCredit> = emptyList(),
    val releases: List<MusicBrainzRelease> = emptyList(),
    val isrcs: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

data class MusicBrainzArtistCredit(
    val name: String,
    val artistId: String?
)

data class MusicBrainzRelease(
    val id: String,
    val title: String,
    val date: String? = null,
    val country: String? = null,
    val status: String? = null,
    val barcode: String? = null,
    val releaseGroupId: String? = null,
    val label: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val mediumPosition: Int? = null
)

data class LocalTrackIdentity(
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val isrc: String? = null,
    val recordingId: String? = null
)
