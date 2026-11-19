package com.example.audio

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Playback Timeline interface providing single source of truth for media time.
 * Consumed identically by:
 * - Decoded audio engine
 * - Now Playing elapsed/remaining display
 * - Detailed scrolling waveform
 * - Full-track overview scrubber
 * - Beatgrid, cue and loop markers
 */
interface PlaybackTimeline {
    /** Authoritative discrete playback position in milliseconds. */
    val currentPositionMs: StateFlow<Long>

    /** Authoritative sub-millisecond playback position for 60fps/120fps UI rendering. */
    fun getCurrentPositionFloatMs(): Float

    /** Current playback speed multiplier (e.g. 1.0f). */
    val playbackSpeed: Float

    /** Comprehensive snapshot of the playback timeline. */
    fun getSnapshot(): TimelineSnapshot

    /** Register listener for timeline discontinuities (seek, pause, track change, loop wrap). */
    fun addDiscontinuityListener(listener: (TimelineDiscontinuity) -> Unit)

    /** Remove previously registered discontinuity listener. */
    fun removeDiscontinuityListener(listener: (TimelineDiscontinuity) -> Unit)
}

/**
 * Snapshot of the playback timeline at a specific point in time.
 */
data class TimelineSnapshot(
    val trackId: String,
    val mediaTimeMs: Long,
    val mediaTimeFloatMs: Float,
    val durationMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val generationSession: Long,
    val discontinuityId: Long,
    val anchorNanoTime: Long,
    val estimatedBufferLagMs: Float = 0f
)

/**
 * Reason for an abrupt discontinuity in the timeline.
 */
enum class DiscontinuityReason {
    SEEK,
    TRACK_TRANSITION,
    PAUSE,
    RESUME,
    SPEED_CHANGE,
    LOOP_WRAP,
    RESET
}

/**
 * Event delivered to observers when timeline experiences a discontinuity.
 */
data class TimelineDiscontinuity(
    val reason: DiscontinuityReason,
    val previousMediaTimeMs: Long,
    val newMediaTimeMs: Long,
    val trackId: String,
    val timestampNanos: Long
)

/**
 * Centralized, authoritative Audio Clock owned by the playback engine.
 * Derives true audio media time directly from Android [AudioTrack] hardware presentation
 * timestamps, completely eliminating duplicate clocks, UI extrapolation drift,
 * and buffer latency offsets.
 */
class AudioClock : PlaybackTimeline {

    companion object {
        private const val TAG = "AudioClock"
    }

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val discontinuityListeners = CopyOnWriteArrayList<(TimelineDiscontinuity) -> Unit>()

    @Volatile private var activeTrackId: String = ""
    @Volatile private var trackDurationMs: Long = 0L
    @Volatile private var isPlaying: Boolean = false
    @Volatile private var _playbackSpeed: Float = 1.0f
    override val playbackSpeed: Float get() = _playbackSpeed
    @Volatile private var sampleRate: Int = 44100
    @Volatile private var currentSessionId: Long = 0L

    // The media time (ms) corresponding to baseFramePosition
    @Volatile private var baseMediaTimeMs: Long = 0L
    // The hardware audio frame corresponding to baseMediaTimeMs
    @Volatile private var baseFramePosition: Long = 0L

    // Hardware frame tracking for AudioTrack
    @Volatile private var unwrappedPlayedFrames: Long = 0L
    @Volatile private var lastRawPlaybackHead: Long = 0L
    @Volatile private var totalFramesWritten: Long = 0L

    // Discontinuity identification
    @Volatile private var discontinuityId: Long = 0L
    @Volatile private var lastAnchorNanoTime: Long = System.nanoTime()

    // Weak/volatile reference to currently playing AudioTrack
    @Volatile private var boundAudioTrack: AudioTrack? = null
    private val reusableAudioTimestamp = AudioTimestamp()

