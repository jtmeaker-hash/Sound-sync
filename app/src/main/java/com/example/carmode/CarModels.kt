package com.example.carmode

/**
 * Per-vehicle audio & playback profile.
 */
data class CarAudioProfile(
    val deviceAddress: String,
    val deviceName: String,
    val eqPreset: String = "Car Flat", // "Car Flat", "Bass Reduction", "Road Noise Compensation", "Custom Car EQ"
    val customEqLow: Float = 1.0f,
    val customEqMid: Float = 1.0f,
    val customEqHigh: Float = 1.0f,
    val haasEnabled: Boolean = false,
    val haasAmount: Float = 0.5f,
    val haasDelayMs: Float = 18f,
    val crossfadeDurationSec: Int = 4,
    val replayGainEnabled: Boolean = true,
    val preferredDisplayMode: CarDisplayMode = CarDisplayMode.ARTWORK,
    val autoLaunch: Boolean = true,
    val resumeOnConnect: Boolean = true,
    val pauseOnDisconnect: Boolean = true
)

enum class CarDisplayMode(val label: String) {
    ARTWORK("Artwork"),
    WAVEFORM("Live Waveform"),
    DJ_DASHBOARD("DJ Dashboard")
}

enum class PlaySomethingSource(val label: String) {
    LIBRARY("Entire Library"),
    FAVORITES("Favourites"),
    DRIVING_PLAYLIST("Driving Playlist"),
    RECENTLY_ADDED("Recently Added"),
    UNPLAYED("Unplayed Music")
}

/**
 * Historical record of a single driving listening session.
 */
data class DrivingSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val carName: String,
    val startedAt: Long,
    val endedAt: Long = System.currentTimeMillis(),
    val totalDurationMs: Long = 0L,
    val tracksPlayedCount: Int = 0,
    val tracksSkippedCount: Int = 0,
    val trackTitles: List<String> = emptyList(),
    val artistNames: List<String> = emptyList()
)
