package com.example.ui.library

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.ui.components.LibrarySearchBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Album
import com.example.util.AlbumArtHelper
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    onSelectAlbum: (Album) -> Unit,
    isScanning: Boolean = false
) {
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(300L)
        debouncedQuery = searchQuery
    }

    var filteredAlbums by remember { mutableStateOf(emptyList<Album>()) }
    LaunchedEffect(albums, debouncedQuery) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val q = debouncedQuery.trim().lowercase()
            val list = if (q.isBlank()) {
                albums
            } else {
                albums.filter {
                    it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                }
            }
            // Guarantee 100% stable unique keys for Compose LazyVerticalGrid
            val seen = mutableSetOf<String>()
            filteredAlbums = list.filter { album ->
                val key = album.id.ifBlank { "album_${album.title}_${album.artist}" }
                seen.add(key)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("albums_screen")
    ) {
        // Search Header
        LibrarySearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholderText = "Search albums or artists...",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(48.dp),
            testTag = "albums_search_input"
        )

        if (filteredAlbums.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isScanning) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = DeckACyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Scanning and organizing albums...",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "No albums matching '$searchQuery'" else "No albums found in library",
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
            ) {
                items(filteredAlbums, key = { it.id.ifBlank { "album_${it.title}_${it.artist}" } }) { album ->
                    AlbumGridCard(
                        album = album,
                        onClick = { onSelectAlbum(album) }
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumGridCard(
    album: Album,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var artworkBitmap by remember(album.id, album.artworkUri) {
        mutableStateOf(
            try {
                AlbumArtHelper.getCachedArtworkForAlbum(album, 320)
            } catch (_: Throwable) {
                null
            }
        )
    }

    LaunchedEffect(album.id, album.artworkUri) {
        if (artworkBitmap == null) {
            try {
                artworkBitmap = AlbumArtHelper.getArtworkForAlbum(context, album, 320)
            } catch (t: Throwable) {
                Log.w("AlbumGridCard", "Failed to load artwork for album '${album.title}': ${t.message}")
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DjSurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Album Artwork Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceCard),
                contentAlignment = Alignment.Center
            ) {
                val bmp = artworkBitmap
                if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "${album.title} artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = DeckACyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = album.title.ifBlank { "Unknown Album" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = album.artist.ifBlank { "Unknown Artist" },
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${album.trackCount} ${if (album.trackCount == 1) "track" else "tracks"}",
                fontSize = 11.sp,
                color = TextMuted
            )
        }
    }
}

