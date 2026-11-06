
package com.recall.app

import android.content.Context

object GestureSettings {

    private const val PREFS_NAME = "recall_gesture_settings"

    private const val KEY_TRIPLE_TAP_ENABLED = "triple_tap_enabled"
    private const val KEY_SENSITIVITY = "tap_sensitivity"

    private const val DEFAULT_SENSITIVITY = 50f

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun isTripleTapEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(
            KEY_TRIPLE_TAP_ENABLED,
            false
        )
    }

    fun setTripleTapEnabled(
        context: Context,
        enabled: Boolean
    ) {
        preferences(context).edit()
            .putBoolean(KEY_TRIPLE_TAP_ENABLED, enabled)
            .apply()
    }

    fun getSensitivity(context: Context): Float {
        return preferences(context).getFloat(
            KEY_SENSITIVITY,
            DEFAULT_SENSITIVITY
        )
    }

    fun setSensitivity(
        context: Context,
        sensitivity: Float
    ) {
        preferences(context).edit()
            .putFloat(
                KEY_SENSITIVITY,
                sensitivity.coerceIn(0f, 100f)
            )
            .apply()
    }

    // Lower threshold means higher sensitivity.
    // Maximum sensitivity reaches 0.30 m/s².

    fun getTapThreshold(context: Context): Float {
        val sensitivity = getSensitivity(context) / 100f

        return 2.5f - (sensitivity * 2.2f)
    }

    fun getSensitivityLabel(sensitivity: Float): String {
        return when {
            sensitivity < 15f -> "Very Firm"
            sensitivity < 35f -> "Firm"
            sensitivity < 60f -> "Balanced"
            sensitivity < 80f -> "Sensitive"
            else -> "Very Sensitive"
        }
    }
}
