package com.example.diagnostics

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the activation and persistence of SoundSync Developer Mode.
 *
 * Developer Mode is hidden by default to avoid cluttering standard DJ workflows.
 * It is unlocked by tapping the SoundSync version number 7 times in succession,
 * long-pressing the version badge, or explicitly toggling it via preferences.
 */
class DeveloperModeManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "soundsync_developer_mode"
        private const val KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled"
        private const val REQUIRED_TAPS = 7
        private const val TAP_WINDOW_MS = 3500L

        @Volatile
        private var instance: DeveloperModeManager? = null

        fun getInstance(context: Context): DeveloperModeManager {
            return instance ?: synchronized(this) {
                instance ?: DeveloperModeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isDeveloperModeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DEVELOPER_MODE_ENABLED, false)
    )
    val isDeveloperModeEnabled: StateFlow<Boolean> = _isDeveloperModeEnabled.asStateFlow()

    private var tapCount = 0
    private var lastTapTimestamp = 0L

    /**
     * Registers a tap on the version indicator.
     * Returns true if Developer Mode is now active (or just became active).
     */
    fun registerTap(): Boolean {
        if (_isDeveloperModeEnabled.value) {
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastTapTimestamp > TAP_WINDOW_MS) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTimestamp = now

        val remaining = REQUIRED_TAPS - tapCount

        if (tapCount >= REQUIRED_TAPS) {
            setDeveloperModeEnabled(true)
            tapCount = 0
            try {
                Toast.makeText(context, "Developer Mode enabled! Diagnostics unlocked.", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                // Ignore in headless / test environments
            }
            return true
        } else if (remaining in 1..4) {
            try {
                Toast.makeText(context, "Tap $remaining more times to unlock Developer Mode", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                // Ignore
            }
        }
        return false
    }

    /**
     * Directly sets Developer Mode status.
     */
    fun setDeveloperModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE_ENABLED, enabled).apply()
        _isDeveloperModeEnabled.value = enabled
        DiagnosticLogger.getInstance().info(
            DiagnosticSubsystem.SYSTEM,
            "DEV_MODE_CHANGED",
            "Developer Mode set to $enabled"
        )
    }

    /**
     * Toggles Developer Mode on or off.
     */
    fun toggleDeveloperMode(): Boolean {
        val next = !_isDeveloperModeEnabled.value
        setDeveloperModeEnabled(next)
        return next
    }
}
