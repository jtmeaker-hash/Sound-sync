import re

with open('app/src/main/java/com/example/ui/components/SpectrogramAnalyzerView.kt', 'r') as f:
    content = f.read()

content = re.sub(r'currentPositionSec:\s*Int\s*=\s*0,', 'currentPositionSecProvider: () -> Int = { 0 },', content)
content = re.sub(r'currentPositionSec:\s*Int,', 'currentPositionSecProvider: () -> Int,', content)
content = re.sub(r'currentPositionSec\s*=\s*currentPositionSec', 'currentPositionSecProvider = currentPositionSecProvider', content)
content = re.sub(r'currentPositionSec / 60', 'currentPositionSecProvider() / 60', content)
content = re.sub(r'currentPositionSec % 60', 'currentPositionSecProvider() % 60', content)
content = re.sub(r'playbackProgress:\s*Float\s*=\s*0f,', 'playbackProgressProvider: () -> Float = { 0f },', content)
content = re.sub(r'playbackProgress:\s*Float,', 'playbackProgressProvider: () -> Float,', content)
content = re.sub(r'playbackProgress\s*=\s*playbackProgress', 'playbackProgressProvider = playbackProgressProvider', content)
# It's used in Canvas probably. Let's see how playbackProgress is used.
content = re.sub(r'val playheadX\s*=\s*playbackProgress\s*\*\s*width', 'val playheadX = playbackProgressProvider() * width', content)
# any other usages? Let's check visually later.

with open('app/src/main/java/com/example/ui/components/SpectrogramAnalyzerView.kt', 'w') as f:
    f.write(content)

