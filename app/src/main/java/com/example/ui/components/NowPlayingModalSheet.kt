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
    // EQ parameters
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    onSetEqLow: (Float) -> Unit = {},
    onSetEqMid: (Float) -> Unit = {},
    onSetEqHigh: (Float) -> Unit = {},
    // Haas parameters
    haasEnabled: Boolean = false,
    haasAmount: Float = 0.5f,
    haasDelayMs: Float = 5f,
    onSetHaasEnabled: (Boolean) -> Unit = {},
    onSetHaasAmount: (Float) -> Unit = {},
    onSetHaasDelayMs: (Float) -> Unit = {},
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
        eqLow = eqLow,
        eqMid = eqMid,
        eqHigh = eqHigh,
        onSetEqLow = onSetEqLow,
        onSetEqMid = onSetEqMid,
        onSetEqHigh = onSetEqHigh,
        haasEnabled = haasEnabled,
        haasAmount = haasAmount,
        haasDelayMs = haasDelayMs,
        onSetHaasEnabled = onSetHaasEnabled,
        onSetHaasAmount = onSetHaasAmount,
        onSetHaasDelayMs = onSetHaasDelayMs,
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

