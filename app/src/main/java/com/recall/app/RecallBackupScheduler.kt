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

        val autoBackupEnabled =
            RecallBackupPreferences.isAutoBackupEnabled(context)

        val hasDestination =
            RecallBackupPreferences.hasAnyBackupDestinationEnabled(context)

        if (!autoBackupEnabled || !hasDestination) {
            cancel(context)
            return
        }

        val frequency =
            RecallBackupPreferences.getFrequency(context)

        val intervalDays = when (frequency) {
            RecallBackupPreferences.DAILY -> 1L
            RecallBackupPreferences.WEEKLY -> 7L
            RecallBackupPreferences.MONTHLY -> 30L
            else -> 1L
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupRequest =
            PeriodicWorkRequestBuilder<RecallAutoBackupWorker>(
                intervalDays,
                TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            backupRequest
        )
    }

    fun cancel(context: Context) {

        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME)
    }
}