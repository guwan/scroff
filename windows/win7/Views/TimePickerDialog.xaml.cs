using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Input;

namespace Scroff.Win7.Views
{
    public partial class TimePickerDialog : Window
    {
        public int SelectedHour { get; private set; }
        public int SelectedMinute { get; private set; }

        private static readonly Regex DigitRegex = new Regex("^[0-9]+$");

        public TimePickerDialog(int hour, int minute)
        {
            InitializeComponent();
            HourBox.Text = hour.ToString("00");
            MinuteBox.Text = minute.ToString("00");
            Loaded += (s, e) => HourBox.Focus();
        }

        private void OnHourTextInput(object sender, TextCompositionEventArgs e)
        {
            e.Handled = !DigitRegex.IsMatch(e.Text);
        }

        private void OnMinuteTextInput(object sender, TextCompositionEventArgs e)
        {
            e.Handled = !DigitRegex.IsMatch(e.Text);
        }

        private void OnOkClick(object sender, RoutedEventArgs e)
        {
            if (int.TryParse(HourBox.Text, out int h) && h >= 0 && h <= 23 &&
                int.TryParse(MinuteBox.Text, out int m) && m >= 0 && m <= 59)
            {
                SelectedHour = h;
                SelectedMinute = m;
                DialogResult = true;
            }
            else
            {
                MessageBox.Show("时间格式不正确（时：0-23，分：0-59）", "提示",
                    MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void OnCancelClick(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
        }
    }
}
