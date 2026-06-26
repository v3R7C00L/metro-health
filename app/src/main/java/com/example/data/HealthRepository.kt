package com.example.data

import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HealthRepository(private val healthDao: HealthDao) {

    fun getDailyStats(date: String): Flow<DailyStats?> = healthDao.getDailyStats(date)
    
    fun getAllDailyStats(): Flow<List<DailyStats>> = healthDao.getAllDailyStats()

    fun getAllWorkoutSessions(): Flow<List<WorkoutSession>> = healthDao.getAllWorkoutSessions()

    fun getAllWeightLogs(): Flow<List<WeightLog>> = healthDao.getAllWeightLogs()

    suspend fun insertDailyStats(stats: DailyStats) {
        healthDao.insertDailyStats(stats)
    }

    suspend fun addSteps(date: String, stepsToAdd: Int, userWeightKg: Float = 70f, userHeightCm: Float = 175f) {
        val current = healthDao.getDailyStatsDirect(date) ?: DailyStats(date = date, steps = 0, meters = 0f, calories = 0f)
        
        val newSteps = current.steps + stepsToAdd
        // Calculate meters from steps. Average stride length is roughly height * 0.415
        val strideLengthMeters = (userHeightCm * 0.415f) / 100f
        val newMeters = newSteps * strideLengthMeters
        
        // Calculate calories from steps. Formula adjusted for weight: steps * 0.00057 * weight
        val calorieFactor = 0.00057f * userWeightKg
        val newCalories = newSteps * calorieFactor

        healthDao.insertDailyStats(
            current.copy(
                steps = newSteps,
                meters = newMeters,
                calories = newCalories
            )
        )
    }

    suspend fun insertWorkoutSession(session: WorkoutSession) {
        healthDao.insertWorkoutSession(session)
        
        // When we save a workout session, let's also add its meters and calories to our daily total!
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = sdf.format(Date(session.startTime))
        
        val current = healthDao.getDailyStatsDirect(dateStr) ?: DailyStats(date = dateStr, steps = 0, meters = 0f, calories = 0f)
        healthDao.insertDailyStats(
            current.copy(
                meters = current.meters + session.meters,
                calories = current.calories + session.calories
            )
        )
    }

    suspend fun deleteWorkoutSession(id: Int) {
        healthDao.deleteWorkoutSession(id)
    }

    suspend fun insertWeightLog(log: WeightLog) {
        healthDao.insertWeightLog(log)
    }

    suspend fun deleteWeightLog(id: Int) {
        healthDao.deleteWeightLog(id)
    }
}
