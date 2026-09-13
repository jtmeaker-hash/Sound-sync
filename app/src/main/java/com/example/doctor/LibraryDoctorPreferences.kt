package com.example.doctor

import android.content.Context
import android.content.SharedPreferences

/**
 * SoundSync Library Doctor Preferences:
 * Persists ignored issues, review statuses, and custom user decisions across scans.
 */
class LibraryDoctorPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "soundsync_library_doctor_prefs"
        private const val KEY_IGNORED = "ignored_issues"
        private const val KEY_REVIEWED = "reviewed_issues"
        private const val KEY_FIXED = "fixed_issues"

        @Volatile
        private var INSTANCE: LibraryDoctorPreferences? = null

        fun getInstance(context: Context): LibraryDoctorPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryDoctorPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getIssueStatus(issueId: String): DoctorReviewStatus {
        val ignored = prefs.getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()
        if (issueId in ignored) return DoctorReviewStatus.IGNORED

        val fixed = prefs.getStringSet(KEY_FIXED, emptySet()) ?: emptySet()
        if (issueId in fixed) return DoctorReviewStatus.FIXED

        val reviewed = prefs.getStringSet(KEY_REVIEWED, emptySet()) ?: emptySet()
        if (issueId in reviewed) return DoctorReviewStatus.NEEDS_REVIEW

        return DoctorReviewStatus.OPEN
    }

    @Synchronized
    fun setIssueStatus(issueId: String, status: DoctorReviewStatus) {
        when (status) {
            DoctorReviewStatus.OPEN -> resetIssue(issueId)
            DoctorReviewStatus.IGNORED -> ignoreIssue(issueId)
            DoctorReviewStatus.NEEDS_REVIEW -> markReviewed(issueId)
            DoctorReviewStatus.FIXED -> markFixed(issueId)
        }
    }

    @Synchronized
    fun isIgnored(issueId: String): Boolean {
        val ignored = prefs.getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()
        return issueId in ignored
    }

    @Synchronized
    fun ignoreIssue(issueId: String) {
        val current = (prefs.getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()).toMutableSet()
        current.add(issueId)
        prefs.edit().putStringSet(KEY_IGNORED, current).apply()

        // Remove from other status sets
        removeFromStringSet(KEY_REVIEWED, issueId)
        removeFromStringSet(KEY_FIXED, issueId)
    }

    @Synchronized
    fun unignoreIssue(issueId: String) {
        removeFromStringSet(KEY_IGNORED, issueId)
    }

    @Synchronized
    fun markReviewed(issueId: String) {
        val current = (prefs.getStringSet(KEY_REVIEWED, emptySet()) ?: emptySet()).toMutableSet()
        current.add(issueId)
        prefs.edit().putStringSet(KEY_REVIEWED, current).apply()

        removeFromStringSet(KEY_IGNORED, issueId)
        removeFromStringSet(KEY_FIXED, issueId)
    }

    @Synchronized
    fun markFixed(issueId: String) {
        val current = (prefs.getStringSet(KEY_FIXED, emptySet()) ?: emptySet()).toMutableSet()
        current.add(issueId)
        prefs.edit().putStringSet(KEY_FIXED, current).apply()

        removeFromStringSet(KEY_IGNORED, issueId)
        removeFromStringSet(KEY_REVIEWED, issueId)
    }

    @Synchronized
    fun resetIssue(issueId: String) {
        removeFromStringSet(KEY_IGNORED, issueId)
        removeFromStringSet(KEY_REVIEWED, issueId)
        removeFromStringSet(KEY_FIXED, issueId)
    }

    @Synchronized
    fun getIgnoredCount(): Int {
        return (prefs.getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()).size
    }

    @Synchronized
    fun getReviewedCount(): Int {
        return (prefs.getStringSet(KEY_REVIEWED, emptySet()) ?: emptySet()).size
    }

    @Synchronized
    fun clearAllPreferences() {
        prefs.edit().clear().apply()
    }

    private fun removeFromStringSet(key: String, item: String) {
        val set = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        if (set.remove(item)) {
            prefs.edit().putStringSet(key, set).apply()
        }
    }
}
