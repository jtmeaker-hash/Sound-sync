import re

with open('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'r') as f:
    content = f.read()

content = re.sub(r'currentPositionMs:\s*Long,', 'currentPositionProvider: () -> Long,', content)
content = re.sub(r'currentPositionMs\s*=\s*currentPositionMs', 'currentPositionProvider = currentPositionProvider', content)

with open('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'w') as f:
    f.write(content)

