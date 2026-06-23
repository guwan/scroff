/**
 * Android 原生模块占位文件
 *
 * === 实现指引（Java / Kotlin）===
 *
 * 1) 在 android/app/src/main/java/com/scroff/screen/ 下新建
 *    ScreenControlModule.java（或 ScreenControlModule.kt）
 *
 * 2) 类签名（Kotlin 示例）：
 *
 *    class ScreenControlModule(reactContext: ReactApplicationContext)
 *      : ReactContextBaseJavaModule(reactContext) {
 *
 *      override fun getName() = "ScreenControl"
 *
 *      // 需要设备管理员权限（DEVICE_POWER）或 root
 *      // 推荐：反射调用 IWindowManager.setScreenState 或 PowerManager.goToSleep/wakeUp
 *      @ReactMethod
 *      fun turnScreenOn() { ... }
 *
 *      @ReactMethod
 *      fun turnScreenOff() { ... }
 *
 *      @ReactMethod
 *      fun isScreenOn(promise: Promise) { ... }
 *    }
 *
 * 3) 注册 Package：
 *    class ScreenControlPackage : ReactPackage {
 *      override fun createNativeModules(reactContext: ReactApplicationContext) =
 *        listOf(ScreenControlModule(reactContext))
 *    }
 *    并在 MainApplication.kt 的 getPackages() 中加入 ScreenControlPackage()
 *
 * 4) AndroidManifest.xml 需追加：
 *    <uses-permission android:name="android.permission.WAKE_LOCK" />
 *    <uses-permission android:name="android.permission.DEVICE_POWER" />
 *    <!-- 如需锁屏控制 -->
 *    <uses-permission android:name="android.permission.SHUTDOWN" />
 *
 * 5) 若使用设备管理员 API，需要：
 *    - res/xml/device_admin_policy.xml
 *    - 在 Manifest 中注册 DeviceAdminReceiver
 *    - 运行时申请：DevicePolicyManager.bindDeviceAdmin()
 *
 * 6) 定时任务后台保活推荐方案：
 *    - WorkManager + ForegroundService（API 26+）
 *    - 或 HeadlessJS（react-native-headless-js）
 *
 * 占位文件，等待原生实现完成后可删除。
 */
export const ANDROID_NATIVE_PLACEHOLDER = true;
