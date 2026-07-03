using System.Runtime.InteropServices;

namespace Scroff.Services;

/// <summary>
/// 屏幕控制服务 - 通过 Windows API 控制屏幕开关
/// </summary>
public class ScreenControlService
{
    // ===== 显示器电源控制 (WM_SYSCOMMAND) =====
    [DllImport("user32.dll")]
    private static extern int SendMessage(int hWnd, int hMsg, int wParam, int lParam);

    private const int HWND_BROADCAST = 0xFFFF;
    private const int WM_SYSCOMMAND = 0x0112;
    private const int SC_MONITORPOWER = 0xF170;
    private const int MONITOR_OFF = 2;
    private const int MONITOR_ON = -1;

    // ===== 模拟鼠标输入 (用于唤醒显示器) =====
    [DllImport("user32.dll")]
    private static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);

    private const int MOUSEEVENTF_MOVE = 0x0001;

    // ===== 模拟键盘输入 (备用唤醒) =====
    [DllImport("user32.dll")]
    private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);

    private const byte VK_SHIFT = 0x10;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    /// <summary>
    /// 关闭显示器
    /// </summary>
    public void TurnScreenOff()
    {
        SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, MONITOR_OFF);
    }

    /// <summary>
    /// 打开显示器
    /// </summary>
    /// <remarks>
    /// 单纯 SendMessage(SC_MONITORPOWER, MONITOR_ON) 在现代 LCD/LED 显示器上
    /// 只能更新系统内部状态，无法真正唤醒处于省电模式的硬件。
    /// 需要叠加模拟输入事件来触发显示器的 DDC/CI 唤醒信号。
    /// </remarks>
    public void TurnScreenOn()
    {
        // 1. 发送系统消息（更新 Windows 内部显示器状态）
        SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, MONITOR_ON);

        // 2. 模拟鼠标微移（1 像素，触发显示器唤醒）
        // 用相对移动 (0,0) 不会真正移动鼠标但能产生事件
        mouse_event(MOUSEEVENTF_MOVE, 1, 1, 0, 0);
        System.Threading.Thread.Sleep(20);
        mouse_event(MOUSEEVENTF_MOVE, -1, -1, 0, 0);

        // 3. 模拟 Shift 键按下与释放（进一步确保唤醒）
        keybd_event(VK_SHIFT, 0, 0, 0);
        System.Threading.Thread.Sleep(20);
        keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, 0);
    }
}
