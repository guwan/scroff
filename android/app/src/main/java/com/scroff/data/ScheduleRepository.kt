package com.scroff.data

import com.scroff.data.dao.ScheduleDao
import com.scroff.data.model.Schedule
import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val dao: ScheduleDao) {
    fun getAll(): Flow<List<Schedule>> = dao.getAll()
    fun getEnabled(): Flow<List<Schedule>> = dao.getEnabled()
    suspend fun count() = dao.count()
    suspend fun getEnabledList() = dao.getEnabledList()
    suspend fun insert(schedule: Schedule) = dao.insert(schedule)
    suspend fun update(schedule: Schedule) = dao.update(schedule)
    suspend fun delete(schedule: Schedule) = dao.delete(schedule)
}
