package com.scroff.data.dao

import androidx.room.*
import com.scroff.data.model.Schedule
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY hour, minute")
    fun getAll(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE enabled = 1 ORDER BY hour, minute")
    fun getEnabled(): Flow<List<Schedule>>

    @Query("SELECT COUNT(*) FROM schedules")
    suspend fun count(): Int

    @Insert
    suspend fun insert(schedule: Schedule): Long

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun getEnabledList(): List<Schedule>
}
