package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDao {
    @Query("SELECT * FROM daily_stats WHERE date = :date")
    fun getDailyStats(date: String): Flow<DailyStats?>

    @Query("SELECT * FROM daily_stats WHERE date = :date")
    suspend fun getDailyStatsDirect(date: String): DailyStats?

    @Query("SELECT * FROM daily_stats ORDER BY date DESC")
    fun getAllDailyStats(): Flow<List<DailyStats>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyStats(stats: DailyStats)

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC")
    fun getAllWorkoutSessions(): Flow<List<WorkoutSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutSession(session: WorkoutSession)

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun deleteWorkoutSession(id: Int)

    @Query("SELECT * FROM weight_logs ORDER BY timestamp DESC")
    fun getAllWeightLogs(): Flow<List<WeightLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeightLog(log: WeightLog)

    @Query("DELETE FROM weight_logs WHERE id = :id")
    suspend fun deleteWeightLog(id: Int)
}
