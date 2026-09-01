package com.example.metadata

import java.util.Locale

const val AUDIO_ANALYSIS_VERSION = "1"

data class AudioAnalysisResult(
    val bpm: Double? = null,
    val bpmConfidence: Double = 0.0,
    val musicalKey: String? = null,
    val camelotKey: String? = null,
    val keyConfidence: Double = 0.0,
    val analysisVersion: String = AUDIO_ANALYSIS_VERSION,
    val analyzedAt: Long = System.currentTimeMillis()
)

object CamelotKey {
    private val major = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val minor = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val majorNumbers = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val minorNumbers = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

    fun fromPitchClass(pitchClass: Int, isMinor: Boolean): String {
        val index = ((pitchClass % 12) + 12) % 12
        return "${if (isMinor) minorNumbers[index] else majorNumbers[index]}${if (isMinor) 'A' else 'B'}"
    }

    fun fromMusicalKey(raw: String?): String? {
        val key = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (key.isBlank()) return null
        if (Regex("^(1[0-2]|[1-9])[ab]$").matches(key)) return key.uppercase(Locale.ROOT)
        val minorMode = key.contains("minor") || key.endsWith("m")
        val root = key.replace("minor", "").replace("major", "").replace("maj", "").replace("min", "").replace("m", "").trim()
        val aliases = mapOf("db" to "c#", "eb" to "d#", "gb" to "f#", "ab" to "g#", "bb" to "a#")
        val normalized = aliases[root] ?: root
        val pitch = major.indexOfFirst { it.lowercase(Locale.ROOT) == normalized }
        return pitch.takeIf { it >= 0 }?.let { fromPitchClass(it, minorMode) }
    }
}
