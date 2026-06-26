package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DailyStats
import com.example.data.HealthDatabase
import com.example.data.HealthRepository
import com.example.data.WeightLog
import com.example.data.WorkoutSession
import com.example.service.TrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HealthViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: HealthRepository = HealthRepository(HealthDatabase.getDatabase(application).healthDao())
    private val sharedPrefs: SharedPreferences = application.getSharedPreferences("health_prefs", Context.MODE_PRIVATE)

    private val _todayDate = MutableStateFlow(getTodayDateString())
    val todayDate = _todayDate.asStateFlow()

    // Preferences
    private val _weightKg = MutableStateFlow(sharedPrefs.getFloat("weight_kg", 75.0f))
    val weightKg = _weightKg.asStateFlow()

    private val _heightCm = MutableStateFlow(sharedPrefs.getFloat("height_cm", 175.0f))
    val heightCm = _heightCm.asStateFlow()

    private val _stepGoal = MutableStateFlow(sharedPrefs.getInt("step_goal", 10000))
    val stepGoal = _stepGoal.asStateFlow()

    private val _isHealthSynced = MutableStateFlow(sharedPrefs.getBoolean("health_synced", false))
    val isHealthSynced = _isHealthSynced.asStateFlow()

    // Database flows
    val todayStats: StateFlow<DailyStats?> = _todayDate
        .flatMapLatest { date -> repository.getDailyStats(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allDailyStats: StateFlow<List<DailyStats>> = repository.getAllDailyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workoutSessions: StateFlow<List<WorkoutSession>> = repository.getAllWorkoutSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weightLogs: StateFlow<List<WeightLog>> = repository.getAllWeightLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        val database = HealthDatabase.getDatabase(application)
        
        // Auto initialize today's database row if it doesn't exist
        viewModelScope.launch {
            val today = getTodayDateString()
            val existing = database.healthDao().getDailyStatsDirect(today)
            if (existing == null) {
                repository.insertDailyStats(
                    DailyStats(
                        date = today,
                        steps = 0,
                        meters = 0f,
                        calories = 0f,
                        goalSteps = _stepGoal.value,
                        goalCalories = _stepGoal.value * 0.04f
                    )
                )
            }
        }
    }

    private fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    fun updateTodayDate() {
        _todayDate.value = getTodayDateString()
    }

    // Profile Settings Actions
    fun saveProfile(weight: Float, height: Float, goal: Int) {
        _weightKg.value = weight
        _heightCm.value = height
        _stepGoal.value = goal
        sharedPrefs.edit()
            .putFloat("weight_kg", weight)
            .putFloat("height_cm", height)
            .putInt("step_goal", goal)
            .apply()

        // Sync new goal to today's stats if they exist
        viewModelScope.launch {
            val today = _todayDate.value
            val current = HealthDatabase.getDatabase(getApplication()).healthDao().getDailyStatsDirect(today)
            if (current != null) {
                repository.insertDailyStats(
                    current.copy(
                        goalSteps = goal,
                        goalCalories = goal * 0.04f
                    )
                )
            }
        }
    }

    fun toggleHealthSync(enabled: Boolean) {
        _isHealthSynced.value = enabled
        sharedPrefs.edit().putBoolean("health_synced", enabled).apply()
    }

    // Manual sensor adjustments / manual entry for steps & weights
    fun logManualSteps(steps: Int) {
        viewModelScope.launch {
            repository.addSteps(_todayDate.value, steps, _weightKg.value, _heightCm.value)
        }
    }

    fun logManualWeight(weight: Float) {
        viewModelScope.launch {
            repository.insertWeightLog(WeightLog(timestamp = System.currentTimeMillis(), weightKg = weight))
            // Update weight setting too
            _weightKg.value = weight
            sharedPrefs.edit().putFloat("weight_kg", weight).apply()
        }
    }

    fun deleteWeight(id: Int) {
        viewModelScope.launch {
            repository.deleteWeightLog(id)
        }
    }

    fun deleteWorkout(id: Int) {
        viewModelScope.launch {
            repository.deleteWorkoutSession(id)
        }
    }

    // Workout Tracking Intents
    fun startWorkoutTracking(type: String) {
        val intent = Intent(getApplication(), TrackingService::class.java).apply {
            action = "START_TRACKING"
            putExtra("TRACKING_TYPE", type)
        }
        getApplication<Application>().startService(intent)
    }

    fun stopWorkoutTracking(saveSession: Boolean = true) {
        val intent = Intent(getApplication(), TrackingService::class.java).apply {
            action = "STOP_TRACKING"
            putExtra("SAVE_SESSION", saveSession)
        }
        getApplication<Application>().startService(intent)
    }
}
