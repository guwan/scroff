package com.scroff.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class DebugLog(
    val timestamp: Long,
    val tag: String,
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null
) {
    fun formatTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatLevel(): String = when (level) {
        LogLevel.DEBUG -> "D"
        LogLevel.INFO -> "I"
        LogLevel.WARN -> "W"
        LogLevel.ERROR -> "E"
    }
}

/**
 * 应用内日志存储 - 单例
 *
 * 双写策略：
 * 1. 写入 logcat（adb logcat 可查看）
 * 2. 写入内存环形缓冲（应用内"诊断日志"页可查看，无需电脑）
 *
 * 容量：最多 500 条，超出自动丢弃最旧的
 */
object DebugLogStore {
    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<DebugLog>>(emptyList())
    val logs: StateFlow<List<DebugLog>> = _logs.asStateFlow()

    fun log(tag: String, level: LogLevel, message: String, throwable: Throwable? = null) {
        // 1. 写 logcat（adb 调试用）
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
        // 2. 写内存缓冲（应用内查看用）
        _logs.update { logs ->
            (logs + DebugLog(System.currentTimeMillis(), tag, level, message, throwable))
                .takeLast(MAX_LOGS)
        }
    }

    fun d(tag: String, msg: String) = log(tag, LogLevel.DEBUG, msg)
    fun i(tag: String, msg: String) = log(tag, LogLevel.INFO, msg)
    fun w(tag: String, msg: String) = log(tag, LogLevel.WARN, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(tag, LogLevel.ERROR, msg, t)

    fun clear() {
        _logs.value = emptyList()
        Log.i("DebugLogStore", "日志已清空")
    }

    /** 导出为纯文本（用于分享/复制） */
    fun exportText(): String {
        return _logs.value.joinToString("\n") { log ->
            val t = log.throwable?.let { " | ${it.javaClass.name}: ${it.message}" } ?: ""
            "${log.formatTime()} ${log.formatLevel()}/${log.tag}: ${log.message}$t"
        }
    }
}
