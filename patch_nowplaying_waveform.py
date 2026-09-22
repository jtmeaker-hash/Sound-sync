import re

with open('app/src/main/java/com/example/ui/components/NowPlayingFullScreen.kt', 'r') as f:
    content = f.read()

content = re.sub(r'fun NowPlayingWaveform\(\n\s+track: Track,\n\s+waveformData: WaveformData\?,\n\s+isPlaying: Boolean,\n\s+currentPositionMs: Long,',
r'''fun NowPlayingWaveform(
    track: Track,
    waveformData: WaveformData?,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,''', content)

content = re.sub(r'fun NowPlayingCompactTimeDisplay\(\n\s+currentPositionMs: Long,',
r'''fun NowPlayingCompactTimeDisplay(
    currentPositionProvider: () -> Long,''', content)


with open('app/src/main/java/com/example/ui/components/NowPlayingFullScreen.kt', 'w') as f:
    f.write(content)

