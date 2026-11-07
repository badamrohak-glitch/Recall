
package com.recall.app

import android.content.Context

object RecallBackupStatus {

    private const val PREFS_NAME = "recall_backup_status"

    const val NOT_BACKED_UP = "Not backed up"
    const val PENDING = "Pending"
    const val BACKED_UP = "Backed up"
    const val FAILED = "Failed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private fun statusKey(
        fileName: String,
        destination: String
    ): String {
        return "${fileName}_${destination}_status"
    }

    private fun driveIdKey(fileName: String): String {
        return "${fileName}_drive_file_id"
    }

    private fun photosIdKey(fileName: String): String {
        return "${fileName}_photos_media_item_id"
    }

    private fun currentDestination(context: Context): String {
        return RecallBackupPreferences.getDestination(context)
    }

    fun getStatus(
        context: Context,
        fileName: String
    ): String {
        return getStatus(
            context,
            fileName,
            currentDestination(context)
        )
    }

    fun getStatus(
        context: Context,
        fileName: String,
        destination: String
    ): String {

        val preferences = prefs(context)
        val key = statusKey(fileName, destination)

        val savedStatus = preferences.getString(key, null)

        if (savedStatus != null) {
            return savedStatus
        }

        // Migrate older status values to Google Drive.
        if (destination == RecallBackupPreferences.DRIVE) {

            val oldStatus = preferences.getString(
                "${fileName}_status",
                null
            )

            if (oldStatus != null) {

                preferences.edit()
                    .putString(key, oldStatus)
                    .remove("${fileName}_status")
                    .apply()

                return oldStatus
            }
        }

        return NOT_BACKED_UP
    }

    fun setStatus(
        context: Context,
        fileName: String,
        status: String
    ) {
        setStatus(
            context,
            fileName,
            status,
            currentDestination(context)
        )
    }

    fun setStatus(
        context: Context,
        fileName: String,
        status: String,
        destination: String
    ) {
        prefs(context).edit()
            .putString(
                statusKey(fileName, destination),
                status
            )
            .apply()
    }

    fun getDriveFileId(
        context: Context,
        fileName: String
    ): String? {
        return prefs(context).getString(
            driveIdKey(fileName),
            null
        )
    }

    fun setDriveFileId(
        context: Context,
        fileName: String,
        fileId: String
    ) {
        prefs(context).edit()
            .putString(driveIdKey(fileName), fileId)
            .putString(
                statusKey(
                    fileName,
                    RecallBackupPreferences.DRIVE
                ),
                BACKED_UP
            )
            .apply()
    }

    fun getPhotosMediaItemId(
        context: Context,
        fileName: String
    ): String? {
        return prefs(context).getString(
            photosIdKey(fileName),
            null
        )
    }

    fun setPhotosMediaItemId(
        context: Context,
        fileName: String,
        mediaItemId: String
    ) {
        prefs(context).edit()
            .putString(photosIdKey(fileName), mediaItemId)
            .putString(
                statusKey(
                    fileName,
                    RecallBackupPreferences.PHOTOS
                ),
                BACKED_UP
            )
            .apply()
    }

    fun clearStatus(
        context: Context,
        fileName: String
    ) {
        prefs(context).edit()
            .remove(
                statusKey(
                    fileName,
                    RecallBackupPreferences.DRIVE
                )
            )
            .remove(
                statusKey(
                    fileName,
                    RecallBackupPreferences.PHOTOS
                )
            )
            .remove("${fileName}_status")
            .remove(driveIdKey(fileName))
            .remove(photosIdKey(fileName))
            .apply()
    }
}
