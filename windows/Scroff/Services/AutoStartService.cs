using Microsoft.Win32;

namespace Scroff.Services;

/// <summary>
/// 开机自启服务 - 通过注册表管理开机启动
/// </summary>
public static class AutoStartService
{
    private const string AppName = "Scroff";
    private static readonly string ExecutablePath = Environment.ProcessPath ?? "";

    private static RegistryKey GetRunKey(bool writable)
    {
        return Registry.CurrentUser.OpenSubKey(
            @"Software\Microsoft\Windows\CurrentVersion\Run", writable)!;
    }

    /// <summary>
    /// 是否已启用开机自启
    /// </summary>
    public static bool IsEnabled()
    {
        using var key = GetRunKey(false);
        var value = key.GetValue(AppName) as string;
        return value != null && value.Equals(ExecutablePath, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// 启用开机自启
    /// </summary>
    public static void Enable()
    {
        using var key = GetRunKey(true);
        key.SetValue(AppName, ExecutablePath);
    }

    /// <summary>
    /// 禁用开机自启
    /// </summary>
    public static void Disable()
    {
        using var key = GetRunKey(true);
        key.DeleteValue(AppName, false);
    }
}
