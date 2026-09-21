package com.example.shakeflashlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Foreground service that keeps listening to the gyroscope (falling back to the
 * accelerometer on devices without one) so the flashlight can be toggled by a
 * quick "twist" rotation of the phone, whether the app is open, in the
 * background, or the screen is off.
 */
class ShakeFlashlightService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.example.shakeflashlight.action.START"
        const val ACTION_STOP = "com.example.shakeflashlight.action.STOP"

        private const val CHANNEL_ID = "shake_flashlight_channel"
        private const val NOTIFICATION_ID = 1001

        // Angular velocity thresholds (rad/s) used to recognise a fast twist.
        // A relaxed phone sitting still reports well under 1 rad/s, while a
        // deliberate wrist-twist easily spikes past 6-8 rad/s.
        private const val TWIST_TRIGGER_THRESHOLD = 6.0f
        private const val TWIST_REARM_THRESHOLD = 1.5f

        // Fallback thresholds (m/s^2 of "extra" acceleration beyond gravity)
        // used only on devices that have no gyroscope at all.
        private const val SHAKE_TRIGGER_THRESHOLD = 22.0f
        private const val SHAKE_REARM_THRESHOLD = 12.0f

        private const val TRIGGER_COOLDOWN_MS = 800L
    }

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    private lateinit var cameraManager: CameraManager
    private var flashCameraId: String? = null

    @Volatile
    private var torchOn = false

    // Simple "armed" state machine so one twist gesture produces exactly one
    // toggle instead of firing repeatedly while the rotation stays fast.
    private var armed = true
    private var lastTriggerTime = 0L

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flashCameraId) {
                torchOn = enabled
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        findFlashCamera()
        cameraManager.registerTorchCallback(torchCallback, null)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        registerSensors()
        return START_STICKY
    }

    private fun registerSensors() {
        val sensor = gyroscope ?: accelerometer
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun findFlashCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash == true && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    flashCameraId = id
                    break
                }
            }
            // Fall back to any camera reporting a flash unit if no back camera matched.
            if (flashCameraId == null) {
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        flashCameraId = id
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleFlashlight() {
        val id = flashCameraId ?: return
        try {
            cameraManager.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                // Magnitude of angular velocity across all axes: this makes the
                // gesture work no matter which way the phone is oriented when
                // it's twisted.
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                evaluateTrigger(magnitude, TWIST_TRIGGER_THRESHOLD, TWIST_REARM_THRESHOLD, now)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val totalMagnitude = sqrt(x * x + y * y + z * z)
                // Subtract gravity so we measure only the "extra" jerk/twist force.
                val delta = kotlin.math.abs(totalMagnitude - SensorManager.GRAVITY_EARTH)
                evaluateTrigger(delta, SHAKE_TRIGGER_THRESHOLD, SHAKE_REARM_THRESHOLD, now)
            }
        }
    }

    private fun evaluateTrigger(magnitude: Float, highThreshold: Float, lowThreshold: Float, now: Long) {
        if (armed && magnitude > highThreshold && (now - lastTriggerTime) > TRIGGER_COOLDOWN_MS) {
            armed = false
            lastTriggerTime = now
            toggleFlashlight()
        } else if (!armed && magnitude < lowThreshold) {
            armed = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Twist Flashlight Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps twist-to-toggle flashlight detection active in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ShakeFlashlightService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.title_main))
            .setContentText(
                if (torchOn) "Flashlight is ON — twist to turn it off"
                else "Twist your phone to toggle the flashlight"
            )
            .setSmallIcon(R.drawable.ic_flash)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        cameraManager.unregisterTorchCallback(torchCallback)
        if (torchOn) {
            flashCameraId?.let {
                try {
                    cameraManager.setTorchMode(it, false)
                } catch (_: Exception) {
                }
            }
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("running", false).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
