package com.example.service

import android.annotation.SuppressLint
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
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.HealthDatabase
import com.example.data.HealthRepository
import com.example.data.WorkoutSession
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class TrackingService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: HealthRepository

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null

    // For manual fallback step counting (accelerometer-based)
    private var lastAccelMagnitude = 0f
    private val stepThreshold = 12.5f // Simple step threshold
    private var lastStepTime = 0L

    private var timerJob: Job? = null
    private var startTimestamp = 0L

    companion object {
        private const val CHANNEL_ID = "health_tracking_channel"
        private const val NOTIFICATION_ID = 101

        private val _isTracking = MutableStateFlow(false)
        val isTracking = _isTracking.asStateFlow()

        private val _trackingType = MutableStateFlow("RUN")
        val trackingType = _trackingType.asStateFlow()

        private val _elapsedMillis = MutableStateFlow(0L)
        val elapsedMillis = _elapsedMillis.asStateFlow()

        private val _distanceMeters = MutableStateFlow(0f)
        val distanceMeters = _distanceMeters.asStateFlow()

        private val _caloriesBurned = MutableStateFlow(0f)
        val caloriesBurned = _caloriesBurned.asStateFlow()

        private val _gpsPoints = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
        val gpsPoints = _gpsPoints.asStateFlow()

        // Real-time step and sensor feeds
        private val _liveStepsSession = MutableStateFlow(0)
        val liveStepsSession = _liveStepsSession.asStateFlow()

        private val _currentSpeedKmh = MutableStateFlow(0f)
        val currentSpeedKmh = _currentSpeedKmh.asStateFlow()

    }

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val database = HealthDatabase.getDatabase(applicationContext)
        repository = HealthRepository(database.healthDao())

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // Try to get step sensors
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Register sensors
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            // Register accelerometer fallback
            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        // Register accelerometer always for pulse animation & active step fallback
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        createNotificationChannel()
        setupLocationCallback()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Health Active Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notifications during active workout tracking"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!_isTracking.value) return
                val locations = result.locations
                if (locations.isNotEmpty()) {
                    for (location in locations) {
                        handleNewLocation(location)
                    }
                }
            }
        }
    }

    private var lastLocation: Location? = null

    private fun handleNewLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        
        val points = _gpsPoints.value.toMutableList()
        points.add(Pair(lat, lng))
        _gpsPoints.value = points

        lastLocation?.let { prev ->
            val distance = prev.distanceTo(location)
            if (location.accuracy < 25f && distance > 1.5f) {
                _distanceMeters.value += distance
            }
        }
        lastLocation = location

        // Calculate speed in km/h
        _currentSpeedKmh.value = if (location.hasSpeed()) {
            location.speed * 3.6f
        } else {
            0f
        }

        updateCalories()
    }

    private fun updateCalories() {
        val durationHours = _elapsedMillis.value / 3600000f
        // MET (Metabolic Equivalent of Task)
        // Run: 8.0 MET, Bike: 7.5 MET, Walk/Workout: 3.5 MET
        val met = when (_trackingType.value) {
            "RUN" -> 8.0f
            "BIKE" -> 7.5f
            else -> 3.5f
        }
        // Formula: Calories = MET * Weight_KG * duration_hours
        val userWeight = 75f // Default kg, updated in profile
        _caloriesBurned.value = met * userWeight * durationHours
    }

    @SuppressLint("MissingPermission")
    fun startTracking(type: String) {
        if (_isTracking.value) return
        
        _trackingType.value = type
        _isTracking.value = true
        _elapsedMillis.value = 0L
        _distanceMeters.value = 0f
        _caloriesBurned.value = 0f
        _gpsPoints.value = emptyList()
        _liveStepsSession.value = 0
        _currentSpeedKmh.value = 0f
        startTimestamp = System.currentTimeMillis()
        lastLocation = null

        // Start Foreground Notification
        startForeground(NOTIFICATION_ID, buildNotification())

        // Start GPS tracking
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).apply {
            setMinUpdateIntervalMillis(1500L)
            setWaitForAccurateLocation(true)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("TrackingService", "Location permissions missing: ${e.message}")
        }

        // Start timer
        timerJob = serviceScope.launch {
            while (_isTracking.value) {
                delay(1000L)
                _elapsedMillis.value = System.currentTimeMillis() - startTimestamp
                updateCalories()
                updateNotification()
            }
        }
    }

    fun stopTracking(saveSession: Boolean = true) {
        if (!_isTracking.value) return

        _isTracking.value = false
        timerJob?.cancel()
        timerJob = null

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e("TrackingService", "Failed to remove location updates: ${e.message}")
        }

        if (saveSession) {
            val session = WorkoutSession(
                type = _trackingType.value,
                startTime = startTimestamp,
                durationMillis = _elapsedMillis.value,
                meters = _distanceMeters.value,
                calories = _caloriesBurned.value,
                note = "Completed via Metro Tracker"
            )
            serviceScope.launch {
                repository.insertWorkoutSession(session)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeLabel = _trackingType.value
        val formattedTime = formatDuration(_elapsedMillis.value)
        val formattedDistance = String.format(Locale.US, "%.1f m", _distanceMeters.value)
        val formattedCalories = String.format(Locale.US, "%.1f kcal", _caloriesBurned.value)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Metro Active Tracker: $typeLabel")
            .setContentText("Time: $formattedTime | Distance: $formattedDistance | Calories: $formattedCalories")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val action = it.action
            val type = it.getStringExtra("TRACKING_TYPE") ?: "RUN"
            if (action == "START_TRACKING") {
                startTracking(type)
            } else if (action == "STOP_TRACKING") {
                val save = it.getBooleanExtra("SAVE_SESSION", true)
                stopTracking(save)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking(saveSession = false)
        sensorManager.unregisterListener(this)
        serviceScope.cancel()
    }

    // SensorEventListener overrides
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        // 1. Step Counter / Step Detector logic
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            handleStepDetected()
        } else if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            // Step counter gives cumulative steps since boot, let's count increments relative to first reading
            // Or simple step trigger
            handleStepDetected()
        }

        // 2. Accelerometer-based step fallback and pulse simulator
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)

            // Step counter fallback: Detect peak magnitude
            if (magnitude > stepThreshold) {
                val now = System.currentTimeMillis()
                if (now - lastStepTime > 350) { // 350ms debounce
                    lastStepTime = now
                    if (stepSensor == null) {
                        // Use accelerometer fallback step counting
                        handleStepDetected()
                    }
                }
            }
        }
    }

    private fun handleStepDetected() {
        _liveStepsSession.value += 1
        
        // Save steps immediately into our daily statistics for today!
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = sdf.format(Date())

        serviceScope.launch {
            repository.addSteps(todayStr, 1)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
}
