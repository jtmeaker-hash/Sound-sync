package com.example.model

/**
 * Waveform display styles for SoundSync:
 * - RETRO: Classic SoundSync chunky, pixel-like, nostalgic 3-band peak waveform.
 * - DETAILED: High-resolution, multi-band transient-rich professional DJ software waveform.
 */
enum class WaveformStyle {
    RETRO,
    DETAILED;

    val displayName: String
        get() = when (this) {
            RETRO -> "Retro Waveform"
            DETAILED -> "Detailed Waveform"
        }

    val shortName: String
        get() = when (this) {
            RETRO -> "Retro"
            DETAILED -> "Detailed"
        }

    val description: String
        get() = when (this) {
            RETRO -> "Classic chunky pixel-style waveform with nostalgic visual aesthetic"
            DETAILED -> "High-resolution multi-band display capturing sharp transients and full dynamics"
        }

    companion object {
        fun fromString(value: String?): WaveformStyle {
            if (value.isNullOrBlank()) return DETAILED
            val trimmed = value.trim()
            return when {
                trimmed.contains("retro", ignoreCase = true) -> RETRO
                trimmed.contains("detailed", ignoreCase = true) -> DETAILED
                else -> entries.find { it.name.equals(trimmed, ignoreCase = true) } ?: DETAILED
            }
        }
    }
}
