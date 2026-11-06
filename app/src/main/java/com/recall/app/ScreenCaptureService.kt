
package com.recall.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_CAPTURE = "com.recall.app.ACTION_CAPTURE"
        const val ACTION_CAPTURE_READY = "com.recall.app.ACTION_CAPTURE_READY"
        const val ACTION_CAPTURE_FAILED = "com.recall.app.ACTION_CAPTURE_FAILED"

        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "recall_capture_channel"
        private const val NOTIFICATION_ID = 202

        private const val CAPTURE_TIMEOUT_MS = 10000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var captureFinished = false
    private var projectionCallback: MediaProjection.Callback? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var captureTimeoutRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (captureFinished || mediaProjection != null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_CAPTURE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            0
        )

        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            notifyCaptureFailure("Screen capture permission data is missing.")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            startCapture(resultCode, resultData)

        } catch (e: Exception) {
            notifyCaptureFailure(
                e.message ?: "Could not start screen capture."
            )
            finishCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        try {
            val windowManager =
                getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            if (screenWidth <= 0 || screenHeight <= 0) {
                throw IllegalStateException("Invalid screen dimensions.")
            }

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(
                resultCode,
                resultData
            )

            val projection = mediaProjection
                ?: throw IllegalStateException(
                    "Could not initialize screen capture."
                )

            handlerThread = HandlerThread(
                "RecallScreenCapture"
            ).also {
                it.start()
            }

            captureHandler = Handler(
                handlerThread!!.looper
            )

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!captureFinished) {
                        notifyCaptureFailure(
                            "Screen capture was interrupted."
                        )
                        finishCapture()
                    }
                }
            }

            projection.registerCallback(
                projectionCallback!!,
                captureHandler
            )

            imageReader!!.setOnImageAvailableListener(
                { reader ->
                    processCapturedImage(reader)
                },
                captureHandler
            )

            captureTimeoutRunnable = Runnable {
                if (!captureFinished) {
                    notifyCaptureFailure(
                        "Screen capture timed out. Please try again."
                    )
                    finishCapture()
                }
            }

            captureHandler!!.postDelayed(
                captureTimeoutRunnable!!,
                CAPTURE_TIMEOUT_MS
            )

            captureHandler!!.postDelayed({

                if (captureFinished) {
                    return@postDelayed
                }

                virtualDisplay = projection.createVirtualDisplay(
                    "RecallScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    captureHandler
                )

            }, 500)

        } catch (e: Exception) {
            notifyCaptureFailure(
                e.message ?: "Failed to capture the screen."
            )
            finishCapture()
        }
    }

    private fun processCapturedImage(reader: ImageReader) {

        if (captureFinished) {
            return
        }

        val image: Image = try {
            reader.acquireLatestImage() ?: return
        } catch (e: Exception) {
            notifyCaptureFailure(
                e.message ?: "Could not read the screenshot."
            )
            finishCapture()
            return
        }

        captureFinished = true

        var paddedBitmap: Bitmap? = null
        var capturedBitmap: Bitmap? = null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(
                paddedWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            paddedBitmap = padded
            padded.copyPixelsFromBuffer(buffer)

            val captured = Bitmap.createBitmap(
                padded,
                0,
                0,
                screenWidth,
                screenHeight
            )

            capturedBitmap = captured

            saveCapture(captured)

        } catch (e: Exception) {
            notifyCaptureFailure(
                e.message ?: "Could not process the screenshot."
            )

        } finally {
            image.close()

            capturedBitmap?.recycle()
            paddedBitmap?.recycle()

            finishCapture()
        }
    }

    private fun saveCapture(bitmap: Bitmap) {

        var captureFile: File? = null

        try {
            val captureDirectory = cacheDir

            captureFile = File.createTempFile(
                "recall_capture_",
                ".png",
                captureDirectory
            )

            FileOutputStream(captureFile).use { output ->
                val success = bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    output
                )

                if (!success) {
                    throw IllegalStateException(
                        "Could not save the screenshot."
                    )
                }

                output.flush()
            }

            if (!captureFile.exists() || captureFile.length() == 0L) {
                throw IllegalStateException(
                    "Screenshot file is empty."
                )
            }

            val openIntent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                action = ACTION_CAPTURE_READY
                putExtra(
                    EXTRA_IMAGE_PATH,
                    captureFile.absolutePath
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            startActivity(openIntent)

        } catch (e: Exception) {
            captureFile?.delete()

            notifyCaptureFailure(
                e.message ?: "Could not save the screenshot."
            )
        }
    }

    private fun notifyCaptureFailure(message: String) {

        if (captureFinished && mediaProjection == null) {
            return
        }

        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            action = ACTION_CAPTURE_FAILED
            putExtra(EXTRA_ERROR_MESSAGE, message)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            // The service will still clean up its resources.
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recall Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used while Recall captures the screen."
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Recall")
            .setContentText("Capturing your screen...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun finishCapture() {

        if (captureFinished && mediaProjection == null) {
            stopSelf()
            return
        }

        captureFinished = true

        captureTimeoutRunnable?.let {
            captureHandler?.removeCallbacks(it)
        }

        captureTimeoutRunnable = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }

        try {
            projectionCallback?.let {
                mediaProjection?.unregisterCallback(it)
            }
        } catch (_: Exception) {
        }

        projectionCallback = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        mediaProjection = null

        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler = null

        handlerThread?.quitSafely()
        handlerThread = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishCapture()
        super.onDestroy()
    }
}
