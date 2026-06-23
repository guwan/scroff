/**
 * Windows 原生模块占位文件
 *
 * === 实现指引（C# + react-native-windows）===
 *
 * 1) 在 windows/ScroffScreen/ 下新建 ScreenControlModule.cs
 *
 * 2) 类签名示例：
 *
 *    public class ScreenControlModule : ReactContextNativeJavaScriptModule
 *    {
 *        [ReactMethod("turnScreenOn")]
 *        public void TurnScreenOn(IReactPromise<...> promise) { ... }
 *
 *        [ReactMethod("turnScreenOff")]
 *        public void TurnScreenOff(IReactPromise<...> promise) { ... }
 *
 *        [ReactMethod("isScreenOn")]
 *        public void IsScreenOn(IReactPromise<bool> promise) { ... }
 *    }
 *
 * 3) 关闭显示器（管理员权限）：
 *
 *    [DllImport("user32.dll")]
 *    static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, uint wParam, uint lParam);
 *
 *    const uint WM_SYSCOMMAND = 0x0112;
 *    const uint SC_MONITORPOWER = 0xF170;
 *    const IntPtr HWND_BROADCAST = (IntPtr)0xffff;
 *
 *    SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, 2); // 关
 *    SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, -1); // 开
 *
 *    备选（需管理员 + nircmd）：
 *      Process.Start("nircmd.exe", "monitor off");
 *
 * 4) 后台定时保活：使用 BackgroundTaskBuilder 注册 TimeTrigger 后台任务，
 *    或在 AppService 中常驻计时器。
 *
 * 5) Package.appxmanifest 中需声明 runFullTrust 能力：
 *    <rescap:Capability Name="runFullTrust" />
 *
 * 占位文件，等待原生实现完成后可删除。
 */
export const WINDOWS_NATIVE_PLACEHOLDER = true;
