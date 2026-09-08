using Scroff.Services;
using Scroff.ViewModels;
using Scroff.Views;
using System;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Threading;

// 同时引入 WinForms + WPF 时消除 Application/MessageBox 歧义
using Application = System.Windows.Application;
using MessageBox = System.Windows.MessageBox;

namespace Scroff;

public partial class App : Application
{
    private TrayService? _tray;
    private MainWindow? _mainWindow;
    private NetworkMonitorService? _networkMonitor;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 注册全局异常处理
        DispatcherUnhandledException += (s, args) =>
        {
            // 同时输出到调试控制台 + MessageBox，方便定位是哪个 brush 出问题
            var ex = args.Exception;
            var detail = $"类型: {ex.GetType().FullName}\n消息: {ex.Message}\n堆栈:\n{ex.StackTrace}";
            System.Diagnostics.Debug.WriteLine("[Scroff] " + detail);
            try
            {
                var logDir = System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "Scroff");
                System.IO.Directory.CreateDirectory(logDir);
                System.IO.File.AppendAllText(
                    System.IO.Path.Combine(logDir, "scroff-crash.log"),
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {detail}\n{new string('-', 60)}\n",
                    new System.Text.UTF8Encoding(false));
            }
            catch { }
            MessageBox.Show($"发生未处理的异常：{ex.Message}\n\n详情已写入 %AppData%\\Scroff\\scroff-crash.log",
                "错误", MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        // 创建主窗
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

    /// <summary>
    /// 启动网络监控服务：把事件桥接到 MainViewModel（负责把记录插入 UI History 集合）
    /// </summary>
    private void StartNetworkMonitor()
    {
        try
        {
            var vm = _mainWindow?.DataContext as MainViewModel;
            if (vm == null) return;
            var settings = vm.Storage.LoadSettings();
            _networkMonitor = new NetworkMonitorService(vm.Storage, settings);
            _networkMonitor.NetworkEvent += (action, message) =>
            {
                // 跨线程：NetworkMonitorService 来自后台线程，UI 集合必须 Dispatcher
                Dispatcher.Invoke(() => vm.OnNetworkEvent(action, message));
            };
            _networkMonitor.Start();
        }
        catch (Exception ex)
        {
            // 网络监控启动失败不影响屏幕控制主功能
            MessageBox.Show($"网络监控启动失败：{ex.Message}", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    /// <summary>
    /// 托盘"显示主窗口"：从托盘恢复主窗（不再开新窗）
    /// </summary>
    private void OnTrayShowWindow()
    {
        if (_mainWindow == null) return;
        if (!_mainWindow.IsVisible)
        {
            _mainWindow.Show();
        }
        if (_mainWindow.WindowState == WindowState.Minimized)
        {
            _mainWindow.WindowState = WindowState.Normal;
        }
        _mainWindow.Activate();
        _mainWindow.Topmost = true;
        _mainWindow.Topmost = false; // 闪烁一下抢焦点
        _mainWindow.Focus();
    }

    /// <summary>
    /// 托盘"退出"：标记允许关闭后真正 Shutdown
    /// </summary>
    private void OnTrayExit()
    {
        if (_mainWindow != null)
        {
            _mainWindow.AllowClose = true;
        }
        Shutdown();
    }

    /// <summary>
    /// 把托盘点击转给 MainViewModel 的命令（命名约定匹配 [RelayCommand] 生成的属性名）
    /// </summary>
    private void RunMainCommand(string commandName)
    {
        if (_mainWindow?.DataContext is ViewModels.MainViewModel vm)
        {
            var prop = typeof(ViewModels.MainViewModel).GetProperty(commandName + "Command");
            prop?.GetValue(vm);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _tray = null;
        _networkMonitor?.Dispose();
        _networkMonitor = null;
        base.OnExit(e);
    }

    /// <summary>
    /// 加载应用图标作为托盘图标，找不到时退到系统默认图标
    /// </summary>
    private static Icon LoadAppIcon()
    {
        try
        {
            // 优先从可执行文件自身读取（已嵌入 ApplicationIcon）
            var exePath = Assembly.GetEntryAssembly()?.Location;
            if (!string.IsNullOrEmpty(exePath) && File.Exists(exePath))
            {
                using var icon = Icon.ExtractAssociatedIcon(exePath);
                if (icon != null) return (Icon)icon.Clone();
            }
        }
        catch { }
        return SystemIcons.Application;
    }
}
