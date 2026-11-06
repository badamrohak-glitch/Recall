
package com.recall.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sqrt

class GestureDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var sensorRegistered = false

    private var tapCount = 0
    private var firstTapTime = 0L
    private var lastTapTime = 0L
    private var lastActivationTime = 0L

    private val minimumTapGap = 120L
    private val maximumTapDuration = 1400L
    private val tapDebounce = 140L
    private val activationCooldown = 1200L

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    private var gravityInitialized = false
    private val gravityFilter = 0.85f

    private var isArmed = true

    private val channelId = "recall_gesture_channel"
    private val notificationId = 101

    override fun onCreate() {
        super.onCreate()

        sensorManager =
            getSystemService(SENSOR_SERVICE) as SensorManager

        accelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (!GestureSettings.isTripleTapEnabled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification =
            createNotification("Waiting for three taps...")

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e(
                "RecallGesture",
                "Could not start foreground service.",
                e
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (accelerometer == null) {
            android.util.Log.e(
                "RecallGesture",
                "Accelerometer is unavailable."
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!sensorRegistered) {
            resetDetector()
            registerSensor()
        }

        return START_STICKY
    }

    private fun registerSensor() {
        val sensor = accelerometer ?: return

        sensorManager.unregisterListener(this)

        sensorRegistered = sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )

        if (!sensorRegistered) {
            android.util.Log.e(
                "RecallGesture",
                "Could not register accelerometer listener."
            )
        }
    }

    private fun resetDetector() {
        resetTapSequence()

        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f

        gravityInitialized = false
        isArmed = true
    }

    override fun onSensorChanged(event: SensorEvent?) {

        if (event == null) return

        if (!GestureSettings.isTripleTapEnabled(this)) {
            stopSelf()
            return
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!gravityInitialized) {
            gravityX = x
            gravityY = y
            gravityZ = z
            gravityInitialized = true
            return
        }

        gravityX =
            gravityFilter * gravityX +
                    (1f - gravityFilter) * x

        gravityY =
            gravityFilter * gravityY +
                    (1f - gravityFilter) * y

        gravityZ =
            gravityFilter * gravityZ +
                    (1f - gravityFilter) * z

        val linearX = x - gravityX
        val linearY = y - gravityY
        val linearZ = z - gravityZ

        val linearMagnitude = sqrt(
            linearX * linearX +
                    linearY * linearY +
                    linearZ * linearZ
        )

        val currentTime = SystemClock.elapsedRealtime()

        if (
            tapCount > 0 &&
            currentTime - firstTapTime > maximumTapDuration
        ) {
            resetTapSequence()
        }

        val tapThreshold =
            GestureSettings.getTapThreshold(this)

        val releaseThreshold =
            max(0.08f, tapThreshold * 0.35f)

        if (
            !isArmed &&
            linearMagnitude < releaseThreshold
        ) {
            isArmed = true
        }

        if (!isArmed || linearMagnitude < tapThreshold) {
            return
        }

        isArmed = false

        if (
            currentTime - lastTapTime < tapDebounce &&
            tapCount > 0
        ) {
            return
        }

        if (
            currentTime - lastActivationTime <
            activationCooldown
        ) {
            return
        }

        if (tapCount == 0) {

            tapCount = 1
            firstTapTime = currentTime
            lastTapTime = currentTime

        } else {

            val gap = currentTime - lastTapTime
            val totalDuration = currentTime - firstTapTime

            if (
                gap >= minimumTapGap &&
                totalDuration <= maximumTapDuration
            ) {
                tapCount++
                lastTapTime = currentTime

            } else if (totalDuration > maximumTapDuration) {

                tapCount = 1
                firstTapTime = currentTime
                lastTapTime = currentTime
            }
        }

        if (tapCount >= 3) {
            onTripleTapDetected()
            resetTapSequence()
            lastActivationTime = currentTime
        }
    }

    private fun resetTapSequence() {
        tapCount = 0
        firstTapTime = 0L
        lastTapTime = 0L
    }

    private fun onTripleTapDetected() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            updateNotification(
                "Please enable Recall overlay permission."
            )
            return
        }

        val overlayIntent = Intent(
            this,
            FloatingRecallService::class.java
        ).apply {
            action = FloatingRecallService.ACTION_TOGGLE
        }

        try {
            startService(overlayIntent)
        } catch (e: Exception) {
            updateNotification(
                "Could not toggle Recall popup."
            )
            android.util.Log.e(
                "RecallGesture",
                "Could not toggle popup.",
                e
            )
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "Recall Gesture Detection",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        message: String
    ): android.app.Notification {

        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("Recall is active")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(
            notificationId,
            createNotification(message)
        )
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }

    override fun onDestroy() {

        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }

        sensorRegistered = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
