import re

with open('app/src/main/java/com/example/ui/components/NowPlayingFullScreen.kt', 'r') as f:
    content = f.read()

content = re.sub(r'currentPositionMs:\s*Long,', 'currentPositionProvider: () -> Long,', content)
content = re.sub(r'currentPositionMs\s*=\s*currentPositionMs', 'currentPositionProvider = currentPositionProvider', content)
content = re.sub(r'currentPositionMs\.toFloat\(\)', 'currentPositionProvider().toFloat()', content)
content = re.sub(r'\(currentPositionMs / 1000\)\.toInt\(\)', '(currentPositionProvider() / 1000).toInt()', content)

with open('app/src/main/java/com/example/ui/components/NowPlayingFullScreen.kt', 'w') as f:
    f.write(content)

