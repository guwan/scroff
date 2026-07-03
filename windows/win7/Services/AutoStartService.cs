using System;
using Microsoft.Win32;

namespace Scroff.Win7.Services
{
    public static class AutoStartService
    {
        private const string AppName = "Scroff";
        private static readonly string ExecutablePath =
            System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName;

        private static RegistryKey GetRunKey(bool writable)
        {
            return Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Run", writable);
        }

        public static bool IsEnabled()
        {
            using (var key = GetRunKey(false))
            {
                if (key == null) return false;
                var value = key.GetValue(AppName) as string;
                return value != null && value.Equals(ExecutablePath, StringComparison.OrdinalIgnoreCase);
            }
        }

        public static void Enable()
        {
            using (var key = GetRunKey(true))
            {
                if (key == null) return;
                key.SetValue(AppName, ExecutablePath);
            }
        }

        public static void Disable()
        {
            using (var key = GetRunKey(true))
            {
                if (key == null) return;
                key.DeleteValue(AppName, false);
            }
        }
    }
}
