package com.example.smartcrate

import com.example.model.Track
import java.util.Locale

/**
 * Evaluates Smart Crate rule sets against the library dynamically without duplicating tracks.
 * Reference: Step 2 Part H specifications.
 */
object SmartCrateEngine {

    fun evaluate(crate: SmartCrate, libraryTracks: List<Track>): List<Track> {
        if (crate.rules.isEmpty()) {
            return sortAndLimit(libraryTracks, crate)
        }

        val filtered = libraryTracks.filter { track ->
            when (crate.matchMode) {
                SmartMatchMode.MATCH_ALL -> crate.rules.all { rule -> matchesRule(track, rule) }
                SmartMatchMode.MATCH_ANY -> crate.rules.any { rule -> matchesRule(track, rule) }
            }
        }

        return sortAndLimit(filtered, crate)
    }

    private fun sortAndLimit(tracks: List<Track>, crate: SmartCrate): List<Track> {
        val sorted = when (crate.sortField) {
            SmartSortField.TITLE -> if (crate.sortAscending) tracks.sortedBy { it.title.lowercase(Locale.ROOT) } else tracks.sortedByDescending { it.title.lowercase(Locale.ROOT) }
            SmartSortField.ARTIST -> if (crate.sortAscending) tracks.sortedBy { it.artist.lowercase(Locale.ROOT) } else tracks.sortedByDescending { it.artist.lowercase(Locale.ROOT) }
            SmartSortField.BPM -> if (crate.sortAscending) tracks.sortedBy { it.bpm } else tracks.sortedByDescending { it.bpm }
            SmartSortField.DATE_ADDED -> if (crate.sortAscending) tracks.sortedBy { it.dateAdded } else tracks.sortedByDescending { it.dateAdded }
            SmartSortField.DURATION -> if (crate.sortAscending) tracks.sortedBy { it.durationSeconds } else tracks.sortedByDescending { it.durationSeconds }
            SmartSortField.BITRATE -> if (crate.sortAscending) tracks.sortedBy { it.bitrateKbps } else tracks.sortedByDescending { it.bitrateKbps }
        }

        return if (crate.maxTrackLimit > 0) {
            sorted.take(crate.maxTrackLimit)
        } else {
            sorted
        }
    }

    fun matchesRule(track: Track, rule: SmartRule): Boolean {
        val ruleVal = rule.value.trim()
        val secVal = rule.secondaryValue.trim()

        return when (rule.field) {
            SmartField.TITLE -> evaluateString(track.title, rule.operator, ruleVal)
            SmartField.ARTIST -> evaluateString(track.artist, rule.operator, ruleVal)
            SmartField.ALBUM -> evaluateString(track.album, rule.operator, ruleVal)
            SmartField.GENRE -> evaluateString(track.genre, rule.operator, ruleVal)
            SmartField.YEAR -> evaluateNumeric((track.releaseYear ?: 0).toDouble(), rule.operator, ruleVal, secVal)
            SmartField.BPM -> evaluateNumeric(track.bpm, rule.operator, ruleVal, secVal)
            SmartField.MUSICAL_KEY -> evaluateString(track.musicalKey, rule.operator, ruleVal)
            SmartField.CAMELOT_KEY -> evaluateString(track.camelotKey, rule.operator, ruleVal)
            SmartField.RATING -> evaluateNumeric(track.rating.toDouble(), rule.operator, ruleVal, secVal)
            SmartField.DURATION -> evaluateNumeric(track.durationSeconds.toDouble(), rule.operator, ruleVal, secVal)
            SmartField.DATE_ADDED -> evaluateNumeric(track.dateAdded.toDouble(), rule.operator, ruleVal, secVal)
            SmartField.FILE_FORMAT -> evaluateString(track.format, rule.operator, ruleVal)
            SmartField.BITRATE -> evaluateNumeric(track.bitrateKbps.toDouble(), rule.operator, ruleVal, secVal)
            SmartField.SAMPLE_RATE -> {
                val assumedSr = if (track.format.uppercase() in listOf("FLAC", "WAV", "AIFF")) 48000.0 else 44100.0
                evaluateNumeric(assumedSr, rule.operator, ruleVal, secVal)
            }
            SmartField.IS_LOSSLESS -> {
                val isLossless = track.format.uppercase() in listOf("FLAC", "WAV", "AIFF", "ALAC")
                if (ruleVal.equals("false", ignoreCase = true) || ruleVal == "0") !isLossless else isLossless
            }
            SmartField.FOLDER -> evaluateString(track.filePath, rule.operator, ruleVal)
            SmartField.HAS_ARTWORK -> {
                val hasArt = !track.artworkCachePath.isNullOrBlank() || !track.artworkUrl.isNullOrBlank()
                if (ruleVal.equals("false", ignoreCase = true) || ruleVal == "0") !hasArt else hasArt
            }
            SmartField.ENERGY -> evaluateNumeric(track.energyRating.toDouble(), rule.operator, ruleVal, secVal)
            SmartField.CUSTOM_TAGS -> evaluateString(track.customTags, rule.operator, ruleVal)
        }
    }

    private fun evaluateString(target: String?, operator: SmartOperator, ruleVal: String): Boolean {
        val actual = (target ?: "").trim().lowercase(Locale.ROOT)
        val expected = ruleVal.lowercase(Locale.ROOT)

        return when (operator) {
            SmartOperator.EQUALS -> actual == expected
            SmartOperator.DOES_NOT_EQUAL -> actual != expected
            SmartOperator.CONTAINS -> actual.contains(expected)
            SmartOperator.DOES_NOT_CONTAIN -> !actual.contains(expected)
            SmartOperator.IS_EMPTY -> actual.isEmpty()
            SmartOperator.IS_NOT_EMPTY -> actual.isNotEmpty()
            SmartOperator.GREATER_THAN -> actual > expected
            SmartOperator.LESS_THAN -> actual < expected
            SmartOperator.BETWEEN -> false
            SmartOperator.BEFORE_DATE -> false
            SmartOperator.AFTER_DATE -> false
        }
    }

    private fun evaluateNumeric(actual: Double, operator: SmartOperator, ruleVal: String, secondaryVal: String): Boolean {
        val expected = ruleVal.toDoubleOrNull() ?: 0.0
        val secondary = secondaryVal.toDoubleOrNull() ?: 0.0

        return when (operator) {
            SmartOperator.EQUALS -> actual == expected
            SmartOperator.DOES_NOT_EQUAL -> actual != expected
            SmartOperator.GREATER_THAN, SmartOperator.AFTER_DATE -> actual > expected
            SmartOperator.LESS_THAN, SmartOperator.BEFORE_DATE -> actual < expected
            SmartOperator.BETWEEN -> {
                val minVal = minOf(expected, secondary)
                val maxVal = maxOf(expected, secondary)
                actual in minVal..maxVal
            }
            SmartOperator.IS_EMPTY -> actual == 0.0
            SmartOperator.IS_NOT_EMPTY -> actual != 0.0
            SmartOperator.CONTAINS -> actual.toString().contains(ruleVal)
            SmartOperator.DOES_NOT_CONTAIN -> !actual.toString().contains(ruleVal)
        }
    }
}
