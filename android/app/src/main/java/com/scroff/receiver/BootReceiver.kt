package com.scroff.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scroff.data.AppDatabase
import com.scroff.service.ScheduleScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机接收器 - 开机完成后：
 * 1. 重新调度所有已启用的定时任务
 * 2. 启动 MainActivity（如果用户启用了"开机自启"）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 重新调度所有任务
                val dao = AppDatabase.getInstance(context).scheduleDao()
                val enabledSchedules = dao.getEnabledList()
                ScheduleScheduler(context).rescheduleAll(enabledSchedules)

                // 开机自启：如果用户启用了该选项，启动 MainActivity
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_AUTOSTART, false)) {
                    val launchIntent = Intent(context, com.scroff.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(launchIntent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val PREFS_NAME = "scroff_prefs"
        const val KEY_AUTOSTART = "autostart_enabled"
        const val KEY_INITIALIZED = "initialized"
    }
}
