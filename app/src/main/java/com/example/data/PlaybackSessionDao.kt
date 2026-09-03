package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class TrackPlaybackStats(
    val playCount: Int = 0,
    val completedCount: Int = 0,
    val skippedCount: Int = 0,
    val totalListeningMs: Long = 0L,
    val lastPlayed: Long? = null,
    val firstPlayed: Long? = null
) {
    val completionRate: Float
        get() = if (playCount > 0) (completedCount.toFloat() / playCount) * 100f else 0f
}

data class ListeningOverviewStats(
    val totalPlays: Int = 0,
    val totalListeningMs: Long = 0L,
    val uniqueTracksPlayed: Int = 0,
    val totalCompleted: Int = 0,
    val totalSkipped: Int = 0
) {
    val completionRate: Float
        get() = if (totalPlays > 0) (totalCompleted.toFloat() / totalPlays) * 100f else 0f
    val skipRate: Float
        get() = if (totalPlays > 0) (totalSkipped.toFloat() / totalPlays) * 100f else 0f
}

data class TopPlayedTrackResult(
    val trackId: String,
    val playCount: Int,
    val totalListeningMs: Long,
    val completedCount: Int
)

data class TopSkippedTrackResult(
    val trackId: String,
    val totalPlays: Int,
    val skipCount: Int
) {
    val skipRate: Float
        get() = if (totalPlays > 0) (skipCount.toFloat() / totalPlays) * 100f else 0f
}

data class DailyListeningRecord(
    val startedAt: Long,
    val listenedDurationMs: Long
)

@Dao
interface PlaybackSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: PlaybackSessionEntity): Long

    @Query("DELETE FROM playback_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM playback_sessions WHERE trackId = :trackId")
    suspend fun deleteSessionsForTrack(trackId: String)

    @Query("DELETE FROM playback_sessions WHERE startedAt >= :startMs AND startedAt <= :endMs")
    suspend fun deleteSessionsBetween(startMs: Long, endMs: Long)

    @Query("DELETE FROM playback_sessions")
    suspend fun deleteAllSessions()

    @Query("""
        SELECT 
            COUNT(*) as playCount,
            SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as completedCount,
            SUM(CASE WHEN skipped = 1 THEN 1 ELSE 0 END) as skippedCount,
            COALESCE(SUM(listenedDurationMs), 0) as totalListeningMs,
            MAX(startedAt) as lastPlayed,
            MIN(startedAt) as firstPlayed
        FROM playback_sessions
        WHERE trackId = :trackId
    """)
    suspend fun getTrackStats(trackId: String): TrackPlaybackStats?

    @Query("""
        SELECT 
            COUNT(*) as playCount,
            SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as completedCount,
            SUM(CASE WHEN skipped = 1 THEN 1 ELSE 0 END) as skippedCount,
            COALESCE(SUM(listenedDurationMs), 0) as totalListeningMs,
            MAX(startedAt) as lastPlayed,
            MIN(startedAt) as firstPlayed
        FROM playback_sessions
        WHERE trackId = :trackId
    """)
    fun observeTrackStats(trackId: String): Flow<TrackPlaybackStats?>

    @Query("""
        SELECT
            COUNT(*) as totalPlays,
            COALESCE(SUM(listenedDurationMs), 0) as totalListeningMs,
            COUNT(DISTINCT trackId) as uniqueTracksPlayed,
            SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as totalCompleted,
            SUM(CASE WHEN skipped = 1 THEN 1 ELSE 0 END) as totalSkipped
        FROM playback_sessions
        WHERE startedAt >= :fromTime AND startedAt <= :toTime
    """)
    suspend fun getOverviewStats(fromTime: Long, toTime: Long): ListeningOverviewStats

    @Query("""
        SELECT trackId, COUNT(*) as playCount, 
               COALESCE(SUM(listenedDurationMs), 0) as totalListeningMs,
               SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as completedCount
        FROM playback_sessions
        WHERE startedAt >= :fromTime AND startedAt <= :toTime
        GROUP BY trackId
        ORDER BY playCount DESC, totalListeningMs DESC
        LIMIT :limit
    """)
    suspend fun getTopPlayedTracks(fromTime: Long, toTime: Long, limit: Int): List<TopPlayedTrackResult>

    @Query("""
        SELECT trackId, COUNT(*) as totalPlays,
               SUM(CASE WHEN skipped = 1 THEN 1 ELSE 0 END) as skipCount
        FROM playback_sessions
        WHERE startedAt >= :fromTime AND startedAt <= :toTime
        GROUP BY trackId
        HAVING totalPlays >= :minPlays AND skipCount > 0
        ORDER BY (skipCount * 1.0 / totalPlays) DESC, skipCount DESC
        LIMIT :limit
    """)
    suspend fun getTopSkippedTracks(fromTime: Long, toTime: Long, minPlays: Int, limit: Int): List<TopSkippedTrackResult>

    @Query("""
        SELECT trackId, COUNT(*) as playCount,
               COALESCE(SUM(listenedDurationMs), 0) as totalListeningMs,
               SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) as completedCount
        FROM playback_sessions
        WHERE startedAt >= :fromTime AND startedAt <= :toTime
        GROUP BY trackId
        HAVING playCount >= :minPlays
        ORDER BY (completedCount * 1.0 / playCount) DESC, completedCount DESC
        LIMIT :limit
    """)
    suspend fun getHighestCompletionTracks(fromTime: Long, toTime: Long, minPlays: Int, limit: Int): List<TopPlayedTrackResult>

    @Query("""
        SELECT * FROM playback_sessions
        ORDER BY startedAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getHistoryPaginated(limit: Int, offset: Int): List<PlaybackSessionEntity>

    @Query("SELECT COUNT(*) FROM playback_sessions")
    suspend fun getTotalSessionCount(): Int

    @Query("""
        SELECT trackId FROM (
            SELECT trackId, MAX(startedAt) as lastPlayed
            FROM playback_sessions
            GROUP BY trackId
            ORDER BY lastPlayed DESC
            LIMIT :limit
        )
    """)
    suspend fun getRecentlyPlayedTrackIds(limit: Int): List<String>

    @Query("""
        SELECT DISTINCT trackId FROM playback_sessions
    """)
    suspend fun getAllPlayedTrackIds(): List<String>

    @Query("""
        SELECT startedAt, listenedDurationMs
        FROM playback_sessions
        WHERE startedAt >= :fromTime AND startedAt <= :toTime
        ORDER BY startedAt ASC
    """)
    suspend fun getDailyListeningRecords(fromTime: Long, toTime: Long): List<DailyListeningRecord>
}
