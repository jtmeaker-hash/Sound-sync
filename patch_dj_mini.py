import re

with open('app/src/main/java/com/example/ui/components/DjMiniPlayer.kt', 'r') as f:
    content = f.read()

# Replace currentPositionMs: Long with currentPositionProvider: () -> Long in function signatures
content = re.sub(r'currentPositionMs:\s*Long,', 'currentPositionProvider: () -> Long,', content)

# Replace passing currentPositionMs = currentPositionMs with currentPositionProvider = currentPositionProvider
content = re.sub(r'currentPositionMs\s*=\s*currentPositionMs', 'currentPositionProvider = currentPositionProvider', content)

# But wait! Inside MiniPlayerTimeDisplay, we need to read it!
# Let's see MiniPlayerTimeDisplay signature
# fun MiniPlayerTimeDisplay(currentPositionProvider: () -> Long, durationMs: Long, color: Color)
content = re.sub(r'fun MiniPlayerTimeDisplay\(\n\s+currentPositionProvider: \(\) -> Long,', r'fun MiniPlayerTimeDisplay(\n    currentPositionProvider: () -> Long,', content)

# Inside MiniPlayerTimeDisplay, replace `val curSec = (currentPositionMs / 1000).toInt()` with `val curSec = (currentPositionProvider() / 1000).toInt()`
# Wait, let's just find `currentPositionMs` usage inside MiniWaveformProgressStrip and MiniPlayerTimeDisplay.
content = re.sub(r'currentPositionMs\.toFloat\(\)', 'currentPositionProvider().toFloat()', content)
content = re.sub(r'\(currentPositionMs / 1000\)\.toInt\(\)', '(currentPositionProvider() / 1000).toInt()', content)


with open('app/src/main/java/com/example/ui/components/DjMiniPlayer.kt', 'w') as f:
    f.write(content)

