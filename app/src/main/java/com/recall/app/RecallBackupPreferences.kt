package com.recall.app

import android.content.Context

object RecallBackupPreferences {

    private const val PREFS_NAME = "recall_backup_preferences"

    private const val KEY_DESTINATION = "backup_destination"
    private const val KEY_DRIVE_ENABLED = "drive_backup_enabled"
    private const val KEY_PHOTOS_ENABLED = "photos_backup_enabled"
    private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
    private const val KEY_FREQUENCY = "backup_frequency"

    const val DRIVE = "Google Drive"
    const val PHOTOS = "Google Photos"

    const val DAILY = "Daily"
    const val WEEKLY = "Weekly"
    const val MONTHLY = "Monthly"

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * Migrates the old single-destination preference
     * into the new independent Drive and Photos settings.
     */
    private fun ensureDestinationPreferences(context: Context) {

        val prefs = preferences(context)

        val driveExists = prefs.contains(KEY_DRIVE_ENABLED)
        val photosExists = prefs.contains(KEY_PHOTOS_ENABLED)

        if (!driveExists && !photosExists) {

            val oldDestination = prefs.getString(
                KEY_DESTINATION,
                DRIVE
            )

            prefs.edit()
                .putBoolean(
                    KEY_DRIVE_ENABLED,
                    oldDestination != PHOTOS
                )
                .putBoolean(
                    KEY_PHOTOS_ENABLED,
                    oldDestination == PHOTOS
                )
                .apply()
        }
    }

    fun isDriveBackupEnabled(context: Context): Boolean {

        ensureDestinationPreferences(context)

        return preferences(context).getBoolean(
            KEY_DRIVE_ENABLED,
            false
        )
    }

    fun setDriveBackupEnabled(
        context: Context,
        enabled: Boolean
    ) {

        ensureDestinationPreferences(context)

        preferences(context).edit()
            .putBoolean(KEY_DRIVE_ENABLED, enabled)
            .apply()
    }

    fun isPhotosBackupEnabled(context: Context): Boolean {

        ensureDestinationPreferences(context)

        return preferences(context).getBoolean(
            KEY_PHOTOS_ENABLED,
            false
        )
    }

    fun setPhotosBackupEnabled(
        context: Context,
        enabled: Boolean
    ) {

        ensureDestinationPreferences(context)

        preferences(context).edit()
            .putBoolean(KEY_PHOTOS_ENABLED, enabled)
            .apply()
    }

    fun hasAnyBackupDestinationEnabled(context: Context): Boolean {

        return isDriveBackupEnabled(context) ||
                isPhotosBackupEnabled(context)
    }

    // Legacy compatibility methods

    fun getDestination(context: Context): String {

        val driveEnabled = isDriveBackupEnabled(context)
        val photosEnabled = isPhotosBackupEnabled(context)

        return when {
            driveEnabled -> DRIVE
            photosEnabled -> PHOTOS
            else -> preferences(context).getString(
                KEY_DESTINATION,
                DRIVE
            ) ?: DRIVE
        }
    }

    fun setDestination(
        context: Context,
        destination: String
    ) {

        setDriveBackupEnabled(
            context,
            destination == DRIVE
        )

        setPhotosBackupEnabled(
            context,
            destination == PHOTOS
        )

        preferences(context).edit()
            .putString(KEY_DESTINATION, destination)
            .apply()
    }

    // Automatic backup

    fun isAutoBackupEnabled(context: Context): Boolean {

        return preferences(context).getBoolean(
            KEY_AUTO_BACKUP,
            false
        )
    }

    fun setAutoBackupEnabled(
        context: Context,
        enabled: Boolean
    ) {

        preferences(context).edit()
            .putBoolean(KEY_AUTO_BACKUP, enabled)
            .apply()
    }

    // Backup frequency

    fun getFrequency(context: Context): String {

        return preferences(context).getString(
            KEY_FREQUENCY,
            DAILY
        ) ?: DAILY
    }

    fun setFrequency(
        context: Context,
        frequency: String
    ) {

        preferences(context).edit()
            .putString(KEY_FREQUENCY, frequency)
            .apply()
    }
}