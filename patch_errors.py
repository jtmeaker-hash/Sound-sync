import re

with open('app/src/main/java/com/example/ui/components/NowPlayingFullScreen.kt', 'r') as f:
    content = f.read()

content = re.sub(r'currentPositionMs / 1000', 'currentPositionProvider() / 1000', content)
content = re.sub(r'currentPositionProvider = currentPositionProvider', 'currentPositionMs = currentPositionProvider()', content)
content = re.sub(r'currentPositionMs\.toFloat', 'currentPositionProvider().toFloat', content)
# wait, for 'currentPositionMs = currentPositionProvider()', if the child component expects currentPositionMs, I didn't change it!
# what did the compiler say?
# No parameter with name 'currentPositionProvider' found.
# No value passed for parameter 'currentPositionMs'.
content = re.sub(r'currentPositionProvider = currentPositionProvider\(\)', 'currentPositionMs = currentPositionProvider()', content)

# I will just write a script to fix NowPlayingFullScreen.kt