    override fun getCurrentPositionFloatMs(): Float {
        val duration = trackDurationMs.toFloat().coerceAtLeast(0f)
        if (!isPlaying) {
            return baseMediaTimeMs.toFloat().coerceIn(0f, if (duration > 0f) duration else Float.MAX_VALUE)
        }

        val at = boundAudioTrack
        if (at != null && at.state == AudioTrack.STATE_INITIALIZED && at.playState == AudioTrack.PLAYSTATE_PLAYING) {
            var hardwareFrame = -1L
            var hardwareNanos = -1L

            // 1. Prefer AudioTimestamp (hardware DAC presentation clock)
            try {
                if (at.getTimestamp(reusableAudioTimestamp)) {
                    hardwareFrame = reusableAudioTimestamp.framePosition
                    hardwareNanos = reusableAudioTimestamp.nanoTime
                }
            } catch (_: Throwable) {}

            // 2. Fall back to AudioTrack playback head position
            if (hardwareFrame < 0L) {
                try {
                    val rawHead = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    hardwareFrame = updateUnwrappedHead(rawHead)
                    hardwareNanos = System.nanoTime()
                } catch (_: Throwable) {}
            }

            if (hardwareFrame >= 0L) {
                val effectiveSampleRate = if (sampleRate > 0) sampleRate else 44100
                val elapsedFrames = (hardwareFrame - baseFramePosition).coerceAtLeast(0L)
                val mediaElapsedFromFramesMs = (elapsedFrames.toDouble() / effectiveSampleRate.toDouble()) * 1000.0

                val nowNanos = System.nanoTime()
                val nanosSinceHardware = if (hardwareNanos > 0L) (nowNanos - hardwareNanos) else 0L
                // Bound sub-millisecond interpolation to +/- 100ms to safeguard against DAC jitter
                val boundedNanosDelta = nanosSinceHardware.coerceIn(-100_000_000L, 100_000_000L)
                val subFrameMs = (boundedNanosDelta / 1_000_000.0) * playbackSpeed.toDouble()

                val resolvedMs = (baseMediaTimeMs.toDouble() + mediaElapsedFromFramesMs + subFrameMs).toFloat()
                return resolvedMs.coerceIn(0f, if (duration > 0f) duration else Float.MAX_VALUE)
            }
        }

        // 3. Fallback when AudioTrack is not yet outputting or in synthesis fallback:
        // High-precision elapsed time from anchor
        val nowNanos = System.nanoTime()
        val elapsedSec = (nowNanos - lastAnchorNanoTime) / 1_000_000_000.0
        val elapsedMs = (elapsedSec * 1000.0 * playbackSpeed.toDouble()).toFloat()
        val estimatedMs = (baseMediaTimeMs.toFloat() + elapsedMs)
        return estimatedMs.coerceIn(0f, if (duration > 0f) duration else Float.MAX_VALUE)
    }

    override fun getSnapshot(): TimelineSnapshot {
        val currentFloatMs = getCurrentPositionFloatMs()
        val currentMs = currentFloatMs.toLong()
        val lagMs = calculateBufferLagMs()
        return TimelineSnapshot(
            trackId = activeTrackId,
            mediaTimeMs = currentMs,
            mediaTimeFloatMs = currentFloatMs,
            durationMs = trackDurationMs,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            generationSession = currentSessionId,
            discontinuityId = discontinuityId,
            anchorNanoTime = lastAnchorNanoTime,
            estimatedBufferLagMs = lagMs
        )
    }

    override fun addDiscontinuityListener(listener: (TimelineDiscontinuity) -> Unit) {
        discontinuityListeners.add(listener)
    }

    override fun removeDiscontinuityListener(listener: (TimelineDiscontinuity) -> Unit) {
        discontinuityListeners.remove(listener)
    }

    // ── Audio Engine Integration Hooks ───────────────────────────────────────

    /**
     * Binds an active [AudioTrack] and its sample rate to the clock.
     */
    fun bindAudioTrack(audioTrack: AudioTrack?, rate: Int) {
        boundAudioTrack = audioTrack
        sampleRate = if (rate > 0) rate else 44100
        resetHardwareHead()
    }

    /**
     * Unbinds the active [AudioTrack].
     */
    fun unbindAudioTrack() {
        boundAudioTrack = null
    }

    /**
     * Called when a new track is queued or starts playing.
     */
    fun onTrackChanged(track: Track, initialPositionMs: Long = 0L, session: Long = -1L) {
        val prevMs = getCurrentPositionFloatMs().toLong()
        activeTrackId = track.id
        trackDurationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L
        baseMediaTimeMs = initialPositionMs.coerceAtLeast(0L)
        baseFramePosition = 0L
        totalFramesWritten = 0L
        currentSessionId = session
        resetHardwareHead()
        lastAnchorNanoTime = System.nanoTime()
        discontinuityId++

        _currentPositionMs.value = baseMediaTimeMs
        notifyDiscontinuity(
            TimelineDiscontinuity(
                reason = DiscontinuityReason.TRACK_TRANSITION,
                previousMediaTimeMs = prevMs,
                newMediaTimeMs = baseMediaTimeMs,
                trackId = track.id,
                timestampNanos = lastAnchorNanoTime
            )
        )
    }

