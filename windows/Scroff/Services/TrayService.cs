using System;
using System.Drawing;
using System.Windows.Forms;

namespace Scroff.Services;

/// <summary>
/// 系统托盘服务：基于 WinForms NotifyIcon。
/// 负责在关闭主窗时把程序隐藏到系统托盘，提供"显示主窗口 / 立即开/关屏 / 退出"菜单。
/// </summary>
public class TrayService : IDisposable
{
    private readonly NotifyIcon _notifyIcon;
    private bool _disposed;

    /// <summary>用户点击"显示主窗口"或双击托盘时触发</summary>
    public event Action? ShowWindowRequested;

    /// <summary>用户点击"立即关屏"时触发</summary>
    public event Action? TurnOffRequested;

    /// <summary>用户点击"立即开屏"时触发</summary>
    public event Action? TurnOnRequested;

    /// <summary>用户点击"退出"时触发（让 App 真正 Shutdown）</summary>
    public event Action? ExitRequested;

    public TrayService(string title, Icon icon)
    {
        _notifyIcon = new NotifyIcon
        {
            Icon = icon ?? SystemIcons.Application,
            Text = title,
            Visible = true
        };

        _notifyIcon.DoubleClick += (s, e) => ShowWindowRequested?.Invoke();

        // 右键菜单
        var menu = new ContextMenuStrip();

        var showItem = new ToolStripMenuItem("显示主窗口");
        showItem.Click += (s, e) => ShowWindowRequested?.Invoke();
        var baseFont = showItem.Font ?? SystemFonts.MenuFont;
        if (baseFont != null) showItem.Font = new Font(baseFont, FontStyle.Bold);
        menu.Items.Add(showItem);

        menu.Items.Add(new ToolStripSeparator());

        var offItem = new ToolStripMenuItem("立即关闭屏幕");
        offItem.Click += (s, e) => TurnOffRequested?.Invoke();
        menu.Items.Add(offItem);

        var onItem = new ToolStripMenuItem("立即打开屏幕");
        onItem.Click += (s, e) => TurnOnRequested?.Invoke();
        menu.Items.Add(onItem);

        menu.Items.Add(new ToolStripSeparator());

        var exitItem = new ToolStripMenuItem("退出");
        exitItem.Click += (s, e) => ExitRequested?.Invoke();
        menu.Items.Add(exitItem);

        _notifyIcon.ContextMenuStrip = menu;
    }

    /// <summary>
    /// 显示气泡通知（自动消失）
    /// </summary>
    public void ShowBalloon(string title, string text, ToolTipIcon icon = ToolTipIcon.Info)
    {
        if (_disposed) return;
        try
        {
            _notifyIcon.BalloonTipTitle = title;
            _notifyIcon.BalloonTipText = text;
            _notifyIcon.BalloonTipIcon = icon;
            _notifyIcon.ShowBalloonTip(3000);
        }
        catch { /* 气泡通知失败不影响主流程 */ }
    }

    /// <summary>
    /// 更新鼠标悬停时的提示文本
    /// </summary>
    public void SetText(string text)
    {
        if (_disposed) return;
        try { _notifyIcon.Text = text; } catch { }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            _notifyIcon.Visible = false;
            _notifyIcon.ContextMenuStrip?.Dispose();
            _notifyIcon.Dispose();
        }
        catch { /* 清理失败忽略 */ }
    }
}
