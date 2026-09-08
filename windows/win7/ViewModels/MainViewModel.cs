using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using Scroff.Win7.Services;

// 同时引入 WinForms + WPF 时消除 Application 歧义
using Application = System.Windows.Application;

namespace Scroff.Win7.ViewModels
{
    public class MainViewModel : INotifyPropertyChanged
    {
        /// <summary>历史记录最大条数（与 StorageService 保持一致）</summary>
        private const int MaxHistoryEntries = 500;

        private readonly SchedulerService _scheduler;
        private readonly StorageService _storage;

        /// <summary>暴露给 App 层，让 NetworkMonitorService 复用同一个 StorageService 实例</summary>
        public StorageService Storage { get { return _storage; } }

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

        private string _newScheduleName = "";
        private TimeSpan _newScheduleTime = new TimeSpan(7, 50, 0);
        private ScheduleAction _newScheduleAction = ScheduleAction.ScreenOff;
        private bool _isScreenOffAction = true;
        private bool _autoStartEnabled;
        private bool _isEditing;
        private ScheduleItem _editingSchedule;

        public string NewScheduleName
        {
            get { return _newScheduleName; }
            set { _newScheduleName = value; OnPropertyChanged(); }
        }

        public TimeSpan NewScheduleTime
        {
            get { return _newScheduleTime; }
            set { if (_newScheduleTime != value) { _newScheduleTime = value; OnPropertyChanged(); } }
        }

        public ScheduleAction NewScheduleAction
        {
            get { return _newScheduleAction; }
            set { _newScheduleAction = value; OnPropertyChanged(); }
        }

        public bool IsScreenOffAction
        {
            get { return _isScreenOffAction; }
            set
            {
                if (_isScreenOffAction != value)
                {
                    _isScreenOffAction = value;
                    NewScheduleAction = value ? ScheduleAction.ScreenOff : ScheduleAction.ScreenOn;
                    OnPropertyChanged();
                }
            }
        }

        public bool AutoStartEnabled
        {
            get { return _autoStartEnabled; }
            set
            {
                if (_autoStartEnabled != value)
                {
                    _autoStartEnabled = value;
                    if (value) AutoStartService.Enable();
                    else AutoStartService.Disable();
                    OnPropertyChanged();
                }
            }
        }

        public bool IsEditing
        {
            get { return _isEditing; }
            set { _isEditing = value; OnPropertyChanged(); }
        }

        public ScheduleItem EditingSchedule
        {
            get { return _editingSchedule; }
            set { _editingSchedule = value; OnPropertyChanged(); }
        }

        public ObservableCollection<ScheduleItem> Schedules { get { return _scheduler.Schedules; } }

        /// <summary>
        /// 屏幕控制历史记录（最新在前）
        /// </summary>
        public ObservableCollection<HistoryEntry> History { get; } = new ObservableCollection<HistoryEntry>();

        public ICommand AddScheduleCommand { get; }
        public ICommand EditScheduleCommand { get; }
        public ICommand CancelEditCommand { get; }
        public ICommand DeleteScheduleCommand { get; }
        public ICommand ExecuteScheduleCommand { get; }
        public ICommand ToggleEnabledCommand { get; }
        public ICommand TurnOffNowCommand { get; }
        public ICommand TurnOnNowCommand { get; }
        public ICommand ClearHistoryCommand { get; }

        public MainViewModel()
        {
            _storage = new StorageService();
            _scheduler = new SchedulerService();

            // 加载已保存的任务
            foreach (var item in _storage.Load()) _scheduler.AddSchedule(item);

            // 加载历史记录（最新在前）
            var historyLoaded = _storage.LoadHistory();
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
                AutoStartService.Enable();
            }

            _autoStartEnabled = AutoStartService.IsEnabled();

            AddScheduleCommand = new RelayCommand(_ => AddSchedule());
            EditScheduleCommand = new RelayCommand(p => EditSchedule(p as ScheduleItem));
            CancelEditCommand = new RelayCommand(_ => CancelEdit());
            DeleteScheduleCommand = new RelayCommand(p => DeleteSchedule(p as ScheduleItem));
            ExecuteScheduleCommand = new RelayCommand(p => ExecuteSchedule(p as ScheduleItem));
            ToggleEnabledCommand = new RelayCommand(p => ToggleEnabled(p as ScheduleItem));
            TurnOffNowCommand = new RelayCommand(_ => TurnOffNow());
            TurnOnNowCommand = new RelayCommand(_ => TurnOnNow());
            ClearHistoryCommand = new RelayCommand(_ => ClearHistory());
        }

        private void OnScheduleExecuted(HistorySource source, ScheduleAction action,
            string scheduleName, bool success, string message)
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

