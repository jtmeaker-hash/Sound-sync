package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.ui.LocalCategory

/**
 * Crash Protection Manager for SoundSync.
 *
 * Prevents crash loops when restoring previously selected navigation destinations.
 * If a screen crashes during startup (within 10-15 seconds of launch), this manager
 * detects the failure and safely falls back to a safe destination (LocalCategory.SONGS)
 * on the next launch, while preserving the user's normal tab memory under healthy conditions.
 */
object CrashProtectionManager {

    private const val TAG = "CrashProtectionManager"
    private const val PREFS_NAME = "soundsync_crash_protection"
    private const val KEY_STARTUP_ACTIVE = "startup_active"
    private const val KEY_STARTUP_TIMESTAMP = "startup_timestamp"
    private const val KEY_LAST_CATEGORY = "last_category"
    private const val KEY_FAILED_CATEGORY = "failed_category"
    private const val KEY_CONSECUTIVE_STARTUP_CRASHES = "consecutive_startup_crashes"
    private const val STARTUP_WINDOW_MS = 15_000L

    @Volatile
    private var currentActiveCategory: String = LocalCategory.SONGS.name

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Call at app startup to record launch state and check whether emergency recovery is needed.
     */
    fun checkAndApplyRecovery(context: Context, requestedCategory: LocalCategory): LocalCategory {
        val prefs = getPrefs(context)
        val startupActive = prefs.getBoolean(KEY_STARTUP_ACTIVE, false)
        val lastTimestamp = prefs.getLong(KEY_STARTUP_TIMESTAMP, 0L)
        val failedCategory = prefs.getString(KEY_FAILED_CATEGORY, null)
        val consecutiveCrashes = prefs.getInt(KEY_CONSECUTIVE_STARTUP_CRASHES, 0)
        val timeSinceLastStartup = System.currentTimeMillis() - lastTimestamp

        currentActiveCategory = requestedCategory.name

        // Check if the previous session died while startup was active within 60 seconds
        val hadUnfinishedStartup = startupActive && timeSinceLastStartup < 60_000L
        val destinationToRecover = failedCategory ?: if (hadUnfinishedStartup) prefs.getString(KEY_LAST_CATEGORY, null) else null

        if ((hadUnfinishedStartup || consecutiveCrashes > 0) && destinationToRecover == requestedCategory.name) {
            Log.w(
                TAG,
                "EMERGENCY RECOVERY TRIGGERED: Previous session crashed during startup on destination " +
                    "'$destinationToRecover' (consecutive crashes: $consecutiveCrashes). " +
                    "Falling back to SONGS to break crash loop."
            )
            // Clear the failure marker now that we recovered
            prefs.edit()
                .putBoolean(KEY_STARTUP_ACTIVE, true)
                .putLong(KEY_STARTUP_TIMESTAMP, System.currentTimeMillis())
                .putString(KEY_LAST_CATEGORY, LocalCategory.SONGS.name)
                .remove(KEY_FAILED_CATEGORY)
                .putInt(KEY_CONSECUTIVE_STARTUP_CRASHES, 0)
                .apply()
            currentActiveCategory = LocalCategory.SONGS.name
            return LocalCategory.SONGS
        }

        // Normal startup: mark startup as active and record requested destination
        prefs.edit()
            .putBoolean(KEY_STARTUP_ACTIVE, true)
            .putLong(KEY_STARTUP_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_LAST_CATEGORY, requestedCategory.name)
            .apply()

        return requestedCategory
    }

    /**
     * Updates the currently active local library category.
     */
    fun recordCurrentCategory(context: Context, category: LocalCategory) {
        currentActiveCategory = category.name
        try {
            getPrefs(context).edit().putString(KEY_LAST_CATEGORY, category.name).apply()
        } catch (_: Exception) {}
    }

    /**
     * Call when an uncaught exception occurs to record the crash.
     */
    fun recordCrash(context: Context) {
        try {
            val prefs = getPrefs(context)
            val currentCrashes = prefs.getInt(KEY_CONSECUTIVE_STARTUP_CRASHES, 0)
            prefs.edit()
                .putString(KEY_FAILED_CATEGORY, currentActiveCategory)
                .putInt(KEY_CONSECUTIVE_STARTUP_CRASHES, currentCrashes + 1)
                .putBoolean(KEY_STARTUP_ACTIVE, true)
                .commit()
            Log.e(TAG, "Recorded crash on destination '$currentActiveCategory' (crash count: ${currentCrashes + 1})")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to record crash state: ${t.message}")
        }
    }

    /**
     * Call once the app has been running stably for > 15 seconds to clear startup crash tracking.
     */
    fun markStartupHealthy(context: Context) {
        try {
            getPrefs(context).edit()
                .putBoolean(KEY_STARTUP_ACTIVE, false)
                .remove(KEY_FAILED_CATEGORY)
                .putInt(KEY_CONSECUTIVE_STARTUP_CRASHES, 0)
                .apply()
            Log.d(TAG, "Startup marked healthy for destination '$currentActiveCategory'")
        } catch (_: Exception) {}
    }
}
