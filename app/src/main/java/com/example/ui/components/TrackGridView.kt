package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.Track

/**
 * Reusable, responsive Grid View for tracks across SoundSync.
 * Automatically adapts column count to screen width:
 *  - ~2 columns on phone portrait (~155dp minSize)
 *  - 3+ columns on wider landscape / tablet displays
 */
@Composable
fun TrackGridView(
    tracks: List<Track>,
    currentPlayingTrack: Track?,
    isPlaying: Boolean,
    isSelectionMode: Boolean = false,
    selectedTrackIds: Set<String> = emptySet(),
    onToggleSelection: (Track) -> Unit = {},
    onPlayTrack: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onQueueTrack: (Track, Boolean) -> Unit,
    onInspectProperties: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onMixWithThis: ((Track) -> Unit)? = null,
    onInspectQuality: ((Track) -> Unit)? = null,
    onOpenLyrics: ((Track) -> Unit)? = null,
    onOpenTrackIntelligence: ((Track) -> Unit)? = null,
    onOpenPlaybackIssueSheet: ((Track) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 155.dp),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .testTag("tracks_grid_view"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = contentPadding
    ) {
        items(tracks, key = { it.id }) { track ->
            val isSelected = selectedTrackIds.contains(track.id)
            val isCurrent = currentPlayingTrack?.id == track.id
            TrackGridCard(
                track = track,
                isCurrent = isCurrent,
                isPlaying = isPlaying && isCurrent,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onToggleSelection = { onToggleSelection(track) },
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelection(track)
                    } else {
                        onPlayTrack(track)
                    }
                },
                onAddToPlaylist = { onAddToPlaylist(track) },
                onQueueTrack = { playNext -> onQueueTrack(track, playNext) },
                onInspectProperties = { onInspectProperties(track) },
                onInspectSpectrogram = { onInspectSpectrogram(track) },
                onMixWithThis = onMixWithThis?.let { fn -> { fn(track) } },
                onInspectQuality = onInspectQuality?.let { fn -> { fn(track) } },
                onOpenLyrics = onOpenLyrics?.let { fn -> { fn(track) } },
                onOpenTrackIntelligence = onOpenTrackIntelligence?.let { fn -> { fn(track) } },
                onOpenPlaybackIssueSheet = onOpenPlaybackIssueSheet
            )
        }
    }
}
