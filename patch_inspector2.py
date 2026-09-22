import re

with open('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'r') as f:
    content = f.read()

content = content.replace('val currentPositionMs = audioEngine.currentPositionMs.collectAsState().value', 'val currentPositionState = audioEngine.currentPositionMs.collectAsState()')
content = content.replace('currentPositionMs = if (isPlayingThisTrack) currentPositionMs else 0L,', 'currentPositionProvider = if (isPlayingThisTrack) { { currentPositionState.value } } else { { 0L } },')

with open('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'w') as f:
    f.write(content)

