package com.scroff.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import com.scroff.debug.DebugLogStore

/**
 * 无障碍服务 - 用于执行屏幕开关操作
 * 需要用户在系统设置中手动开启无障碍权限
 *
 * 实现说明：
 * - 关闭屏幕：使用 GLOBAL_ACTION_LOCK_SCREEN（API 28+）触发系统锁屏，屏幕随之熄灭
 * - 打开屏幕：使用 PowerManager 的 WakeLock 唤醒屏幕
 *
 * 重要：service 的"是否启用"和"instance 是否非空"是两个状态：
 * - 系统设置里启用了 ≠ 本进程 instance 一定非空
 * - 进程重启后系统不会主动重新调 onServiceConnected()，需要等系统派发第一个事件
 * - 因此【是否启用】必须用 Settings.Secure 检查，不能用 instance != null
 */
@RequiresApi(Build.VERSION_CODES.P)
class ScreenControlService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件
    }

    override fun onInterrupt() {
        DebugLogStore.w(TAG, "无障碍服务被系统中断 onInterrupt()")
    }

    companion object {
        private const val TAG = "ScreenControlService"

        @Volatile
        var instance: ScreenControlService? = null
            private set

        /**
         * 检查无障碍服务是否在**系统设置中**已启用
         *
         * 匹配策略（适配各 ROM 差异）：
         * - Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 返回形如 "pkg/.ClassName" 的列表
         * - 不同 ROM 对内部路径表示不一致（有的带点有的不带点）
         * - 因此采用"包名+类名同时存在"的模糊匹配策略
         *
         * 双方法检测，任一返回 true 即视为已启用：
         * 1. Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES（最可靠）
         * 2. AccessibilityManager.getEnabledAccessibilityServiceList（官方 API）
         */
        fun isEnabled(context: Context): Boolean {
            val pkg = context.packageName
            val cls = ScreenControlService::class.java.name
            DebugLogStore.d(TAG, "isEnabled() 检测 pkg='$pkg', cls='$cls'")

            val fromSettings = checkEnabledFromSettings(context, pkg, cls)
            val fromManager = checkEnabledFromManager(context, pkg, cls)

            val result = fromSettings || fromManager
            DebugLogStore.i(
                TAG,
                "isEnabled() 结果: settings=$fromSettings, manager=$fromManager, 最终=$result"
            )
            return result
        }

        /**
         * 方法 1：直接读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
         * 格式: "com.foo/.BarService:com.baz/.QuxService"
         *
         * 匹配规则（任一命中即视为已启用）：
         * - 完整路径匹配
         * - 包名 + 简化类名（去掉前缀包名）
         * - 包名 + 完整类名（带或不带前导点）
         */
        private fun checkEnabledFromSettings(context: Context, pkg: String, cls: String): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (enabled.isNullOrEmpty()) {
                    DebugLogStore.d(TAG, "Settings.Secure 启用列表为空")
                    return false
                }
                DebugLogStore.d(TAG, "Settings.Secure 启用列表: '$enabled'")
                val services = enabled.split(":").map { it.trim() }.filter { it.isNotEmpty() }
                DebugLogStore.d(TAG, "解析为 ${services.size} 个服务:")
                val simpleClass = cls.removePrefix(pkg).trimStart('.')

                services.forEachIndexed { i, s ->
                    val match = matchesAny(s, pkg, cls, simpleClass)
                    DebugLogStore.d(TAG, "  [$i] '$s' ${if (match) "← 匹配!" else ""}")
                    if (match) return true
                }
                false
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "读取 Settings.Secure 失败", e)
                false
            }
        }

        /**
         * 方法 2：AccessibilityManager.getEnabledAccessibilityServiceList
         */
        private fun checkEnabledFromManager(context: Context, pkg: String, cls: String): Boolean {
            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    ?: run {
                        DebugLogStore.w(TAG, "AccessibilityManager 为 null")
                        return false
                    }
                val services = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                DebugLogStore.d(TAG, "AccessibilityManager 启用服务数: ${services.size}")
                val simpleClass = cls.removePrefix(pkg).trimStart('.')
                services.forEachIndexed { i, info ->
                    val flatten = info.resolveInfo?.serviceInfo?.let { si ->
                        ComponentName(si.packageName, si.name).flattenToString()
                    }.orEmpty()
                    val match = matchesAny(info.id, pkg, cls, simpleClass) ||
                        matchesAny(flatten, pkg, cls, simpleClass)
                    DebugLogStore.d(TAG, "  [$i] id='${info.id}' component='$flatten' ${if (match) "← 匹配!" else ""}")
                    if (match) return true
                }
                false
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "读取 AccessibilityManager 失败", e)
                false
            }
        }

        /**
         * 模糊匹配：检查 entry 是否指向目标服务
         * 适配不同 ROM 对路径的表示差异
         */
        private fun matchesAny(entry: String, pkg: String, cls: String, simpleClass: String): Boolean {
            if (entry.isEmpty()) return false
            return entry == "$pkg/$cls" ||                  // 完整: com.foo/com.foo.Bar
                entry == "$pkg/.${simpleClass}" ||         // 简化: com.foo/.Bar
                entry == "$pkg/${simpleClass}" ||          // 简化(无点): com.foo/Bar
                entry.endsWith("/$cls") ||                 // 后缀匹配完整类名
                (entry.contains("/${simpleClass}"))        // 后缀匹配简化类名
        }

        /**
         * 关闭屏幕（锁屏）
         * 需要无障碍服务已在系统设置中启用
         *
         * 重试策略：
         * 1. 先检查 instance 是否就绪
         * 2. 如果未就绪，尝试主动 bind 强制系统连接
         * 3. 等待最多 3000ms
         *
         * @return true=成功，false=失败
         */
        fun turnScreenOff(context: Context? = null): Boolean {
            DebugLogStore.i(TAG, "turnScreenOff() 开始")
            val svc = waitForInstanceWithBind(context, 3000L) ?: run {
                DebugLogStore.w(
                    TAG,
                    "turnScreenOff 失败：instance 始终为 null（3000ms 内未连接）"
                )
                return false
            }
            DebugLogStore.d(TAG, "instance 就绪，调用 performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)")
            return try {
                val ok = svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                if (ok) {
                    DebugLogStore.i(TAG, "turnScreenOff 成功：performGlobalAction 返回 true")
                } else {
                    DebugLogStore.w(TAG, "turnScreenOff 失败：performGlobalAction 返回 false（可能被系统拒绝）")
                }
                ok
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "turnScreenOff 异常", e)
                false
            }
        }

        /**
         * 等待 instance 就绪（最多 timeoutMs 毫秒）
         * 期间如果 instance 一直为 null，会尝试主动 bind 来强制系统连接
         */
        private fun waitForInstanceWithBind(context: Context?, timeoutMs: Long): ScreenControlService? {
            instance?.let {
                DebugLogStore.d(TAG, "instance 已就绪（无需等待）")
                return it
            }
            DebugLogStore.d(TAG, "instance 为 null，开始轮询等待（最多 ${timeoutMs}ms）")

            if (context != null) {
                // 主动 bind（异步）
                DebugLogStore.d(TAG, "主动调用 bindAccessibilityService")
                bindAccessibilityService(context)
            }

            // 等待
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                instance?.let {
                    val elapsed = System.currentTimeMillis() - start
                    DebugLogStore.d(TAG, "instance 在 ${elapsed}ms 后就绪")
                    return it
                }
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return null
        }

        /**
         * 主动绑定 AccessibilityService
         * 用 SERVICE_INTERFACE + ServiceConnection 异步等待连接
         */
        private fun bindAccessibilityService(context: Context) {
            try {
                val intent = android.content.Intent(android.accessibilityservice.AccessibilityService.SERVICE_INTERFACE)
                    .setComponent(
                        android.content.ComponentName(
                            context.packageName,
                            ScreenControlService::class.java.name
                        )
                    )
                val connection = object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                        DebugLogStore.i(TAG, "ServiceConnection.onServiceConnected: $name")
                        // onServiceConnected 后，onServiceConnected 也会被系统回调（因为我们也是 AccessibilityService）
                    }
                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        DebugLogStore.w(TAG, "ServiceConnection.onServiceDisconnected: $name")
                    }
                }
                val bound = context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
                DebugLogStore.d(TAG, "bindService 返回: $bound")
                if (bound) {
                    // 3s 后解绑（防止泄漏）
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            context.unbindService(connection)
                            DebugLogStore.d(TAG, "AccessibilityService 已解绑")
                        } catch (e: Exception) {
                            DebugLogStore.d(TAG, "解绑异常: ${e.message}")
                        }
                    }, 3000)
                }
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "bindAccessibilityService 异常", e)
            }
        }

        /**
         * 打开屏幕（唤醒）
         */
        @Suppress("DEPRECATION")
        fun turnScreenOn(context: Context): Boolean {
            DebugLogStore.i(TAG, "turnScreenOn() 开始")
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "Scroff:ScreenOn"
                )
                wakeLock.acquire(3000)
                wakeLock.release()
                DebugLogStore.i(TAG, "turnScreenOn 成功：WakeLock 已获取并释放")
                true
            } catch (e: Exception) {
                DebugLogStore.e(TAG, "turnScreenOn 异常", e)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLogStore.i(TAG, "onServiceConnected: 服务已连接到本进程，instance 已赋值")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        DebugLogStore.i(TAG, "onDestroy: 服务实例已销毁")
    }
}
