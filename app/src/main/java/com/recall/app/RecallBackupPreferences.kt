
package com.recall.app

import android.content.Context

object RecallBackupPreferences {

    private const val PREFS_NAME = "recall_backup_preferences"

    private const val KEY_DESTINATION = "backup_destination"
    private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
    private const val KEY_FREQUENCY = "backup_frequency"

    const val DRIVE = "Google Drive"
    const val PHOTOS = "Google Photos"

    const val DAILY = "Daily"
    const val WEEKLY = "Weekly"
    const val MONTHLY = "Monthly"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun getDestination(context: Context): String {
        return prefs(context).getString(
            KEY_DESTINATION,
            DRIVE
        ) ?: DRIVE
    }

    fun setDestination(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_DESTINATION, value)
            .apply()
    }

    fun isAutoBackupEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            KEY_AUTO_BACKUP,
            false
        )
    }

    fun setAutoBackupEnabled(
        context: Context,
        enabled: Boolean
    ) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_BACKUP, enabled)
            .apply()
    }

    fun getFrequency(context: Context): String {
        return prefs(context).getString(
            KEY_FREQUENCY,
            WEEKLY
        ) ?: WEEKLY
    }

    fun setFrequency(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_FREQUENCY, value)
            .apply()
    }
}
