using System;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Threading;
using Scroff.Win7.Services;
using Scroff.Win7.ViewModels;

// 同时引入 WinForms + WPF 时消除 Application 歧义
using Application = System.Windows.Application;
using MessageBox = System.Windows.MessageBox;

namespace Scroff.Win7
{
    public partial class App : Application
    {
        private static readonly string LogFile = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Scroff", "scroff-crash.log");

        private TrayService _tray;
        private MainWindow _mainWindow;
        private NetworkMonitorService _networkMonitor;

        static App()
        {
            // 在 XAML 解析之前就注册 AppDomain 级异常处理，
            // 避免启动期崩了却没记录。
            AppDomain.CurrentDomain.UnhandledException += (s, args) =>
            {
                var ex = args.ExceptionObject as Exception;
                LogCrash("AppDomain.UnhandledException", ex);
            };
        }

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            // WPF 调度器内未捕获异常（XAML 解析期异常也会被这个捕获）
            DispatcherUnhandledException += (s, args) =>
            {
                LogCrash("DispatcherUnhandledException", args.Exception);
                MessageBox.Show(
                    "发生未处理的异常：" + args.Exception.Message +
                    "\n\n详情已写入: " + LogFile,
                    "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                args.Handled = true;
            };

            // 创建主窗（手动 new 以便把引用交给托盘）
            _mainWindow = new MainWindow();
            _mainWindow.Show();

            // 初始化系统托盘
            _tray = new TrayService("Scroff · 屏幕定时开关", LoadAppIcon());
            _tray.ShowWindowRequested += OnTrayShowWindow;
            _tray.TurnOffRequested += () => RunMainCommand("TurnOffNow");
            _tray.TurnOnRequested += () => RunMainCommand("TurnOnNow");
            _tray.ExitRequested += OnTrayExit;

            // 启动网络监控（断网检测 + 自动重连 + 历史记录）
            StartNetworkMonitor();
        }

        /// <summary>启动网络监控服务：把事件桥接到 MainViewModel</summary>
        private void StartNetworkMonitor()
        {
            try
            {
                var vm = _mainWindow.DataContext as MainViewModel;
                if (vm == null) return;
                var settings = vm.Storage.LoadSettings();
                _networkMonitor = new NetworkMonitorService(vm.Storage, settings);
                _networkMonitor.NetworkEvent += (action, message) =>
                {
                    Dispatcher.Invoke(() => vm.OnNetworkEvent(action, message));
                };
                _networkMonitor.Start();
            }
            catch (Exception ex)
            {
                LogCrash("StartNetworkMonitor", ex);
                MessageBox.Show("网络监控启动失败：" + ex.Message, "提示",
                    MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void OnTrayShowWindow()
        {
            if (_mainWindow == null) return;
            if (!_mainWindow.IsVisible) _mainWindow.Show();
            if (_mainWindow.WindowState == WindowState.Minimized)
                _mainWindow.WindowState = WindowState.Normal;
            _mainWindow.Activate();
            _mainWindow.Topmost = true;
            _mainWindow.Topmost = false;
            _mainWindow.Focus();
        }

        private void OnTrayExit()
        {
            if (_mainWindow != null) _mainWindow.AllowClose = true;
            Shutdown();
        }

        private void RunMainCommand(string commandName)
        {
            if (_mainWindow == null) return;
            var vm = _mainWindow.DataContext as MainViewModel;
            if (vm == null) return;
            var prop = typeof(MainViewModel).GetProperty(commandName + "Command");
            if (prop != null) prop.GetValue(vm);
        }

        protected override void OnExit(ExitEventArgs e)
        {
            if (_tray != null)
            {
                _tray.Dispose();
                _tray = null;
            }
            if (_networkMonitor != null)
            {
                _networkMonitor.Dispose();
                _networkMonitor = null;
            }
            base.OnExit(e);
        }

        private static Icon LoadAppIcon()
        {
            try
            {
                var exePath = Assembly.GetEntryAssembly() != null
                    ? Assembly.GetEntryAssembly().Location
                    : null;
                if (!string.IsNullOrEmpty(exePath) && File.Exists(exePath))
                {
                    using (var icon = Icon.ExtractAssociatedIcon(exePath))
                    {
                        if (icon != null) return (Icon)icon.Clone();
                    }
                }
            }
            catch { }
            return SystemIcons.Application;
        }

        private static void LogCrash(string source, Exception ex)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogFile));
                var sb = new System.Text.StringBuilder();
                sb.AppendLine($"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {source}");
                int depth = 0;
                var current = ex;
                while (current != null)
                {
                    var prefix = depth == 0 ? "" : $"  Inner[{depth}]: ";
                    sb.AppendLine(prefix + "Type: " + current.GetType().FullName);
                    sb.AppendLine(prefix + "Message: " + current.Message);
                    if (!string.IsNullOrEmpty(current.StackTrace))
                        sb.AppendLine(prefix + "Stack: " + current.StackTrace);
                    current = current.InnerException;
                    depth++;
                }
                sb.AppendLine(new string('-', 60));
                File.AppendAllText(LogFile, sb.ToString(), new System.Text.UTF8Encoding(false));
            }
            catch { /* 日志失败不影响 */ }
        }
    }
}
