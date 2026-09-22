with open('app/src/main/java/com/example/ui/MainDjViewModel.kt', 'r') as f:
    content = f.read()

# Add trackEntityCache
content = content.replace(
    'private val playlistTrackDao = appDatabase.playlistTrackDao()',
    'private val playlistTrackDao = appDatabase.playlistTrackDao()\n    private val trackEntityCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, com.example.model.Track>>()'
)

orig_map = '''        val tracks = entities.map { entity ->
            val track = entity.toTrack()
            val isAvail = com.example.storage.StorageAvailabilityHelper.isTrackRootAvailable(track.filePath, rootAvailability)
            if (track.isAvailable == isAvail) track else track.copy(isAvailable = isAvail)
        }'''

new_map = '''        val tracks = entities.map { entity ->
            val hash = entity.hashCode()
            val cached = trackEntityCache[entity.id]
            val track = if (cached != null && cached.first == hash) {
                cached.second
            } else {
                val newTrack = entity.toTrack()
                trackEntityCache[entity.id] = Pair(hash, newTrack)
                newTrack
            }
            val isAvail = com.example.storage.StorageAvailabilityHelper.isTrackRootAvailable(track.filePath, rootAvailability)
            if (track.isAvailable == isAvail) track else track.copy(isAvailable = isAvail)
        }
        
        if (entities.size < trackEntityCache.size - 100) {
            val currentIds = entities.map { it.id }.toSet()
            val it = trackEntityCache.keys().iterator()
            while (it.hasNext()) {
                if (!currentIds.contains(it.next())) it.remove()
            }
        }'''

content = content.replace(orig_map, new_map)

with open('app/src/main/java/com/example/ui/MainDjViewModel.kt', 'w') as f:
    f.write(content)
print("Patched VM!")
