with open('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'r') as f:
    text = f.read()

bad_block = """    var waveformData by remember { mutableStateOf<WaveformData?>(null) }
    LaunchedEffect(initialTrack.id) {
        isWaveformLoading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            waveformData = WaveformCache.get(WaveformCache.getCacheKey(initialTrack, context), context)
        }
        isWaveformLoading = false
    }
    var isWaveformLoading by remember { mutableStateOf(false) }"""

good_block = """    var waveformData by remember { mutableStateOf<WaveformData?>(null) }
    var isWaveformLoading by remember { mutableStateOf(false) }
    LaunchedEffect(initialTrack.id) {
        isWaveformLoading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            waveformData = WaveformCache.get(WaveformCache.getCacheKey(initialTrack, context), context)
        }
        isWaveformLoading = false
    }"""

text = text.replace(bad_block, good_block)
with open('app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt', 'w') as f:
    f.write(text)
print("Fixed inspector")
