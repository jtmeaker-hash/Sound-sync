package com.example.model

/**
 * Waveform presentation shape for SoundSync:
 * - HALF: Single-sided waveform where audio peaks rise upward from a bottom baseline.
 * - FULL: Complete, vertically-centered bipolar waveform displaying positive and negative
 *         amplitude excursions above and below the zero-amplitude centre axis.
 */
enum class WaveformShape {
    HALF,
    FULL;

    val displayName: String
        get() = when (this) {
            HALF -> "Half Waveform"
            FULL -> "Full Waveform"
        }

    val shortName: String
        get() = when (this) {
            HALF -> "Half"
            FULL -> "Full"
        }

    val description: String
        get() = when (this) {
            HALF -> "Single-sided waveform rising from the bottom baseline"
            FULL -> "Complete vertically-centered bipolar mirrored waveform"
        }

    companion object {
        fun fromString(value: String?): WaveformShape {
            if (value.isNullOrBlank()) return FULL
            val trimmed = value.trim()
            return when {
                trimmed.equals("HALF", ignoreCase = true) || trimmed.contains("half", ignoreCase = true) -> HALF
                trimmed.equals("FULL", ignoreCase = true) || trimmed.contains("full", ignoreCase = true) -> FULL
                else -> entries.find { it.name.equals(trimmed, ignoreCase = true) } ?: FULL
            }
        }
    }
}
