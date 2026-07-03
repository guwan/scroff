package com.scroff

import android.app.Application
import android.content.Context
import com.scroff.data.AppDatabase
import com.scroff.data.ScheduleRepository
import com.scroff.data.model.Schedule
import com.scroff.data.model.ScheduleAction
import com.scroff.receiver.BootReceiver
import com.scroff.service.ScheduleScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScroffApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        initializeDefaultData()
    }

    /**
     * 首次运行时注入默认任务：07:50 打开屏幕、17:30 关闭屏幕
     * 并默认启用开机自启
     */
    private fun initializeDefaultData() {
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(BootReceiver.KEY_INITIALIZED, false)) {
            return
        }

        appScope.launch {
            val dao = AppDatabase.getInstance(this@ScroffApp).scheduleDao()
            if (dao.count() == 0) {
                val defaults = listOf(
                    Schedule(
                        name = "打开屏幕_1",
                        hour = 7,
                        minute = 50,
                        action = ScheduleAction.SCREEN_ON,
                        enabled = true
                    ),
                    Schedule(
                        name = "关闭屏幕_1",
                        hour = 17,
                        minute = 30,
                        action = ScheduleAction.SCREEN_OFF,
                        enabled = true
                    )
                )

                val scheduler = ScheduleScheduler(this@ScroffApp)
                defaults.forEach { schedule ->
                    val id = dao.insert(schedule)
                    val inserted = schedule.copy(id = id)
                    scheduler.schedule(inserted)
                }

                // 默认启用开机自启
                prefs.edit()
                    .putBoolean(BootReceiver.KEY_AUTOSTART, true)
                    .putBoolean(BootReceiver.KEY_INITIALIZED, true)
                    .apply()
            } else {
                prefs.edit().putBoolean(BootReceiver.KEY_INITIALIZED, true).apply()
            }
        }
    }
}
