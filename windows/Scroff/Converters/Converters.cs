using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using Scroff.Services;

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
            return action == ScheduleAction.ScreenOff ? "关闭屏幕" : "打开屏幕";
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
            return action == ScheduleAction.ScreenOff
                ? new SolidColorBrush(Color.FromRgb(0xE5, 0x48, 0x4D))
                : new SolidColorBrush(Color.FromRgb(0x30, 0xA4, 0x6E));
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
