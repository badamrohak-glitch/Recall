
package com.recall.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class RecallAutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {

        val context = applicationContext

        // Check whether automatic backup is enabled.
        if (!RecallBackupPreferences.isAutoBackupEnabled(context)) {
            return Result.success()
        }

        // Read the selected backup destination.
        val destination =
            RecallBackupPreferences.getDestination(context)

        // Google Photos integration will be added separately.
        if (destination != RecallBackupPreferences.DRIVE) {
            return Result.success()
        }

        // Obtain fresh Google Drive authorization.
        val authorization = try {

            RecallDriveAuthorization.getAuthorization(context)

        } catch (e: Exception) {

            return Result.retry()
        }

        // Google requires user interaction or authorization is unavailable.
        if (authorization == null) {
            return Result.success()
        }

        val storage = RecallDriveStorage(context)

        // Local Recall image folders.
        val folders = listOf(
            File(context.filesDir, "recall_captures"),
            File(context.filesDir, "recall_library_only")
        )

        val supportedExtensions = setOf(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "gif"
        )

        // Collect available local images.
        val imageFiles = folders.flatMap { folder ->

            folder.listFiles()
                ?.filter { file ->

                    file.isFile &&
                            file.extension.lowercase() in supportedExtensions

                }
                ?: emptyList()
        }

        if (imageFiles.isEmpty()) {
            return Result.success()
        }

        var uploadFailed = false

        for (imageFile in imageFiles) {

            // Skip images already backed up to this destination.
            val currentStatus = RecallBackupStatus.getStatus(
                context,
                imageFile.name,
                destination
            )

            if (currentStatus == RecallBackupStatus.BACKED_UP) {
                continue
            }

            try {

                // Mark the image as pending.
                RecallBackupStatus.setStatus(
                    context,
                    imageFile.name,
                    RecallBackupStatus.PENDING,
                    destination
                )

                // Upload the image to Google Drive.
                val uploadedFileId = storage.uploadImage(
                    imageFile = imageFile,
                    accessToken = authorization.accessToken,
                    folderId = authorization.folderId
                )

                // Save the Drive file ID and successful status.
                RecallBackupStatus.setDriveFileId(
                    context,
                    imageFile.name,
                    uploadedFileId
                )

            } catch (e: Exception) {

                // Keep the local image and record the failure.
                RecallBackupStatus.setStatus(
                    context,
                    imageFile.name,
                    RecallBackupStatus.FAILED,
                    destination
                )

                uploadFailed = true
            }
        }

        return if (uploadFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
