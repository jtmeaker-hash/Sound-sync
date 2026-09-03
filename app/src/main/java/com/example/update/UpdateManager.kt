package com.example.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.model.DownloadProgress
import com.example.model.GitHubRelease
import com.example.model.GitHubReleaseAsset
import com.example.model.SemanticVersion
import com.example.model.UpdateErrorType
import com.example.model.UpdateInfo
import com.example.model.UpdateState
import com.example.network.GitHubReleaseApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Robust GitHub Releases-based in-app update manager for SoundSync.
 *
 * Isolated from audio playback and UI threads.
 * Target repository: jtmeaker-hash/Sound-sync
 * Target package: com.aistudio.soundsync.fxmk
 */
object UpdateManager {

    private const val TAG = "SoundSyncUpdate"

    private const val PREFS_NAME = "soundsync_update_prefs"
    private const val KEY_LAST_CHECKED = "last_checked_timestamp"
    private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"

    const val DEFAULT_REPO_OWNER = "jtmeaker-hash"
    const val DEFAULT_REPO_NAME = "Sound-sync"
    const val LATEST_RELEASE_URL = "https://github.com/jtmeaker-hash/Sound-sync/releases/latest"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _lastCheckedTimestamp = MutableStateFlow(0L)
    val lastCheckedTimestamp: StateFlow<Long> = _lastCheckedTimestamp.asStateFlow()

    private val _isAutoCheckEnabled = MutableStateFlow(true)
    val isAutoCheckEnabled: StateFlow<Boolean> = _isAutoCheckEnabled.asStateFlow()

    private var apiService: GitHubReleaseApiService? = null
    private var preferences: SharedPreferences? = null

