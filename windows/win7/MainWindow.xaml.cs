using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Scroff.Win7.Services;
using Scroff.Win7.ViewModels;

// 同时引入 WinForms 时消除 Color 歧义
using Color = System.Windows.Media.Color;

namespace Scroff.Win7
{
    public partial class MainWindow : Window
    {
        private static readonly Color TrackOn = Color.FromRgb(0x4F, 0x7C, 0xFF);
        private static readonly Color TrackOff = Color.FromRgb(0xC9, 0xD0, 0xDC);

        private MainViewModel _vm;

        /// <summary>
        /// 托盘"退出"会先把此标志置 true 后再 Shutdown，让关闭拦截放行真正退出
        /// </summary>
        public bool AllowClose { get; set; }

        /// <summary>首次启动 StateChanged 已处理</summary>
        private bool _initialStateProcessed;

        public MainWindow()
        {
            InitializeComponent();
            DataContextChanged += OnDataContextChanged;
            Loaded += OnLoaded;
            StateChanged += OnStateChanged;
            Closing += OnClosing;
        }

        private void OnDataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
        {
            if (e.OldValue is MainViewModel oldVm)
                oldVm.PropertyChanged -= OnViewModelPropertyChanged;
            if (e.NewValue is MainViewModel newVm)
            {
                newVm.PropertyChanged += OnViewModelPropertyChanged;
                _vm = newVm;
            }
            SyncAutoStartVisual();
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            SyncAutoStartVisual();
            _initialStateProcessed = true;
        }

        private void OnStateChanged(object sender, EventArgs e)
        {
            // 用户点击标题栏最小化按钮 → 隐藏到托盘
            if (!_initialStateProcessed) return;
            if (WindowState == WindowState.Minimized)
            {
                HideToTray();
            }
        }

        private void OnClosing(object sender, CancelEventArgs e)
        {
            if (AllowClose) return;
            e.Cancel = true;
            HideToTray();
        }

        /// <summary>把主窗隐藏到托盘（不退出进程）</summary>
        private void HideToTray()
        {
            WindowState = WindowState.Normal;
            Hide();
        }

        private void OnViewModelPropertyChanged(object sender, PropertyChangedEventArgs e)
        {
            if (e.PropertyName == "AutoStartEnabled")
                Dispatcher.Invoke(SyncAutoStartVisual);
        }

        private void SyncAutoStartVisual()
        {
            if (DataContext is MainViewModel vm)
                AnimateSwitch(AutoStartTrack, AutoStartThumb, vm.AutoStartEnabled, 46, 20);
        }

        private void OnAutoStartToggleClick(object sender, MouseButtonEventArgs e)
        {
            if (DataContext is MainViewModel vm)
            {
                vm.AutoStartEnabled = !vm.AutoStartEnabled;
                AnimateSwitch(AutoStartTrack, AutoStartThumb, vm.AutoStartEnabled, 46, 20);
            }
        }

        private void OnEnabledToggleClick(object sender, MouseButtonEventArgs e)
        {
            if (!(sender is Grid grid) || !(grid.Tag is ScheduleItem item)) return;
            // 视觉由 XAML DataTrigger 绑定 Enabled 自动驱动，这里只负责翻转状态
            if (DataContext is MainViewModel vm)
                vm.ToggleEnabledCommand.Execute(item);
        }

        /// <summary>
        /// 无条件把 track.Background 替换成可修改的新 SolidColorBrush，返回这个新 brush。
        /// 防御性：哪怕传入的 brush 已经被冻结，也保证调用方拿到的是 unfrozen 实例。
        /// </summary>
        private static SolidColorBrush EnsureMutableBrush(Border track, Color fallback)
        {
            var currentBrush = track.Background as SolidColorBrush;
            if (currentBrush != null && !currentBrush.IsFrozen)
            {
                return currentBrush;
            }
            var color = currentBrush != null ? currentBrush.Color : fallback;
            var fresh = new SolidColorBrush(color);
            track.Background = fresh;
            return fresh;
        }

        /// <summary>
        /// 开机自启动开关动画。XAML 字面颜色创建的 brush 是 frozen（共享只读），
        /// 不能直接动画，需先替换为可修改的新 SolidColorBrush 实例。
        /// </summary>
        private static void AnimateSwitch(Border track, Border thumb, bool isOn, int trackWidth, int thumbSize)
        {
            var brush = EnsureMutableBrush(track, TrackOff);

            // 取消正在进行的旧动画
            brush.BeginAnimation(SolidColorBrush.ColorProperty, null);
            thumb.BeginAnimation(MarginProperty, null);

            var duration = TimeSpan.FromMilliseconds(180);
            var fromColor = brush.Color;

            var colorAnim = new ColorAnimation
            {
                From = fromColor,
                To = isOn ? TrackOn : TrackOff,
                Duration = new Duration(duration)
            };
            brush.BeginAnimation(SolidColorBrush.ColorProperty, colorAnim);

            var leftMargin = isOn ? trackWidth - thumbSize - 2 : 2;
            var marginAnim = new ThicknessAnimation
            {
                To = new Thickness(leftMargin, 0, 0, 0),
                Duration = new Duration(duration),
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            };
            thumb.BeginAnimation(MarginProperty, marginAnim);
        }
    }
}
