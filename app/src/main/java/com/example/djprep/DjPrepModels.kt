package com.example.djprep

/**
 * Stage 28: DJ Track Preparation Environment Data Models.
 *
 * Models cue points, memory cues, beat grids, phrase markers,
 * prep status, and manual overrides for DJ track preparation.
 */

enum class PrepStatus(val label: String) {
    NOT_ANALYSED("Not Analysed"),
    ANALYSED("Analysed"),
    NEEDS_REVIEW("Needs Review"),
    PREPPED("Prepped");

    companion object {
        fun fromString(value: String?): PrepStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NOT_ANALYSED
        }
    }
}

enum class CueType {
    HOT_CUE,
    MEMORY_CUE
}

data class CuePoint(
    val id: String, // e.g. "A", "B", "C", "D", "E", "F", "G", "H", or "mem_1"
    val label: String,
    val positionMs: Long,
    val colorHex: String = "#FF3344",
    val type: CueType = CueType.HOT_CUE
) {
    companion object {
        val DEFAULT_HOT_CUE_COLORS = listOf(
            "#FF2D55", // A: Red / Crimson
            "#FF9500", // B: Orange
            "#FFCC00", // C: Yellow
            "#34C759", // D: Green
            "#00C7BE", // E: Teal
            "#30B0C7", // F: Cyan
            "#5856D6", // G: Indigo / Blue
            "#AF52DE"  // H: Purple / Magenta
        )
    }
}

enum class PhraseType(val label: String, val colorHex: String) {
    INTRO("Intro", "#00E5FF"),
    VERSE("Verse", "#2979FF"),
    BUILD("Build", "#FFD600"),
    DROP("Drop", "#FF1744"),
    BREAKDOWN("Breakdown", "#D500F9"),
    CHORUS("Chorus", "#FF6D00"),
    OUTRO("Outro", "#00BFA5"),
    CUSTOM("Custom", "#90A4AE");

    companion object {
        fun fromString(value: String?): PhraseType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CUSTOM
        }
    }
}

data class PhraseMarker(
    val id: String,
    val type: PhraseType,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val startBar: Int = 1,
    val barCount: Int = 16,
    val colorHex: String = type.colorHex
)

data class BeatGridData(
    val bpm: Double = 120.0,
    val firstDownbeatMs: Long = 0L,
    val gridOffsetMs: Long = 0L,
    val isManualOverride: Boolean = false
) {
    val intervalMs: Double
        get() = if (bpm > 0.0) (60_000.0 / bpm) else 500.0

    val barIntervalMs: Double
        get() = intervalMs * 4.0

    /**
     * Calculates the nearest beat timestamp for any given track position.
     */
    fun getNearestBeatMs(positionMs: Long): Long {
        if (intervalMs <= 0.0) return positionMs
        val relative = positionMs - (firstDownbeatMs + gridOffsetMs)
        val beatNumber = Math.round(relative / intervalMs)
        return (firstDownbeatMs + gridOffsetMs + (beatNumber * intervalMs)).toLong().coerceAtLeast(0L)
    }

    /**
     * Calculates the beat index (0, 1, 2, 3) within a 4/4 bar for a given position.
     */
    fun getBeatInBar(positionMs: Long): Int {
        if (intervalMs <= 0.0) return 0
        val relative = positionMs - (firstDownbeatMs + gridOffsetMs)
        val totalBeats = Math.floor(relative / intervalMs).toInt()
        val mod = totalBeats % 4
        return if (mod >= 0) mod else mod + 4
    }
}

data class DjPrepTrackData(
    val trackId: String,
    val bpm: Double = 120.0,
    val isManualBpm: Boolean = false,
    val musicalKey: String = "",
    val camelotKey: String = "",
    val isManualKey: Boolean = false,
    val grid: BeatGridData = BeatGridData(bpm = bpm),
    val hotCues: List<CuePoint> = emptyList(),
    val memoryCues: List<CuePoint> = emptyList(),
    val phraseMarkers: List<PhraseMarker> = emptyList(),
    val prepStatus: PrepStatus = PrepStatus.NOT_ANALYSED,
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
