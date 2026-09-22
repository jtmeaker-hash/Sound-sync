import sys

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'r') as f:
    lines = f.readlines()

start = -1
end = -1
for i, line in enumerate(lines):
    if "override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap =" in line:
        start = i
    if "private val albumArtworkSemaphore = kotlinx.coroutines.sync.Semaphore(3)" in line:
        end = i - 1
        break

if start == -1 or end == -1:
    print("Could not find bounds")
    sys.exit(1)

old_body = "".join(lines[start:end])

# Create new body
lines_inner = old_body.split('\n')
inner_body = '\n'.join(lines_inner[1:-2]) # drop first line and last brace

inner_body = inner_body.replace('decodeFileToBitmap(file, sizePx)', 'ensureActive()\n                        decodeFileToBitmap(file, sizePx)')
inner_body = inner_body.replace('decodeFileToBitmap(cachedFile, sizePx)', 'ensureActive()\n                    decodeFileToBitmap(cachedFile, sizePx)')
inner_body = inner_body.replace('decodeStreamToBitmap(stream, sizePx)', 'ensureActive()\n                    decodeStreamToBitmap(stream, sizePx)')
inner_body = inner_body.replace('extractEmbeddedPicture(context, track.filePath, sizePx)', 'ensureActive()\n        extractEmbeddedPicture(context, track.filePath, sizePx)')
inner_body = inner_body.replace('decodeByteArrayToBitmap(embeddedMeta.embeddedArtworkBytes, sizePx)', 'ensureActive()\n                decodeByteArrayToBitmap(embeddedMeta.embeddedArtworkBytes, sizePx)')
inner_body = inner_body.replace('decodeFileToBitmap(localResult.file, sizePx)', 'ensureActive()\n                decodeFileToBitmap(localResult.file, sizePx)')

import re
inner_body = re.sub(
    r'val fallbackKey = .*?\n        fallbackCache\.get\(fallbackKey\)\?\.let \{ return@withContext it \}\n        val generated = generateFallbackArtwork\(track, sizePx\)\n        fallbackCache\.put\(fallbackKey, generated\)\n        generated',
    'null',
    inner_body,
    flags=re.DOTALL
)

new_body = """    override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap = coroutineScope {
        val cacheKey = computeCacheKey(track, sizePx)
        memoryCache.get(cacheKey)?.let { return@coroutineScope it }
        val deferred = inFlightDecodes.getOrPut(cacheKey) {
            async(Dispatchers.IO) {
                val bitmap = getArtworkForTrackInternal(context, track, sizePx)
                if (bitmap != null) { memoryCache.put(cacheKey, bitmap) }
                bitmap
            }
        }
        val result = deferred.await()
        inFlightDecodes.remove(cacheKey)
        result ?: fallbackCache.get("fallback_${track.id}_$sizePx") ?: generateFallbackArtwork(track, sizePx).also { fallbackCache.put("fallback_${track.id}_$sizePx", it) }
    }

    private suspend fun getArtworkForTrackInternal(context: Context, track: Track, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
""" + inner_body + "\n    }\n"

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'w') as f:
    f.write("".join(lines[:start]) + new_body + "".join(lines[end:]))
print("Track patched")
