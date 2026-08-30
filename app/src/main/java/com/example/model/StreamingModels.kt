package com.example.model

enum class PlaybackSource(val label: String, val brandColorHex: Long) {
    LOCAL("Local", 0xFF05FFA1),
    SOUNDCLOUD("SoundCloud", 0xFFFF5500),
    SPOTIFY("Spotify", 0xFF1DB954)
}

enum class DjAppTab(val title: String) {
    LOCAL("Local Library"),
    SOUNDCLOUD("SoundCloud"),
    SPOTIFY("Spotify")
}

// ==========================================
// SPOTIFY MODELS
// ==========================================

data class SpotifyUserProfile(
    val id: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val product: String = "premium", // premium, free, open
    val followersCount: Int = 0,
    val country: String? = null
)

data class SpotifyTrackItem(
    val id: String,
    val name: String,
    val artistName: String,
    val albumName: String,
    val albumArtUrl: String?,
    val durationMs: Long,
    val uri: String, // spotify:track:xxxx
    val previewUrl: String? = null,
    val isPlayable: Boolean = true,
    val isExplicit: Boolean = false,
    val popularity: Int = 0
) {
    fun toAppTrack(): Track {
        return Track(
            id = "spotify_$id",
            title = name,
            artist = artistName,
            album = albumName,
            genre = "Spotify Stream",
            subGenre = "Streaming",
            bpm = 124.0,
            musicalKey = "8A",
            durationSeconds = (durationMs / 1000).toInt(),
            bitrateKbps = 320,
            format = "OGG Vorbis",
            fileSizeMb = (durationMs / 1000.0 * 0.04),
            filePath = uri,
            directoryPath = "Spotify",
            isOfflineReady = false,
            syncState = SyncState.CLOUD_ONLY,
            platforms = listOf(MusicPlatform.SPOTIFY),
            energyRating = 7,
            isAiTagged = false,
            qualityRating = AudioQualityRating.TRUE_320,
            sourceId = "spotify"
        )
    }
}

data class SpotifyPlaylistItem(
    val id: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val trackCount: Int,
    val ownerName: String,
    val uri: String
)

data class SpotifyAuthState(
    val isConnected: Boolean = false,
    val userProfile: SpotifyUserProfile? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiryEpochMs: Long = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ==========================================
// SOUNDCLOUD MODELS
// ==========================================

data class SoundCloudUserProfile(
    val id: Long,
    val username: String,
    val avatarUrl: String?,
    val permalinkUrl: String?,
    val trackCount: Int = 0,
    val followersCount: Int = 0,
    val country: String? = null
)

data class SoundCloudTrackItem(
    val id: Long,
    val title: String,
    val artistName: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val streamUrl: String?, // Progressive MP3 or HLS stream
    val permalinkUrl: String?,
    val playbackCount: Long = 0,
    val likesCount: Long = 0,
    val genre: String? = null,
    val isStreamable: Boolean = true,
    val isPreviewOnly: Boolean = false, // 30s preview if not fully licensed
    val isGeoBlocked: Boolean = false
) {
    fun toAppTrack(): Track {
        return Track(
            id = "sc_$id",
            title = title,
            artist = artistName,
            album = genre ?: "SoundCloud Stream",
            genre = genre ?: "Electronic",
            subGenre = "Cloud Stream",
            bpm = 128.0,
            musicalKey = "5A",
            durationSeconds = (durationMs / 1000).toInt(),
            bitrateKbps = if (isPreviewOnly) 128 else 256,
            format = "MP3 Stream",
            fileSizeMb = (durationMs / 1000.0 * 0.032),
            filePath = streamUrl ?: permalinkUrl ?: "",
            directoryPath = "SoundCloud",
            isOfflineReady = false,
            syncState = SyncState.CLOUD_ONLY,
            platforms = listOf(MusicPlatform.SOUNDCLOUD),
            energyRating = 8,
            isAiTagged = false,
            qualityRating = if (isPreviewOnly) AudioQualityRating.LOW_128 else AudioQualityRating.TRUE_256,
            sourceId = "soundcloud"
        )
    }
}

data class SoundCloudPlaylistItem(
    val id: Long,
    val title: String,
    val artistName: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val permalinkUrl: String?
)

data class SoundCloudAuthState(
    val isConnected: Boolean = false,
    val userProfile: SoundCloudUserProfile? = null,
    val accessToken: String? = null,
    val clientId: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
