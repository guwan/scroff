// ScreenControlModule.cs 参考实现
// 路径：windows/ScroffScreen/ScreenControlModule.cs

using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.ReactNative.Managed;
using Windows.System;

namespace ScroffScreen
{
    [ReactModule("ScreenControl")]
    public class ScreenControlModule
    {
        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, uint wParam, uint lParam);

        private const uint WM_SYSCOMMAND = 0x0112;
        private const uint SC_MONITORPOWER = 0xF170;
        private static readonly IntPtr HWND_BROADCAST = (IntPtr)0xffff;

        [ReactMethod("turnScreenOn")]
        public void TurnScreenOn(IReactPromise<JSValue> promise)
        {
            try
            {
                // 唤醒显示器
                SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, (uint)(-1));
                promise.Resolve(JSValue.Null);
            }
            catch (Exception ex)
            {
                promise.Reject(new ReactError { Code = "E_TURN_ON", Message = ex.Message });
            }
        }

        [ReactMethod("turnScreenOff")]
        public void TurnScreenOff(IReactPromise<JSValue> promise)
        {
            try
            {
                // 关闭显示器（需要当前进程具备桌面访问权限，UWP 需 runFullTrust）
                SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, 2);
                promise.Resolve(JSValue.Null);
            }
            catch (Exception ex)
            {
                promise.Reject(new ReactError { Code = "E_TURN_OFF", Message = ex.Message });
            }
        }

        [ReactMethod("isScreenOn")]
        public void IsScreenOn(IReactPromise<bool> promise)
        {
            // Windows 没有直接的 API；可借助 SetThreadExecutionState / 会话状态机判断
            promise.Resolve(true);
        }
    }
}
