package com.example.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

enum class ScanStatus {
    IDLE,
    SCANNING,
    COMPLETED,
    FAILED
}

/**
 * Manages persisted state of the media scanning lifecycle.
 * Prevents crash loops and infinite scans across app restarts.
 */
class ScanStateManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var status: ScanStatus
        get() {
            val name = prefs.getString(KEY_STATUS, ScanStatus.IDLE.name) ?: ScanStatus.IDLE.name
            return try {
                ScanStatus.valueOf(name)
            } catch (e: Exception) {
                ScanStatus.IDLE
            }
        }
        set(value) {
            prefs.edit().putString(KEY_STATUS, value.name).apply()
        }

    var lastScanTime: Long
        get() = prefs.getLong(KEY_LAST_SCAN_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SCAN_TIME, value).apply()

    var lastScannedCount: Int
        get() = prefs.getInt(KEY_LAST_SCANNED_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SCANNED_COUNT, value).apply()

    var lastErrorMessage: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    /**
     * Check if a previous scan crashed or was killed by the OS mid-execution.
     * If status is SCANNING at startup, we reset to FAILED/IDLE and record the recovery
     * to prevent repeating broken scan loops automatically on every launch.
     */
    fun checkAndRecoverInterruptedScan(): Boolean {
        if (status == ScanStatus.SCANNING) {
            Log.w(TAG, "Detected interrupted scan from previous app session. Safely recovering state.")
            status = ScanStatus.FAILED
            lastErrorMessage = "Previous scan was interrupted or app was closed during scan."
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "ScanStateManager"
        private const val PREFS_NAME = "soundsync_scan_state_prefs"
        private const val KEY_STATUS = "key_scan_status"
        private const val KEY_LAST_SCAN_TIME = "key_last_scan_time"
        private const val KEY_LAST_SCANNED_COUNT = "key_last_scanned_count"
        private const val KEY_LAST_ERROR = "key_last_error"
    }
}
