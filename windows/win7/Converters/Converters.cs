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
                return ((ScheduleAction)value) == ScheduleAction.ScreenOff ? "关闭屏幕" : "打开屏幕";
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
                return ((ScheduleAction)value) == ScheduleAction.ScreenOff
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

}
