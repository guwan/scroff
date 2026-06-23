// ScreenControlModule.java 参考实现（Kotlin 风格）
// 路径：android/app/src/main/java/com/scroff/screen/ScreenControlModule.kt

package com.scroff.screen

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = ScreenControlModule.NAME)
class ScreenControlModule(reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = NAME

    @ReactMethod
    fun turnScreenOn(promise: Promise) {
        try {
            val ctx = reactApplicationContext
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            // 部分设备需要 DEVICE_POWER 权限或 root
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ScroffScreen::TurnOn"
            )
            wl.acquire(3000L)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("E_TURN_ON", e)
        }
    }

    @ReactMethod
    fun turnScreenOff(promise: Promise) {
        try {
            val ctx = reactApplicationContext
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            // 需要设备管理员权限；如未激活，会抛 SecurityException
            dpm.lockNow()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("E_TURN_OFF", e)
        }
    }

    @ReactMethod
    fun isScreenOn(promise: Promise) {
        try {
            val ctx = reactApplicationContext
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            promise.resolve(pm.isInteractive)
        } catch (e: Exception) {
            promise.reject("E_QUERY", e)
        }
    }

    companion object {
        const val NAME = "ScreenControl"
    }
}
