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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _lastCheckedTimestamp = MutableStateFlow(0L)
    val lastCheckedTimestamp: StateFlow<Long> = _lastCheckedTimestamp.asStateFlow()

    private val _isAutoCheckEnabled = MutableStateFlow(true)
    val isAutoCheckEnabled: StateFlow<Boolean> = _isAutoCheckEnabled.asStateFlow()

    private var apiService: GitHubReleaseApiService? = null
    private var preferences: SharedPreferences? = null

    /**
     * Pending install file when user is redirected to grant Unknown Sources permission.
     */
    var pendingInstallApk: File? = null
        private set

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

        // Don't interrupt an active download
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading || currentState is UpdateState.Installing) {
            Log.d(TAG, "Skipping update check because download/install is active.")
            return Job().apply { complete() }
        }

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
                        message = "Unable to connect to GitHub.",
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

        // Look for APK asset in release
        val apkAsset = findApkAsset(release.assets)
        if (apkAsset == null) {
            Log.w(TAG, "Release $releaseTag does not have an attached .apk asset.")
            if (isManual) {
                _updateState.value = UpdateState.Error(
                    message = "Latest release does not contain an APK.",
                    isManual = true,
                    errorType = UpdateErrorType.NO_RELEASE_ASSETS
                )
            } else {
                _updateState.value = UpdateState.Idle
            }
            return
        }

        // Look for companion .sha256 asset if present
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
            apkDownloadUrl = apkAsset.browserDownloadUrl,
            apkFileName = apkAsset.name,
            apkSizeBytes = apkAsset.size,
            sha256ChecksumUrl = sha256Asset?.browserDownloadUrl,
            isPrerelease = release.prerelease,
            githubReleaseUrl = release.htmlUrl
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
     * Finds the most appropriate .apk asset from the release assets list.
     */
    fun findApkAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset? {
        if (assets.isEmpty()) return null
        // 1. Exact match for soundsync release apk
        val exactMatch = assets.firstOrNull {
            it.name.contains("SoundSync", ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch

        // 2. Any .apk file
        return assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    /**
     * Starts downloading the update APK with real-time progress tracking.
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        init(context)

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val updateDir = getUpdatesDirectory(context)
            if (!updateDir.exists()) {
                updateDir.mkdirs()
            }

            val sanitizedFileName = info.apkFileName.ifBlank { "SoundSync-${info.tagName}.apk" }
            val tempFile = File(updateDir, "$sanitizedFileName.download")
            val targetFile = File(updateDir, sanitizedFileName)

            // If file already completely downloaded and matches size, check verification
            if (targetFile.exists() && targetFile.length() == info.apkSizeBytes && info.apkSizeBytes > 0) {
                Log.i(TAG, "Found existing completed APK download at ${targetFile.absolutePath}")
                _updateState.value = UpdateState.Downloaded(info, targetFile, isVerified = true)
                return@launch
            }

            _updateState.value = UpdateState.Downloading(
                info = info,
                progress = DownloadProgress(
                    progressFraction = 0f,
                    bytesDownloaded = 0L,
                    totalBytes = info.apkSizeBytes
                )
            )

            Log.i(TAG, "Starting APK download from: ${info.apkDownloadUrl} -> ${targetFile.absolutePath}")

            try {
                val service = apiService ?: GitHubReleaseApiService.create()
                val response = service.downloadFile(info.apkDownloadUrl)

                if (!response.isSuccessful) {
                    throw IllegalStateException("Download HTTP failed with status ${response.code()}")
                }

                val body = response.body() ?: throw IllegalStateException("Download response body was empty")
                val totalBytes = if (body.contentLength() > 0) body.contentLength() else info.apkSizeBytes

                var downloadedBytes = 0L
                var lastProgressUpdate = 0L
                val buffer = ByteArray(32 * 1024) // 32KB buffer

                body.byteStream().use { input: InputStream ->
                    FileOutputStream(tempFile).use { output: FileOutputStream ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 100 || downloadedBytes == totalBytes) {
                                lastProgressUpdate = now
                                val fraction = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else 0f

                                _updateState.value = UpdateState.Downloading(
                                    info = info,
                                    progress = DownloadProgress(
                                        progressFraction = fraction,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                // Verify file is non-empty
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    throw IllegalStateException("Downloaded APK file is empty or missing")
                }

                // Verify expected SHA-256 if checksum URL is provided
                var sha256Matches: Boolean? = null
                if (!info.sha256ChecksumUrl.isNullOrBlank()) {
                    try {
                        val shaResponse = service.downloadFile(info.sha256ChecksumUrl)
                        if (shaResponse.isSuccessful) {
                            val shaText = shaResponse.body()?.string()?.trim() ?: ""
                            val expectedHash = extractSha256Hex(shaText)
                            if (expectedHash.isNotBlank()) {
                                val actualHash = calculateSha256(tempFile)
                                val matches = actualHash.equals(expectedHash, ignoreCase = true)
                                sha256Matches = matches
                                Log.i(TAG, "SHA-256 Checksum: expected=$expectedHash, actual=$actualHash, matches=$matches")
                                if (!matches) {
                                    tempFile.delete()
                                    throw IllegalStateException("SHA-256 checksum mismatch! Expected: $expectedHash, got: $actualHash")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not verify SHA-256 asset: ${e.message}")
                    }
                }

                // Atomic rename temp -> target
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)

                Log.i(TAG, "APK download completed successfully: ${targetFile.length()} bytes")
                _updateState.value = UpdateState.Downloaded(
                    info = info,
                    apkFile = targetFile,
                    isVerified = true,
                    sha256Verified = sha256Matches
                )

            } catch (e: CancellationException) {
                Log.d(TAG, "APK download was cancelled by user.")
                if (tempFile.exists()) tempFile.delete()
                _updateState.value = UpdateState.UpdateAvailable(info)
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed: ${e.message}", e)
                if (tempFile.exists()) tempFile.delete()
                _updateState.value = UpdateState.Error(
                    message = "Download failed: ${e.localizedMessage ?: "Unknown error"}",
                    isManual = true,
                    errorType = UpdateErrorType.DOWNLOAD_FAILED
                )
            }
        }
    }

    /**
     * Cancels active download.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        val current = _updateState.value
        if (current is UpdateState.Downloading) {
            _updateState.value = UpdateState.UpdateAvailable(current.info)
        } else {
            _updateState.value = UpdateState.Idle
        }
    }

    /**
     * Requests installation of the downloaded APK using Android PackageInstaller.
     */
    fun installApk(activity: Activity, apkFile: File, info: UpdateInfo? = null) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "Cannot install: APK file does not exist or is 0 bytes at ${apkFile.absolutePath}")
            _updateState.value = UpdateState.Error("APK file not found on device", isManual = true)
            return
        }

        // On Android 8.0+ (Oreo, API 26+), verify Unknown Sources permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageManager = activity.packageManager
            if (!packageManager.canRequestPackageInstalls()) {
                Log.i(TAG, "Unknown apps installation permission not granted. Prompting user to Settings.")
                pendingInstallApk = apkFile
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open UNKNOWN_APP_SOURCES settings: ${e.message}")
                    // Fallback to generic security settings
                    try {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to open SECURITY_SETTINGS: ${e2.message}")
                    }
                }
                return
            }
        }

        try {
            Log.i(TAG, "Launching Android PackageInstaller for: ${apkFile.absolutePath}")
            val authority = "${activity.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(activity, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (info != null) {
                _updateState.value = UpdateState.Installing(info, apkFile)
            }

            pendingInstallApk = null
            activity.startActivity(installIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            _updateState.value = UpdateState.Error(
                message = "Failed to launch installer: ${e.localizedMessage}",
                isManual = true,
                errorType = UpdateErrorType.INSTALLATION_FAILED
            )
        }
    }

    /**
     * Resumes install if returning from Android Settings with granted permission.
     */
    fun resumePendingInstall(activity: Activity) {
        val apk = pendingInstallApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (activity.packageManager.canRequestPackageInstalls()) {
                Log.i(TAG, "User returned from Settings with granted install permissions. Resuming install.")
                installApk(activity, apk)
            }
        } else {
            installApk(activity, apk)
        }
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
     * Calculates SHA-256 hash of a file.
     */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts SHA-256 hex string from release sha256 output.
     */
    fun extractSha256Hex(text: String): String {
        val match = Regex("""([a-fA-F0-9]{64})""").find(text)
        return match?.value?.lowercase(Locale.US) ?: ""
    }

    private fun getUpdatesDirectory(context: Context): File {
        val dir = File(context.filesDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
