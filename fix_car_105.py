import re

with open('app/src/main/java/com/example/carmode/CarModeScreen.kt', 'r') as f:
    content = f.read()

# at line 105 and 145 approx
content = re.sub(r'displayMode = displayMode,\s*currentPositionProvider = currentPositionProvider,',
                 'displayMode = displayMode,\n                currentPositionProvider = { currentPositionState.value },',
                 content)

with open('app/src/main/java/com/example/carmode/CarModeScreen.kt', 'w') as f:
    f.write(content)

