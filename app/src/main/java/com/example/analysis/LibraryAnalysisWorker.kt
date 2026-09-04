package com.example.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager persistent worker to ensure library metadata analysis continues
 * in the background even if the app process is closed, screen is off, or phone is locked.
 */
class LibraryAnalysisWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LibraryAnalysisWorker"
        const val WORK_NAME = "soundsync_library_metadata_analysis"
        const val CHANNEL_ID = "soundsync_library_analysis_channel"
        const val NOTIFICATION_ID = 4096

        fun enqueueWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<LibraryAnalysisWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Enqueued unique background analysis work.")
        }

        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, 0, "Starting background library analysis…")
    }

    private fun createForegroundInfo(
        processed: Int,
        total: Int,
        currentTrack: String = ""
    ): ForegroundInfo {
        createNotificationChannel()

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "SoundSync is analysing your library"
        val contentText = when {
            total > 0 && currentTrack.isNotBlank() -> "$processed / $total tracks analysed • $currentTrack"
            total > 0 -> "$processed / $total tracks analysed"
            currentTrack.isNotBlank() -> "Analysing • $currentTrack"
            else -> "Analysing library…"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_soundsync)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelIntent
            )

        if (total > 0) {
            builder.setProgress(total, processed, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Library Metadata Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background metadata and audio analysis progress"
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "LibraryAnalysisWorker starting background analysis run.")
        try {
            val manager = TrackAnalysisManager.getInstance(context)
            if (!manager.isBackgroundAnalysisEnabled) {
                Log.d(TAG, "Background analysis disabled in settings. Skipping.")
                return@withContext Result.success()
            }

            val pendingCount = manager.getPendingCount()
            if (pendingCount <= 0) {
                Log.d(TAG, "No pending tracks for analysis. Worker finished.")
                return@withContext Result.success()
            }

            try {
                setForeground(createForegroundInfo(0, pendingCount))
            } catch (e: Exception) {
                Log.w(TAG, "Could not set foreground info: ${e.message}")
            }

            var lastNotifiedMs = System.currentTimeMillis()
            var lastNotifiedCount = 0

            manager.runAnalysisLoopSuspended { processed, total, trackTitle ->
                val now = System.currentTimeMillis()
                if ((now - lastNotifiedMs > 4000) || (processed - lastNotifiedCount >= 10)) {
                    lastNotifiedMs = now
                    lastNotifiedCount = processed
                    try {
                        setForegroundAsync(createForegroundInfo(processed, total, trackTitle))
                    } catch (_: Exception) {}
                }
            }

            Log.d(TAG, "LibraryAnalysisWorker analysis completed successfully.")
            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "LibraryAnalysisWorker cancelled.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "LibraryAnalysisWorker error: ${e.message}", e)
            Result.retry()
        }
    }
}
