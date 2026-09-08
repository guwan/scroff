using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Scroff.Services;
using System.Collections.ObjectModel;
using System.Reflection;
using System.Windows;

// 同时引入 WinForms + WPF 时消除 Application 歧义
using Application = System.Windows.Application;

namespace Scroff.ViewModels;

public partial class MainViewModel : ObservableObject
{
    /// <summary>历史记录最大条数（与 StorageService 保持一致）</summary>
    private const int MaxHistoryEntries = 500;

    private readonly SchedulerService _scheduler;
    private readonly StorageService _storage;

    /// <summary>暴露给 App 层，让 NetworkMonitorService 复用同一个 StorageService 实例</summary>
    public StorageService Storage => _storage;

    /// <summary>软件版本号，从 InformationalVersionAttribute 读取（配置在 csproj 的 &lt;InformationalVersion&gt;）</summary>
    public string AppVersion
    {
        get
        {
            try
            {
                var v = Assembly.GetExecutingAssembly()
                    .GetCustomAttribute<AssemblyInformationalVersionAttribute>();
                return v != null && !string.IsNullOrEmpty(v.InformationalVersion)
                    ? v.InformationalVersion
                    : "未知版本";
            }
            catch
            {
                return "未知版本";
            }
        }
    }

    [ObservableProperty]
    private string _newScheduleName = "";

    [ObservableProperty]
    private TimeSpan _newScheduleTime = DateTime.Now.TimeOfDay;

    [ObservableProperty]
    private ScheduleAction _newScheduleAction = ScheduleAction.ScreenOff;

    /// <summary>
    /// RadioButton 绑定用：是否为"关闭屏幕"动作
    /// </summary>
    [ObservableProperty]
    private bool _isScreenOffAction = true;

    /// <summary>
    /// 是否开机自启
    /// </summary>
    [ObservableProperty]
    private bool _autoStartEnabled;

    /// <summary>
    /// 是否处于编辑模式
    /// </summary>
    [ObservableProperty]
    private bool _isEditing;

    /// <summary>
    /// 正在编辑的任务
    /// </summary>
    [ObservableProperty]
    private ScheduleItem? _editingSchedule;

    public ObservableCollection<ScheduleItem> Schedules => _scheduler.Schedules;

    /// <summary>
    /// 屏幕控制历史记录（最新在前）
    /// </summary>
    public ObservableCollection<HistoryEntry> History { get; } = new();

    public MainViewModel()
    {
        _storage = new StorageService();
        _scheduler = new SchedulerService();

        // 加载已保存的定时任务
        var loaded = _storage.Load();
        foreach (var item in loaded)
        {
            _scheduler.AddSchedule(item);
        }

        // 加载历史记录（最新在前）
        var historyLoaded = _storage.LoadHistory();
        // 反向遍历使最新的排在最前
        for (int i = historyLoaded.Count - 1; i >= 0; i--)
        {
            History.Add(historyLoaded[i]);
        }

        // 订阅屏幕控制执行完毕事件，写入历史
        _scheduler.Executed += OnScheduleExecuted;

        // 首次运行：注入默认任务并启用开机自启
        if (_storage.IsFirstRun)
        {
            EnsureDefaultSchedules();
            _storage.MarkInitialized();
            // 直接调用注册表 API，避免通过字段赋值绕过 partial 方法
            AutoStartService.Enable();
        }

        // 读取开机自启状态（同步到 UI）
        _autoStartEnabled = AutoStartService.IsEnabled();
    }

    /// <summary>
    /// 首次运行时注入默认任务：07:50 打开屏幕、17:30 关闭屏幕
    /// </summary>
    private void EnsureDefaultSchedules()
    {
        var defaults = new[]
        {
            new ScheduleItem
            {
                Id = DateTime.Now.Ticks,
                Name = "打开屏幕_1",
                Time = new TimeSpan(7, 50, 0),
                Action = ScheduleAction.ScreenOn,
                Enabled = true
            },
            new ScheduleItem
            {
                Id = DateTime.Now.Ticks + 1,
                Name = "关闭屏幕_1",
                Time = new TimeSpan(17, 30, 0),
                Action = ScheduleAction.ScreenOff,
                Enabled = true
            }
        };

        foreach (var s in defaults)
        {
            _scheduler.AddSchedule(s);
        }
        SaveSchedules();
    }

    private void OnScheduleExecuted(HistorySource source, ScheduleAction action,
        string? scheduleName, bool success, string? message)
    {
        var entry = new HistoryEntry
        {
            Id = DateTime.Now.Ticks,
            Timestamp = DateTime.Now,
            Action = action,
            Source = source,
            ScheduleName = scheduleName,
            Success = success,
            Message = message
        };

        // UI 线程安全插入（Executed 事件总是从 UI Dispatcher 触发，但保险起见 Invoke）
        Application.Current?.Dispatcher.Invoke(() =>
        {
            History.Insert(0, entry);
            // 超出上限时移除尾部最旧条目
            while (History.Count > MaxHistoryEntries)
            {
                History.RemoveAt(History.Count - 1);
            }
        });

        // 持久化追加（AppendHistory 内部会再次按上限截断文件）
        _storage.AppendHistory(entry);
    }

