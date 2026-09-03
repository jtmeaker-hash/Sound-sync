package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    suspend fun getAllPlaylistsSync(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylistById(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistByIdSync(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position ASC")
    fun getAllPlaylistTracks(): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getTracksForPlaylist(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getTracksForPlaylistSync(playlistId: String): List<PlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistEntityById(id: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deletePlaylistTracks(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND position = :position")
    suspend fun deleteTrackAtPosition(playlistId: String, position: Int)

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackCountForPlaylist(playlistId: String): Int

    @Query("""
        SELECT p.* FROM playlists p
        INNER JOIN playlist_tracks pt ON p.id = pt.playlistId
        WHERE pt.trackId = :trackId
    """)
    suspend fun getPlaylistsContainingTrack(trackId: String): List<PlaylistEntity>

    @Query("""
        SELECT p.* FROM playlists p
        INNER JOIN playlist_tracks pt ON p.id = pt.playlistId
        WHERE pt.trackId = :trackId
    """)
    fun observePlaylistsContainingTrack(trackId: String): Flow<List<PlaylistEntity>>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deleteTrackFromPlaylist(playlistId: String, trackId: String)

    @Transaction
    suspend fun deletePlaylist(playlistId: String) {
        deletePlaylistTracks(playlistId)
        deletePlaylistEntityById(playlistId)
    }

    @Transaction
    suspend fun replacePlaylistTracks(playlistId: String, trackIds: List<String>) {
        deletePlaylistTracks(playlistId)
        val now = System.currentTimeMillis()
        val entities = trackIds.mapIndexed { index, trackId ->
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = trackId,
                position = index,
                dateAdded = now
            )
        }
        insertPlaylistTracks(entities)
        val playlist = getPlaylistByIdSync(playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedAt = now))
        }
    }

    @Transaction
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        val existing = getTracksForPlaylistSync(playlistId)
        val startPos = existing.size
        val now = System.currentTimeMillis()
        val newEntities = trackIds.mapIndexed { index, trackId ->
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = trackId,
                position = startPos + index,
                dateAdded = now
            )
        }
        insertPlaylistTracks(newEntities)
        val playlist = getPlaylistByIdSync(playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedAt = now))
        }
    }

    @Transaction
    suspend fun removeTrackAtPosition(playlistId: String, position: Int) {
        val currentTracks = getTracksForPlaylistSync(playlistId).sortedBy { it.position }
        val remaining = currentTracks.filter { it.position != position }
        deletePlaylistTracks(playlistId)
        val reindexed = remaining.mapIndexed { index, item ->
            item.copy(id = 0, position = index)
        }
        insertPlaylistTracks(reindexed)
        val playlist = getPlaylistByIdSync(playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    @Transaction
    suspend fun reorderTrack(playlistId: String, fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val currentTracks = getTracksForPlaylistSync(playlistId).sortedBy { it.position }.toMutableList()
        if (fromPosition !in currentTracks.indices || toPosition !in currentTracks.indices) return

        val item = currentTracks.removeAt(fromPosition)
        currentTracks.add(toPosition, item)

        deletePlaylistTracks(playlistId)
        val reindexed = currentTracks.mapIndexed { index, trackEntity ->
            trackEntity.copy(id = 0, position = index)
        }
        insertPlaylistTracks(reindexed)
        val playlist = getPlaylistByIdSync(playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}
