using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Windows;

namespace Scroff.Win7.Services
{
    /// <summary>
    /// 定时调度服务 - 管理屏幕定时开关任务
    /// </summary>
    public class SchedulerService
    {
        private readonly List<Timer> _timers = new List<Timer>();
        private readonly ScreenControlService _screenControl;

        public ObservableCollection<ScheduleItem> Schedules { get; } = new ObservableCollection<ScheduleItem>();

        public SchedulerService()
        {
            _screenControl = new ScreenControlService();
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
            if (schedule.Action == ScheduleAction.ScreenOff)
                _screenControl.TurnScreenOff();
            else
                _screenControl.TurnScreenOn();
        }

        private void StartTimer(ScheduleItem schedule)
        {
            StopTimer(schedule);
            var now = DateTime.Now;
            var target = DateTime.Today.Add(new TimeSpan(schedule.Hour, schedule.Minute, 0));
            if (target <= now) target = target.AddDays(1);
            var dueTime = target - now;
            var period = TimeSpan.FromDays(1);

            var timer = new Timer(_ =>
            {
                Application.Current.Dispatcher.Invoke(() =>
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
            set { if (_hour != value) { _hour = value; OnPropertyChanged(); OnPropertyChanged(nameof(Time)); } }
        }

        public int Minute
        {
            get { return _minute; }
            set { if (_minute != value) { _minute = value; OnPropertyChanged(); OnPropertyChanged(nameof(Time)); } }
        }

        public ScheduleAction Action
        {
            get { return _action; }
            set { if (_action != value) { _action = value; OnPropertyChanged(); } }
        }

        public bool Enabled
        {
            get { return _enabled; }
            set { if (_enabled != value) { _enabled = value; OnPropertyChanged(); } }
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
        ScreenOn
    }
}