            var app = Application.Current;
            if (app != null)
            {
                app.Dispatcher.Invoke(new Action(() =>
                {
                    History.Insert(0, entry);
                    while (History.Count > MaxHistoryEntries)
                    {
                        History.RemoveAt(History.Count - 1);
                    }
                }));
            }
            else
            {
                History.Insert(0, entry);
            }

            _storage.AppendHistory(entry);
        }

        /// <summary>
        /// 网络事件回调（由 NetworkMonitorService 在 App 层 Dispatcher 内调用）：
        /// 往 UI History 集合前插一条（持久化由 NetworkMonitorService 内部完成）
        /// </summary>
        public void OnNetworkEvent(ScheduleAction action, string message)
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

        /// <summary>
        /// 首次运行注入默认任务：07:50 打开屏幕、17:30 关闭屏幕
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
            foreach (var s in defaults) _scheduler.AddSchedule(s);
            SaveSchedules();
        }

        private string GenerateDefaultName()
        {
            var actionText = NewScheduleAction == ScheduleAction.ScreenOff ? "关闭屏幕" : "打开屏幕";
            var count = Schedules.Count(s => s.Action == NewScheduleAction) + 1;
            return actionText + "_" + count;
        }

        private void AddSchedule()
        {
            if (IsEditing && EditingSchedule != null)
            {
                EditingSchedule.Name = string.IsNullOrWhiteSpace(NewScheduleName) ? EditingSchedule.Name : NewScheduleName.Trim();
                EditingSchedule.Time = NewScheduleTime;
                EditingSchedule.Action = NewScheduleAction;
                _scheduler.UpdateSchedule(EditingSchedule);
            }
            else
            {
                var name = string.IsNullOrWhiteSpace(NewScheduleName) ? GenerateDefaultName() : NewScheduleName.Trim();
                _scheduler.AddSchedule(new ScheduleItem
                {
                    Id = DateTime.Now.Ticks,
                    Name = name,
                    Time = NewScheduleTime,
                    Action = NewScheduleAction,
                    Enabled = true
                });
            }
            SaveSchedules();
            CancelEdit();
        }

        private void EditSchedule(ScheduleItem schedule)
        {
            if (schedule == null) return;
            IsEditing = true;
            EditingSchedule = schedule;
            NewScheduleName = schedule.Name;
            NewScheduleTime = schedule.Time;
            NewScheduleAction = schedule.Action;
            IsScreenOffAction = schedule.Action == ScheduleAction.ScreenOff;
        }

        private void CancelEdit()
        {
            IsEditing = false;
            EditingSchedule = null;
            NewScheduleName = "";
            NewScheduleTime = new TimeSpan(7, 50, 0);
            IsScreenOffAction = true;
        }

        private void DeleteSchedule(ScheduleItem schedule)
        {
            if (schedule == null) return;
            if (EditingSchedule == schedule) CancelEdit();
            _scheduler.RemoveSchedule(schedule);
            SaveSchedules();
        }

        private void ExecuteSchedule(ScheduleItem schedule)
        {
            if (schedule == null) return;
            _scheduler.ExecuteNow(schedule);
        }

        private void ToggleEnabled(ScheduleItem schedule)
        {
            if (schedule == null) return;
            var newValue = !schedule.Enabled;
            schedule.Enabled = newValue;
            if (newValue) _scheduler.EnableSchedule(schedule);
            else _scheduler.DisableSchedule(schedule);
            SaveSchedules();
        }

        /// <summary>手动立即关屏（托盘菜单用）</summary>
        public void TurnOffNow()
        {
            _scheduler.Execute(ScheduleAction.ScreenOff, HistorySource.Manual, null);
        }

        /// <summary>手动立即开屏（托盘菜单用）</summary>
        public void TurnOnNow()
        {
            _scheduler.Execute(ScheduleAction.ScreenOn, HistorySource.Manual, null);
        }

        /// <summary>清空历史记录</summary>
        public void ClearHistory()
        {
            History.Clear();
            _storage.ClearHistory();
        }

        private void SaveSchedules()
        {
            _storage.Save(Schedules);
        }

        public event PropertyChangedEventHandler PropertyChanged;

        protected void OnPropertyChanged([CallerMemberName] string propertyName = null)
        {
            var handler = PropertyChanged;
            if (handler != null) handler(this, new PropertyChangedEventArgs(propertyName));
        }
    }

    public class RelayCommand : ICommand
    {
        private readonly Action<object> _execute;
        private readonly Predicate<object> _canExecute;

        public RelayCommand(Action<object> execute, Predicate<object> canExecute = null)
        {
            _execute = execute;
            _canExecute = canExecute;
        }

        public bool CanExecute(object parameter) { return _canExecute == null || _canExecute(parameter); }
        public void Execute(object parameter) { _execute(parameter); }

        public event EventHandler CanExecuteChanged
        {
            add { CommandManager.RequerySuggested += value; }
            remove { CommandManager.RequerySuggested -= value; }
        }
    }
}
