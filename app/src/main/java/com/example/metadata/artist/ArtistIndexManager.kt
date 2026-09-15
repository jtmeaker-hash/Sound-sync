package com.example.metadata.artist

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ArtistDao
import com.example.data.ArtistEntity
import com.example.data.TrackArtistEntity
import com.example.data.TrackEntity
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Manages the Artist library index, splitting collaborations into distinct individual artists
 * and linking each track to multiple artists without mutating underlying track metadata.
 */
class ArtistIndexManager(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val artistDao: ArtistDao = database.artistDao()

    companion object {
        private const val TAG = "ArtistIndexManager"

        @Volatile
        private var INSTANCE: ArtistIndexManager? = null

        fun getInstance(context: Context): ArtistIndexManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtistIndexManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun generateArtistId(name: String): String {
            val normalized = name.trim().lowercase(Locale.ROOT)
            val hash = normalized.hashCode().toLong().let { if (it < 0) -it else it }.toString(16)
            return "artist_$hash"
        }

        /**
         * Pure function to construct the domain Artist models from tracks and albums.
         * Used for high-performance in-memory state flows without blocking the database.
         */
        fun buildArtistsFromTracks(
            tracks: List<Track>,
            albums: List<Album> = emptyList()
        ): List<Artist> {
            val artistTrackMap = mutableMapOf<String, MutableList<Track>>()
            val artistDisplayNames = mutableMapOf<String, String>()

            for (track in tracks) {
                val individualArtists = ArtistCollaborationParser.splitArtists(track.artist)
                for (artistName in individualArtists) {
                    val norm = artistName.trim().lowercase(Locale.ROOT)
                    if (norm.isBlank()) continue
                    artistTrackMap.getOrPut(norm) { mutableListOf() }.add(track)

                    // Keep best capitalization (prefer uppercase characters)
                    val existing = artistDisplayNames[norm]
                    if (existing == null || artistName.count { it.isUpperCase() } > existing.count { it.isUpperCase() }) {
                        artistDisplayNames[norm] = artistName
                    }
                }
            }

            val albumMap = albums.associateBy { it.title.trim().lowercase(Locale.ROOT) }

            return artistTrackMap.map { (normKey, songs) ->
                val displayName = artistDisplayNames[normKey] ?: songs.firstOrNull()?.artist ?: "Unknown Artist"
                val distinctSongList = songs.distinctBy { it.id }.sortedBy { it.title.lowercase(Locale.ROOT) }
                val distinctAlbumTitles = distinctSongList.map { it.album.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotBlank() && it != "single" && it != "<unknown>" }
                    .distinct()

                val matchedAlbums = distinctAlbumTitles.mapNotNull { albumMap[it] }
                val totalSec = distinctSongList.sumOf { it.durationSeconds }

                Artist(
                    id = generateArtistId(displayName),
                    name = displayName,
                    albumCount = distinctAlbumTitles.size,
                    songCount = distinctSongList.size,
                    totalDurationSeconds = totalSec,
                    albums = matchedAlbums,
                    songs = distinctSongList
                )
            }.sortedBy { if (it.name.equals("Unknown Artist", ignoreCase = true)) "zzzz" else it.name.lowercase(Locale.ROOT) }
        }
    }

    /**
     * Completely rebuilds the persistent Artist index in Room from the current library tracks.
     */
    suspend fun rebuildIndex(tracks: List<TrackEntity>) = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        val artistMap = mutableMapOf<String, MutableList<TrackEntity>>()
        val artistDisplayNames = mutableMapOf<String, String>()

        for (track in tracks) {
            val artists = ArtistCollaborationParser.splitArtists(track.artist)
            for (artist in artists) {
                val norm = artist.trim().lowercase(Locale.ROOT)
                if (norm.isBlank()) continue
                artistMap.getOrPut(norm) { mutableListOf() }.add(track)
                val existing = artistDisplayNames[norm]
                if (existing == null || artist.count { it.isUpperCase() } > existing.count { it.isUpperCase() }) {
                    artistDisplayNames[norm] = artist
                }
            }
        }

        val artistEntities = mutableListOf<ArtistEntity>()
        val trackArtistEntities = mutableListOf<TrackArtistEntity>()

        for ((norm, trackList) in artistMap) {
            val displayName = artistDisplayNames[norm] ?: norm
            val artistId = generateArtistId(displayName)
            val distinctTracks = trackList.distinctBy { it.id }
            val distinctAlbums = distinctTracks.map { it.album.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() && it != "single" && it != "<unknown>" }
                .distinct()
            val totalSec = distinctTracks.sumOf { it.durationSeconds }

            artistEntities.add(
                ArtistEntity(
                    id = artistId,
                    name = displayName,
                    normalizedName = norm,
                    songCount = distinctTracks.size,
                    albumCount = distinctAlbums.size,
                    totalDurationSeconds = totalSec,
                    updatedAt = System.currentTimeMillis()
                )
            )

            for (track in distinctTracks) {
                trackArtistEntities.add(
                    TrackArtistEntity(
                        trackId = track.id,
                        artistId = artistId,
                        artistName = displayName,
                        role = "PRIMARY"
                    )
                )
            }
        }

        try {
            artistDao.clearTrackArtists()
            artistDao.clearArtists()
            artistDao.insertArtists(artistEntities)
            artistDao.insertTrackArtists(trackArtistEntities)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            Log.i(TAG, "Rebuilt artist index in ${elapsedMs}ms (${artistEntities.size} artists, ${trackArtistEntities.size} track-artist links)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild artist index", e)
        }
    }

    /**
     * Incrementally updates the artist index for a single track.
     */
    suspend fun indexTrack(track: TrackEntity) = withContext(Dispatchers.IO) {
        val artists = ArtistCollaborationParser.splitArtists(track.artist)
        artistDao.deleteTrackArtistsForTrack(track.id)

        val newLinks = mutableListOf<TrackArtistEntity>()
        for (artistName in artists) {
            val norm = artistName.trim().lowercase(Locale.ROOT)
            if (norm.isBlank()) continue
            val artistId = generateArtistId(artistName)
            val existing = artistDao.getArtistById(artistId)
            if (existing == null) {
                artistDao.insertArtist(
                    ArtistEntity(
                        id = artistId,
                        name = artistName,
                        normalizedName = norm,
                        songCount = 1,
                        albumCount = if (track.album.isNotBlank() && !track.album.equals("single", ignoreCase = true)) 1 else 0,
                        totalDurationSeconds = track.durationSeconds
                    )
                )
            }
            newLinks.add(
                TrackArtistEntity(
                    trackId = track.id,
                    artistId = artistId,
                    artistName = artistName,
                    role = "PRIMARY"
                )
            )
        }
        artistDao.insertTrackArtists(newLinks)
        artistDao.deleteOrphanArtists()
    }
}
