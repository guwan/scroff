using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Threading;

namespace Scroff.Services;

/// <summary>
/// 定时调度服务 - 管理屏幕定时开关任务
/// </summary>
public class SchedulerService
{
    private readonly List<System.Threading.Timer> _timers = new();
    private readonly ScreenControlService _screenControl;

    public ObservableCollection<ScheduleItem> Schedules { get; } = new();

    public SchedulerService()
    {
        _screenControl = new ScreenControlService();
    }

    /// <summary>
    /// 添加定时任务
    /// </summary>
    public void AddSchedule(ScheduleItem schedule)
    {
        Schedules.Add(schedule);
        if (schedule.Enabled)
        {
            StartTimer(schedule);
        }
    }

    /// <summary>
    /// 删除定时任务
    /// </summary>
    public void RemoveSchedule(ScheduleItem schedule)
    {
        StopTimer(schedule);
        Schedules.Remove(schedule);
    }

    /// <summary>
    /// 更新定时任务（时间或动作变更后重新调度）
    /// </summary>
    public void UpdateSchedule(ScheduleItem schedule)
    {
        if (schedule.Enabled)
            StartTimer(schedule);
        else
            StopTimer(schedule);
    }

    /// <summary>
    /// 切换定时任务启用状态
    /// </summary>
    public void ToggleSchedule(ScheduleItem schedule, bool enabled)
    {
        schedule.Enabled = enabled;
        if (enabled)
            StartTimer(schedule);
        else
            StopTimer(schedule);
    }

    /// <summary>
    /// 立即执行一次定时任务（手动触发测试用）
    /// </summary>
    public void ExecuteNow(ScheduleItem schedule)
    {
        if (schedule.Action == ScheduleAction.ScreenOff)
            _screenControl.TurnScreenOff();
        else
            _screenControl.TurnScreenOn();
    }

    private void StartTimer(ScheduleItem schedule)
    {
        StopTimer(schedule);

        var now = DateTime.Now;
        var target = DateTime.Today.Add(schedule.Time);
        if (target <= now)
            target = target.AddDays(1);

        var dueTime = target - now;
        var period = TimeSpan.FromDays(1); // 每天重复

        var timer = new System.Threading.Timer(_ =>
        {
            System.Windows.Application.Current.Dispatcher.Invoke(() =>
            {
                if (schedule.Action == ScheduleAction.ScreenOff)
                    _screenControl.TurnScreenOff();
                else
                    _screenControl.TurnScreenOn();
            });
        }, null, dueTime, period);

        schedule.Timer = timer;
        _timers.Add(timer);
    }

    private void StopTimer(ScheduleItem schedule)
    {
        if (schedule.Timer != null)
        {
            schedule.Timer.Dispose();
            _timers.Remove(schedule.Timer);
            schedule.Timer = null;
        }
    }
}

/// <summary>
/// 定时任务项（支持属性变化通知）
/// </summary>
public class ScheduleItem : INotifyPropertyChanged
{
    private string _name = "";
    private TimeSpan _time;
    private ScheduleAction _action;
    private bool _enabled = true;

    public long Id { get; set; }

    public string Name
    {
        get => _name;
        set { if (_name != value) { _name = value; OnPropertyChanged(); } }
    }

    public TimeSpan Time
    {
        get => _time;
        set { if (_time != value) { _time = value; OnPropertyChanged(); } }
    }

    public ScheduleAction Action
    {
        get => _action;
        set { if (_action != value) { _action = value; OnPropertyChanged(); } }
    }

    public bool Enabled
    {
        get => _enabled;
        set { if (_enabled != value) { _enabled = value; OnPropertyChanged(); } }
    }

    public string RepeatDays { get; set; } = ""; // 逗号分隔的星期几 (1-7)

    internal System.Threading.Timer? Timer { get; set; }

    public event PropertyChangedEventHandler? PropertyChanged;

    protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

public enum ScheduleAction
{
    ScreenOff,
    ScreenOn
}
