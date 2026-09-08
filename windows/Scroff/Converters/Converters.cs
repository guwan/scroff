using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using Scroff.Services;

// 同时引入 WinForms 时消除 Color 歧义
using Color = System.Windows.Media.Color;

namespace Scroff.Converters;

/// <summary>
/// ScheduleAction 枚举转显示文本
/// </summary>
public class ScheduleActionToTextConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is ScheduleAction action)
        {
            return action switch
            {
                ScheduleAction.ScreenOff => "关闭屏幕",
                ScheduleAction.ScreenOn => "打开屏幕",
                ScheduleAction.NetworkDisconnected => "网络断开",
                ScheduleAction.NetworkReconnected => "网络恢复",
                ScheduleAction.NetworkReconnectFailed => "重连失败",
                _ => action.ToString()
            };
        }
        return "";
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotImplementedException();
    }
}

/// <summary>
/// ScheduleAction 枚举转颜色
/// </summary>
public class ScheduleActionToColorConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is ScheduleAction action)
        {
            return action switch
            {
                ScheduleAction.ScreenOff => new SolidColorBrush(Color.FromRgb(0xE5, 0x48, 0x4D)), // 红
                ScheduleAction.ScreenOn => new SolidColorBrush(Color.FromRgb(0x30, 0xA4, 0x6E)),  // 绿
                ScheduleAction.NetworkDisconnected => new SolidColorBrush(Color.FromRgb(0xE5, 0xA2, 0x3B)), // 橙
                ScheduleAction.NetworkReconnected => new SolidColorBrush(Color.FromRgb(0x30, 0xA4, 0x6E)),  // 绿
                ScheduleAction.NetworkReconnectFailed => new SolidColorBrush(Color.FromRgb(0xE5, 0x48, 0x4D)), // 红
                _ => new SolidColorBrush(Colors.Gray)
            };
        }
        return new SolidColorBrush(Colors.Gray);
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotImplementedException();
    }
}

/// <summary>
/// 布尔值转开关文本
/// </summary>
public class BoolToOnOffConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is bool enabled)
        {
            return enabled ? "已启用" : "已禁用";
        }
        return "";
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotImplementedException();
    }
}

/// <summary>
/// 布尔值转 Visibility（true=Visible, false=Collapsed）
/// </summary>
public class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is bool b)
        {
            return b ? Visibility.Visible : Visibility.Collapsed;
        }
        return Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is Visibility v)
        {
            return v == Visibility.Visible;
        }
        return false;
    }
}

/// <summary>
/// 编辑模式标题：true=编辑定时任务, false=添加定时任务
/// </summary>
public class EditModeToTitleConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is bool isEditing)
        {
            return isEditing ? "编辑定时任务" : "添加定时任务";
        }
        return "添加定时任务";
    }
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotImplementedException();
    }
}

/// <summary>
/// 十六进制颜色字符串（如 "#30A46E"）转 SolidColorBrush
/// </summary>
public class HexToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is string hex && !string.IsNullOrWhiteSpace(hex))
        {
            try
            {
                var color = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(hex);
                return new System.Windows.Media.SolidColorBrush(color);
            }
            catch
            {
                return System.Windows.Media.Brushes.Gray;
            }
        }
        return System.Windows.Media.Brushes.Gray;
    }
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
    {
        throw new NotImplementedException();
    }
}
