package com.scroff.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.scroff.data.AppDatabase
import com.scroff.data.ScheduleRepository
import com.scroff.data.model.Schedule
import com.scroff.data.model.ScheduleAction
import com.scroff.debug.DebugLogStore
import com.scroff.receiver.BootReceiver
import com.scroff.service.KernelScreenController
import com.scroff.service.ScheduleScheduler
import com.scroff.service.ScreenControlService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleFormState(
    val isEditing: Boolean = false,
    val editingId: Long? = null,
    val name: String = "",
    val hour: Int = 7,
    val minute: Int = 50,
    val action: ScheduleAction = ScheduleAction.SCREEN_ON
)

/** 单次事件：执行结果反馈（成功/失败原因） */
sealed class ExecuteResult {
    data class Success(val message: String) : ExecuteResult()
    /** 无障碍服务未开启，需要用户去设置开启 */
    data object AccessibilityNotEnabled : ExecuteResult()
    /** 系统级已启用但本进程未连接，提示用户按 Home 再回来 */
    data object AccessibilityNotConnected : ExecuteResult()
    /** 调用被系统拒绝（设备/ROM 限制） */
    data class Rejected(val message: String) : ExecuteResult()
}

class ScheduleViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = ScheduleRepository(AppDatabase.getInstance(application).scheduleDao())
    private val scheduler = ScheduleScheduler(application)

    val schedules: StateFlow<List<Schedule>> = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    var formState: ScheduleFormState = ScheduleFormState()
        private set

    private val _executeResults = Channel<ExecuteResult>(Channel.BUFFERED)
    val executeResults: Flow<ExecuteResult> = _executeResults.receiveAsFlow()

    val isAccessibilityEnabled: Boolean
        get() = ScreenControlService.isEnabled(getApplication())

    fun isAutoStartEnabled(): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(
            BootReceiver.PREFS_NAME, Context.MODE_PRIVATE
        )
        return prefs.getBoolean(BootReceiver.KEY_AUTOSTART, false)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences(
            BootReceiver.PREFS_NAME, Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(BootReceiver.KEY_AUTOSTART, enabled).apply()
    }

    fun startAdd() {
        formState = ScheduleFormState()
    }

    fun startEdit(schedule: Schedule) {
        formState = ScheduleFormState(
            isEditing = true,
            editingId = schedule.id,
            name = schedule.name,
            hour = schedule.hour,
            minute = schedule.minute,
            action = schedule.action
        )
    }

    fun updateForm(
        name: String? = null,
        hour: Int? = null,
        minute: Int? = null,
        action: ScheduleAction? = null
    ) {
        formState = formState.copy(
            name = name ?: formState.name,
            hour = hour ?: formState.hour,
            minute = minute ?: formState.minute,
            action = action ?: formState.action
        )
    }

    fun saveForm(onComplete: () -> Unit) {
        val state = formState
        viewModelScope.launch {
            val displayName = if (state.name.isBlank()) generateDefaultName(state.action) else state.name.trim()
            if (state.isEditing && state.editingId != null) {
                val existing = schedules.value.find { it.id == state.editingId } ?: return@launch
                val updated = existing.copy(
                    name = displayName,
                    hour = state.hour,
                    minute = state.minute,
                    action = state.action
                )
                repository.update(updated)
                scheduler.cancel(updated)
                if (updated.enabled) scheduler.schedule(updated)
            } else {
                val schedule = Schedule(
                    name = displayName,
                    hour = state.hour,
                    minute = state.minute,
                    action = state.action,
                    enabled = true
                )
                val id = repository.insert(schedule)
                scheduler.schedule(schedule.copy(id = id))
            }
            onComplete()
        }
    }

    fun delete(schedule: Schedule) {
        viewModelScope.launch {
            scheduler.cancel(schedule)
            repository.delete(schedule)
        }
    }

    fun toggleEnabled(schedule: Schedule) {
        viewModelScope.launch {
            val updated = schedule.copy(enabled = !schedule.enabled)
            repository.update(updated)
            if (updated.enabled) {
                scheduler.schedule(updated)
            } else {
                scheduler.cancel(updated)
            }
        }
    }

    /**
     * 立即执行任务
     *
     * 优先级：
     * 1. 内核通道（root 设备 + dispdbg 可用）—— 厂家推荐方案，最可靠
     * 2. 无障碍服务（系统设置中已启用）—— fallback
     * 3. 弹错提示
     */
    fun executeNow(schedule: Schedule) {
        viewModelScope.launch {
            DebugLogStore.i(TAG, "executeNow() 点击执行: schedule=${schedule.name}, action=${schedule.action}")
            if (schedule.action == ScheduleAction.SCREEN_OFF) {
                DebugLogStore.d(TAG, "执行路径: 关屏")
                // 1) 内核通道优先
                DebugLogStore.d(TAG, "尝试内核通道...")
                val kernelOk = KernelScreenController.isAvailable() &&
                    KernelScreenController.setScreenPower(false)
                if (kernelOk) {
                    DebugLogStore.i(TAG, "内核通道关屏成功")
                    _executeResults.send(ExecuteResult.Success("已关屏（内核通道）"))
                    return@launch
                }
                DebugLogStore.d(TAG, "内核通道不可用或失败，回退到无障碍服务")
                // 2) 无障碍服务 fallback
                val enabled = isAccessibilityEnabled
                DebugLogStore.d(TAG, "无障碍服务状态: $enabled")
                if (!enabled) {
                    DebugLogStore.w(TAG, "执行失败：无障碍服务未启用")
                    _executeResults.send(ExecuteResult.AccessibilityNotEnabled)
                    return@launch
                }
                val ok = ScreenControlService.turnScreenOff(getApplication())
                if (ok) {
                    DebugLogStore.i(TAG, "无障碍服务关屏成功")
                    _executeResults.send(ExecuteResult.Success("已触发锁屏"))
                } else {
                    DebugLogStore.w(TAG, "无障碍服务关屏失败（instance 未就绪）")
                    _executeResults.send(ExecuteResult.AccessibilityNotConnected)
                }
            } else {
                DebugLogStore.d(TAG, "执行路径: 开屏")
                // 开屏
                DebugLogStore.d(TAG, "尝试内核通道...")
                val kernelOk = KernelScreenController.isAvailable() &&
                    KernelScreenController.setScreenPower(true)
                if (kernelOk) {
                    DebugLogStore.i(TAG, "内核通道开屏成功")
                    _executeResults.send(ExecuteResult.Success("已开屏（内核通道）"))
                    return@launch
                }
                DebugLogStore.d(TAG, "内核通道不可用或失败，回退到 WakeLock")
                val ok = ScreenControlService.turnScreenOn(getApplication())
                if (ok) {
                    DebugLogStore.i(TAG, "WakeLock 开屏成功")
                    _executeResults.send(ExecuteResult.Success("已唤醒屏幕"))
                } else {
                    DebugLogStore.w(TAG, "WakeLock 开屏失败")
                    _executeResults.send(ExecuteResult.Rejected("系统拒绝了唤醒操作"))
                }
            }
        }
    }

    /**
     * 一键自检 - 收集设备环境信息写入日志，便于排查
     */
    fun runDiagnostics() {
        viewModelScope.launch {
            DebugLogStore.i(TAG, "═══════════ 开始自检 ═══════════")
            val ctx = getApplication<Application>()

            // 1. 应用包名
            DebugLogStore.i(TAG, "[1] 应用包名: ${ctx.packageName}")

            // 2. 服务组件名（预期值）
            val expected = android.content.ComponentName(ctx, ScreenControlService::class.java).flattenToString()
            DebugLogStore.i(TAG, "[2] 预期服务组件名: $expected")

            // 3. Android 版本
            DebugLogStore.i(TAG, "[3] Android 版本: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            DebugLogStore.i(TAG, "[3] 设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")

            // 4. 无障碍服务状态
            DebugLogStore.i(TAG, "[4] 检测无障碍服务状态...")
            val enabled = ScreenControlService.isEnabled(ctx)
            DebugLogStore.i(TAG, "[4] 无障碍服务启用: $enabled")

            // 5. instance 状态
            DebugLogStore.i(TAG, "[5] 本进程 instance: ${if (ScreenControlService.instance != null) "非空" else "null"}")

            // 6. Root 状态
            DebugLogStore.i(TAG, "[6] 检测 root...")
            val root = KernelScreenController.isAvailable()
            DebugLogStore.i(TAG, "[6] Root 可用: $root")

            // 7. dispdbg 路径
            val dispdbgFile = java.io.File("/sys/kernel/debug/dispdbg")
            DebugLogStore.i(TAG, "[7] dispdbg (Rockchip) 路径存在: ${dispdbgFile.exists()}")
            // Allwinner fb
            val fbBlank = java.io.File("/sys/devices/virtual/graphics/fb0/blank")
            DebugLogStore.i(TAG, "[7] fb0/blank (Allwinner) 路径存在: ${fbBlank.exists()}")
            // 通用 backlight
            val blPaths = listOf(
                "/sys/class/backlight/backlight/brightness",
                "/sys/class/backlight/lcd-backlight/brightness",
                "/sys/class/backlight/sprd_backlight/brightness"
            )
            blPaths.forEach { p ->
                val exists = java.io.File(p).exists()
                DebugLogStore.i(TAG, "[7] backlight 路径 '$p' 存在: $exists")
            }

            // 8. 开机自启状态
            val autoStart = isAutoStartEnabled()
            DebugLogStore.i(TAG, "[8] 开机自启: $autoStart")

            // 9. 任务数
            val count = schedules.value.size
            DebugLogStore.i(TAG, "[9] 任务总数: $count")

            // 10. 内核通道平台检测
            DebugLogStore.i(TAG, "[10] 内核通道平台检测...")
            val kernelOk = KernelScreenController.isAvailable()
            DebugLogStore.i(TAG, "[10] 内核通道可用: $kernelOk")

            DebugLogStore.i(TAG, "═══════════ 自检完成 ═══════════")
        }
    }

    /** 清空日志 */
    fun clearLogs() {
        DebugLogStore.clear()
    }

    companion object {
        private const val TAG = "ScheduleViewModel"
    }

    private fun generateDefaultName(action: ScheduleAction): String {
        val actionText = if (action == ScheduleAction.SCREEN_OFF) "关闭屏幕" else "打开屏幕"
        val count = schedules.value.count { it.action == action } + 1
        return "${actionText}_$count"
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val app: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScheduleViewModel(app) as T
        }
    }
}
