package com.example.ui.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.SoundCloudAuthState
import com.example.model.SoundCloudPlaylistItem
import com.example.model.SoundCloudTrackItem
import com.example.model.SpotifyAuthState
import com.example.model.SpotifyPlaylistItem
import com.example.model.SpotifyTrackItem
import com.example.model.Track
import com.example.streaming.SoundCloudStreamingProvider
import com.example.streaming.SpotifyStreamingProvider
import com.example.streaming.StreamingProviderState
import com.example.streaming.StreamingServiceId
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.SoundCloudOrange
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Centralized Streaming Tab composable.
 * Displays all integrated streaming services (Spotify, SoundCloud) behind a unified
 * provider interface. Allows seamless browsing within the tab with back navigation.
 */
@Composable
fun StreamingView(
    activeProviderId: StreamingServiceId?,
    onSelectProvider: (StreamingServiceId?) -> Unit,
    // Spotify Props
    spotifyAuthState: SpotifyAuthState,
    spotifySavedTracks: List<SpotifyTrackItem>,
    spotifyPlaylists: List<SpotifyPlaylistItem>,
    spotifySearchResults: List<SpotifyTrackItem>,
    spotifyIsLoading: Boolean,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onSearchSpotify: (String) -> Unit,
    onPlaySpotifyTrack: (SpotifyTrackItem) -> Unit,
    onRefreshSpotify: () -> Unit,
    // SoundCloud Props
    soundCloudAuthState: SoundCloudAuthState,
    soundCloudLikedTracks: List<SoundCloudTrackItem>,
    soundCloudPlaylists: List<SoundCloudPlaylistItem>,
    soundCloudSearchResults: List<SoundCloudTrackItem>,
    soundCloudIsLoading: Boolean,
    onConnectSoundCloud: () -> Unit,
    onDisconnectSoundCloud: () -> Unit,
    onSearchSoundCloud: (String) -> Unit,
    onPlaySoundCloudTrack: (SoundCloudTrackItem) -> Unit,
    onRefreshSoundCloud: () -> Unit,
    // General Audio Engine / Diagnostics
    currentTrack: Track?,
    isPlaying: Boolean,
    onInspectSpectrogram: (Track) -> Unit,
    onOpenConfigDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyState = remember(spotifyAuthState) {
        SpotifyStreamingProvider.mapState(spotifyAuthState)
    }
    val soundCloudState = remember(soundCloudAuthState) {
        SoundCloudStreamingProvider.mapState(soundCloudAuthState)
    }

    // Handle back button when inspecting a specific provider
    BackHandler(enabled = activeProviderId != null) {
        onSelectProvider(null)
    }

    AnimatedContent(
        targetState = activeProviderId,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "streaming_sub_navigation",
        modifier = modifier.fillMaxSize()
    ) { provider ->
        when (provider) {
            StreamingServiceId.SPOTIFY -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    StreamingProviderSubHeader(
                        title = "Spotify",
                        brandColor = SpotifyGreen,
                        isConnected = spotifyAuthState.isConnected,
                        accountName = spotifyAuthState.userProfile?.displayName,
                        onBack = { onSelectProvider(null) },
                        onOpenConfig = onOpenConfigDialog
                    )
                    SpotifyTab(
                        authState = spotifyAuthState,
                        savedTracks = spotifySavedTracks,
                        playlists = spotifyPlaylists,
                        searchResults = spotifySearchResults,
                        isLoading = spotifyIsLoading,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        onConnectSpotify = onConnectSpotify,
                        onDisconnect = onDisconnectSpotify,
                        onOpenConfigDialog = onOpenConfigDialog,
                        onSearch = onSearchSpotify,
                        onPlayTrack = onPlaySpotifyTrack,
                        onInspectSpectrogram = onInspectSpectrogram,
                        onRefresh = onRefreshSpotify
                    )
                }
            }
            StreamingServiceId.SOUNDCLOUD -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    StreamingProviderSubHeader(
                        title = "SoundCloud",
                        brandColor = SoundCloudOrange,
                        isConnected = soundCloudAuthState.isConnected,
                        accountName = soundCloudAuthState.userProfile?.username,
                        onBack = { onSelectProvider(null) },
                        onOpenConfig = onOpenConfigDialog
                    )
                    SoundCloudTab(
                        authState = soundCloudAuthState,
                        likedTracks = soundCloudLikedTracks,
                        playlists = soundCloudPlaylists,
                        searchResults = soundCloudSearchResults,
                        isLoading = soundCloudIsLoading,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        onConnectSoundCloud = onConnectSoundCloud,
                        onDisconnect = onDisconnectSoundCloud,
                        onOpenConfigDialog = onOpenConfigDialog,
                        onSearch = onSearchSoundCloud,
                        onPlayTrack = onPlaySoundCloudTrack,
                        onInspectSpectrogram = onInspectSpectrogram,
                        onRefresh = onRefreshSoundCloud
                    )
                }
            }
            null -> {
                StreamingHubHome(
                    spotifyState = spotifyState,
                    soundCloudState = soundCloudState,
                    onOpenSpotify = { onSelectProvider(StreamingServiceId.SPOTIFY) },
                    onConnectSpotify = onConnectSpotify,
                    onDisconnectSpotify = onDisconnectSpotify,
                    onOpenSoundCloud = { onSelectProvider(StreamingServiceId.SOUNDCLOUD) },
                    onConnectSoundCloud = onConnectSoundCloud,
                    onDisconnectSoundCloud = onDisconnectSoundCloud,
                    onOpenConfig = onOpenConfigDialog
                )
            }
        }
    }
}

