package com.recall.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RecallAutoBackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        val context = applicationContext

        if (!RecallBackupPreferences.isAutoBackupEnabled(context)) {
            return@withContext Result.success()
        }

        val driveEnabled =
            RecallBackupPreferences.isDriveBackupEnabled(context)

        val photosEnabled =
            RecallBackupPreferences.isPhotosBackupEnabled(context)

        if (!driveEnabled && !photosEnabled) {
            return@withContext Result.success()
        }

        var driveAuthorization: RecallDriveAuthorizationResult? = null
        var photosAuthorization: RecallPhotosAuthorizationResult? = null

        var uploadFailed = false

        // GOOGLE DRIVE AUTHORIZATION

        if (driveEnabled) {
            try {
                driveAuthorization =
                    RecallDriveAuthorization.getAuthorization(context)
            } catch (e: Exception) {
                uploadFailed = true
            }
        }

        // GOOGLE PHOTOS AUTHORIZATION

        if (photosEnabled) {
            try {
                photosAuthorization =
                    RecallPhotosAuthorization.getAuthorization(context)
            } catch (e: Exception) {
                uploadFailed = true
            }
        }

        val imageFiles = getRecallImages(context)

        if (imageFiles.isEmpty()) {
            return@withContext if (uploadFailed) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        val driveStorage = RecallDriveStorage(context)

        for (imageFile in imageFiles) {

            if (isStopped) {
                return@withContext Result.retry()
            }

            if (!imageFile.exists()) {
                continue
            }

            // GOOGLE DRIVE BACKUP

            if (
                driveEnabled &&
                driveAuthorization != null &&
                RecallBackupPreferences.isDriveBackupEnabled(context)
            ) {

                val currentStatus =
                    RecallBackupStatus.getStatus(
                        context,
                        imageFile.name,
                        RecallBackupPreferences.DRIVE
                    )

                if (currentStatus != RecallBackupStatus.BACKED_UP) {

                    try {

                        RecallBackupStatus.setStatus(
                            context,
                            imageFile.name,
                            RecallBackupStatus.PENDING,
                            RecallBackupPreferences.DRIVE
                        )

                        val authorization = driveAuthorization!!

                        val uploadedFileId =
                            driveStorage.uploadImage(
                                imageFile,
                                authorization.accessToken,
                                authorization.folderId
                            )

                        if (uploadedFileId.isNotBlank()) {

                            RecallBackupStatus.setDriveFileId(
                                context,
                                imageFile.name,
                                uploadedFileId
                            )

                            RecallBackupStatus.setStatus(
                                context,
                                imageFile.name,
                                RecallBackupStatus.BACKED_UP,
                                RecallBackupPreferences.DRIVE
                            )

                        } else {
                            throw Exception("Google Drive returned an empty file ID.")
                        }

                    } catch (e: Exception) {

                        uploadFailed = true

                        RecallBackupStatus.setStatus(
                            context,
                            imageFile.name,
                            RecallBackupStatus.FAILED,
                            RecallBackupPreferences.DRIVE
                        )
                    }
                }
            }

            if (isStopped) {
                return@withContext Result.retry()
            }

            // GOOGLE PHOTOS BACKUP

            if (
                photosEnabled &&
                photosAuthorization != null &&
                RecallBackupPreferences.isPhotosBackupEnabled(context)
            ) {

                val currentStatus =
                    RecallBackupStatus.getStatus(
                        context,
                        imageFile.name,
                        RecallBackupPreferences.PHOTOS
                    )

                if (currentStatus != RecallBackupStatus.BACKED_UP) {

                    try {

                        RecallBackupStatus.setStatus(
                            context,
                            imageFile.name,
                            RecallBackupStatus.PENDING,
                            RecallBackupPreferences.PHOTOS
                        )

                        val authorization = photosAuthorization!!

                        // RecallPhotosStorage is an OBJECT.
                        // Do not create an instance.

                        val uploadedMediaItemId =
                            RecallPhotosStorage.uploadImage(
                                imageFile,
                                authorization.accessToken
                            )

                        if (uploadedMediaItemId.isNotBlank()) {

                            RecallBackupStatus.setPhotosMediaItemId(
                                context,
                                imageFile.name,
                                uploadedMediaItemId
                            )

                            RecallBackupStatus.setStatus(
                                context,
                                imageFile.name,
                                RecallBackupStatus.BACKED_UP,
                                RecallBackupPreferences.PHOTOS
                            )

                        } else {
                            throw Exception("Google Photos returned an empty media item ID.")
                        }

                    } catch (e: Exception) {

                        uploadFailed = true

                        RecallBackupStatus.setStatus(
                            context,
                            imageFile.name,
                            RecallBackupStatus.FAILED,
                            RecallBackupPreferences.PHOTOS
                        )
                    }
                }
            }
        }

        if (uploadFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun getRecallImages(context: Context): List<File> {

        val supportedExtensions = setOf(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "gif",
            "bmp",
            "heic",
            "heif"
        )

        val folders = listOf(
            File(context.filesDir, "recall_captures"),
            File(context.filesDir, "recall_library_only")
        )

        return folders
            .filter { it.exists() && it.isDirectory }
            .flatMap { folder ->
                folder.listFiles()
                    ?.filter { file ->
                        file.isFile &&
                                file.extension.lowercase() in supportedExtensions
                    }
                    ?: emptyList()
            }
            .distinctBy { it.absolutePath }
    }
}