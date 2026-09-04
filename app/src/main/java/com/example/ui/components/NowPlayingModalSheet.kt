package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.audio.WaveformData
import com.example.model.NowPlayingDisplayMode
import com.example.model.Track
import com.example.model.WaveformStyle

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
    eqEnabled: Boolean = true,
    eqLow: Float = 1f,
    eqMid: Float = 1f,
    eqHigh: Float = 1f,
    onSetEqEnabled: (Boolean) -> Unit = {},
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
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onToggleWaveformStyle: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
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
        eqEnabled = eqEnabled,
        eqLow = eqLow,
        eqMid = eqMid,
        eqHigh = eqHigh,
        onSetEqEnabled = onSetEqEnabled,
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
        waveformStyle = waveformStyle,
        onToggleWaveformStyle = onToggleWaveformStyle,
        onOpenSettings = onOpenSettings,
        onOpenProperties = onOpenProperties,
        modifier = modifier
    )
}

