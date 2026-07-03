package com.scroff.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val hour: Int,
    val minute: Int,
    val action: ScheduleAction,
    val enabled: Boolean = true
)

enum class ScheduleAction {
    SCREEN_OFF,
    SCREEN_ON
}
