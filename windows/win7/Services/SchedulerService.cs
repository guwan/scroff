using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Text;
using System.Threading;
using System.Windows;

namespace Scroff.Win7.Services
{
    /// <summary>
    /// 屏幕控制执行结果（用于历史记录）
    /// </summary>
    public class ExecutionResult
    {
        public ScheduleAction Action { get; set; }
        public bool Success { get; set; }
        public string Message { get; set; }
    }

    /// <summary>
    /// 定时调度服务 - 管理屏幕定时开关任务
    /// </summary>
    public class SchedulerService
    {
        private readonly List<Timer> _timers = new List<Timer>();
        private readonly ScreenControlService _screenControl;

        public ObservableCollection<ScheduleItem> Schedules { get; } = new ObservableCollection<ScheduleItem>();

        /// <summary>
        /// 屏幕控制执行完毕事件
        /// </summary>
        public event Action<HistorySource, ScheduleAction, string, bool, string> Executed;

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

        public void AddSchedule(ScheduleItem schedule)
        {
            Schedules.Add(schedule);
            if (schedule.Enabled) StartTimer(schedule);
        }

        public void RemoveSchedule(ScheduleItem schedule)
        {
            StopTimer(schedule);
            Schedules.Remove(schedule);
        }

        public void UpdateSchedule(ScheduleItem schedule)
        {
            StopTimer(schedule);
            if (schedule.Enabled) StartTimer(schedule);
        }

        public void ToggleSchedule(ScheduleItem schedule, bool enabled)
        {
            schedule.Enabled = enabled;
            if (enabled) StartTimer(schedule);
            else StopTimer(schedule);
        }

        public void ExecuteNow(ScheduleItem schedule)
        {
            Execute(schedule.Action, HistorySource.Schedule, schedule.Name);
        }

        /// <summary>
        /// 执行屏幕控制（手动入口，托盘菜单 / 立即开/关屏按钮共用）
        /// </summary>
        public void Execute(ScheduleAction action, HistorySource source, string scheduleName)
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
                result = new ExecutionResult
                {
                    Action = action,
                    Success = false,
                    Message = ex.GetType().Name + ": " + ex.Message
                };
            }

            if (Executed != null)
                Executed(source, action, scheduleName, result.Success, result.Message);
        }

        private void StartTimer(ScheduleItem schedule)
        {
            StopTimer(schedule);
            var now = DateTime.Now;
            var target = DateTime.Today.Add(new TimeSpan(schedule.Hour, schedule.Minute, 0));
            if (target <= now) target = target.AddDays(1);
            var dueTime = target - now;
            var period = TimeSpan.FromDays(1);

            Log($"StartTimer: 任务 '{schedule.Name}' (id={schedule.Id}) 计划 {target:yyyy-MM-dd HH:mm:ss} 触发，" +
                $"距 now 还有 {dueTime.TotalMinutes:F2} 分钟，周期 {period.TotalDays} 天，" +
                $"动作={schedule.Action} 已启用={schedule.Enabled}");

            var timer = new Timer(_ =>
            {
                Application.Current.Dispatcher.Invoke(() =>
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

        // 公开的开关接口
        public void EnableSchedule(ScheduleItem schedule)
        {
            if (schedule == null || !schedule.Enabled) return;
            StartTimer(schedule);
        }

        public void DisableSchedule(ScheduleItem schedule)
        {
            if (schedule == null) return;
            StopTimer(schedule);
        }
    }

    public class ScheduleItem : INotifyPropertyChanged
    {
        private string _name = "";
        private int _hour;
        private int _minute;
        private ScheduleAction _action;
        private bool _enabled = true;

        public long Id { get; set; }

        public string Name
        {
            get { return _name; }
            set { if (_name != value) { _name = value; OnPropertyChanged(); } }
        }

        public int Hour
        {
            get { return _hour; }
            set { if (_hour != value) { _hour = value; OnPropertyChanged(); OnPropertyChanged(nameof(Time)); OnPropertyChanged(nameof(NextFireText)); } }
        }

        public int Minute
        {
            get { return _minute; }
            set { if (_minute != value) { _minute = value; OnPropertyChanged(); OnPropertyChanged(nameof(Time)); OnPropertyChanged(nameof(NextFireText)); } }
        }

        public ScheduleAction Action
        {
            get { return _action; }
            set { if (_action != value) { _action = value; OnPropertyChanged(); } }
        }

        public bool Enabled
        {
            get { return _enabled; }
            set { if (_enabled != value) { _enabled = value; OnPropertyChanged(); OnPropertyChanged(nameof(NextFireText)); } }
        }

        /// <summary>下次执行时间的简短描述（用于界面提示，如"明天 07:50"），仅供显示用</summary>
        [Newtonsoft.Json.JsonIgnore]
        public string NextFireText
        {
            get
            {
                if (!_enabled) return "已停用";
                var now = DateTime.Now;
                var target = DateTime.Today.Add(new TimeSpan(_hour, _minute, 0));
                if (target <= now) target = target.AddDays(1);
                if (target.Date == DateTime.Today.AddDays(1).Date)
                    return "明天 " + target.ToString("HH:mm");
                return "今天 " + target.ToString("HH:mm");
            }
        }

        // 兼容性：保留 TimeSpan 属性供旧代码使用，但已不再序列化
        [Newtonsoft.Json.JsonIgnore]
        public TimeSpan Time
        {
            get { return new TimeSpan(_hour, _minute, 0); }
            set { Hour = value.Hours; Minute = value.Minutes; }
        }

        internal Timer Timer { get; set; }

        public event PropertyChangedEventHandler PropertyChanged;

        protected void OnPropertyChanged([CallerMemberName] string propertyName = null)
        {
            var handler = PropertyChanged;
            if (handler != null) handler(this, new PropertyChangedEventArgs(propertyName));
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
}
