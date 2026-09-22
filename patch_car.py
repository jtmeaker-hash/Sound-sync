import re

def replace_in_file(file_path, old_str, new_str):
    try:
        with open(file_path, 'r') as f:
            content = f.read()
        content = content.replace(old_str, new_str)
        with open(file_path, 'w') as f:
            f.write(content)
    except:
        pass

replace_in_file('app/src/main/java/com/example/carmode/CarModeScreen.kt', 'currentPositionMs = currentPositionMs', 'currentPositionProvider = { currentPositionMs }')

