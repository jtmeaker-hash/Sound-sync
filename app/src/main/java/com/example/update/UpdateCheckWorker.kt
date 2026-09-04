package com.example.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.model.SemanticVersion
import com.example.network.GitHubReleaseApiService
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager periodic worker for checking SoundSync updates.
 */
class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SoundSyncUpdateWorker"
        private const val WORK_NAME = "soundsync_periodic_update_check"
        private const val NOTIFICATION_CHANNEL_ID = "soundsync_updates"
        private const val NOTIFICATION_ID = 4040

        fun schedulePeriodicCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Run check once every 24 hours
            val periodicWork = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            Log.i(TAG, "Scheduled periodic update check work.")
        }

        fun cancelPeriodicCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic update check work.")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Background update check worker running...")

        try {
            val service = GitHubReleaseApiService.create()
            val response = service.getLatestRelease(
                UpdateManager.DEFAULT_REPO_OWNER,
                UpdateManager.DEFAULT_REPO_NAME
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "Background update check HTTP error: ${response.code()}")
                return Result.retry()
            }

            val release = response.body() ?: return Result.success()
            if (release.draft) return Result.success()

            val currentVer = SemanticVersion.parse(BuildConfig.VERSION_NAME)
            val releaseVer = SemanticVersion.parse(release.tagName)

            if (releaseVer.isNewerThan(currentVer)) {
                val apkAsset = UpdateManager.findApkAsset(release.assets)
                if (apkAsset != null) {
                    Log.i(TAG, "Found new version in background check: ${release.tagName}")
                    showUpdateNotification(release.tagName, release.name ?: release.tagName)
                }
            } else {
                Log.i(TAG, "Background check: application is up to date.")
            }

            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background update check exception: ${e.message}")
            return Result.retry()
        }
    }

    private fun showUpdateNotification(versionTag: String, releaseTitle: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SoundSync Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new SoundSync app releases"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_soundsync)
            .setContentTitle("SoundSync Update Available: $versionTag")
            .setContentText("A new release ($versionTag) is available. Tap to open and update.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("A new release $versionTag is available with recent updates and performance improvements. Tap to update SoundSync.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }
}
