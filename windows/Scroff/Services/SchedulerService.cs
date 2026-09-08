using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Text;
using System.Threading;

namespace Scroff.Services;

/// <summary>
/// 屏幕控制执行结果（用于历史记录）
/// </summary>
public class ExecutionResult
{
    public ScheduleAction Action { get; set; }
    public bool Success { get; set; }
    public string? Message { get; set; }
}

/// <summary>
/// 定时调度服务 - 管理屏幕定时开关任务
/// </summary>
public class SchedulerService
{
    private readonly List<System.Threading.Timer> _timers = new();
    private readonly ScreenControlService _screenControl;

    public ObservableCollection<ScheduleItem> Schedules { get; } = new();

    /// <summary>
    /// 屏幕控制执行完毕事件，参数：(来源, 动作, 任务名或 null, 成功标记, 消息)
    /// 订阅者负责写入历史记录
    /// </summary>
    public event Action<HistorySource, ScheduleAction, string?, bool, string?>? Executed;

    public SchedulerService()
    {
        _screenControl = new ScreenControlService();
    }

    /// <summary>写日志到 %AppData%\Scroff\scroff-debug.log，方便排障</summary>
    private static void Log(string message)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Scroff");
            Directory.CreateDirectory(dir);
            var file = Path.Combine(dir, "scroff-debug.log");
            File.AppendAllText(file,
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {message}{Environment.NewLine}",
                new UTF8Encoding(false));
        }
        catch { /* 日志失败不影响主流程 */ }
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
        Execute(schedule.Action, HistorySource.Schedule, schedule.Name);
    }

    /// <summary>
    /// 执行屏幕控制（手动入口，托盘菜单 / 立即开/关屏按钮共用）
    /// </summary>
    public void Execute(ScheduleAction action, HistorySource source, string? scheduleName)
    {
        ExecutionResult result;
        try
        {
            result = action == ScheduleAction.ScreenOff
                ? _screenControl.TurnScreenOff()
                : _screenControl.TurnScreenOn();
        }
        catch (Exception ex)
        {
            // 任何异常都包装为失败结果，不让 UI 崩
            result = new ExecutionResult
            {
                Action = action,
                Success = false,
                Message = ex.GetType().Name + ": " + ex.Message
            };
        }

        Executed?.Invoke(source, action, scheduleName, result.Success, result.Message);
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

        Log($"StartTimer: 任务 '{schedule.Name}' (id={schedule.Id}) 计划 {target:yyyy-MM-dd HH:mm:ss} 触发，" +
            $"距 now 还有 {dueTime.TotalMinutes:F2} 分钟，周期 {period.TotalDays} 天，" +
            $"动作={schedule.Action} 已启用={schedule.Enabled}");

        var timer = new System.Threading.Timer(_ =>
        {
            System.Windows.Application.Current.Dispatcher.Invoke(() =>
            {
                Log($"Timer 触发: 任务 '{schedule.Name}' 动作={schedule.Action} now={DateTime.Now:yyyy-MM-dd HH:mm:ss}");
                Execute(schedule.Action, HistorySource.Schedule, schedule.Name);
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
        set { if (_time != value) { _time = value; OnPropertyChanged(); OnPropertyChanged(nameof(NextFireText)); } }
    }

    public ScheduleAction Action
    {
        get => _action;
        set { if (_action != value) { _action = value; OnPropertyChanged(); } }
    }

    public bool Enabled
    {
        get => _enabled;
        set { if (_enabled != value) { _enabled = value; OnPropertyChanged(); OnPropertyChanged(nameof(NextFireText)); } }
    }

    public string RepeatDays { get; set; } = ""; // 逗号分隔的星期几 (1-7)

    /// <summary>下次执行时间的简短描述（用于界面提示，如"明天 07:50"），仅供显示用</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public string NextFireText
    {
        get
        {
            if (!_enabled) return "已停用";
            var now = DateTime.Now;
            var target = DateTime.Today.Add(_time);
            if (target <= now) target = target.AddDays(1);
            if (target.Date == DateTime.Today.AddDays(1).Date)
                return "明天 " + target.ToString("HH:mm");
            return "今天 " + target.ToString("HH:mm");
        }
    }

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
    ScreenOn,
    // ===== 网络监控事件（由 NetworkMonitorService 触发，历史记录复用本枚举） =====
    NetworkDisconnected,
    NetworkReconnected,
    NetworkReconnectFailed
}
