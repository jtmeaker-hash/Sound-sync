import re

with open('app/src/main/java/com/example/ui/components/RekordboxWaveformView.kt', 'r') as f:
    content = f.read()

content = re.sub(r'currentPositionMs:\s*Long,', 'currentPositionProvider: () -> Long,', content)
content = re.sub(r'currentPositionMs\.toFloat\(\)', 'currentPositionProvider().toFloat()', content)

with open('app/src/main/java/com/example/ui/components/RekordboxWaveformView.kt', 'w') as f:
    f.write(content)

