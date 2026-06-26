package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_stats")
data class DailyStats(
    @PrimaryKey val date: String, // "yyyy-MM-dd"
    val steps: Int,
    val meters: Float,
    val calories: Float,
    val goalSteps: Int = 10000,
    val goalCalories: Float = 500f
)
