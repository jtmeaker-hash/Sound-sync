package com.example.analysis

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager persistent worker to ensure library metadata analysis continues
 * even if the app process was stopped and restarted.
 */
class LibraryAnalysisWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LibraryAnalysisWorker"
        const val WORK_NAME = "soundsync_library_metadata_analysis"

        fun enqueueWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "LibraryAnalysisWorker starting background analysis run.")
        try {
            val manager = TrackAnalysisManager.getInstance(context)
            manager.triggerQueueProcessing()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "LibraryAnalysisWorker error: ${e.message}", e)
            Result.retry()
        }
    }
}
