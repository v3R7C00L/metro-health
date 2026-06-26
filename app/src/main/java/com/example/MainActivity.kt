package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import com.example.ui.theme.MetroYellow
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DailyStats
import com.example.data.WeightLog
import com.example.data.WorkoutSession
import com.example.service.TrackingService
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.HealthViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private lateinit var viewModel: HealthViewModel

    private var lastStepTime = 0L
    private val stepThreshold = 12.5f

    // Static flow for live shake/accel graph
    companion object {
        val liveShakeMagnitude = kotlinx.coroutines.flow.MutableStateFlow(9.8f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[HealthViewModel::class.java]

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        stepDetectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        stepDetectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
            liveShakeMagnitude.value = magnitude

            // Fallback step counting if no hardware step detector is present
            if (stepDetectorSensor == null && magnitude > stepThreshold) {
                val now = System.currentTimeMillis()
                if (now - lastStepTime > 350) { // 350ms debounce
                    lastStepTime = now
                    viewModel.logManualSteps(1)
                }
            }
        } else if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            val now = System.currentTimeMillis()
            if (now - lastStepTime > 250) { // debounce
                lastStepTime = now
                viewModel.logManualSteps(1)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun MainAppScreen(viewModel: HealthViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State bindings
    val todayStats by viewModel.todayStats.collectAsStateWithLifecycle()
    val allDailyStats by viewModel.allDailyStats.collectAsStateWithLifecycle()
    val workoutSessions by viewModel.workoutSessions.collectAsStateWithLifecycle()
    val weightLogs by viewModel.weightLogs.collectAsStateWithLifecycle()

    val weightKg by viewModel.weightKg.collectAsStateWithLifecycle()
    val heightCm by viewModel.heightCm.collectAsStateWithLifecycle()
    val stepGoal by viewModel.stepGoal.collectAsStateWithLifecycle()
    val isHealthSynced by viewModel.isHealthSynced.collectAsStateWithLifecycle()

    // Tracking states from Foreground Service
    val isTracking by TrackingService.isTracking.collectAsStateWithLifecycle()
    val trackingType by TrackingService.trackingType.collectAsStateWithLifecycle()
    val elapsedMillis by TrackingService.elapsedMillis.collectAsStateWithLifecycle()
    val distanceMeters by TrackingService.distanceMeters.collectAsStateWithLifecycle()
    val caloriesBurned by TrackingService.caloriesBurned.collectAsStateWithLifecycle()
    val gpsPoints by TrackingService.gpsPoints.collectAsStateWithLifecycle()
    val liveStepsSession by TrackingService.liveStepsSession.collectAsStateWithLifecycle()
    val currentSpeedKmh by TrackingService.currentSpeedKmh.collectAsStateWithLifecycle()
    val liveShakeState by MainActivity.liveShakeMagnitude.collectAsStateWithLifecycle()

    // Permission Launcher
    val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (!permissionsGranted) {
            Toast.makeText(context, "Permissions are required for real tracking & sensors!", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Refresh today's date on start
    LaunchedEffect(Unit) {
        viewModel.updateTodayDate()
    }

    var showConfetti by remember { mutableStateOf(false) }
    val sharedPrefs = remember { context.getSharedPreferences("health_prefs", Context.MODE_PRIVATE) }

    // Reactively monitor step goal completion
    LaunchedEffect(todayStats, stepGoal) {
        val stats = todayStats
        if (stats != null && stats.steps >= stepGoal && stepGoal > 0) {
            val todayDate = stats.date
            val notifiedKey = "goal_notified_${todayDate}_$stepGoal"
            val alreadyNotified = sharedPrefs.getBoolean(notifiedKey, false)
            if (!alreadyNotified) {
                sharedPrefs.edit().putBoolean(notifiedKey, true).apply()
                sendGoalNotification(context, stats.steps, stepGoal)
                showConfetti = true
            }
        }
    }

    // Windows Phone style 4-Page Pivot Pager
    val pagerState = rememberPagerState(pageCount = { 4 })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showConfetti) {
                ConfettiEffect(
                    modifier = Modifier.fillMaxSize(),
                    onFinished = { showConfetti = false }
                )
            }

            // Main Pivot layout structure
            Column(modifier = Modifier.fillMaxSize()) {
                
                // 1. App branding header (Tiny, all uppercase, tracking spacing)
                Text(
                    text = "METRO HEALTH",
                    modifier = Modifier
                        .padding(start = 24.dp, top = 16.dp, bottom = 4.dp)
                        .testTag("app_brand_title"),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 5.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                // 2. Giant Pivot Navigation (Clickable horizontal headers with automatic parallax scroll)
                val headerScrollState = rememberScrollState()
                LaunchedEffect(pagerState.currentPage, pagerState.currentPageOffsetFraction) {
                    val totalPages = 4
                    val maxScroll = headerScrollState.maxValue
                    if (maxScroll > 0) {
                        val fraction = (pagerState.currentPage + pagerState.currentPageOffsetFraction) / (totalPages - 1)
                        headerScrollState.scrollTo((fraction * maxScroll).toInt())
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(headerScrollState)
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    val titles = listOf("today", "workout", "history", "profile")
                    titles.forEachIndexed { index, title ->
                        val isSelected = pagerState.currentPage == index
                        Text(
                            text = title,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                fontWeight = FontWeight.W100,
                                fontSize = 68.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                                letterSpacing = (-3).sp
                            ),
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                                .testTag("pivot_tab_$title")
                        )
                    }
                }

                // Divider line (Classic thin Metro line)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF222222))
                        .padding(horizontal = 24.dp)
                )

                // 3. Horizontal Pager holding our Pivot Contents with custom 3D Turnstile Effect
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { pageIndex ->
                    val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                    val absOffset = kotlin.math.abs(pageOffset)
                    val density = LocalDensity.current
                    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                    val densityFloat = density.density

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 3D Turnstile / Hinge transition
                                cameraDistance = 12f * densityFloat
                                transformOrigin = TransformOrigin(0f, 0.5f) // Hinged on the left side
                                
                                // Rotate Y: Swing backwards/outwards as it moves away
                                rotationY = -45f * pageOffset
                                
                                // Scale down slightly for depth
                                val scale = (1f - absOffset * 0.12f).coerceIn(0.85f, 1f)
                                scaleX = scale
                                scaleY = scale
                                
                                // Fade out to prevent overlapping
                                alpha = (1f - absOffset).coerceIn(0f, 1f)
                                
                                // Translate on X to make it feel even more 3D
                                translationX = pageOffset * screenWidthPx * 0.2f
                            }
                    ) {
                        when (pageIndex) {
                            0 -> TodayPivot(
                                todayStats = todayStats,
                                liveShake = liveShakeState,
                                viewModel = viewModel,
                                isHealthSynced = isHealthSynced
                            )
                            1 -> WorkoutPivot(
                                isTracking = isTracking,
                                trackingType = trackingType,
                                elapsedMillis = elapsedMillis,
                                distanceMeters = distanceMeters,
                                caloriesBurned = caloriesBurned,
                                currentSpeedKmh = currentSpeedKmh,
                                liveStepsSession = liveStepsSession,
                                liveShake = liveShakeState,
                                gpsPoints = gpsPoints,
                                permissionsGranted = permissionsGranted,
                                onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                                onStartTracking = { type -> viewModel.startWorkoutTracking(type) },
                                onStopTracking = { save -> viewModel.stopWorkoutTracking(save) }
                            )
                            2 -> HistoryPivot(
                                workoutSessions = workoutSessions,
                                allDailyStats = allDailyStats,
                                viewModel = viewModel
                            )
                            3 -> ProfilePivot(
                                weightKg = weightKg,
                                heightCm = heightCm,
                                stepGoal = stepGoal,
                                isHealthSynced = isHealthSynced,
                                weightLogs = weightLogs,
                                onSaveProfile = { w, h, g -> viewModel.saveProfile(w, h, g) },
                                onAddWeight = { w -> viewModel.logManualWeight(w) },
                                onDeleteWeight = { id -> viewModel.deleteWeight(id) },
                                onToggleHealthSync = { enabled -> viewModel.toggleHealthSync(enabled) }
                            )
                        }
                    }
                }
            }

            // 4. Floating indicator overlay if active tracking in background
            if (isTracking && pagerState.currentPage != 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { scope.launch { pagerState.animateScrollToPage(1) } }
                        .padding(vertical = 12.dp, horizontal = 24.dp)
                        .testTag("floating_tracking_bar"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Active Workout",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ACTIVE WORKOUT IN BACKGROUND",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%.0f m", distanceMeters),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

// ---------------------- PIVOT 1: TODAY ----------------------
@Composable
fun TodayPivot(
    todayStats: DailyStats?,
    liveShake: Float,
    viewModel: HealthViewModel,
    isHealthSynced: Boolean
) {
    var showManualStepsDialog by remember { mutableStateOf(false) }
    var showCustomGoalDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val steps = todayStats?.steps ?: 0
    val meters = todayStats?.meters ?: 0f
    val calories = todayStats?.calories ?: 0f
    val goalSteps = todayStats?.goalSteps ?: 10000

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        item {
            Column {
                Text(
                    text = "TODAY'S ACTIVITY",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.W100
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date()).uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                )
            }
        }

        // Large 2x2 steps block (Standard Metro tile)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RectangleShape)
                    .clickable { showManualStepsDialog = true }
                    .padding(24.dp)
                    .testTag("steps_metric_tile")
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "STEPS WALKED",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Log Manual Steps",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = String.format(Locale.US, "%,d", steps),
                        style = MaterialTheme.typography.displayLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.W100
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Simple rectangular progress bar (Metro Style, no curves)
                    val progressRatio = if (goalSteps > 0) (steps.toFloat() / goalSteps).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Color(0xFF222222))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressRatio)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomGoalDialog = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(Locale.US, "%d%% OF %s STEP GOAL", (progressRatio * 100).toInt(), String.format(Locale.US, "%,d", goalSteps)),
                            style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "EDIT GOAL",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Step Goal",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // Two medium-sized tiles (Calories & Meters walked)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Calories burned flipping tile
                MetroFlippingTile(
                    frontContent = {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "CALORIES",
                                style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format(Locale.US, "%.0f", calories),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.W100
                                )
                            )
                            Text(
                                text = "KCAL BURNED",
                                style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                            )
                        }
                    },
                    backContent = {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "BURN GOALS",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "EVERY ACTIVE CALORIE BURNED INCREASES LONG-TERM HEALTH VIGOR.",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(130.dp)
                        .testTag("calories_metric_tile"),
                    delayMs = 5200L
                )

                // Meters walked flipping tile
                MetroFlippingTile(
                    frontContent = {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "DISTANCE",
                                style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format(Locale.US, "%.0f", meters),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.W100
                                )
                            )
                            Text(
                                text = "METERS WALKED",
                                style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                            )
                        }
                    },
                    backContent = {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "KEEP MOVING",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "WALKING AT A BRISK PACE STRENGTHENS THE CARDIO SYSTEM.",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(130.dp)
                        .testTag("distance_metric_tile"),
                    delayMs = 7400L
                )
            }
        }

        // Dynamic motion hub / kinetic activity monitor (uses accelerometer)
        item {
            val kineticState = when {
                liveShake < 10.3f -> "STATIONARY"
                liveShake < 13.5f -> "WALKING"
                else -> "RUNNING"
            }
            val kineticColor = when (kineticState) {
                "RUNNING" -> MaterialTheme.colorScheme.primary
                "WALKING" -> Color(0xFFE5E500)
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .border(1.5.dp, kineticColor, RectangleShape)
                    .padding(16.dp)
                    .testTag("motion_kinetic_tile")
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE MOTION SENSOR",
                            style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(kineticColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = kineticState,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = kineticColor,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Draw live wave based on real physical motion
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                    ) {
                        LiveWaveCanvas(shakeMagnitude = liveShake)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ACCELERATION FORCE",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color.Gray,
                                fontSize = 9.sp
                            )
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f M/S²", liveShake),
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }

        // Sync details if enabled
        if (isHealthSynced) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF002200))
                        .border(1.dp, Color(0xFF00AA00), RectangleShape)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Sync Enabled",
                        tint = Color(0xFF00FF00),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "HEALTH CONNECT AUTO-SYNC COMPATIBLE",
                        style = MaterialTheme.typography.labelLarge.copy(color = Color(0xFF00FF00))
                    )
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .border(1.dp, Color(0xFF333333), RectangleShape)
                    .padding(16.dp)
                    .testTag("sporting_push_tile")
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SPORTING REMINDERS (PUSH)",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Sporting push notifications",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Schedule motivational push notifications to fire occasionally in the background and remind you to get sporting!",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                scheduleSportReminder(context, 5000L) // 5 seconds
                                coroutineScope.launch {
                                    delay(5000L)
                                    val intent = Intent(context, com.example.service.SportReminderReceiver::class.java)
                                    context.sendBroadcast(intent)
                                }
                                Toast.makeText(context, "Reminder scheduled in 5 seconds! Lock screen or press Home to see it.", Toast.LENGTH_LONG).show()
                            },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                            border = BorderStroke(1.dp, Color.Gray),
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) {
                            Text("TEST IN 5S", style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontWeight = FontWeight.Bold))
                        }

                        Button(
                            onClick = {
                                scheduleSportReminder(context, 24 * 3600 * 1000L) // 1 day
                                Toast.makeText(context, "Rare sporting push scheduled for tomorrow!", Toast.LENGTH_LONG).show()
                            },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) {
                            Text("DAILY ACTIVE", style = MaterialTheme.typography.bodySmall.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }

    // Manual step log dialog
    if (showManualStepsDialog) {
        var inputSteps by remember { mutableStateOf("1000") }
        AlertDialog(
            onDismissRequest = { showManualStepsDialog = false },
            shape = RectangleShape,
            containerColor = Color(0xFF111111),
            title = {
                Text(
                    text = "LOG MANUAL STEPS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.W100
                    )
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter steps to simulate walked distance and calorie calculations.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputSteps,
                        onValueChange = { inputSteps = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_steps_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = inputSteps.toIntOrNull() ?: 0
                        if (parsed > 0) {
                            viewModel.logManualSteps(parsed)
                        }
                        showManualStepsDialog = false
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("save_manual_steps_button")
                ) {
                    Text("ADD STEPS", style = MaterialTheme.typography.bodyMedium.copy(color = Color.Black))
                }
            },
            dismissButton = {
                Button(
                    onClick = { showManualStepsDialog = false },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    Text("CANCEL", style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray))
                }
            }
        )
    }

    // Custom Step Goal dialog
    if (showCustomGoalDialog) {
        var inputGoalVal by remember { mutableStateOf(goalSteps.toString()) }
        AlertDialog(
            onDismissRequest = { showCustomGoalDialog = false },
            shape = RectangleShape,
            containerColor = Color(0xFF111111),
            title = {
                Text(
                    text = "SET CUSTOM STEP GOAL",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.W100
                    )
                )
            },
            text = {
                Column {
                    Text(
                        text = "Customize your daily active step goal. Your distance, calories, and achievement triggers will adapt dynamically.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputGoalVal,
                        onValueChange = { inputGoalVal = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_goal_input_dialog")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = inputGoalVal.toIntOrNull() ?: 10000
                        if (parsed > 0) {
                            viewModel.saveProfile(viewModel.weightKg.value, viewModel.heightCm.value, parsed)
                            Toast.makeText(context, "Step Goal updated to $parsed steps!", Toast.LENGTH_SHORT).show()
                        }
                        showCustomGoalDialog = false
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("save_custom_goal_button")
                ) {
                    Text("UPDATE GOAL", style = MaterialTheme.typography.bodyMedium.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                }
            },
            dismissButton = {
                Button(
                    onClick = { showCustomGoalDialog = false },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    Text("CANCEL", style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray))
                }
            }
        )
    }
}

