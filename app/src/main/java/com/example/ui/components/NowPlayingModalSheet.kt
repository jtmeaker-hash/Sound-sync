package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.audio.WaveformData
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track

/**
 * Full-screen Now Playing screen delegator for backward compatibility.
 */
@Composable
fun NowPlayingModalSheet(
    track: Track,
    displayMode: NowPlayingDisplayMode,
    waveformData: WaveformData?,
    isWaveformLoading: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onToggleDisplayMode: () -> Unit,
    onSetDisplayMode: (NowPlayingDisplayMode) -> Unit,
    onOpenProperties: (Track) -> Unit = {},
    modifier: Modifier = Modifier
) {
    NowPlayingFullScreen(
        track = track,
        displayMode = displayMode,
        waveformData = waveformData,
        isWaveformLoading = isWaveformLoading,
        isPlaying = isPlaying,
        currentPositionMs = currentPositionMs,
        durationMs = durationMs,
        onDismiss = onDismiss,
        onTogglePlayPause = onTogglePlayPause,
        onPreviousTrack = onPreviousTrack,
        onNextTrack = onNextTrack,
        onSeekToMs = onSeekToMs,
        onToggleDisplayMode = onToggleDisplayMode,
        onSetDisplayMode = onSetDisplayMode,
        onOpenProperties = onOpenProperties,
        modifier = modifier
    )
}

