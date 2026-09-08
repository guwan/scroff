using System;
using System.Drawing;
using System.Windows.Forms;

namespace Scroff.Win7.Services
{
    /// <summary>
    /// 系统托盘服务：基于 WinForms NotifyIcon。
    /// 负责在关闭主窗时把程序隐藏到系统托盘，提供"显示主窗口 / 立即开/关屏 / 退出"菜单。
    /// </summary>
    public class TrayService : IDisposable
    {
        private readonly NotifyIcon _notifyIcon;
        private bool _disposed;

        public event Action ShowWindowRequested;
        public event Action TurnOffRequested;
        public event Action TurnOnRequested;
        public event Action ExitRequested;

        public TrayService(string title, Icon icon)
        {
            _notifyIcon = new NotifyIcon
            {
                Icon = icon != null ? icon : SystemIcons.Application,
                Text = title,
                Visible = true
            };

            _notifyIcon.DoubleClick += (s, e) => { var h = ShowWindowRequested; if (h != null) h(); };

            var menu = new ContextMenuStrip();

            var showItem = new ToolStripMenuItem("显示主窗口");
            showItem.Click += (s, e) => { var h = ShowWindowRequested; if (h != null) h(); };
            var baseFont = showItem.Font ?? SystemFonts.MenuFont;
            if (baseFont != null) showItem.Font = new Font(baseFont, FontStyle.Bold);
            menu.Items.Add(showItem);

            menu.Items.Add(new ToolStripSeparator());

            var offItem = new ToolStripMenuItem("立即关闭屏幕");
            offItem.Click += (s, e) => { var h = TurnOffRequested; if (h != null) h(); };
            menu.Items.Add(offItem);

            var onItem = new ToolStripMenuItem("立即打开屏幕");
            onItem.Click += (s, e) => { var h = TurnOnRequested; if (h != null) h(); };
            menu.Items.Add(onItem);

            menu.Items.Add(new ToolStripSeparator());

            var exitItem = new ToolStripMenuItem("退出");
            exitItem.Click += (s, e) => { var h = ExitRequested; if (h != null) h(); };
            menu.Items.Add(exitItem);

            _notifyIcon.ContextMenuStrip = menu;
        }

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
            catch { }
        }

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
                if (_notifyIcon.ContextMenuStrip != null) _notifyIcon.ContextMenuStrip.Dispose();
                _notifyIcon.Dispose();
            }
            catch { }
        }
    }
}
