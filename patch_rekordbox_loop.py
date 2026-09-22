import re

with open('app/src/main/java/com/example/ui/components/RekordboxWaveformView.kt', 'r') as f:
    content = f.read()

# Remove the LaunchedEffect(currentPositionMs, track.id) block entirely.
content = re.sub(r'\s*// Synchronize anchor with authoritative audio engine updates\s*LaunchedEffect\(currentPositionMs, track\.id\) \{[\s\S]*?\}\s*// 60fps/120fps display refresh rate loop', '\n    // 60fps/120fps display refresh rate loop', content)

# Rewrite the loop
loop_old = r'''    LaunchedEffect\(isPlaying, track\.id\) \{
        if \(\!isPlaying\) \{
            animatedPositionMs = currentPositionProvider\(\)\.toFloat\(\)
            return@LaunchedEffect
        \}
        while \(true\) \{
            withFrameNanos \{ frameNanos ->
                if \(\!isUserDragging\) \{
                    val elapsedSec = \(frameNanos - anchorNanoTime\) / 1_000_000_000f
                    val estimatedMs = anchorPositionMs \+ \(elapsedSec \* 1000f\)
                    animatedPositionMs = estimatedMs\.coerceIn\(0f, safeDurationMs\.toFloat\(\)\)
                \}
            \}
        \}
    \}'''

loop_new = '''    var lastIncoming by remember(track.id) { mutableFloatStateOf(currentPositionProvider().toFloat()) }
    LaunchedEffect(isPlaying, track.id) {
        if (!isPlaying) {
            animatedPositionMs = currentPositionProvider().toFloat()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { frameNanos ->
                val incoming = currentPositionProvider().toFloat()
                if (incoming != lastIncoming) {
                    val diff = kotlin.math.abs(incoming - animatedPositionMs)
                    if (diff > 120f) {
                        anchorPositionMs = incoming
                        anchorNanoTime = frameNanos
                        animatedPositionMs = incoming
                    } else {
                        anchorPositionMs = incoming
                        anchorNanoTime = frameNanos
                    }
                    lastIncoming = incoming
                }
                
                if (!isUserDragging) {
                    val elapsedSec = (frameNanos - anchorNanoTime) / 1_000_000_000f
                    val estimatedMs = anchorPositionMs + (elapsedSec * 1000f)
                    animatedPositionMs = estimatedMs.coerceIn(0f, safeDurationMs.toFloat())
                }
            }
        }
    }'''

content = re.sub(loop_old, loop_new, content)

with open('app/src/main/java/com/example/ui/components/RekordboxWaveformView.kt', 'w') as f:
    f.write(content)

