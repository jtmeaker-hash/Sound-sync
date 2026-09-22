import re

with open('/tmp/AlbumArtHelper.kt', 'r') as f:
    content = f.read()

# Add imports
imports = """import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
"""
content = re.sub(r'import java.util.Locale\n', f'import java.util.Locale\n{imports}', content)

content = re.sub(
    r'val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow\(\)\n',
    'val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow()\n\n    private val inFlightDecodes = ConcurrentHashMap<String, Deferred<Bitmap?>>()\n',
    content
)

# Fix getArtworkForTrack
track_func_regex = r'override suspend fun getArtworkForTrack\(context: Context, track: Track, sizePx: Int\): Bitmap = withContext\(Dispatchers\.IO\) \{.*?\n    \}'
track_match = re.search(track_func_regex, content, re.DOTALL)
if track_match:
    orig_body = track_match.group(0)
    
    # We strip the first line and last brace
    lines = orig_body.split('\n')
    inner_body = '\n'.join(lines[1:-1])
    
    new_func = """override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap = coroutineScope {
        val cacheKey = computeCacheKey(track, sizePx)
        memoryCache.get(cacheKey)?.let { return@coroutineScope it }
        
        val deferred = inFlightDecodes.getOrPut(cacheKey) {
            async(Dispatchers.IO) { getArtworkForTrackInternal(context, track, sizePx, cacheKey) }
        }
        val bitmap = deferred.await()
        inFlightDecodes.remove(cacheKey)
        bitmap ?: generateFallbackArtwork(track, sizePx)
    }

    private suspend fun getArtworkForTrackInternal(context: Context, track: Track, sizePx: Int, cacheKey: String): Bitmap? = withContext(Dispatchers.IO) {"""
    
    # Replace returns inside inner_body
    inner_body = inner_body.replace('return@withContext cacheDecodedArtwork(track, cacheKey, decoded)', 'return@withContext cacheDecodedArtwork(track, cacheKey, decoded)')
    
    # Add ensureActive() before decodes
    inner_body = inner_body.replace('decodeFileToBitmap(file, sizePx)', 'ensureActive()\n                        decodeFileToBitmap(file, sizePx)')
    inner_body = inner_body.replace('decodeFileToBitmap(cachedFile, sizePx)', 'ensureActive()\n                    decodeFileToBitmap(cachedFile, sizePx)')
    inner_body = inner_body.replace('decodeFileToBitmap(cFile, sizePx)', 'ensureActive()\n                    decodeFileToBitmap(cFile, sizePx)')
    inner_body = inner_body.replace('extractEmbeddedPicture(context, resolvedUri, sizePx)', 'ensureActive()\n        extractEmbeddedPicture(context, resolvedUri, sizePx)')
    inner_body = inner_body.replace('decodeFileToBitmap(localFolderArt, sizePx)', 'ensureActive()\n            decodeFileToBitmap(localFolderArt, sizePx)')
    
    # Remove fallback caching at the end
    inner_body = re.sub(
        r'val fallback = generateFallbackArtwork\(track, sizePx\)\n        fallbackCache\.put\(cacheKey, fallback\)\n        fallback',
        'null',
        inner_body
    )
    
    new_func += '\n' + inner_body + '\n    }'
    content = content.replace(orig_body, new_func)


# Fix getArtworkForAlbum
album_func_regex = r'override suspend fun getArtworkForAlbum\(context: Context, album: Album, sizePx: Int\): Bitmap = withContext\(Dispatchers\.IO\) \{.*?\n        \}\n    \}'
album_match = re.search(album_func_regex, content, re.DOTALL)
if album_match:
    orig_body = album_match.group(0)
    lines = orig_body.split('\n')
    inner_body = '\n'.join(lines[1:-1])
    
    new_func = """override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap = coroutineScope {
        val safeSize = sizePx.coerceIn(64, 512)
        val cacheKey = computeAlbumCacheKey(album, safeSize)
        memoryCache.get(cacheKey)?.let { return@coroutineScope it }
        
        val deferred = inFlightDecodes.getOrPut(cacheKey) {
            async(Dispatchers.IO) { getArtworkForAlbumInternal(context, album, safeSize, cacheKey) }
        }
        val bitmap = deferred.await()
        inFlightDecodes.remove(cacheKey)
        bitmap ?: generateFallbackArtwork(album.title, album.artist, album.id, safeSize)
    }

    private suspend fun getArtworkForAlbumInternal(context: Context, album: Album, safeSize: Int, cacheKey: String): Bitmap? = withContext(Dispatchers.IO) {"""
    
    # Remove semaphore logic
    inner_body = re.sub(r'albumArtworkSemaphore\.acquire\(\)\n        try \{\n            // Re-check cache after acquiring semaphore\n            memoryCache\.get\(cacheKey\)\?\.let \{ return@withContext it \}\n', 'ensureActive()\n', inner_body)
    inner_body = inner_body.replace('decodeFileToBitmap(file, safeSize)', 'ensureActive()\n                            decodeFileToBitmap(file, safeSize)')
    inner_body = inner_body.replace('decodeStreamToBitmap(stream, safeSize)', 'ensureActive()\n                            decodeStreamToBitmap(stream, safeSize)')
    inner_body = inner_body.replace('decodeFileToBitmap(cachedFile, safeSize)', 'ensureActive()\n                    decodeFileToBitmap(cachedFile, safeSize)')
    inner_body = inner_body.replace('decodeFileToBitmap(File(path), safeSize)', 'ensureActive()\n                        decodeFileToBitmap(File(path), safeSize)')
    inner_body = inner_body.replace('decodeFileToBitmap(coverFile, safeSize)', 'ensureActive()\n                            decodeFileToBitmap(coverFile, safeSize)')
    
    # Replace end logic
    inner_body = re.sub(
        r'val fallback = generateFallbackArtwork\(album\.title, album\.artist, album\.id, safeSize\)\n            fallbackCache\.put\(cacheKey, fallback\)\n            fallback\n        \} finally \{\n            albumArtworkSemaphore\.release\(\)',
        'null',
        inner_body
    )
    
    # Remove the first 3 lines of inner_body (val safeSize, val cacheKey, memoryCache.get) since they are in outer
    # Wait, actually inner_body starts with "val safeSize = ..."
    inner_lines = inner_body.split('\n')
    if "val safeSize" in inner_lines[0]:
        inner_lines = inner_lines[3:]
    inner_body = '\n'.join(inner_lines)
    
    new_func += '\n' + inner_body + '\n    }'
    
    content = content.replace(orig_body, new_func)

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'w') as f:
    f.write(content)
print("Done!")
