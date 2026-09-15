package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtist(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackArtists(trackArtists: List<TrackArtistEntity>)

    @Query("SELECT * FROM artists ORDER BY CASE WHEN normalizedName = 'unknown artist' THEN 1 ELSE 0 END, normalizedName ASC")
    fun observeAllArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists ORDER BY CASE WHEN normalizedName = 'unknown artist' THEN 1 ELSE 0 END, normalizedName ASC")
    suspend fun getAllArtists(): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE id = :id LIMIT 1")
    suspend fun getArtistById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getArtistByNormalizedName(normalizedName: String): ArtistEntity?

    @Query("SELECT trackId FROM track_artists WHERE artistId = :artistId")
    suspend fun getTrackIdsForArtist(artistId: String): List<String>

    @Query("SELECT artistId FROM track_artists WHERE trackId = :trackId")
    suspend fun getArtistIdsForTrack(trackId: String): List<String>

    @Query("SELECT * FROM track_artists WHERE trackId = :trackId")
    suspend fun getTrackArtistsForTrack(trackId: String): List<TrackArtistEntity>

    @Query("DELETE FROM track_artists WHERE trackId = :trackId")
    suspend fun deleteTrackArtistsForTrack(trackId: String)

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artistId FROM track_artists)")
    suspend fun deleteOrphanArtists()

    @Query("DELETE FROM track_artists")
    suspend fun clearTrackArtists()

    @Query("DELETE FROM artists")
    suspend fun clearArtists()

    @Query("SELECT COUNT(*) FROM artists")
    suspend fun getArtistCount(): Int

    @Query("SELECT COUNT(*) FROM track_artists")
    suspend fun getTrackArtistCount(): Int
}
