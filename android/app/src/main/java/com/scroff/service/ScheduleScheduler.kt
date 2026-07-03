package com.scroff.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.scroff.data.model.Schedule
import com.scroff.data.model.ScheduleAction
import com.scroff.receiver.ScheduleReceiver
import java.util.Calendar

/**
 * 定时调度器 - 使用 AlarmManager 精确调度屏幕开关任务
 *
 * 说明：Android 的 setRepeating 在 Doze 模式下不精确，
 * 因此使用 setAlarmClock（最精确，会触发系统闹钟图标）确保准时执行。
 * 任务每天重复，靠 ScheduleReceiver 触发后重新调度下一天。
 */
class ScheduleScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(schedule: Schedule) {
        if (!schedule.enabled) {
            cancel(schedule)
            return
        }

        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = when (schedule.action) {
                ScheduleAction.SCREEN_OFF -> ACTION_SCREEN_OFF
                ScheduleAction.SCREEN_ON -> ACTION_SCREEN_ON
            }
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, schedule.hour)
            set(Calendar.MINUTE, schedule.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // setAlarmClock 最精确，会在状态栏显示闹钟图标
        val info = AlarmManager.AlarmClockInfo(calendar.timeInMillis, null)
        alarmManager.setAlarmClock(info, pendingIntent)
    }

    fun cancel(schedule: Schedule) {
        val intent = Intent(context, ScheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.cancel()
        alarmManager.cancel(pendingIntent)
    }

    /**
     * 重新调度所有已启用的任务（开机后调用）
     */
    fun rescheduleAll(schedules: List<Schedule>) {
        schedules.filter { it.enabled }.forEach { schedule(it) }
    }

    companion object {
        const val ACTION_SCREEN_OFF = "com.scroff.ACTION_SCREEN_OFF"
        const val ACTION_SCREEN_ON = "com.scroff.ACTION_SCREEN_ON"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
