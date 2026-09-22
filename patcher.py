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

# Add inFlightDecodes map
content = re.sub(
    r'val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow\(\)\n',
    'val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow()\n\n    private val inFlightDecodes = ConcurrentHashMap<String, Deferred<Bitmap?>>()\n',
    content
)

# Replace getArtworkForTrack
track_orig = r'override suspend fun getArtworkForTrack\(context: Context, track: Track, sizePx: Int\): Bitmap = withContext\(Dispatchers\.IO\) \{'
track_new = """override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap = coroutineScope {
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
content = re.sub(track_orig, track_new, content)

# Inside getArtworkForTrackInternal, change return@withContext cacheDecodedArtwork(track, cacheKey, decoded) to cacheDecodedArtwork(track, cacheKey, decoded)
# Actually, the original function returns fallback at the end:
#         val fallback = generateFallbackArtwork(track, sizePx)
#         fallbackCache.put(cacheKey, fallback)
#         fallback
# We need to change that to `null`.
content = re.sub(
    r'val fallback = generateFallbackArtwork\(track, sizePx\)\n        fallbackCache\.put\(cacheKey, fallback\)\n        fallback',
    'null',
    content
)

# Now, add ensureActive() before decodeFileToBitmap and extractEmbeddedPicture
content = content.replace(
    'decodeFileToBitmap(file, sizePx)',
    'ensureActive()\n                        decodeFileToBitmap(file, sizePx)'
)
content = content.replace(
    'decodeFileToBitmap(cachedFile, sizePx)',
    'ensureActive()\n                    decodeFileToBitmap(cachedFile, sizePx)'
)
content = content.replace(
    'decodeFileToBitmap(cFile, sizePx)',
    'ensureActive()\n                    decodeFileToBitmap(cFile, sizePx)'
)
content = content.replace(
    'extractEmbeddedPicture(context, resolvedUri, sizePx)',
    'ensureActive()\n        extractEmbeddedPicture(context, resolvedUri, sizePx)'
)
content = content.replace(
    'decodeFileToBitmap(localFolderArt, sizePx)',
    'ensureActive()\n            decodeFileToBitmap(localFolderArt, sizePx)'
)

# Replace getArtworkForAlbum
album_orig = r'override suspend fun getArtworkForAlbum\(context: Context, album: Album, sizePx: Int\): Bitmap = withContext\(Dispatchers\.IO\) \{'
album_new = """override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap = coroutineScope {
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
content = re.sub(album_orig, album_new, content)

# Remove semaphore in getArtworkForAlbumInternal
content = re.sub(
    r'albumArtworkSemaphore\.acquire\(\)\n        try \{\n            // Re-check cache after acquiring semaphore\n            memoryCache\.get\(cacheKey\)\?\.let \{ return@withContext it \}\n',
    'ensureActive()\n',
    content
)
content = re.sub(
    r'val fallback = generateFallbackArtwork\(album\.title, album\.artist, album\.id, safeSize\)\n            fallbackCache\.put\(cacheKey, fallback\)\n            fallback\n        \} finally \{\n            albumArtworkSemaphore\.release\(\)\n        \}',
    'null',
    content
)

# Save
with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'w') as f:
    f.write(content)

print("Patched!")
