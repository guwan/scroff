using System;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Scroff.Services;
using Scroff.ViewModels;

// 同时引入 WinForms 时消除 Color 歧义
using Color = System.Windows.Media.Color;

namespace Scroff.Views;

public partial class MainWindow : Window
{
    private static readonly Color TrackOn = Color.FromRgb(0x4F, 0x7C, 0xFF);
    private static readonly Color TrackOff = Color.FromRgb(0xC9, 0xD0, 0xDC);

    /// <summary>
    /// 托盘"退出"会先把此标志置 true 后再 Shutdown，让关闭拦截放行真正退出
    /// </summary>
    public bool AllowClose { get; set; }

    /// <summary>
    /// 首次启动时是否已经处理过 StateChanged（避免 Loaded → 第一次状态变化误触发）
    /// </summary>
    private bool _initialStateProcessed;

    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
        Loaded += OnLoaded;
        StateChanged += OnStateChanged;
        Closing += OnClosing;
        SchedulesList.Loaded += OnSchedulesListLoaded;
    }

    private void OnLoaded(object? sender, RoutedEventArgs e)
    {
        try { SyncAutoStartVisual(); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine("[Scroff] SyncAutoStartVisual: " + ex); }
        try { RefreshAllEnabledSwitches(); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine("[Scroff] RefreshAllEnabledSwitches: " + ex); }
        _initialStateProcessed = true;
    }

    private void OnStateChanged(object? sender, EventArgs e)
    {
        // 用户点击标题栏最小化按钮 → 隐藏到托盘
        if (!_initialStateProcessed) return;
        if (WindowState == WindowState.Minimized)
        {
            HideToTray();
        }
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        // 托盘"退出"或程序真正关闭时才会放行
        if (AllowClose) return;
        e.Cancel = true;
        HideToTray();
    }

    /// <summary>
    /// 把主窗隐藏到托盘（不退出进程）
    /// </summary>
    private void HideToTray()
    {
        // 把窗体还原，避免下次显示时还卡在 Minimize 状态
        WindowState = WindowState.Normal;
        Hide();
    }

    private void OnDataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.OldValue is MainViewModel oldVm)
        {
            oldVm.PropertyChanged -= OnViewModelPropertyChanged;
            if (oldVm.Schedules is INotifyCollectionChanged oldColl)
                oldColl.CollectionChanged -= OnSchedulesCollectionChanged;
        }
        if (e.NewValue is MainViewModel newVm)
        {
            newVm.PropertyChanged += OnViewModelPropertyChanged;
            if (newVm.Schedules is INotifyCollectionChanged newColl)
                newColl.CollectionChanged += OnSchedulesCollectionChanged;
        }
        SyncAutoStartVisual();
    }

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.AutoStartEnabled))
        {
            Dispatcher.Invoke(SyncAutoStartVisual);
        }
    }

    private void SyncAutoStartVisual()
    {
        if (DataContext is MainViewModel vm)
        {
            AnimateSwitch(AutoStartTrack, AutoStartThumb, vm.AutoStartEnabled, 46, 20);
        }
    }

    /// <summary>
    /// 开机自启开关点击
    /// </summary>
    private void OnAutoStartToggleClick(object sender, MouseButtonEventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.AutoStartEnabled = !vm.AutoStartEnabled;
            AnimateSwitch(AutoStartTrack, AutoStartThumb, vm.AutoStartEnabled, 46, 20);
        }
    }

    /// <summary>
    /// 列表项启用开关点击
    /// </summary>
    private void OnEnabledToggleClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not Grid grid || grid.Tag is not ScheduleItem item) return;
        if (DataContext is not MainViewModel vm) return;

        // 由 ViewModel 统一切换 Enabled，UI 通过 PropertyChanged 自动动画
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

    private static void AnimateSwitch(Border track, Border thumb, bool isOn, int trackWidth, int thumbSize)
    {
        // 关键修复：XAML 里 #C9D0DC 这种字面颜色创建的 brush 是 frozen（共享只读），
        // 直接 BeginAnimation 会抛 "Cannot animate... sealed or frozen"。
        // 用 EnsureMutableBrush 强制替换为可修改的新 SolidColorBrush。
        var brush = EnsureMutableBrush(track, TrackOff);

        // 取消正在进行的旧动画（此时 brush 一定是 unfrozen）
        brush.BeginAnimation(SolidColorBrush.ColorProperty, null);
        thumb.BeginAnimation(MarginProperty, null);

        var duration = TimeSpan.FromMilliseconds(180);

        // 颜色动画
        var colorAnim = new ColorAnimation
        {
            To = isOn ? TrackOn : TrackOff,
            Duration = new Duration(duration)
        };
        brush.BeginAnimation(SolidColorBrush.ColorProperty, colorAnim);

        // 位移动画
        var leftMargin = isOn ? trackWidth - thumbSize - 2 : 2;
        var marginAnim = new ThicknessAnimation
        {
            To = new Thickness(leftMargin, 0, 0, 0),
            Duration = new Duration(duration),
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        };
        thumb.BeginAnimation(MarginProperty, marginAnim);
    }

    private static void AnimateEnabledSwitch(Grid grid, bool isOn)
    {
        Border? track = null;
        Border? thumb = null;
        foreach (var child in grid.Children)
        {
            if (child is Border b)
            {
                if (b.Name == "EnTrack") track = b;
                else if (b.Name == "EnThumb") thumb = b;
            }
        }
        if (track != null && thumb != null)
        {
            AnimateSwitch(track, thumb, isOn, 38, 16);
        }
    }

    private void OnSchedulesListLoaded(object sender, RoutedEventArgs e)
    {
        RefreshAllEnabledSwitches();
    }

    private void OnSchedulesCollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        // 集合变化后新生成的 ListBoxItem 需要刷新开关状态
        Dispatcher.BeginInvoke(new Action(RefreshAllEnabledSwitches));
    }

    /// <summary>
    /// 遍历 ListBox 可见项，把所有"启用开关"颜色根据 item.Enabled 同步好
    /// 解决：UI 硬编码灰色，但 item.Enabled 实际是 true 的初始不一致问题
    /// </summary>
    private void RefreshAllEnabledSwitches()
    {
        if (SchedulesList == null) return;
        for (int i = 0; i < SchedulesList.Items.Count; i++)
        {
            var container = SchedulesList.ItemContainerGenerator.ContainerFromIndex(i) as ListBoxItem;
            if (container == null) continue;
            var grid = FindVisualChild<Grid>(container, g => g.Tag is ScheduleItem);
            if (grid == null) continue;
            if (grid.Tag is ScheduleItem item)
            {
                // 直接同步颜色，不走动画
                SetSwitchStateInstantly(grid, item.Enabled);
                // 订阅 item 的 PropertyChanged 以便后续翻转
                item.PropertyChanged -= OnItemPropertyChanged;
                item.PropertyChanged += OnItemPropertyChanged;
            }
        }
    }

    private void OnItemPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(ScheduleItem.Enabled) || sender is not ScheduleItem item) return;
        Dispatcher.BeginInvoke(new Action(() =>
        {
            // 找到对应 Grid 并更新动画
            for (int i = 0; i < SchedulesList.Items.Count; i++)
            {
                var container = SchedulesList.ItemContainerGenerator.ContainerFromIndex(i) as ListBoxItem;
                if (container == null) continue;
                var grid = FindVisualChild<Grid>(container, g => ReferenceEquals(g.Tag, item));
                if (grid != null)
                {
                    AnimateEnabledSwitch(grid, item.Enabled);
                    break;
                }
            }
        }));
    }

    private static void SetSwitchStateInstantly(Grid grid, bool isOn)
    {
        Border? track = null;
        Border? thumb = null;
        foreach (var child in grid.Children)
        {
            if (child is Border b)
            {
                if (b.Name == "EnTrack") track = b;
                else if (b.Name == "EnThumb") thumb = b;
            }
        }
        if (track == null || thumb == null) return;

        // 关键：先确保 brush 是可修改的（XAML 字面颜色 brush 是 frozen），
        // 再 BeginAnimation 和替换，否则 frozen brush 上调 BeginAnimation 会抛异常。
        var brush = EnsureMutableBrush(track, TrackOff);

        // 取消正在进行的动画
        brush.BeginAnimation(SolidColorBrush.ColorProperty, null);
        thumb.BeginAnimation(MarginProperty, null);

        // 直接同步颜色，不走动画
        brush.Color = isOn ? TrackOn : TrackOff;

        int leftMargin = isOn ? 38 - 16 - 2 : 2;
        thumb.Margin = new Thickness(leftMargin, 0, 0, 0);
    }

    private static T? FindVisualChild<T>(DependencyObject parent, Func<T, bool> predicate) where T : DependencyObject
    {
        int count = VisualTreeHelper.GetChildrenCount(parent);
        for (int i = 0; i < count; i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T t && predicate(t)) return t;
            var deeper = FindVisualChild<T>(child, predicate);
            if (deeper != null) return deeper;
        }
        return null;
    }
}
