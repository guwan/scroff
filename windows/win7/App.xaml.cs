using System;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Threading;

namespace Scroff.Win7
{
    public partial class App : Application
    {
        private static readonly string LogFile = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Scroff", "scroff-crash.log");

        static App()
        {
            // 在 XAML 解析之前就注册 AppDomain 级异常处理，
            // 避免启动期崩了却没记录。
            AppDomain.CurrentDomain.UnhandledException += (s, args) =>
            {
                var ex = args.ExceptionObject as Exception;
                LogCrash("AppDomain.UnhandledException", ex);
            };
        }

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            // WPF 调度器内未捕获异常（XAML 解析期异常也会被这个捕获）
            DispatcherUnhandledException += (s, args) =>
            {
                LogCrash("DispatcherUnhandledException", args.Exception);
                MessageBox.Show(
                    "发生未处理的异常：" + args.Exception.Message +
                    "\n\n详情已写入: " + LogFile,
                    "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                args.Handled = true;
            };
        }

        private static void LogCrash(string source, Exception ex)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(LogFile));
                var sb = new StringBuilder();
                sb.AppendLine($"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {source}");
                int depth = 0;
                var current = ex;
                while (current != null)
                {
                    var prefix = depth == 0 ? "" : $"  Inner[{depth}]: ";
                    sb.AppendLine(prefix + "Type: " + current.GetType().FullName);
                    sb.AppendLine(prefix + "Message: " + current.Message);
                    if (!string.IsNullOrEmpty(current.StackTrace))
                        sb.AppendLine(prefix + "Stack: " + current.StackTrace);
                    current = current.InnerException;
                    depth++;
                }
                sb.AppendLine(new string('-', 60));
                File.AppendAllText(LogFile, sb.ToString(), new UTF8Encoding(false));
            }
            catch { /* 日志失败不影响 */ }
        }
    }
}
