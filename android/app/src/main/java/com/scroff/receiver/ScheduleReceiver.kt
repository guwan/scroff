package com.scroff.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scroff.data.AppDatabase
import com.scroff.data.model.ScheduleAction
import com.scroff.debug.DebugLogStore
import com.scroff.service.KernelScreenController
import com.scroff.service.ScheduleScheduler
import com.scroff.service.ScreenControlService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟触发接收器 - 执行屏幕开关操作并重新调度下一次
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ScheduleScheduler.ACTION_SCREEN_OFF -> ScheduleAction.SCREEN_OFF
            ScheduleScheduler.ACTION_SCREEN_ON -> ScheduleAction.SCREEN_ON
            else -> return
        }

        val scheduleId = intent.getLongExtra(ScheduleScheduler.EXTRA_SCHEDULE_ID, -1L)
        DebugLogStore.i(TAG, "收到定时任务触发: action=$action, scheduleId=$scheduleId")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val powerOn = (action == ScheduleAction.SCREEN_ON)
                DebugLogStore.d(TAG, "执行路径选择: powerOn=$powerOn")

                val ok = if (KernelScreenController.isAvailable()) {
                    DebugLogStore.d(TAG, "使用内核通道执行")
                    KernelScreenController.setScreenPower(powerOn)
                } else if (powerOn) {
                    DebugLogStore.d(TAG, "使用 WakeLock 开屏")
                    ScreenControlService.turnScreenOn(context)
                } else {
                    DebugLogStore.d(TAG, "使用无障碍服务关屏")
                    if (!ScreenControlService.isEnabled(context)) {
                        DebugLogStore.w(TAG, "定时任务执行失败：无障碍服务未启用")
                        false
                    } else {
                        ScreenControlService.turnScreenOff(context)
                    }
                }

                if (ok) {
                    DebugLogStore.i(TAG, "定时任务执行成功")
                } else {
                    DebugLogStore.w(TAG, "定时任务执行失败")
                }

                // 重新调度明天的同一任务
                val dao = AppDatabase.getInstance(context).scheduleDao()
                val schedules = dao.getEnabledList()
                val scheduler = ScheduleScheduler(context)
                schedules.find { it.id == scheduleId }?.let {
                    scheduler.schedule(it)
                    DebugLogStore.d(TAG, "已重新调度下一次: scheduleId=$scheduleId")
                }
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "定时任务执行异常", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleReceiver"
    }
}