    /// <summary>
    /// 网络事件回调（由 NetworkMonitorService 在 App 层 Dispatcher 内调用）：
    /// 往 UI History 集合前插一条 + 持久化（持久化由 NetworkMonitorService 内部完成）
    /// </summary>
    public void OnNetworkEvent(ScheduleAction action, string? message)
    {
        var entry = new HistoryEntry
        {
            Id = DateTime.Now.Ticks,
            Timestamp = DateTime.Now,
            Action = action,
            Source = HistorySource.Network,
            ScheduleName = null,
            Success = action != ScheduleAction.NetworkReconnectFailed,
            Message = message
        };
        History.Insert(0, entry);
        while (History.Count > MaxHistoryEntries)
        {
            History.RemoveAt(History.Count - 1);
        }
    }

    partial void OnIsScreenOffActionChanged(bool value)
    {
        NewScheduleAction = value ? ScheduleAction.ScreenOff : ScheduleAction.ScreenOn;
    }

    partial void OnAutoStartEnabledChanged(bool value)
    {
        if (value)
            AutoStartService.Enable();
        else
            AutoStartService.Disable();
    }

    /// <summary>
    /// 生成默认任务名称，如"关闭屏幕_1"、"打开屏幕_2"
    /// </summary>
    private string GenerateDefaultName()
    {
        var actionText = NewScheduleAction == ScheduleAction.ScreenOff ? "关闭屏幕" : "打开屏幕";
        var count = Schedules.Count(s => s.Action == NewScheduleAction) + 1;
        return $"{actionText}_{count}";
    }

    [RelayCommand]
    private void AddSchedule()
    {
        if (IsEditing && EditingSchedule != null)
        {
            // 更新现有任务
            EditingSchedule.Name = string.IsNullOrWhiteSpace(NewScheduleName)
                ? EditingSchedule.Name
                : NewScheduleName.Trim();
            EditingSchedule.Time = NewScheduleTime;
            EditingSchedule.Action = NewScheduleAction;
            _scheduler.UpdateSchedule(EditingSchedule);
        }
        else
        {
            var name = string.IsNullOrWhiteSpace(NewScheduleName)
                ? GenerateDefaultName()
                : NewScheduleName.Trim();

            var schedule = new ScheduleItem
            {
                Id = DateTime.Now.Ticks,
                Name = name,
                Time = NewScheduleTime,
                Action = NewScheduleAction,
                Enabled = true
            };

            _scheduler.AddSchedule(schedule);
        }

        SaveSchedules();
        CancelEdit();
    }

    [RelayCommand]
    private void EditSchedule(ScheduleItem schedule)
    {
        IsEditing = true;
        EditingSchedule = schedule;
        NewScheduleName = schedule.Name;
        NewScheduleTime = schedule.Time;
        NewScheduleAction = schedule.Action;
        IsScreenOffAction = schedule.Action == ScheduleAction.ScreenOff;
    }

    [RelayCommand]
    private void CancelEdit()
    {
        IsEditing = false;
        EditingSchedule = null;
        NewScheduleName = "";
    }

    [RelayCommand]
    private void DeleteSchedule(ScheduleItem schedule)
    {
        if (EditingSchedule == schedule)
        {
            CancelEdit();
        }
        _scheduler.RemoveSchedule(schedule);
        SaveSchedules();
    }

    [RelayCommand]
    private void ExecuteSchedule(ScheduleItem schedule)
    {
        _scheduler.ExecuteNow(schedule);
    }

    [RelayCommand]
    private void ToggleEnabled(ScheduleItem? schedule)
    {
        if (schedule == null) return;
        var newValue = !schedule.Enabled;
        _scheduler.ToggleSchedule(schedule, newValue);
        SaveSchedules();
    }

    /// <summary>
    /// 手动立即关屏（托盘菜单用）
    /// </summary>
    [RelayCommand]
    public void TurnOffNow()
    {
        _scheduler.Execute(ScheduleAction.ScreenOff, HistorySource.Manual, null);
    }

    /// <summary>
    /// 手动立即开屏（托盘菜单用）
    /// </summary>
    [RelayCommand]
    public void TurnOnNow()
    {
        _scheduler.Execute(ScheduleAction.ScreenOn, HistorySource.Manual, null);
    }

    /// <summary>
    /// 清空历史记录
    /// </summary>
    [RelayCommand]
    public void ClearHistory()
    {
        History.Clear();
        _storage.ClearHistory();
    }

    private void SaveSchedules()
    {
        _storage.Save(Schedules);
    }
}
