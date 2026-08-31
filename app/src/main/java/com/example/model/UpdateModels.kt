package com.example.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.io.File

/**
 * GitHub API Release Response Model.
 */
@JsonClass(generateAdapter = true)
data class GitHubRelease(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "tag_name") val tagName: String = "",
    @Json(name = "name") val name: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "draft") val draft: Boolean = false,
    @Json(name = "prerelease") val prerelease: Boolean = false,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null,
    @Json(name = "assets") val assets: List<GitHubReleaseAsset> = emptyList()
)

/**
 * GitHub Release Asset (e.g. .apk or .sha256).
 */
@JsonClass(generateAdapter = true)
data class GitHubReleaseAsset(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "name") val name: String = "",
    @Json(name = "size") val size: Long = 0,
    @Json(name = "content_type") val contentType: String? = null,
    @Json(name = "browser_download_url") val browserDownloadUrl: String = "",
    @Json(name = "created_at") val createdAt: String? = null
)

/**
 * Processed Application Update Information.
 */
data class UpdateInfo(
    val releaseId: Long,
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAt: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val sha256ChecksumUrl: String? = null,
    val isPrerelease: Boolean = false,
    val githubReleaseUrl: String? = null
) {
    val formattedSize: String
        get() {
            if (apkSizeBytes <= 0) return "Unknown size"
            val mb = apkSizeBytes.toDouble() / (1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f MB", mb)
        }
}

/**
 * Live download progress info.
 */
data class DownloadProgress(
    val progressFraction: Float, // 0.0f to 1.0f
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0
) {
    val progressPercent: Int
        get() = (progressFraction * 100).toInt().coerceIn(0, 100)

    val formattedProgress: String
        get() {
            val downMb = bytesDownloaded.toDouble() / (1024 * 1024)
            return if (totalBytes > 0) {
                val totalMb = totalBytes.toDouble() / (1024 * 1024)
                String.format(java.util.Locale.US, "%.1f / %.1f MB (%d%%)", downMb, totalMb, progressPercent)
            } else {
                String.format(java.util.Locale.US, "%.1f MB downloaded", downMb)
            }
        }
}

/**
 * State machine for In-App Updates.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Checking(val isManual: Boolean) : UpdateState
    data class UpdateAvailable(val info: UpdateInfo, val isManual: Boolean = false) : UpdateState
    data class UpToDate(val checkedAt: Long = System.currentTimeMillis(), val isManual: Boolean = false) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: DownloadProgress) : UpdateState
    data class Downloaded(
        val info: UpdateInfo,
        val apkFile: File,
        val isVerified: Boolean = true,
        val sha256Verified: Boolean? = null
    ) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apkFile: File) : UpdateState
    data class Installing(val info: UpdateInfo, val apkFile: File) : UpdateState
    data class Error(val message: String, val isManual: Boolean = false, val errorType: UpdateErrorType = UpdateErrorType.GENERAL) : UpdateState
}

enum class UpdateErrorType {
    NETWORK_ERROR,
    RATE_LIMITED,
    NO_RELEASE_ASSETS,
    DOWNLOAD_FAILED,
    CHECKSUM_MISMATCH,
    INSTALLATION_FAILED,
    GENERAL
}
