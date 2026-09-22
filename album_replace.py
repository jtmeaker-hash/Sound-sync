import sys

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'r') as f:
    lines = f.readlines()

start = -1
end = -1
for i, line in enumerate(lines):
    if "override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap =" in line:
        start = i
    if "override fun invalidateTrack(trackId: String, artist: String?, album: String?)" in line:
        end = i - 1
        break

if start == -1 or end == -1:
    print("Could not find bounds")
    sys.exit(1)

old_body = "".join(lines[start:end])

lines_inner = old_body.split('\n')
inner_body = '\n'.join(lines_inner[1:-2]) # drop first line and last brace

import re
inner_body = re.sub(
    r'albumArtworkSemaphore\.acquire\(\)\n        try \{\n            // Re-check cache after acquiring semaphore\n            memoryCache\.get\(cacheKey\)\?\.let \{ return@withContext it \}',
    'ensureActive()',
    inner_body
)
inner_body = re.sub(
    r'val fallbackKey = .*?\n            fallbackCache\.get\(fallbackKey\)\?\.let \{ return@withContext it \}\n            val generated = generateFallbackArtwork\(.*?\n            fallbackCache\.put\(fallbackKey, generated\)\n            generated',
    'null',
    inner_body,
    flags=re.DOTALL
)

inner_body = inner_body.replace('decodeFileToBitmap(file, safeSize)', 'ensureActive()\n                            decodeFileToBitmap(file, safeSize)')
inner_body = inner_body.replace('decodeStreamToBitmap(stream, safeSize)', 'ensureActive()\n                            decodeStreamToBitmap(stream, safeSize)')
inner_body = inner_body.replace('decodeFileToBitmap(cachedFile, safeSize)', 'ensureActive()\n                    decodeFileToBitmap(cachedFile, safeSize)')
inner_body = inner_body.replace('decodeFileToBitmap(File(path), safeSize)', 'ensureActive()\n                        decodeFileToBitmap(File(path), safeSize)')
inner_body = inner_body.replace('decodeFileToBitmap(coverFile, safeSize)', 'ensureActive()\n                            decodeFileToBitmap(coverFile, safeSize)')

inner_body = re.sub(
    r'\} catch \(oom: OutOfMemoryError\) \{.*?\n        \} catch \(t: Throwable\) \{.*?\n        \} finally \{\n            albumArtworkSemaphore\.release\(\)\n        \}',
    '} catch (oom: OutOfMemoryError) { null } catch (t: Throwable) { null }',
    inner_body,
    flags=re.DOTALL
)

new_body = """    override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap = coroutineScope {
        val safeSize = sizePx.coerceIn(64, 512)
        val cacheKey = computeAlbumCacheKey(album, safeSize)
        memoryCache.get(cacheKey)?.let { return@coroutineScope it }
        val deferred = inFlightDecodes.getOrPut(cacheKey) {
            async(Dispatchers.IO) {
                val bitmap = getArtworkForAlbumInternal(context, album, safeSize)
                if (bitmap != null) { memoryCache.put(cacheKey, bitmap) }
                bitmap
            }
        }
        val result = deferred.await()
        inFlightDecodes.remove(cacheKey)
        result ?: fallbackCache.get("fallback_album_${album.id}_$safeSize") ?: generateFallbackArtwork(album.title, album.artist, album.id, safeSize).also { fallbackCache.put("fallback_album_${album.id}_$safeSize", it) }
    }

    private suspend fun getArtworkForAlbumInternal(context: Context, album: Album, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
""" + inner_body + "\n    }\n\n"

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'w') as f:
    f.write("".join(lines[:start]) + new_body + "".join(lines[end:]))
print("Album patched")