@Composable
fun MetroFlippingTile(
    frontContent: @Composable () -> Unit,
    backContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMs: Long = 6000L
) {
    var isFlipped by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(delayMs)
            isFlipped = !isFlipped
        }
    }
    
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "tile_flip"
    )
    
    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .background(Color(0xFF111111))
            .border(1.dp, Color(0xFF333333), RectangleShape)
    ) {
        if (rotation <= 90f) {
            Box(Modifier.fillMaxSize()) {
                frontContent()
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = 180f
                    }
            ) {
                backContent()
            }
        }
    }
}

@Composable
fun LiveWaveCanvas(shakeMagnitude: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val phaseShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val path = Path()

        // Exertion height multiplier
        val baseAmplitude = 8f
        val maxAmplitude = (shakeMagnitude - 9.8f).coerceAtLeast(0f) * 4f + baseAmplitude
        val frequency = 0.04f

        for (x in 0 until width.toInt() step 5) {
            val radians = x * frequency + phaseShift
            val y = (height / 2f) + sin(radians.toDouble()).toFloat() * maxAmplitude
            if (x == 0) {
                path.moveTo(x.toFloat(), y)
            } else {
                path.lineTo(x.toFloat(), y)
            }
        }

        drawPath(
            path = path,
            color = MetroYellow,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

// ---------------------- PIVOT 2: WORKOUT ----------------------
@Composable
fun WorkoutPivot(
    isTracking: Boolean,
    trackingType: String,
    elapsedMillis: Long,
    distanceMeters: Float,
    caloriesBurned: Float,
    currentSpeedKmh: Float,
    liveStepsSession: Int,
    liveShake: Float,
    gpsPoints: List<Pair<Double, Double>>,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartTracking: (String) -> Unit,
    onStopTracking: (Boolean) -> Unit
) {
    var selectedMode by remember { mutableStateOf("RUN") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isTracking) {
            Text(
                text = "START ACTIVE TRACKER",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = Color.White,
                    fontWeight = FontWeight.W100
                )
            )

            Text(
                text = "Metro Active tracking runs in a background service, using physical GPS and sensors to map workouts with 0 mock data.",
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)
            )

            // Sport Type Selector (Horizontal Windows Phone Style Buttons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf("RUN", "BIKE", "WALK").forEach { mode ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF111111))
                            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333), RectangleShape)
                            .clickable { selectedMode = mode }
                            .padding(12.dp)
                            .testTag("workout_mode_select_$mode"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (isSelected) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!permissionsGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF330000))
                        .border(1.dp, Color(0xFF990000), RectangleShape)
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "LOCATION PERMISSION REQURIED",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To track run and bike modes in real-time, please grant fine location and sensor permissions.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.LightGray)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRequestPermissions,
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) {
                            Text("GRANT PERMISSIONS", style = MaterialTheme.typography.bodyMedium.copy(color = Color.White))
                        }
                    }
                }
            } else {
                // START ACTIVE WORKOUT BUTTON
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onStartTracking(selectedMode) }
                        .padding(24.dp)
                        .testTag("start_workout_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Workout",
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "START $selectedMode MODE",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }

        } else {
            // LIVE WORKOUT SESSION HUB
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE ACTIVE TRACKING",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.W100
                        )
                    )
                    Box(
                        modifier = Modifier
                            .background(Color.Red)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "REC",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Text(
                    text = "SPORT MODE: $trackingType",
                    style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray, letterSpacing = 2.sp)
                )

                // Large Elapsed Time display
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111))
                        .border(1.dp, MaterialTheme.colorScheme.primary, RectangleShape)
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "ELAPSED TIME",
                            style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        val seconds = (elapsedMillis / 1000) % 60
                        val minutes = (elapsedMillis / (1000 * 60)) % 60
                        val hours = (elapsedMillis / (1000 * 60 * 60))
                        val timeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.displayLarge.copy(
                                color = Color.White,
                                fontSize = 64.sp,
                                fontWeight = FontWeight.W100
                            )
                        )
                    }
                }

                // Grid of real-time statistics
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Distance tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF111111))
                            .border(1.dp, Color(0xFF333333), RectangleShape)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(text = "DISTANCE", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f", distanceMeters),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.W100
                                )
                            )
                            Text(text = "METERS", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                        }
                    }

                    // Calories tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF111111))
                            .border(1.dp, Color(0xFF333333), RectangleShape)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(text = "BURNING", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f", caloriesBurned),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.W100
                                )
                            )
                            Text(text = "KCAL", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Speed tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF111111))
                            .border(1.dp, Color(0xFF333333), RectangleShape)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(text = "SPEED", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f", currentSpeedKmh),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.W100
                                )
                            )
                            Text(text = "KM / H", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                        }
                    }

                    // Accelerometer / intensity tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF111111))
                            .border(1.dp, Color(0xFF333333), RectangleShape)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(text = "ACCELERATION", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f", liveShake),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.W100
                                )
                            )
                            Text(text = "M / S²", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                        }
                    }
                }

                // GPS Tracking Status block
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111))
                        .border(1.dp, Color(0xFF333333), RectangleShape)
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "GPS STREAM (0 MOCK)",
                            style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (gpsPoints.isNotEmpty()) {
                            val latest = gpsPoints.last()
                            Text(
                                text = String.format(Locale.US, "LAT: %.6f\nLNG: %.6f", latest.first, latest.second),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color.LightGray,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${gpsPoints.size} SATELLITE DATAPOINTS LOGGED",
                                style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
                            )
                        } else {
                            Text(
                                text = "Awaiting satellite signal...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { onStopTracking(true) },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1.5f)
                            .height(60.dp)
                            .testTag("stop_save_workout_button")
                    ) {
                        Text(
                            text = "STOP & SAVE",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Button(
                        onClick = { onStopTracking(false) },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        border = BorderStroke(1.dp, Color.Red),
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .testTag("discard_workout_button")
                    ) {
                        Text(
                            text = "DISCARD",
                            style = MaterialTheme.typography.bodyLarge.copy(color = Color.Red)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------- PIVOT 3: HISTORY ----------------------
@Composable
fun HistoryPivot(
    workoutSessions: List<WorkoutSession>,
    allDailyStats: List<DailyStats>,
    viewModel: HealthViewModel
) {
    var historySubTab by remember { mutableStateOf("WORKOUTS") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Horizontal mini tab headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WORKOUTS",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = if (historySubTab == "WORKOUTS") MaterialTheme.colorScheme.primary else Color.Gray,
                    fontWeight = if (historySubTab == "WORKOUTS") FontWeight.Bold else FontWeight.Light
                ),
                modifier = Modifier
                    .clickable { historySubTab = "WORKOUTS" }
                    .testTag("history_workouts_tab")
            )

            Text(
                text = "DAILY STEPS",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = if (historySubTab == "STEPS") MaterialTheme.colorScheme.primary else Color.Gray,
                    fontWeight = if (historySubTab == "STEPS") FontWeight.Bold else FontWeight.Light
                ),
                modifier = Modifier
                    .clickable { historySubTab = "STEPS" }
                    .testTag("history_steps_tab")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        AnimatedContent(
            targetState = historySubTab,
            transitionSpec = {
                if (targetState == "STEPS") {
                    (slideInHorizontally { width -> width / 2 } + fadeIn(animationSpec = tween(220)))
                        .togetherWith(slideOutHorizontally { width -> -width / 2 } + fadeOut(animationSpec = tween(220)))
                } else {
                    (slideInHorizontally { width -> -width / 2 } + fadeIn(animationSpec = tween(220)))
                        .togetherWith(slideOutHorizontally { width -> width / 2 } + fadeOut(animationSpec = tween(220)))
                }
            },
            label = "history_tab_transition",
            modifier = Modifier.weight(1f)
        ) { tab ->
            if (tab == "WORKOUTS") {
                if (workoutSessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO WORKOUT RECORDS",
                            style = MaterialTheme.typography.titleMedium.copy(color = Color.Gray, fontWeight = FontWeight.W100)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(workoutSessions) { session ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF111111))
                                    .border(1.dp, Color(0xFF333333), RectangleShape)
                                    .padding(16.dp)
                                    .testTag("workout_session_item_${session.id}")
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column {
                                            Text(
                                                text = session.type,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.startTime))
                                            Text(text = dateStr, style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                                        }

                                        IconButton(
                                            onClick = { viewModel.deleteWorkout(session.id) },
                                            modifier = Modifier.size(24.dp).testTag("delete_workout_btn_${session.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Workout",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = "DISTANCE", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                                            Text(text = String.format(Locale.US, "%.1f m", session.meters), style = MaterialTheme.typography.bodyLarge.copy(color = Color.White))
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = "CALORIES", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                                            Text(text = String.format(Locale.US, "%.1f kcal", session.calories), style = MaterialTheme.typography.bodyLarge.copy(color = Color.White))
                                        }
                                        Column(modifier = Modifier.weight(1.2f)) {
                                            Text(text = "DURATION", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                                            val min = session.durationMillis / 60000
                                            val sec = (session.durationMillis / 1000) % 60
                                            Text(text = String.format(Locale.US, "%d min %d sec", min, sec), style = MaterialTheme.typography.bodyLarge.copy(color = Color.White))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // DAILY STEPS HISTORY
                if (allDailyStats.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO STEP RECORDS",
                            style = MaterialTheme.typography.titleMedium.copy(color = Color.Gray, fontWeight = FontWeight.W100)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(allDailyStats) { stats ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF111111))
                                    .border(1.dp, Color(0xFF222222), RectangleShape)
                                    .padding(16.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stats.date,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%,d steps", stats.steps),
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.W100
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "%.1f km traveled", stats.meters / 1000f),
                                            style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.0f kcal burned", stats.calories),
                                            style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------- PIVOT 4: PROFILE ----------------------
@Composable
fun ProfilePivot(
    weightKg: Float,
    heightCm: Float,
    stepGoal: Int,
    isHealthSynced: Boolean,
    weightLogs: List<WeightLog>,
    onSaveProfile: (Float, Float, Int) -> Unit,
    onAddWeight: (Float) -> Unit,
    onDeleteWeight: (Int) -> Unit,
    onToggleHealthSync: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var inputWeight by remember { mutableStateOf(weightKg.toString()) }
    var inputHeight by remember { mutableStateOf(heightCm.toString()) }
    var inputGoal by remember { mutableStateOf(stepGoal.toString()) }
    var weightLogInput by remember { mutableStateOf("") }

    LaunchedEffect(weightKg, heightCm, stepGoal) {
        inputWeight = weightKg.toString()
        inputHeight = heightCm.toString()
        inputGoal = stepGoal.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "BODY PARAMETERS",
            style = MaterialTheme.typography.titleLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.W100
            )
        )

        // Weight & Height profile inputs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "WEIGHT (KG)", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = inputWeight,
                    onValueChange = { inputWeight = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RectangleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("profile_weight_input")
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = "HEIGHT (CM)", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = inputHeight,
                    onValueChange = { inputHeight = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RectangleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("profile_height_input")
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "DAILY STEP GOAL", style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = inputGoal,
                onValueChange = { inputGoal = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray
                ),
                modifier = Modifier.fillMaxWidth().testTag("profile_goal_input")
            )
        }

        Button(
            onClick = {
                val w = inputWeight.toFloatOrNull() ?: 75f
                val h = inputHeight.toFloatOrNull() ?: 175f
                val g = inputGoal.toIntOrNull() ?: 10000
                onSaveProfile(w, h, g)
                Toast.makeText(context, "Profile parameters updated!", Toast.LENGTH_SHORT).show()
            },
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("save_profile_button")
        ) {
            Text("UPDATE PROFILE", style = MaterialTheme.typography.bodyLarge.copy(color = Color.Black, fontWeight = FontWeight.Bold))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Health Sync permission sector
        Text(
            text = "HEALTH APP SYNCHRONIZATION",
            style = MaterialTheme.typography.titleLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.W100
            )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF333333), RectangleShape)
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HEALTH CONNECT AUTO-SYNC",
                        style = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontWeight = FontWeight.Bold)
                    )
                    Switch(
                        checked = isHealthSynced,
                        onCheckedChange = {
                            onToggleHealthSync(it)
                            if (it) {
                                Toast.makeText(context, "Auto-sync with Android Health API activated!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.testTag("health_sync_switch")
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Automatically writes daily step increments, calorie metrics, and workout logs to Android's native health store whenever they are logged.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)
                )

                if (isHealthSynced) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            // Launch standard Android Health settings if available, or Settings.ACTION_SETTINGS
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                            } else {
                                Intent(Settings.ACTION_SETTINGS)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "System Health platform requested.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CONFIGURE NATIVE ACCESS", style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Weight tracking logs section
        Text(
            text = "LOG WEIGHT HISTORY",
            style = MaterialTheme.typography.titleLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.W100
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = weightLogInput,
                onValueChange = { weightLogInput = it },
                placeholder = { Text("Weight in kg", color = Color.Gray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray
                ),
                modifier = Modifier.weight(1f).testTag("weight_log_input")
            )

            Button(
                onClick = {
                    val w = weightLogInput.toFloatOrNull()
                    if (w != null && w > 0) {
                        onAddWeight(w)
                        weightLogInput = ""
                        Toast.makeText(context, "Weight logged!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Please enter a valid weight", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.height(56.dp).testTag("log_weight_button")
            ) {
                Text("LOG", style = MaterialTheme.typography.bodyLarge.copy(color = Color.Black, fontWeight = FontWeight.Bold))
            }
        }

        // List of past weights
        if (weightLogs.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                weightLogs.take(5).forEach { log ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111111))
                            .border(1.dp, Color(0xFF222222), RectangleShape)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = String.format(Locale.US, "%.1f kg", log.weightKg),
                                style = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontWeight = FontWeight.Bold)
                            )
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(log.timestamp))
                            Text(text = dateStr, style = MaterialTheme.typography.labelLarge.copy(color = Color.Gray))
                        }

                        IconButton(
                            onClick = { onDeleteWeight(log.id) },
                            modifier = Modifier.size(24.dp).testTag("delete_weight_btn_${log.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Weight Log",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Custom mock wrapper for Health Connect setting intent compatibility
object HealthConnectClient {
    const val ACTION_HEALTH_CONNECT_SETTINGS = "androidx.health.connect.action.HEALTH_CONNECT_SETTINGS"
}

@Composable
fun ConfettiEffect(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit
) {
    val colors = listOf(
        Color(0xFFE5E500), // Yellow
        Color(0xFF107C41), // Green
        Color(0xFF0078D4), // Blue
        Color(0xFFD83B01), // Red
        Color(0xFFB4009E), // Purple
        Color(0xFFF7630C), // Orange
        Color(0xFF00B7C3)  // Teal
    )
    
    // Generate some confetti particles
    val particles = remember {
        List(80) {
            ConfettiParticle(
                x = (0..100).random().toFloat() / 100f, // relative X [0..1]
                y = -0.1f - (0..100).random().toFloat() / 100f, // staggered start above screen
                speed = 0.5f + (0..100).random().toFloat() / 100f, // speed factor
                color = colors.random(),
                size = (12..24).random().dp,
                angle = (0..360).random().toFloat(),
                rotationSpeed = (-10..10).random().toFloat() * 10f,
                drift = (-50..50).random().toFloat() / 100f
            )
        }
    }
    
    var time by remember { mutableStateOf(0f) }
    
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= 4000) break
            time = elapsed / 1000f
            androidx.compose.runtime.withFrameMillis { }
        }
        onFinished()
    }
    
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            particles.forEach { p ->
                val elapsed = time * p.speed
                val currentY = (p.y + elapsed * 0.6f) * heightPx
                val currentX = (p.x + kotlin.math.sin(time * 3f) * 0.05f + p.drift * elapsed) * widthPx
                
                // Only draw if on screen
                if (currentY in 0f..heightPx && currentX in 0f..widthPx) {
                    val particleRotation = p.angle + time * p.rotationSpeed
                    
                    drawContext.canvas.save()
                    drawContext.canvas.translate(currentX, currentY)
                    drawContext.canvas.rotate(particleRotation)
                    
                    val sizePx = p.size.toPx()
                    drawRect(
                        color = p.color,
                        topLeft = androidx.compose.ui.geometry.Offset(-sizePx / 2, -sizePx / 2),
                        size = androidx.compose.ui.geometry.Size(sizePx, sizePx * 0.6f)
                    )
                    
                    drawContext.canvas.restore()
                }
            }
        }
    }
}

data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val speed: Float,
    val color: Color,
    val size: androidx.compose.ui.unit.Dp,
    val angle: Float,
    val rotationSpeed: Float,
    val drift: Float
)

fun createGoalNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channelId = "goal_achievements_channel"
        val name = "Goal Achievements"
        val desc = "Notifies you when you achieve your daily steps goal"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = desc
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

fun sendGoalNotification(context: Context, steps: Int, goal: Int) {
    createGoalNotificationChannel(context)
    val channelId = "goal_achievements_channel"
    val notificationId = 2002

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("🏆 Step Goal Achieved!")
        .setContentText("Amazing job! You hit your goal of ${String.format(Locale.US, "%,d", goal)} steps with ${String.format(Locale.US, "%,d", steps)} steps today!")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(notificationId, notification)
}

fun scheduleSportReminder(context: Context, delayMillis: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, com.example.service.SportReminderReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        100,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val triggerTime = System.currentTimeMillis() + delayMillis
    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
}

