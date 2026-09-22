import re

with open('app/src/main/java/com/example/ui/MainDjScreen.kt', 'r') as f:
    content = f.read()

# For PositionAwareMiniPlayer:
# val currentPositionMs = audioEngine.currentPositionMs.collectAsState().value
# becomes val currentPositionState = audioEngine.currentPositionMs.collectAsState()
# currentPositionMs = currentPositionMs
# becomes currentPositionProvider = { currentPositionState.value }
content = re.sub(r'val currentPositionMs = audioEngine\.currentPositionMs\.collectAsState\(\)\.value\n\s+DjMiniPlayer\(\n\s+track = track,\n\s+displayMode = displayMode,\n\s+waveformData = waveformData,\n\s+isPlaying = isPlaying,\n\s+currentPositionMs = currentPositionMs,', 
r'''val currentPositionState = audioEngine.currentPositionMs.collectAsState()
    DjMiniPlayer(
        track = track,
        displayMode = displayMode,
        waveformData = waveformData,
        isPlaying = isPlaying,
        currentPositionProvider = { currentPositionState.value },''', content)

# Same for NowPlayingFullScreen:
content = re.sub(r'val currentPositionMs = audioEngine\.currentPositionMs\.collectAsState\(\)\.value\n\s+NowPlayingFullScreen\(\n\s+track = track,\n\s+displayMode = displayMode,\n\s+waveformData = waveformData,\n\s+isWaveformLoading = isWaveformLoading,\n\s+isPlaying = isPlaying,\n\s+currentPositionMs = currentPositionMs,',
r'''val currentPositionState = audioEngine.currentPositionMs.collectAsState()
    NowPlayingFullScreen(
        track = track,
        displayMode = displayMode,
        waveformData = waveformData,
        isWaveformLoading = isWaveformLoading,
        isPlaying = isPlaying,
        currentPositionProvider = { currentPositionState.value },''', content)

# For PositionAwareSpectrogramTab:
content = re.sub(r'val currentPositionSec = audioEngine\.currentPositionSec\.collectAsState\(\)\.value\n\s+val playbackProgress = audioEngine\.playbackProgress\.collectAsState\(\)\.value\n\s+val analysisProgressPercent by analysisProgressFlow\.collectAsState\(\)\n\s+SpectrogramAnalyzerView\(\n\s+analyzedTrack = analyzedTrack,\n\s+spectrogramData = spectrogramData,\n\s+allTracks = allTracks,\n\s+isPlaying = isPlaying,\n\s+currentPositionSec = currentPositionSec,\n\s+playbackProgress = playbackProgress,',
r'''val currentPositionSecState = audioEngine.currentPositionSec.collectAsState()
    val playbackProgressState = audioEngine.playbackProgress.collectAsState()
    val analysisProgressPercent by analysisProgressFlow.collectAsState()
    SpectrogramAnalyzerView(
        analyzedTrack = analyzedTrack,
        spectrogramData = spectrogramData,
        allTracks = allTracks,
        isPlaying = isPlaying,
        currentPositionSecProvider = { currentPositionSecState.value },
        playbackProgressProvider = { playbackProgressState.value },''', content)

with open('app/src/main/java/com/example/ui/MainDjScreen.kt', 'w') as f:
    f.write(content)

