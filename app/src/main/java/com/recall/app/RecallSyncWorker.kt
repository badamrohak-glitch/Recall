
package com.recall.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.isActive
import kotlin.coroutines.resume

class RecallSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (RecallAuthManager().getCurrentUser() == null) {
            return Result.success()
        }

        return try {
            val syncManager = RecallSyncManager(applicationContext)

            val result = suspendCancellableCoroutine<Pair<Int, Int>> { continuation ->
                syncManager.syncPending { successful, remaining ->
                    if (continuation.isActive) {
                        continuation.resume(successful to remaining)
                    }
                }
            }

            if (result.second > 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "recall_cloud_sync"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<RecallSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
