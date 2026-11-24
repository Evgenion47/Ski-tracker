package com.skitracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val topSpeedMs: Float,
    val avgSpeedMs: Float,
    val distanceMeters: Float,
    val durationMs: Long,
    val elevationGainM: Float = 0f,
    val elevationLossM: Float = 0f,
)