    /**
     * Initializes preferences and loads persisted state.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        if (preferences == null) {
            preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        preferences?.let { prefs ->
            _lastCheckedTimestamp.value = prefs.getLong(KEY_LAST_CHECKED, 0L)
            _isAutoCheckEnabled.value = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true)
        }

        if (apiService == null) {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            apiService = GitHubReleaseApiService.create(okHttpClient)
        }

        Log.i(
            TAG,
            "UpdateManager initialized. Current Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}), AutoCheck: ${_isAutoCheckEnabled.value}"
        )
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        init(context)
        _isAutoCheckEnabled.value = enabled
        preferences?.edit()?.putBoolean(KEY_AUTO_CHECK_ENABLED, enabled)?.apply()
        Log.i(TAG, "Automatic update checks set to: $enabled")

        if (enabled) {
            UpdateCheckWorker.schedulePeriodicCheck(context.applicationContext)
        } else {
            UpdateCheckWorker.cancelPeriodicCheck(context.applicationContext)
        }
    }

    /**
     * Asynchronously checks GitHub Releases for new updates.
     */
    fun checkForUpdates(
        context: Context,
        isManual: Boolean = false,
        owner: String = DEFAULT_REPO_OWNER,
        repo: String = DEFAULT_REPO_NAME
    ): Job {
        init(context)

        checkJob?.cancel()
        checkJob = scope.launch {
            _updateState.value = UpdateState.Checking(isManual)
            Log.i(TAG, "Update check started. Querying GitHub: $owner/$repo (Manual=$isManual)")

            try {
                val service = apiService ?: GitHubReleaseApiService.create()
                val response = service.getLatestRelease(owner, repo)

                val now = System.currentTimeMillis()
                _lastCheckedTimestamp.value = now
                preferences?.edit()?.putLong(KEY_LAST_CHECKED, now)?.apply()

                if (!response.isSuccessful) {
                    val code = response.code()
                    val errorBody = response.errorBody()?.string() ?: "HTTP error"
                    Log.w(TAG, "GitHub API returned status code $code: $errorBody")

                    val (errorMessage, errorType) = when {
                        code == 404 -> "No SoundSync release has been published yet." to UpdateErrorType.NO_RELEASE_PUBLISHED
                        code == 403 || code == 429 -> "GitHub API rate limit reached." to UpdateErrorType.RATE_LIMITED
                        else -> "Unable to connect to GitHub." to UpdateErrorType.NETWORK_ERROR
                    }

                    if (isManual) {
                        _updateState.value = UpdateState.Error(
                            message = errorMessage,
                            isManual = true,
                            errorType = errorType
                        )
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                    return@launch
                }

                val release = response.body()
                if (release == null || release.draft) {
                    Log.i(TAG, "No published release found on GitHub.")
                    if (isManual) {
                        _updateState.value = UpdateState.Error(
                            message = "No SoundSync release has been published yet.",
                            isManual = true,
                            errorType = UpdateErrorType.NO_RELEASE_PUBLISHED
                        )
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                    return@launch
                }

                processDiscoveredRelease(release, isManual)

            } catch (e: CancellationException) {
                Log.d(TAG, "Update check was cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates: ${e.message}", e)
                if (isManual) {
                    _updateState.value = UpdateState.Error(
                        message = "Unable to connect to GitHub: ${e.localizedMessage}",
                        isManual = true,
                        errorType = UpdateErrorType.NETWORK_ERROR
                    )
                } else {
                    _updateState.value = UpdateState.Idle
                }
            }
        }

        return checkJob!!
    }

    private suspend fun processDiscoveredRelease(release: GitHubRelease, isManual: Boolean) {
        val currentVersion = SemanticVersion.parse(BuildConfig.VERSION_NAME)
        val releaseTag = release.tagName
        val releaseVersion = SemanticVersion.parse(releaseTag)

        Log.i(
            TAG,
            "Discovered release: tag=${release.tagName}, parsed=$releaseVersion vs current=$currentVersion (app code: ${BuildConfig.VERSION_CODE})"
        )

        val isNewer = releaseVersion.isNewerThan(currentVersion)

        if (!isNewer) {
            Log.i(TAG, "Application is up to date (Current: ${currentVersion.displayString}, Release: ${releaseVersion.displayString}).")
            _updateState.value = UpdateState.UpToDate(System.currentTimeMillis(), isManual)
            return
        }

        val apkAsset = findApkAsset(release.assets)
        val sha256Asset = release.assets.firstOrNull {
            it.name.endsWith(".sha256", ignoreCase = true) || it.name.endsWith(".sha256sum", ignoreCase = true)
        }

        val updateInfo = UpdateInfo(
            releaseId = release.id,
            tagName = release.tagName,
            versionName = releaseVersion.displayString,
            releaseTitle = release.name ?: release.tagName,
            releaseNotes = release.body ?: "Bug fixes and performance improvements.",
            publishedAt = release.publishedAt ?: "",
            apkDownloadUrl = apkAsset?.browserDownloadUrl ?: "",
            apkFileName = apkAsset?.name ?: "",
            apkSizeBytes = apkAsset?.size ?: 0L,
            sha256ChecksumUrl = sha256Asset?.browserDownloadUrl,
            isPrerelease = release.prerelease,
            githubReleaseUrl = if (!release.htmlUrl.isNullOrBlank()) release.htmlUrl else LATEST_RELEASE_URL
        )

        // Check if user previously dismissed this exact version (only suppress on auto-checks)
        val dismissedVersion = preferences?.getString(KEY_DISMISSED_VERSION, null)
        if (!isManual && dismissedVersion == release.tagName) {
            Log.i(TAG, "Release ${release.tagName} was previously dismissed by user. Suppressing popup.")
            _updateState.value = UpdateState.Idle
            return
        }

        _updateState.value = UpdateState.UpdateAvailable(updateInfo, isManual)
    }

    /**
     * Advances to the second confirmation dialog ("Prepare update?") with data warnings.
     */
    fun prepareUpdate(info: UpdateInfo) {
        _updateState.value = UpdateState.PrepareUpdate(info)
    }

    /**
     * Cancels the prepare update dialog and returns to UpdateAvailable or Idle.
     */
    fun cancelPrepareUpdate(info: UpdateInfo? = null) {
        _updateState.value = if (info != null) {
            UpdateState.UpdateAvailable(info)
        } else {
            UpdateState.Idle
        }
    }

    /**
     * Executes the clean uninstall/reinstall update flow:
     * 1. Verifies a web browser / activity can handle ACTION_VIEW for the GitHub Releases URL.
     *    If no browser is available: DO NOT uninstall, display an error, leave SoundSync installed.
     * 2. Opens https://github.com/jtmeaker-hash/Sound-sync/releases/latest in external browser task.
     * 3. Immediately launches Android's system package uninstall prompt for SoundSync.
     */
    fun openReleaseAndUninstall(context: Context, releaseUrl: String = LATEST_RELEASE_URL): Boolean {
        val uri = Uri.parse(releaseUrl)
        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val packageManager = context.packageManager
        val canOpenBrowser = try {
            val resolved = browserIntent.resolveActivity(packageManager)
            if (resolved != null) {
                true
            } else {
                val activities = packageManager.queryIntentActivities(browserIntent, 0)
                activities.isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }

        if (!canOpenBrowser) {
            Log.e(TAG, "No web browser activity found to handle release URL: $releaseUrl")
            _updateState.value = UpdateState.Error(
                message = "Could not find a web browser to open the release page ($releaseUrl). Update aborted; SoundSync remains installed.",
                isManual = true,
                errorType = UpdateErrorType.BROWSER_NOT_FOUND
            )
            return false
        }

        try {
            Log.i(TAG, "Opening GitHub Releases in external browser: $releaseUrl")
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser intent: ${e.message}", e)
            _updateState.value = UpdateState.Error(
                message = "Failed to launch web browser: ${e.localizedMessage}. SoundSync remains installed.",
                isManual = true,
                errorType = UpdateErrorType.BROWSER_NOT_FOUND
            )
            return false
        }

        // Launch Android's system package uninstall prompt for SoundSync
        try {
            Log.i(TAG, "Launching system package uninstall for: ${context.packageName}")
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(uninstallIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ACTION_DELETE uninstall intent: ${e.message}", e)
            try {
                @Suppress("DEPRECATION")
                val fallbackIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:${context.packageName}")
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed fallback uninstall intent: ${e2.message}", e2)
            }
        }

        _updateState.value = UpdateState.Idle
        return true
    }

    /**
     * User dismissed current update dialog ("Later").
     */
    fun dismissUpdate(tagName: String? = null) {
        if (!tagName.isNullOrBlank()) {
            preferences?.edit()?.putString(KEY_DISMISSED_VERSION, tagName)?.apply()
        }
        _updateState.value = UpdateState.Idle
    }

    /**
     * Finds the most appropriate .apk asset from the release assets list.
     */
    fun findApkAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
        if (assets.isEmpty()) return null
        val exactMatch = assets.firstOrNull {
            it.name.contains("SoundSync", ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch
        return assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    /**
     * Extracts SHA-256 hex string from release sha256 output.
     */
    fun extractSha256Hex(text: String): String {
        val match = Regex("""([a-fA-F0-9]{64})""").find(text)
        return match?.value?.lowercase(Locale.US) ?: ""
    }
}
