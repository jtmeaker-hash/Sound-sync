package com.example.service

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.PlaybackSessionEntity
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-precision, crash-resilient Playback Stats and History Tracker.
 * Canonical tracker invoked directly by the playback engine/service to guarantee
 * accurate play counts, skips, completion rates, and listening durations regardless
 * of whether playback is controlled via UI, lock-screen, notifications, or Bluetooth.
 */
class PlaybackStatsTracker private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = AppDatabase.getDatabase(context)
    private val sessionDao = database.playbackSessionDao()

    private var activeTrackId: String? = null
    private var activeTrackDurationMs: Long = 0L
    private var activeStartedAt: Long = 0L
    private var activeContext: String = "LIBRARY"
    private var activePlaylistId: String? = null

    private var accumulatedListenedMs: Long = 0L
    private var lastPlayClockTime: Long = 0L
    private var lastObservedPositionMs: Long = 0L
    private var isPlayingActive = AtomicBoolean(false)
    private var hasReachedCompletion = false

    @Synchronized
    fun onTrackStarted(track: Track, contextName: String = "LIBRARY", playlistId: String? = null) {
        // Finalize previous session if exists
        finalizeActiveSession(intentionalSkip = true)

        activeTrackId = track.id
        activeTrackDurationMs = (track.durationSeconds * 1000L).coerceAtLeast(1000L)
        activeStartedAt = System.currentTimeMillis()
        activeContext = contextName
        activePlaylistId = playlistId

        accumulatedListenedMs = 0L
        lastPlayClockTime = System.currentTimeMillis()
        lastObservedPositionMs = 0L
        isPlayingActive.set(true)
        hasReachedCompletion = false

        Log.d(TAG, "Started tracking playback session for '${track.title}' (id: ${track.id})")
    }

    @Synchronized
    fun onPlaybackStateChanged(isPlaying: Boolean, currentPositionMs: Long) {
        val now = System.currentTimeMillis()
        if (isPlayingActive.get()) {
            val delta = now - lastPlayClockTime
            // Protect against massive intervals from device sleep or background suspend (cap to 2500ms per tick)
            if (delta in 1..2500) {
                accumulatedListenedMs += delta
            }
        }
        lastPlayClockTime = now
        lastObservedPositionMs = currentPositionMs
        isPlayingActive.set(isPlaying)

        // Check if playback position reached completion threshold (>= 90%)
        if (activeTrackDurationMs > 0 && currentPositionMs >= (activeTrackDurationMs * 0.90)) {
            hasReachedCompletion = true
        }
    }

    @Synchronized
    fun onTrackPositionTick(currentPositionMs: Long) {
        if (!isPlayingActive.get()) return
        val now = System.currentTimeMillis()
        val delta = now - lastPlayClockTime
        if (delta in 1..2500) {
            accumulatedListenedMs += delta
        }
        lastPlayClockTime = now
        lastObservedPositionMs = currentPositionMs

        if (activeTrackDurationMs > 0 && currentPositionMs >= (activeTrackDurationMs * 0.90)) {
            hasReachedCompletion = true
        }
    }

    @Synchronized
    fun onTrackCompletedNormally() {
        hasReachedCompletion = true
        finalizeActiveSession(intentionalSkip = false)
    }

    @Synchronized
    fun onPlaybackStopped() {
        finalizeActiveSession(intentionalSkip = false)
    }

    @Synchronized
    private fun finalizeActiveSession(intentionalSkip: Boolean) {
        val trackId = activeTrackId ?: return
        val startedAt = activeStartedAt
        val durationMs = activeTrackDurationMs
        val listenedMs = accumulatedListenedMs
        val completed = hasReachedCompletion || (durationMs > 0 && lastObservedPositionMs >= durationMs * 0.90)

        // Play qualification: at least 30 seconds OR >= 50% for tracks < 60s
        val qualifiesAsPlay = if (durationMs in 1..59_999) {
            listenedMs >= (durationMs / 2)
        } else {
            listenedMs >= 30_000L
        }

        // Intentional skip: switched away before completion, having listened for at least 2s
        val wasSkipped = intentionalSkip && !completed && listenedMs >= 2000L

        // Record session if it was either a qualified play, a completed track, or a valid intentional skip
        if (qualifiesAsPlay || completed || wasSkipped) {
            val session = PlaybackSessionEntity(
                trackId = trackId,
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                listenedDurationMs = listenedMs,
                trackDurationMs = durationMs,
                completed = completed,
                skipped = wasSkipped,
                playbackContext = activeContext,
                playlistId = activePlaylistId
            )
            scope.launch {
                try {
                    sessionDao.insertSession(session)
                    Log.d(TAG, "Recorded playback session: trackId=$trackId, completed=$completed, skipped=$wasSkipped, listenedMs=$listenedMs")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist playback session", e)
                }
            }
        } else {
            Log.d(TAG, "Session discarded (below play threshold): listenedMs=$listenedMs ms")
        }

        // Clear active session
        activeTrackId = null
        activeTrackDurationMs = 0L
        accumulatedListenedMs = 0L
        hasReachedCompletion = false
        isPlayingActive.set(false)
    }

    companion object {
        private const val TAG = "PlaybackStatsTracker"

        @Volatile
        private var INSTANCE: PlaybackStatsTracker? = null

        fun getInstance(context: Context): PlaybackStatsTracker {
            return INSTANCE ?: synchronized(this) {
                val instance = PlaybackStatsTracker(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
