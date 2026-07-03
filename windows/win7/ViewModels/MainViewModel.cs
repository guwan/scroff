using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using Scroff.Win7.Services;

namespace Scroff.Win7.ViewModels
{
    public class MainViewModel : INotifyPropertyChanged
    {
        private readonly SchedulerService _scheduler;
        private readonly StorageService _storage;

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

        public ICommand AddScheduleCommand { get; }
        public ICommand EditScheduleCommand { get; }
        public ICommand CancelEditCommand { get; }
        public ICommand DeleteScheduleCommand { get; }
        public ICommand ExecuteScheduleCommand { get; }
        public ICommand ToggleEnabledCommand { get; }

        public MainViewModel()
        {
            _storage = new StorageService();
            _scheduler = new SchedulerService();

            // 加载已保存的任务
            foreach (var item in _storage.Load()) _scheduler.AddSchedule(item);

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
