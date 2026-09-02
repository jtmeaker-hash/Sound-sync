package com.example.audio

/**
 * Monotonic ownership token for playback sessions.
 *
 * Every [DjAudioEngine.loadTrack] call mints a new generation. Decoder and
 * synthesis loops capture the generation they were started with and may only
 * write audio, mutate shared loop state, or fire track callbacks while their
 * generation is still current. This is what guarantees A -> B -> C rapid
 * switching leaves ONLY C audible, even if A's or B's loops were still alive
 * when C was requested.
 */
class PlaybackGenerationGate {

    private val lock = Any()
    private var current = 0L

    /** The most recently minted generation. */
    val latest: Long
        get() = synchronized(lock) { current }

    /** Mint the next generation and return it. */
    fun next(): Long = synchronized(lock) {
        current += 1
        current
    }

    /** True only while [generation] is the newest minted generation. */
    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        generation == current
    }
}
