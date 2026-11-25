package com.skitracker.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.skitracker.MainActivity
import com.skitracker.R
import com.skitracker.SkiTrackerApp
import com.skitracker.data.TrackPointEntity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that streams high-accuracy location updates while a
 * session is active and publishes live metrics through [SessionStateHolder].
 * The completed session is persisted to Room on stop.
 */
class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationProvider: LocationProvider
    private lateinit var accumulator: SessionMetricsAccumulator

    private var sessionId: Long = 0L
    private val pendingPoints = mutableListOf<TrackPointEntity>()

    private var sensorManager: SensorManager? = null
    private var pressureListener: SensorEventListener? = null

    /** Converts pressure (hPa) to altitude relative to the first reading. */
    private val pressureListenerImpl = object : SensorEventListener {
        private var referencePressure = 0f
        override fun onSensorChanged(event: SensorEvent) {
            val pressure = event.values[0]
            if (referencePressure == 0f) referencePressure = pressure
            val relativeAlt = 44330.0 *
                (1.0 - Math.pow(
                    (pressure / referencePressure).toDouble(),
                    1.0 / 5.255,
                ))
            if (::accumulator.isInitialized) accumulator.onBarometricAltitude(relativeAlt)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                handleLocation(location)
            }
        }
    }

    private var tracking = false

    override fun onCreate() {
        super.onCreate()
        locationProvider = LocationProvider(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            else -> startTracking()
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (tracking) return
        tracking = true
        accumulator = SessionMetricsAccumulator()
        SessionStateHolder.startSession()
        // Create the DB row now so track points can reference it immediately.
        val repo = (application as SkiTrackerApp).sessionRepository
        sessionId = runBlocking { repo.beginSession() }
        startBarometer()
        startInForeground()
        locationProvider.startUpdates(locationCallback)
    }

    private fun startBarometer() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        sensorManager = manager
        pressureListener = pressureListenerImpl
        manager.registerListener(pressureListenerImpl, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopBarometer() {
        val listener = pressureListener ?: return
        sensorManager?.unregisterListener(listener)
        pressureListener = null
        sensorManager = null
    }

    private fun handleLocation(location: Location) {
        val snapshot = accumulator.onLocation(location) ?: return
        SessionStateHolder.update { snapshot }

        pendingPoints.add(
            TrackPointEntity(
                sessionId = sessionId,
                timeMillis = location.time,
                lat = location.latitude,
                lng = location.longitude,
                altitudeM = if (location.hasAltitude()) {
                    accumulator.currentAltitudeForStorage(location.altitude)
                } else {
                    0.0
                },
                speedMs = snapshot.currentSpeedMs,
            ),
        )
        if (pendingPoints.size >= POINT_FLUSH_BATCH) flushPoints()
    }

    private fun flushPoints() {
        if (pendingPoints.isEmpty()) return
        val batch = pendingPoints.toList()
        pendingPoints.clear()
        val repo = (application as SkiTrackerApp).sessionRepository
        serviceScope.launch { repo.saveTrackPoints(batch) }
    }

    private fun stopTracking() {
        if (!tracking) {
            stopSelf()
            return
        }
        tracking = false
        locationProvider.stopUpdates(locationCallback)
        stopBarometer()
        flushPoints()

        val snapshot = SessionStateHolder.snapshot.value
        SessionStateHolder.reset()

        val repo = (application as SkiTrackerApp).sessionRepository
        if (snapshot.isTracking && snapshot.durationMs > MIN_SESSION_MS) {
            serviceScope.launch { repo.completeSession(sessionId, snapshot) }
        } else {
            // Too short to count: remove the placeholder row and its points.
            serviceScope.launch { repo.delete(sessionId) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (tracking) {
            // Ensures updates stop and the session is saved if the service
            // is torn down without an explicit STOP action.
            stopTracking()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away: end the session cleanly.
        stopTracking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "active_session"
        private const val NOTIFICATION_ID = 1
        private const val MIN_SESSION_MS = 1_000L
        private const val POINT_FLUSH_BATCH = 10

        const val ACTION_START = "com.skitracker.action.START"
        const val ACTION_STOP = "com.skitracker.action.STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, TrackingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
    }
}
