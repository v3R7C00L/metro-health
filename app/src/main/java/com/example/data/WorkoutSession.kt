package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String, // "RUN", "BIKE", "WALK", "WORKOUT"
    val startTime: Long,
    val durationMillis: Long,
    val meters: Float,
    val calories: Float,
    val note: String = ""
)
