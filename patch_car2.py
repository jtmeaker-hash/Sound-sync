import re

with open('app/src/main/java/com/example/carmode/CarModeScreen.kt', 'r') as f:
    content = f.read()

content = content.replace('val currentPositionMs by audioEngine.currentPositionMs.collectAsState()', 'val currentPositionState = audioEngine.currentPositionMs.collectAsState()')
content = content.replace('currentPositionProvider = currentPositionProvider', 'currentPositionProvider = { currentPositionState.value }')
content = content.replace('currentPositionMs.toFloat()', 'currentPositionProvider().toFloat()')
content = content.replace('formatTime(currentPositionMs)', 'formatTime(currentPositionProvider())')

with open('app/src/main/java/com/example/carmode/CarModeScreen.kt', 'w') as f:
    f.write(content)

