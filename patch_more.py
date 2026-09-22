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

# NowPlayingView.kt
replace_in_file('app/src/main/java/com/example/ui/components/NowPlayingView.kt', 'currentPositionMs = currentPositionMs', 'currentPositionProvider = currentPositionProvider')
replace_in_file('app/src/main/java/com/example/ui/components/NowPlayingView.kt', 'currentPositionMs: Long', 'currentPositionProvider: () -> Long')

# RekordboxWaveformView.kt
replace_in_file('app/src/main/java/com/example/ui/components/RekordboxWaveformView.kt', 'currentPositionMs / 1000', 'currentPositionProvider() / 1000')
replace_in_file('app/src/main/java/com/example/ui/components/RekordboxWaveformView.kt', 'currentPositionMs = currentPositionMs', 'currentPositionProvider = currentPositionProvider')

# TrackInspectorScreen.kt
replace_in_file('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'currentPositionMs = 0L', 'currentPositionProvider = { 0L }')

