package com.example.metadata

import android.content.Context

data class MetadataSettings(
    val enrichmentEnabled: Boolean = true,
    val appleSearchEnabled: Boolean = true,
    val theAudioDbEnabled: Boolean = true,
    val storefrontCountry: String = "AU",
    val bpmAnalysisEnabled: Boolean = true,
    val keyAnalysisEnabled: Boolean = true,
    val writeToFileEnabled: Boolean = false,
    val showProvenanceBadges: Boolean = true,
    val concurrency: Int = 2,
    val bpmMin: Int = 60,
    val bpmMax: Int = 300
) {
    init {
        require(bpmMin in BPM_HARD_MIN..BPM_HARD_MAX)
        require(bpmMax in BPM_HARD_MIN..BPM_HARD_MAX)
        require(bpmMin <= bpmMax)
        require(concurrency in 1..MAX_CONCURRENCY)
    }

    companion object {
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

class MetadataSettingsStore(private val context: Context) {
    fun load(): MetadataSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (min, max) = MetadataSettings.clampBpmRange(
            prefs.getInt(KEY_BPM_MIN, MetadataSettings.DEFAULT_BPM_MIN),
            prefs.getInt(KEY_BPM_MAX, MetadataSettings.DEFAULT_BPM_MAX)
        )
        return MetadataSettings(
            enrichmentEnabled = prefs.getBoolean(KEY_ENABLED, true),
            appleSearchEnabled = prefs.getBoolean(KEY_APPLE_SEARCH, true),
            theAudioDbEnabled = prefs.getBoolean(KEY_THEAUDIODB, true),
            storefrontCountry = prefs.getString(KEY_STOREFRONT, "AU") ?: "AU",
            bpmAnalysisEnabled = prefs.getBoolean(KEY_BPM, true),
            keyAnalysisEnabled = prefs.getBoolean(KEY_KEY, true),
            writeToFileEnabled = prefs.getBoolean(KEY_WRITE_TO_FILE, false),
            showProvenanceBadges = prefs.getBoolean(KEY_SHOW_PROVENANCE_BADGES, true),
            concurrency = prefs.getInt(KEY_CONCURRENCY, 2).coerceIn(1, MetadataSettings.MAX_CONCURRENCY),
            bpmMin = min,
            bpmMax = max
        )
    }

    fun save(settings: MetadataSettings) {
        val (min, max) = MetadataSettings.clampBpmRange(settings.bpmMin, settings.bpmMax)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, settings.enrichmentEnabled)
            .putBoolean(KEY_APPLE_SEARCH, settings.appleSearchEnabled)
            .putBoolean(KEY_THEAUDIODB, settings.theAudioDbEnabled)
            .putString(KEY_STOREFRONT, settings.storefrontCountry)
            .putBoolean(KEY_BPM, settings.bpmAnalysisEnabled)
            .putBoolean(KEY_KEY, settings.keyAnalysisEnabled)
            .putBoolean(KEY_WRITE_TO_FILE, settings.writeToFileEnabled)
            .putBoolean(KEY_SHOW_PROVENANCE_BADGES, settings.showProvenanceBadges)
            .putInt(KEY_CONCURRENCY, settings.concurrency.coerceIn(1, MetadataSettings.MAX_CONCURRENCY))
            .putInt(KEY_BPM_MIN, min)
            .putInt(KEY_BPM_MAX, max)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "soundsync_metadata_settings"
        const val KEY_ENABLED = "enrichment_enabled"
        const val KEY_APPLE_SEARCH = "apple_search_enabled"
        const val KEY_THEAUDIODB = "theaudiodb_enabled"
        const val KEY_STOREFRONT = "apple_storefront_country"
        const val KEY_BPM = "bpm_analysis_enabled"
        const val KEY_KEY = "key_analysis_enabled"
        const val KEY_WRITE_TO_FILE = "write_to_file_enabled"
        const val KEY_SHOW_PROVENANCE_BADGES = "show_provenance_badges"
        const val KEY_CONCURRENCY = "enrichment_concurrency"
        const val KEY_BPM_MIN = "bpm_range_min"
        const val KEY_BPM_MAX = "bpm_range_max"
    }
}
