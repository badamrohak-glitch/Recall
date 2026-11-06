
package com.recall.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RecallBackupScheduler {

    private const val WORK_NAME = "recall_automatic_backup"

    fun schedule(context: Context) {

        val appContext = context.applicationContext

        // Stop scheduling if automatic backup is disabled.
        if (!RecallBackupPreferences.isAutoBackupEnabled(appContext)) {
            cancel(appContext)
            return
        }

        // Automatic uploads currently support Google Drive only.
        if (RecallBackupPreferences.getDestination(appContext) !=
            RecallBackupPreferences.DRIVE
        ) {
            cancel(appContext)
            return
        }

        // Read the selected backup frequency.
        val interval = when (
            RecallBackupPreferences.getFrequency(appContext)
        ) {
            RecallBackupPreferences.DAILY -> 1L
            RecallBackupPreferences.WEEKLY -> 7L
            RecallBackupPreferences.MONTHLY -> 30L
            else -> 7L
        }

        // Run only when an internet connection is available.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupRequest =
            PeriodicWorkRequestBuilder<RecallAutoBackupWorker>(
                interval,
                TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

        // Update the existing schedule instead of creating duplicates.
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                backupRequest
            )
    }

    fun cancel(context: Context) {

        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(WORK_NAME)
    }
}
