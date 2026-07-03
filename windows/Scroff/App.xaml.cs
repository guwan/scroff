using System.Windows;

namespace Scroff;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 注册全局异常处理
        DispatcherUnhandledException += (s, args) =>
        {
            MessageBox.Show($"发生未处理的异常：{args.Exception.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };
    }
}