@Composable
private fun StreamingProviderSubHeader(
    title: String,
    brandColor: Color,
    isConnected: Boolean,
    accountName: String?,
    onBack: () -> Unit,
    onOpenConfig: () -> Unit
) {
    Surface(
        color = DjSurfaceDark,
        border = BorderStroke(0.5.dp, DjSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("back_to_streaming_hub")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Streaming Hub",
                        tint = DeckACyan
                    )
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = brandColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, brandColor)
                        ) {
                            Text(
                                text = if (isConnected) "CONNECTED" else "OAUTH READY",
                                color = brandColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (accountName != null) {
                        Text(
                            text = "Active account: $accountName",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            IconButton(
                onClick = onOpenConfig,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "API Configuration",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun StreamingHubHome(
    spotifyState: StreamingProviderState,
    soundCloudState: StreamingProviderState,
    onOpenSpotify: () -> Unit,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onOpenSoundCloud: () -> Unit,
    onConnectSoundCloud: () -> Unit,
    onDisconnectSoundCloud: () -> Unit,
    onOpenConfig: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DjObsidian)
            .testTag("streaming_hub_view"),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Banner
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DjSurfaceCard,
                border = BorderStroke(1.dp, DjSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DeckACyan.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, DeckACyan),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(
                                text = "STREAMING HUB",
                                color = TextPrimary,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }

                        IconButton(
                            onClick = onOpenConfig,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "API Keys", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        text = "Access and manage all cloud music platforms from one unified center. Connect your external accounts to browse your saved tracks, likes, and playlists alongside your local library.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Section Title
        item {
            Text(
                text = "AVAILABLE PROVIDERS",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }

        // Spotify Card
        item {
            StreamingProviderCard(
                providerState = spotifyState,
                onBrowse = onOpenSpotify,
                onConnect = onConnectSpotify,
                onDisconnect = onDisconnectSpotify,
                onOpenConfig = onOpenConfig,
                icon = Icons.Default.LibraryMusic
            )
        }

        // SoundCloud Card
        item {
            StreamingProviderCard(
                providerState = soundCloudState,
                onBrowse = onOpenSoundCloud,
                onConnect = onConnectSoundCloud,
                onDisconnect = onDisconnectSoundCloud,
                onOpenConfig = onOpenConfig,
                icon = Icons.Default.Cloud
            )
        }

        // Future Providers Extensibility Note
        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DjSurfaceDark,
                border = BorderStroke(0.5.dp, DjSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "MORE STREAMING INTEGRATIONS",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "SoundSync's unified streaming architecture is built to support future external providers including TIDAL, Bandcamp, Apple Music, YouTube Music, and Deezer.",
                        color = TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingProviderCard(
    providerState: StreamingProviderState,
    onBrowse: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenConfig: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DjSurfaceCard,
        border = BorderStroke(1.dp, if (providerState.isConnected) providerState.brandColor.copy(alpha = 0.5f) else DjSurfaceBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("streaming_provider_card_${providerState.serviceId.key}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (providerState.avatarUrl != null) {
                        AsyncImage(
                            model = providerState.avatarUrl,
                            contentDescription = providerState.name,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, providerState.brandColor, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = providerState.brandColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, providerState.brandColor),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, tint = providerState.brandColor, modifier = Modifier.size(22.dp))
                            }
                        }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = providerState.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextPrimary
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (providerState.isConnected) providerState.brandColor.copy(alpha = 0.15f) else DjSurfaceDark,
                                border = BorderStroke(1.dp, if (providerState.isConnected) providerState.brandColor else DjSurfaceBorder)
                            ) {
                                Text(
                                    text = providerState.badgeText ?: (if (providerState.isConnected) "CONNECTED" else "READY"),
                                    color = if (providerState.isConnected) providerState.brandColor else TextMuted,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = providerState.accountName?.let { "Connected as: $it" } ?: providerState.subtitle,
                            color = if (providerState.isConnected) TextPrimary else TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Description
            Text(
                text = providerState.description,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (providerState.isConnected) {
                    Button(
                        onClick = onBrowse,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = providerState.brandColor,
                            contentColor = DjObsidian
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browse_${providerState.serviceId.key}_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Browse & Search", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, DjSurfaceBorder),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Disconnect", fontSize = 11.sp)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = providerState.brandColor,
                            contentColor = DjObsidian
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("connect_${providerState.serviceId.key}_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("Connect ${providerState.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenConfig,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, DjSurfaceBorder),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Config", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
