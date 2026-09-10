package com.example.metadata

import android.content.Context

data class MetadataSettings(
    val enrichmentEnabled: Boolean = true,
    // Metadata Safety Settings (Section 17)
    val backgroundScanningEnabled: Boolean = true,
    val autoSearchEnabled: Boolean = true,
    val autoQueueVerified: Boolean = true,
    val replaceExistingTitle: Boolean = false,
    val replaceExistingArtist: Boolean = false,
    val replaceExistingArtwork: Boolean = false,
    val writeMetadataOnlyAfterApproval: Boolean = true,
    val keepOriginalMetadataBackup: Boolean = true,
    // External Catalog & Analysis Settings
    val appleSearchEnabled: Boolean = true,
    val theAudioDbEnabled: Boolean = true,
    val autoFindArtwork: Boolean = true,
    val backgroundArtworkScanning: Boolean = true,
    val artworkFallbackEnabled: Boolean = true,
    val storefrontCountry: String = "AU",
    val bpmAnalysisEnabled: Boolean = true,
    val keyAnalysisEnabled: Boolean = true,
    val writeToFileEnabled: Boolean = true,
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
            backgroundScanningEnabled = prefs.getBoolean(KEY_BACKGROUND_SCANNING, true),
            autoSearchEnabled = prefs.getBoolean(KEY_AUTO_SEARCH, true),
            autoQueueVerified = prefs.getBoolean(KEY_AUTO_QUEUE_VERIFIED, true),
            replaceExistingTitle = prefs.getBoolean(KEY_REPLACE_TITLE, false),
            replaceExistingArtist = prefs.getBoolean(KEY_REPLACE_ARTIST, false),
            replaceExistingArtwork = prefs.getBoolean(KEY_REPLACE_ARTWORK, false),
            writeMetadataOnlyAfterApproval = prefs.getBoolean(KEY_WRITE_ONLY_AFTER_APPROVAL, true),
            keepOriginalMetadataBackup = prefs.getBoolean(KEY_KEEP_BACKUP, true),
            appleSearchEnabled = prefs.getBoolean(KEY_APPLE_SEARCH, true),
            theAudioDbEnabled = prefs.getBoolean(KEY_THEAUDIODB, true),
            autoFindArtwork = prefs.getBoolean(KEY_AUTO_FIND_ARTWORK, true),
            backgroundArtworkScanning = prefs.getBoolean(KEY_BG_ARTWORK_SCAN, true),
            artworkFallbackEnabled = prefs.getBoolean(KEY_ARTWORK_FALLBACK, true),
            storefrontCountry = prefs.getString(KEY_STOREFRONT, "AU") ?: "AU",
            bpmAnalysisEnabled = prefs.getBoolean(KEY_BPM, true),
            keyAnalysisEnabled = prefs.getBoolean(KEY_KEY, true),
            writeToFileEnabled = prefs.getBoolean(KEY_WRITE_TO_FILE, true),
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
            .putBoolean(KEY_BACKGROUND_SCANNING, settings.backgroundScanningEnabled)
            .putBoolean(KEY_AUTO_SEARCH, settings.autoSearchEnabled)
            .putBoolean(KEY_AUTO_QUEUE_VERIFIED, settings.autoQueueVerified)
            .putBoolean(KEY_REPLACE_TITLE, settings.replaceExistingTitle)
            .putBoolean(KEY_REPLACE_ARTIST, settings.replaceExistingArtist)
            .putBoolean(KEY_REPLACE_ARTWORK, settings.replaceExistingArtwork)
            .putBoolean(KEY_WRITE_ONLY_AFTER_APPROVAL, settings.writeMetadataOnlyAfterApproval)
            .putBoolean(KEY_KEEP_BACKUP, settings.keepOriginalMetadataBackup)
            .putBoolean(KEY_APPLE_SEARCH, settings.appleSearchEnabled)
            .putBoolean(KEY_THEAUDIODB, settings.theAudioDbEnabled)
            .putBoolean(KEY_AUTO_FIND_ARTWORK, settings.autoFindArtwork)
            .putBoolean(KEY_BG_ARTWORK_SCAN, settings.backgroundArtworkScanning)
            .putBoolean(KEY_ARTWORK_FALLBACK, settings.artworkFallbackEnabled)
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
        const val KEY_BACKGROUND_SCANNING = "background_metadata_scanning"
        const val KEY_AUTO_SEARCH = "automatically_search_metadata"
        const val KEY_AUTO_QUEUE_VERIFIED = "automatically_queue_verified"
        const val KEY_REPLACE_TITLE = "replace_existing_title"
        const val KEY_REPLACE_ARTIST = "replace_existing_artist"
        const val KEY_REPLACE_ARTWORK = "replace_existing_artwork"
        const val KEY_WRITE_ONLY_AFTER_APPROVAL = "write_metadata_only_after_approval"
        const val KEY_KEEP_BACKUP = "keep_original_metadata_backup"
        const val KEY_APPLE_SEARCH = "apple_search_enabled"
        const val KEY_THEAUDIODB = "theaudiodb_enabled"
        const val KEY_AUTO_FIND_ARTWORK = "auto_find_artwork"
        const val KEY_BG_ARTWORK_SCAN = "background_artwork_scanning"
        const val KEY_ARTWORK_FALLBACK = "artwork_fallback_enabled"
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
