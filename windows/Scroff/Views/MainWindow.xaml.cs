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

namespace Scroff.Views;

public partial class MainWindow : Window
{
    private static readonly Color TrackOn = Color.FromRgb(0x4F, 0x7C, 0xFF);
    private static readonly Color TrackOff = Color.FromRgb(0xC9, 0xD0, 0xDC);

    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
        Loaded += OnLoaded;
        SchedulesList.Loaded += OnSchedulesListLoaded;
    }

    private void OnLoaded(object? sender, RoutedEventArgs e)
    {
        SyncAutoStartVisual();
        RefreshAllEnabledSwitches();
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

    private static void AnimateSwitch(Border track, Border thumb, bool isOn, int trackWidth, int thumbSize)
    {
        // 关键修复：XAML 里 #C9D0DC 这种字面颜色创建的 brush 是 frozen（共享只读），
        // 直接 BeginAnimation 会抛 "Cannot animate... sealed or frozen"。
        // 必须在动画前替换为可修改的新 SolidColorBrush。
        var currentBrush = track.Background as SolidColorBrush;
        if (currentBrush == null || currentBrush.IsFrozen)
        {
            var color = currentBrush != null ? currentBrush.Color : TrackOff;
            track.Background = new SolidColorBrush(color);
        }

        // 取消正在进行的旧动画
        track.Background.BeginAnimation(SolidColorBrush.ColorProperty, null);
        thumb.BeginAnimation(MarginProperty, null);

        var duration = TimeSpan.FromMilliseconds(180);

        // 颜色动画
        var colorAnim = new ColorAnimation
        {
            To = isOn ? TrackOn : TrackOff,
            Duration = new Duration(duration)
        };
        track.Background.BeginAnimation(SolidColorBrush.ColorProperty, colorAnim);

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

        // 取消正在进行的动画，并使用非 frozen brush
        track.Background.BeginAnimation(SolidColorBrush.ColorProperty, null);
        thumb.BeginAnimation(MarginProperty, null);
        track.Background = new SolidColorBrush(isOn ? TrackOn : TrackOff);

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
