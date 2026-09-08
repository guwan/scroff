using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using Scroff.Win7.Services;

namespace Scroff.Win7.Converters
{
    public class ScheduleActionToTextConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is ScheduleAction)
            {
                var action = (ScheduleAction)value;
                switch (action)
                {
                    case ScheduleAction.ScreenOff: return "关闭屏幕";
                    case ScheduleAction.ScreenOn: return "打开屏幕";
                    case ScheduleAction.NetworkDisconnected: return "网络断开";
                    case ScheduleAction.NetworkReconnected: return "网络恢复";
                    case ScheduleAction.NetworkReconnectFailed: return "重连失败";
                    default: return action.ToString();
                }
            }
            return "";
        }
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class ScheduleActionToColorConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is ScheduleAction)
            {
                var action = (ScheduleAction)value;
                switch (action)
                {
                    case ScheduleAction.ScreenOff: return new SolidColorBrush(Color.FromRgb(0xE5, 0x48, 0x4D));
                    case ScheduleAction.ScreenOn: return new SolidColorBrush(Color.FromRgb(0x30, 0xA4, 0x6E));
                    case ScheduleAction.NetworkDisconnected: return new SolidColorBrush(Color.FromRgb(0xE5, 0xA2, 0x3B));
                    case ScheduleAction.NetworkReconnected: return new SolidColorBrush(Color.FromRgb(0x30, 0xA4, 0x6E));
                    case ScheduleAction.NetworkReconnectFailed: return new SolidColorBrush(Color.FromRgb(0xE5, 0x48, 0x4D));
                    default: return new SolidColorBrush(Colors.Gray);
                }
            }
            return new SolidColorBrush(Colors.Gray);
        }
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class BoolToOnOffConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is bool) return ((bool)value) ? "已启用" : "已禁用";
            return "";
        }
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

    public class BoolToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is bool) return ((bool)value) ? Visibility.Visible : Visibility.Collapsed;
            return Visibility.Collapsed;
        }
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is Visibility) return ((Visibility)value) == Visibility.Visible;
            return false;
        }
    }

    public class EditModeToTitleConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is bool) return ((bool)value) ? "编辑定时任务" : "添加定时任务";
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
                    var c = (Color)ColorConverter.ConvertFromString(hex);
                    return new SolidColorBrush(c);
                }
                catch
                {
                    return Brushes.Gray;
                }
            }
            return Brushes.Gray;
        }
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotImplementedException();
        }
    }

}
