import re

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'r') as f:
    text = f.read()

imports = """import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
"""
text = text.replace('import java.util.Locale\n', f'import java.util.Locale\n{imports}')

text = text.replace(
    'val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow()\n',
    'val artworkInvalidationFlow: SharedFlow<String> = _artworkInvalidationFlow.asSharedFlow()\n\n    private val inFlightDecodes = ConcurrentHashMap<String, Deferred<Bitmap?>>()\n'
)

# getArtworkForTrack
old_func = re.search(r'override suspend fun getArtworkForTrack\(context: Context, track: Track, sizePx: Int\): Bitmap = withContext\(Dispatchers\.IO\) \{.*?\n        generated\n    \}', text, re.DOTALL)
if old_func:
    inner = old_func.group(0)
    inner = inner.replace('override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap = withContext(Dispatchers.IO) {', 'private suspend fun getArtworkForTrackInternal(context: Context, track: Track, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {')
    inner = inner.replace('decodeFileToBitmap', 'ensureActive()\n        decodeFileToBitmap')
    inner = inner.replace('decodeStreamToBitmap', 'ensureActive()\n        decodeStreamToBitmap')
    inner = inner.replace('decodeByteArrayToBitmap', 'ensureActive()\n        decodeByteArrayToBitmap')
    inner = inner.replace('extractEmbeddedPicture', 'ensureActive()\n        extractEmbeddedPicture')
    inner = re.sub(r'val fallbackKey = .*?\n        fallbackCache\.get\(fallbackKey\)\?\.let \{ return@withContext it \}\n        val generated = generateFallbackArtwork\(track, sizePx\)\n        fallbackCache\.put\(fallbackKey, generated\)\n        generated', 'null', inner, flags=re.DOTALL)
    
    wrapper = """override suspend fun getArtworkForTrack(context: Context, track: Track, sizePx: Int): Bitmap = coroutineScope {
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
"""
    text = text.replace(old_func.group(0), wrapper + '\n    ' + inner)

# getArtworkForAlbum
old_album = re.search(r'override suspend fun getArtworkForAlbum\(context: Context, album: Album, sizePx: Int\): Bitmap = withContext\(Dispatchers\.IO\) \{.*?\n        \} finally \{\n            albumArtworkSemaphore\.release\(\)\n        \}\n    \}', text, re.DOTALL)
if old_album:
    inner = old_album.group(0)
    inner = inner.replace('override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap = withContext(Dispatchers.IO) {', 'private suspend fun getArtworkForAlbumInternal(context: Context, album: Album, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {')
    inner = inner.replace('decodeFileToBitmap', 'ensureActive()\n        decodeFileToBitmap')
    inner = inner.replace('decodeStreamToBitmap', 'ensureActive()\n        decodeStreamToBitmap')
    inner = inner.replace('memoryCache.put(cacheKey, decoded)', '') # Wrapper does it
    inner = re.sub(r'val fallback = generateFallbackArtwork\(.*?\n            fallbackCache\.put\(cacheKey, fallback\)\n            fallback', 'null', inner, flags=re.DOTALL)
    
    wrapper = """override suspend fun getArtworkForAlbum(context: Context, album: Album, sizePx: Int): Bitmap = coroutineScope {
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
"""
    text = text.replace(old_album.group(0), wrapper + '\n    ' + inner)

with open('app/src/main/java/com/example/util/AlbumArtHelper.kt', 'w') as f:
    f.write(text)
print("Done rewriting")
