using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Scroff.Services;
using System.Collections.ObjectModel;
using System.Windows;

namespace Scroff.ViewModels;

public partial class MainViewModel : ObservableObject
{
    private readonly SchedulerService _scheduler;
    private readonly StorageService _storage;

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

    private void SaveSchedules()
    {
        _storage.Save(Schedules);
    }
}
