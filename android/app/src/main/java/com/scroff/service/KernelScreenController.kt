package com.scroff.service

import com.scroff.debug.DebugLogStore
import java.io.File

/**
 * 内核级屏幕控制（多平台厂家方案）
 *
 * 支持的平台/接口（按优先级探测）：
 * 1. Rockchip dispdbg（厂家原始方案）
 *    开屏: echo 0 > param ; 关屏: echo 1 > param
 * 2. Allwinner fb blank
 *    开屏: echo 0 ; 关屏: echo 4
 * 3. 通用 backlight brightness
 *    开屏: echo 最高亮度 ; 关屏: echo 0
 *
 * 优势：
 * - 不依赖无障碍服务，用户无需手动开启
 * - 直接走内核级，控制可靠
 * - 速度更快（无系统锁屏动画）
 *
 * 限制：
 * - 需要 root 权限
 */
object KernelScreenController {
    private const val TAG = "KernelScreenController"

    // Rockchip dispdbg 路径
    private const val DEBUG_DISPDB_PATH = "/sys/kernel/debug/dispdbg"

    // Allwinner fb blank 路径（多种可能的 framebuffer）
    private val FB_BLANK_PATHS = listOf(
        "/sys/devices/virtual/graphics/fb0/blank",
        "/sys/devices/virtual/graphics/fb1/blank"
    )

    // Backlight 目录（多种设备）
    private val BACKLIGHT_PATHS = listOf(
        "/sys/class/backlight/backlight/brightness",
        "/sys/class/backlight/lcd-backlight/brightness",
        "/sys/class/backlight/sprd_backlight/brightness"
    )

    /** 当前选择的平台 */
    private enum class Platform { UNKNOWN, ROCKCHIP, ALLWINNER_FB, BACKLIGHT }

    @Volatile
    private var platform: Platform = Platform.UNKNOWN

    /**
     * 检查内核通道是否可用
     */
    fun isAvailable(): Boolean {
        DebugLogStore.d(TAG, "isAvailable() 开始探测")
        val root = isRootAvailable()
        if (!root) {
            DebugLogStore.w(TAG, "isAvailable() false: 设备未 root")
            return false
        }
        // 探测可用平台
        val detected = detectPlatform()
        DebugLogStore.i(TAG, "isAvailable() root=true, 探测到的平台=$detected")
        return detected != Platform.UNKNOWN
    }

    /**
     * 通过内核通道设置屏幕开关
     */
    fun setScreenPower(on: Boolean): Boolean {
        val p = detectPlatform()
        platform = p
        DebugLogStore.i(TAG, "setScreenPower(on=$on) 平台=$p")
        return when (p) {
            Platform.ROCKCHIP -> doRockchip(on)
            Platform.ALLWINNER_FB -> doAllwinnerFb(on)
            Platform.BACKLIGHT -> doBacklight(on)
            Platform.UNKNOWN -> {
                DebugLogStore.w(TAG, "setScreenPower 失败：未检测到支持的平台")
                false
            }
        }
    }

    private fun detectPlatform(): Platform {
        if (platform != Platform.UNKNOWN) return platform
        // 按优先级探测
        if (File(DEBUG_DISPDB_PATH).exists()) {
            platform = Platform.ROCKCHIP
            return platform
        }
        val (exit0, out0, _) = runShell("ls $DEBUG_DISPDB_PATH 2>/dev/null")
        if (exit0 == 0 && out0.isNotEmpty()) {
            platform = Platform.ROCKCHIP
            return platform
        }
        FB_BLANK_PATHS.forEach { path ->
            if (File(path).exists()) {
                platform = Platform.ALLWINNER_FB
                return platform
            }
        }
        val (exitFb, outFb, _) = runShell("ls ${FB_BLANK_PATHS[0]} 2>/dev/null")
        if (exitFb == 0 && outFb.isNotEmpty()) {
            platform = Platform.ALLWINNER_FB
            return platform
        }
        BACKLIGHT_PATHS.forEach { path ->
            if (File(path).exists()) {
                platform = Platform.BACKLIGHT
                return platform
            }
        }
        val (exitBl, outBl, _) = runShell("ls ${BACKLIGHT_PATHS[0]} 2>/dev/null")
        if (exitBl == 0 && outBl.isNotEmpty()) {
            platform = Platform.BACKLIGHT
            return platform
        }
        return Platform.UNKNOWN
    }

    /**
     * Rockchip dispdbg 方案（厂家原命令）
     */
    private fun doRockchip(on: Boolean): Boolean {
        val param = if (on) "0" else "1"
        val cmd = "cd $DEBUG_DISPDB_PATH && " +
            "echo disp0 > name; " +
            "echo blank > command; " +
            "echo $param > param; " +
            "echo 1 > start"
        DebugLogStore.i(TAG, "[Rockchip] 执行: su -c \"$cmd\"")
        return runShell("su -c \"$cmd\"").let { (exit, out, err) ->
            if (exit == 0) {
                DebugLogStore.i(TAG, "[Rockchip] 成功 stdout='${out.trim()}'")
                true
            } else {
                DebugLogStore.w(TAG, "[Rockchip] 失败 exit=$exit stderr='${err.trim()}'")
                false
            }
        }
    }

