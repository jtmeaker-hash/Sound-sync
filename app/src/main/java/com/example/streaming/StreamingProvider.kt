package com.example.streaming

import androidx.compose.ui.graphics.Color
import com.example.model.SoundCloudAuthState
import com.example.model.SpotifyAuthState
import com.example.ui.theme.SoundCloudOrange
import com.example.ui.theme.SpotifyGreen

/**
 * Supported external streaming service identifiers.
 * Designed for clean extensibility so additional providers (TIDAL, Bandcamp, Apple Music, Deezer)
 * can be plugged in without disrupting core navigation.
 */
enum class StreamingServiceId(val key: String, val displayName: String) {
    SPOTIFY("spotify", "Spotify"),
    SOUNDCLOUD("soundcloud", "SoundCloud")
}

/**
 * Standardized data model representing the dynamic state of a music streaming provider.
 */
data class StreamingProviderState(
    val serviceId: StreamingServiceId,
    val name: String,
    val subtitle: String,
    val brandColor: Color,
    val isConnected: Boolean,
    val accountName: String?,
    val badgeText: String?,
    val avatarUrl: String? = null,
    val description: String,
    val isAvailable: Boolean = true
)

/**
 * Contract for a streaming provider integration in SoundSync.
 */
interface StreamingProvider {
    val serviceId: StreamingServiceId
    val displayName: String
    val brandColor: Color
    val description: String
}

/**
 * Concrete provider definition for Spotify.
 */
object SpotifyStreamingProvider : StreamingProvider {
    override val serviceId: StreamingServiceId = StreamingServiceId.SPOTIFY
    override val displayName: String = "Spotify"
    override val brandColor: Color = SpotifyGreen
    override val description: String = "Stream Spotify saved tracks, user playlists, and search global catalog via Web API."

    fun mapState(authState: SpotifyAuthState): StreamingProviderState {
        return StreamingProviderState(
            serviceId = serviceId,
            name = displayName,
            subtitle = if (authState.isConnected) "Connected Library & Playlists" else "OAuth PKCE Authorization",
            brandColor = brandColor,
            isConnected = authState.isConnected,
            accountName = authState.userProfile?.displayName ?: if (authState.isConnected) "Connected" else null,
            badgeText = authState.userProfile?.product?.uppercase() ?: if (authState.isConnected) "CONNECTED" else "DISCONNECTED",
            avatarUrl = authState.userProfile?.avatarUrl,
            description = description,
            isAvailable = true
        )
    }
}

/**
 * Concrete provider definition for SoundCloud.
 */
object SoundCloudStreamingProvider : StreamingProvider {
    override val serviceId: StreamingServiceId = StreamingServiceId.SOUNDCLOUD
    override val displayName: String = "SoundCloud"
    override val brandColor: Color = SoundCloudOrange
    override val description: String = "Stream SoundCloud likes, public playlists, and direct audio stream previews."

    fun mapState(authState: SoundCloudAuthState): StreamingProviderState {
        return StreamingProviderState(
            serviceId = serviceId,
            name = displayName,
            subtitle = if (authState.isConnected) "Connected Stream & Likes" else "OAuth Authorization",
            brandColor = brandColor,
            isConnected = authState.isConnected,
            accountName = authState.userProfile?.username ?: if (authState.isConnected) "Connected" else null,
            badgeText = if (authState.isConnected) "CONNECTED" else "DISCONNECTED",
            avatarUrl = authState.userProfile?.avatarUrl,
            description = description,
            isAvailable = true
        )
    }
}