    /**
     * Called when playback state toggles.
     */
    fun onPlayStateChanged(playing: Boolean) {
        if (isPlaying == playing) return
        val currentFloatMs = getCurrentPositionFloatMs()
        val prevMs = currentFloatMs.toLong()

        if (!playing) {
            // Pausing: freeze base media time strictly at current played frame
            baseMediaTimeMs = prevMs
            baseFramePosition = 0L
            resetHardwareHead()
        } else {
            // Resuming: re-anchor time
            lastAnchorNanoTime = System.nanoTime()
            baseFramePosition = 0L
            resetHardwareHead()
        }

        isPlaying = playing
        discontinuityId++
        _currentPositionMs.value = baseMediaTimeMs

        notifyDiscontinuity(
            TimelineDiscontinuity(
                reason = if (playing) DiscontinuityReason.RESUME else DiscontinuityReason.PAUSE,
                previousMediaTimeMs = prevMs,
                newMediaTimeMs = baseMediaTimeMs,
                trackId = activeTrackId,
                timestampNanos = System.nanoTime()
            )
        )
    }

    /**
     * Called when a seek resolves to an exact media position.
     * Hardware buffers should be flushed before or during this call.
     */
    fun onSeekResolved(resolvedMediaTimeMs: Long, audioTrackFlushed: Boolean = true) {
        val prevMs = getCurrentPositionFloatMs().toLong()
        baseMediaTimeMs = resolvedMediaTimeMs.coerceIn(0L, if (trackDurationMs > 0) trackDurationMs else Long.MAX_VALUE)
        baseFramePosition = 0L
        totalFramesWritten = 0L
        resetHardwareHead()
        lastAnchorNanoTime = System.nanoTime()
        discontinuityId++

        _currentPositionMs.value = baseMediaTimeMs
        notifyDiscontinuity(
            TimelineDiscontinuity(
                reason = DiscontinuityReason.SEEK,
                previousMediaTimeMs = prevMs,
                newMediaTimeMs = baseMediaTimeMs,
                trackId = activeTrackId,
                timestampNanos = lastAnchorNanoTime
            )
        )
    }

    /**
     * Records PCM frames written to the AudioTrack to monitor buffer depth.
     */
    fun recordFramesWritten(frames: Int) {
        if (frames > 0) {
            totalFramesWritten += frames
        }
    }

    /**
     * Updates the discrete StateFlow position when engine emits a throttled tick.
     */
    fun updatePublishedPosition(positionMs: Long) {
        _currentPositionMs.value = positionMs
        if (!isPlaying || boundAudioTrack == null) {
            baseMediaTimeMs = positionMs
            lastAnchorNanoTime = System.nanoTime()
        }
    }

    /**
     * Called when pitch or playback rate changes.
     */
    fun onPlaybackSpeedChanged(speed: Float) {
        if (speed <= 0f) return
        val currentFloatMs = getCurrentPositionFloatMs()
        baseMediaTimeMs = currentFloatMs.toLong()
        baseFramePosition = 0L
        resetHardwareHead()
        _playbackSpeed = speed
        lastAnchorNanoTime = System.nanoTime()
        discontinuityId++
    }

    /**
     * Called on loop restart / wrap.
     */
    fun onLoopWrap(loopStartMs: Long) {
        val prevMs = getCurrentPositionFloatMs().toLong()
        baseMediaTimeMs = loopStartMs
        baseFramePosition = 0L
        resetHardwareHead()
        lastAnchorNanoTime = System.nanoTime()
        discontinuityId++
        _currentPositionMs.value = baseMediaTimeMs

        notifyDiscontinuity(
            TimelineDiscontinuity(
                reason = DiscontinuityReason.LOOP_WRAP,
                previousMediaTimeMs = prevMs,
                newMediaTimeMs = baseMediaTimeMs,
                trackId = activeTrackId,
                timestampNanos = lastAnchorNanoTime
            )
        )
    }

    private fun resetHardwareHead() {
        unwrappedPlayedFrames = 0L
        lastRawPlaybackHead = 0L
        val at = boundAudioTrack
        if (at != null) {
            try {
                lastRawPlaybackHead = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (_: Throwable) {}
        }
    }

    private fun updateUnwrappedHead(rawHead: Long): Long {
        val delta = (rawHead - lastRawPlaybackHead) and 0xFFFFFFFFL
        if (delta < 0x80000000L) {
            unwrappedPlayedFrames += delta
        }
        lastRawPlaybackHead = rawHead
        return unwrappedPlayedFrames
    }

    private fun calculateBufferLagMs(): Float {
        val at = boundAudioTrack ?: return 0f
        val effectiveRate = if (sampleRate > 0) sampleRate else 44100
        val unplayedFrames = (totalFramesWritten - unwrappedPlayedFrames).coerceAtLeast(0L)
        return ((unplayedFrames.toFloat() / effectiveRate.toFloat()) * 1000f)
    }

    private fun notifyDiscontinuity(discontinuity: TimelineDiscontinuity) {
        for (listener in discontinuityListeners) {
            try {
                listener(discontinuity)
            } catch (e: Throwable) {
                Log.w(TAG, "Error in discontinuity listener: ${e.message}")
            }
        }
    }
}
