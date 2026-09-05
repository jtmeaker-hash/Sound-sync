package com.example.model

/**
 * Waveform display styles for SoundSync (Step 2 Part G):
 * - RETRO: Classic SoundSync chunky, pixel-like, nostalgic 3-band peak waveform.
 * - DETAILED: High-resolution, multi-band transient-rich professional DJ software waveform.
 * - CLASSIC_AMPLITUDE: Classic smooth mono-amplitude dynamic envelope.
 * - FREQUENCY_COLOURED: RGB frequency separation (Bass: Red/Orange, Mid: Green/Yellow, High: Cyan/Blue).
 * - SPECTRUM_INSPIRED: Vertical harmonic spectrum density visualization.
 * - DJ_OVERVIEW: High-contrast cue-focused overview with phrase section markers.
 * - MINIMAL: Ultra-clean, sleek single-line transient waveform.
 */
enum class WaveformStyle {
    RETRO,
    DETAILED,
    CLASSIC_AMPLITUDE,
    FREQUENCY_COLOURED,
    SPECTRUM_INSPIRED,
    DJ_OVERVIEW,
    MINIMAL;

    val displayName: String
        get() = when (this) {
            RETRO -> "Retro Waveform"
            DETAILED -> "Detailed Waveform"
            CLASSIC_AMPLITUDE -> "Classic Amplitude"
            FREQUENCY_COLOURED -> "Frequency-Coloured (RGB)"
            SPECTRUM_INSPIRED -> "Spectrum-Inspired"
            DJ_OVERVIEW -> "DJ Overview"
            MINIMAL -> "Minimal"
        }

    val shortName: String
        get() = when (this) {
            RETRO -> "Retro"
            DETAILED -> "Detailed"
            CLASSIC_AMPLITUDE -> "Classic"
            FREQUENCY_COLOURED -> "RGB Freq"
            SPECTRUM_INSPIRED -> "Spectrum"
            DJ_OVERVIEW -> "DJ Overview"
            MINIMAL -> "Minimal"
        }

    val description: String
        get() = when (this) {
            RETRO -> "Classic chunky pixel-style waveform with nostalgic visual aesthetic"
            DETAILED -> "High-resolution multi-band display capturing sharp transients and full dynamics"
            CLASSIC_AMPLITUDE -> "Monochromatic dynamic amplitude envelope with smooth analog curvature"
            FREQUENCY_COLOURED -> "Direct frequency band colorization: Bass (Orange/Red), Mid (Green), High (Cyan)"
            SPECTRUM_INSPIRED -> "Multi-layered spectral density gradient highlighting harmonic overtone energy"
            DJ_OVERVIEW -> "High-contrast DJ deck visualizer with prominent phrase and breakdown markers"
            MINIMAL -> "Sleek low-profile transient display designed for distraction-free listening"
        }

    companion object {
        fun fromString(value: String?): WaveformStyle {
            if (value.isNullOrBlank()) return DETAILED
            val trimmed = value.trim()
            return when {
                trimmed.contains("retro", ignoreCase = true) -> RETRO
                trimmed.contains("detailed", ignoreCase = true) -> DETAILED
                trimmed.contains("classic", ignoreCase = true) -> CLASSIC_AMPLITUDE
                trimmed.contains("freq", ignoreCase = true) || trimmed.contains("rgb", ignoreCase = true) -> FREQUENCY_COLOURED
                trimmed.contains("spectrum", ignoreCase = true) -> SPECTRUM_INSPIRED
                trimmed.contains("overview", ignoreCase = true) -> DJ_OVERVIEW
                trimmed.contains("minimal", ignoreCase = true) -> MINIMAL
                else -> entries.find { it.name.equals(trimmed, ignoreCase = true) } ?: DETAILED
            }
        }
    }
}
