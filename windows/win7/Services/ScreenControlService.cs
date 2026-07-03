using System;
using System.Runtime.InteropServices;

namespace Scroff.Win7.Services
{
    public class ScreenControlService
    {
        // 显示器电源控制
        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        private static readonly IntPtr HWND_BROADCAST = new IntPtr(0xFFFF);
        private const uint WM_SYSCOMMAND = 0x0112;
        private static readonly IntPtr SC_MONITORPOWER = new IntPtr(0xF170);
        private static readonly IntPtr MONITOR_OFF = new IntPtr(2);
        private static readonly IntPtr MONITOR_ON = new IntPtr(-1);

        // 枚举顶层窗口（用于 Win7 兜底发送 SC_MONITORPOWER）
        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        // 模拟鼠标输入
        [DllImport("user32.dll")]
        private static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);

        private const int MOUSEEVENTF_MOVE = 0x0001;

        // 模拟键盘输入
        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);

        private const byte VK_SHIFT = 0x10;
        private const byte VK_SCROLL = 0x91;
        private const uint KEYEVENTF_KEYUP = 0x0002;

        public void TurnScreenOff()
        {
            // Win7 上 HWND_BROADCAST + SC_MONITORPOWER 不可靠：
            // 很多顶层窗口不响应广播，必须枚举后逐个发送。
            // 1) PostMessage 异步广播，2) EnumWindows 同步兜底
            PostMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, MONITOR_OFF);
            BroadcastMonitorPower(MONITOR_OFF);
        }

        /// <summary>
        /// 打开显示器
        /// Win7 显示器被 SC_MONITORPOWER(2) 关闭后处于 DDC/CI 关闭态，
        /// 不再监听 OS 消息 —— 单纯发 SC_MONITORPOWER(-1) 唤醒不了硬件。
        /// 唯一可靠的唤醒手段是模拟"用户活动"输入（触发 DDC 中断），
        /// 然后再发 -1 让 GPU 从省电态恢复。
        /// </summary>
        public void TurnScreenOn()
        {
            // 1) 先模拟输入（关键）：较大动作的鼠标移动 + SCROLL LOCK 按键
            //    SCROLL LOCK 是远程唤醒工具的标准键（TeamViewer 等），无副作用
            mouse_event(MOUSEEVENTF_MOVE, 3, 3, 0, 0);
            System.Threading.Thread.Sleep(30);
            mouse_event(MOUSEEVENTF_MOVE, -3, -3, 0, 0);

            keybd_event(VK_SCROLL, 0, 0, 0);
            System.Threading.Thread.Sleep(30);
            keybd_event(VK_SCROLL, 0, KEYEVENTF_KEYUP, 0);

            // 2) 再发 MONITOR_ON 消息兜底：让 GPU 状态从省电恢复
            PostMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, MONITOR_ON);
            BroadcastMonitorPower(MONITOR_ON);
        }

        /// <summary>
        /// 枚举所有可见顶层窗口，对每个发送 SC_MONITORPOWER 消息。
        /// 解决 Win7 上 HWND_BROADCAST 广播不到顶层窗口的问题。
        /// </summary>
        private static void BroadcastMonitorPower(IntPtr monitorState)
        {
            EnumWindows((hWnd, lParam) =>
            {
                if (IsWindowVisible(hWnd))
                    SendMessage(hWnd, WM_SYSCOMMAND, SC_MONITORPOWER, monitorState);
                return true; // 继续枚举
            }, IntPtr.Zero);
        }
    }
}
