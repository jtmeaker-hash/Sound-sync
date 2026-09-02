package com.example.metadata

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User-controllable metadata pipeline settings, persisted in SharedPreferences.
 *
 * BPM_RANGE: the tempo window the local BPM analyzer searches. Defaults to
 * 60-300 BPM (the analyzer folds out-of-range candidates by half/double into
 * this window). Users working exclusively in, say, drum & bass may narrow the
 * window to 130-200 to disambiguate 85-vs-170 style half/double conflicts.
 */
data class MetadataSettings(
    val enrichmentEnabled: Boolean = true,
    val musicBrainzEnabled: Boolean = true,
    val bpmAnalysisEnabled: Boolean = true,
    val keyAnalysisEnabled: Boolean = true,
    val writeToFileEnabled: Boolean = false,
    val concurrency: Int = 2,
    val bpmMin: Int = 60,
    val bpmMax: Int = 300,
) {
    init {
        require(bpmMin in 30..BPM_HARD_MAX) { "bpmMin must be within 30..$BPM_HARD_MAX" }
        require(bpmMax in 30..BPM_HARD_MAX) { "bpmMax must be within 30..$BPM_HARD_MAX" }
        require(bpmMin <= bpmMax) { "bpmMin must not exceed bpmMax" }
        require(concurrency in 1..MAX_CONCURRENCY) { "concurrency must be within 1..$MAX_CONCURRENCY" }
    }

    companion object {
        /** Absolute analyzer bounds regardless of user settings. */
        const val BPM_HARD_MIN = 30
        const val BPM_HARD_MAX = 300
        const val MAX_CONCURRENCY = 4
        const val DEFAULT_BPM_MIN = 60
        const val DEFAULT_BPM_MAX = 300

        fun clampBpmRange(min: Int, max: Int): Pair<Int, Int> {
            val lo = min.coerceIn(BPM_HARD_MIN, BPM_HARD_MAX)
            val hi = max.coerceIn(BPM_HARD_MIN, BPM_HARD_MAX)
            return if (lo <= hi) lo to hi else hi to lo
        }
    }
}

/** Loads/saves [MetadataSettings] from the app's SharedPreferences store. */
class MetadataSettingsStore(private val context: Context) {
    fun load(): MetadataSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (bpmMin, bpmMax) = MetadataSettings.clampBpmRange(
            prefs.getInt(KEY_BPM_MIN, MetadataSettings.DEFAULT_BPM_MIN),
            prefs.getInt(KEY_BPM_MAX, MetadataSettings.DEFAULT_BPM_MAX),
        )
        return MetadataSettings(
            enrichmentEnabled = prefs.getBoolean(KEY_ENABLED, true),
            musicBrainzEnabled = prefs.getBoolean(KEY_MUSICBRAINZ, true),
            bpmAnalysisEnabled = prefs.getBoolean(KEY_BPM, true),
            keyAnalysisEnabled = prefs.getBoolean(KEY_KEY, true),
            writeToFileEnabled = prefs.getBoolean(KEY_WRITE_TO_FILE, false),
            concurrency = prefs.getInt(KEY_CONCURRENCY, 2).coerceIn(1, MetadataSettings.MAX_CONCURRENCY),
            bpmMin = bpmMin,
            bpmMax = bpmMax,
        )
    }

    fun save(settings: MetadataSettings) {
        val (bpmMin, bpmMax) = MetadataSettings.clampBpmRange(settings.bpmMin, settings.bpmMax)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, settings.enrichmentEnabled)
            .putBoolean(KEY_MUSICBRAINZ, settings.musicBrainzEnabled)
            .putBoolean(KEY_BPM, settings.bpmAnalysisEnabled)
            .putBoolean(KEY_KEY, settings.keyAnalysisEnabled)
            .putBoolean(KEY_WRITE_TO_FILE, settings.writeToFileEnabled)
            .putInt(KEY_CONCURRENCY, settings.concurrency.coerceIn(1, MetadataSettings.MAX_CONCURRENCY))
            .putInt(KEY_BPM_MIN, bpmMin)
            .putInt(KEY_BPM_MAX, bpmMax)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "soundsync_metadata_settings"
        private const val KEY_ENABLED = "enrichment_enabled"
        private const val KEY_MUSICBRAINZ = "musicbrainz_enabled"
        private const val KEY_BPM = "bpm_analysis_enabled"
        private const val KEY_KEY = "key_analysis_enabled"
        private const val KEY_WRITE_TO_FILE = "write_to_file_enabled"
        private const val KEY_CONCURRENCY = "enrichment_concurrency"
        private const val KEY_BPM_MIN = "bpm_range_min"
        private const val KEY_BPM_MAX = "bpm_range_max"
    }
}