    /**
     * Allwinner /sys/devices/virtual/graphics/fb0/blank 方案
     * 开屏: echo 0 > fb0/blank
     * 关屏: echo 4 > fb0/blank （4 = powerdown）
     */
    private fun doAllwinnerFb(on: Boolean): Boolean {
        val value = if (on) "0" else "4"
        // 找到实际存在的 fb blank 路径
        val path = FB_BLANK_PATHS.firstOrNull { File(it).exists() } ?: FB_BLANK_PATHS[0]
        val cmd = "echo $value > $path"
        DebugLogStore.i(TAG, "[Allwinner-fb] 执行: su -c \"$cmd\"")
        return runShell("su -c \"$cmd\"").let { (exit, out, err) ->
            if (exit == 0) {
                DebugLogStore.i(TAG, "[Allwinner-fb] 成功 stdout='${out.trim()}'")
                true
            } else {
                DebugLogStore.w(TAG, "[Allwinner-fb] 失败 exit=$exit stderr='${err.trim()}'")
                false
            }
        }
    }

    /**
     * 通用 backlight 方案
     * 开屏: echo 255 > brightness (或当前最大亮度)
     * 关屏: echo 0 > brightness
     */
    private fun doBacklight(on: Boolean): Boolean {
        val path = BACKLIGHT_PATHS.firstOrNull { File(it).exists() } ?: BACKLIGHT_PATHS[0]
        // 先读最大亮度
        val maxBrightnessRaw = runShell("su -c 'cat $path'").second
        val maxBrightness = maxBrightnessRaw.trim().toIntOrNull() ?: 255
        val value = if (on) maxBrightness.toString() else "0"
        val cmd = "echo $value > $path"
        DebugLogStore.i(TAG, "[Backlight] 最大亮度=$maxBrightness, 执行: su -c \"$cmd\"")
        return runShell("su -c \"$cmd\"").let { (exit, out, err) ->
            if (exit == 0) {
                DebugLogStore.i(TAG, "[Backlight] 成功 stdout='${out.trim()}'")
                true
            } else {
                DebugLogStore.w(TAG, "[Backlight] 失败 exit=$exit stderr='${err.trim()}'")
                false
            }
        }
    }

    /**
     * 探测设备是否有 root
     */
    private fun isRootAvailable(): Boolean {
        val suPaths = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/vendor/bin/su")
        val suExists = suPaths.any { File(it).exists() }
        DebugLogStore.d(TAG, "Root 探测: su 文件存在=$suExists")
        if (!suExists) return false

        val attempts = listOf(
            "su -c id" to arrayOf("su", "-c", "id"),
            "su 0 id" to arrayOf("su", "0", "id"),
            "su -c 'id'" to arrayOf("su", "-c", "id")
        )
        for ((desc, cmd) in attempts) {
            val (exit, out, _) = runShellWithArgs(cmd)
            if (exit == 0 && (out.contains("uid=0") || out.contains("root"))) {
                DebugLogStore.i(TAG, "Root 探测成功: 方式=$desc, output=${out.trim()}")
                return true
            }
        }
        // stdin 方式
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            p.outputStream.bufferedWriter().use { it.write("id\n") }
            p.outputStream.bufferedWriter().use { it.flush() }
            val exited = p.waitFor()
            val out = p.inputStream.bufferedReader().readText()
            p.errorStream.bufferedReader().readText()
            p.destroy()
            val ok = exited == 0 && (out.contains("uid=0") || out.contains("root"))
            if (ok) DebugLogStore.i(TAG, "Root 探测成功: 方式=stdin")
            ok
        } catch (e: Exception) {
            DebugLogStore.d(TAG, "Root 探测全部失败: ${e.message}")
            false
        }
    }

    /**
     * 用 sh -c 方式执行命令
     */
    private fun runShell(command: String): Triple<Int, String, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exit = p.waitFor()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.destroy()
            Triple(exit, out, err)
        } catch (e: Exception) {
            DebugLogStore.e(TAG, "runShell 异常: ${e.message}")
            Triple(-1, "", e.message ?: "")
        }
    }

    private fun runShellWithArgs(cmd: Array<String>): Triple<Int, String, String> {
        return try {
            val p = Runtime.getRuntime().exec(cmd)
            val exit = p.waitFor()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.destroy()
            Triple(exit, out, err)
        } catch (e: Exception) {
            Triple(-1, "", e.message ?: "")
        }
    }
}
