using System;
using System.Diagnostics;
using System.Linq;
using System.Net.NetworkInformation;
using System.Text.RegularExpressions;
using System.Threading;

using Timer = System.Threading.Timer;

namespace Scroff.Services;

/// <summary>
/// 网络监控服务：检测本机网络连通性，断开时自动重连，并把事件写入历史。
///
/// 检测策略（双保险）：
/// 1. 订阅 OS 的 <see cref="NetworkChange.NetworkAvailabilityChanged"/> 事件（网卡状态变化瞬间触发）
/// 2. 10 秒间隔轮询（兜底"网卡显示已连但实际无流量"，比如连上 WiFi 但未通过认证）
///
/// 联网判断：
/// 1. 至少有一张非回环网卡 OperationalStatus == Up
/// 2. Ping 公共 DNS（114.114.114.114）3 秒超时，验证"链路 up + 实际有外网"
///
/// 重连策略（按顺序尝试）：
/// 1. <c>netsh wlan connect name=&lt;profile&gt;</c> —— 重连最近一次保存的 WiFi profile
/// 2. <c>netsh interface set interface "Wi-Fi" disable</c> + <c>enable</c> —— 重置无线网卡驱动
/// 3. 失败后退避：30s 后再试一次，连续失败 3 次后等 5 分钟再试
/// 成功后写"网络恢复"历史，失败写"重连失败"历史。
/// </summary>
public class NetworkMonitorService : IDisposable
{
    private readonly StorageService _storage;
    private readonly AppSettings _settings;
    private Timer? _pollTimer;
    private bool _isOnline = true;
    private bool _disposed;
    private DateTime _lastReconnectAttempt = DateTime.MinValue;
    private int _consecutiveFailures = 0;
    private readonly object _stateLock = new object();

    /// <summary>当前网络状态（最近一次检测结果）</summary>
    public bool IsOnline => _isOnline;

    /// <summary>最近一次重连尝试的时间</summary>
    public DateTime LastReconnectAttempt => _lastReconnectAttempt;

    /// <summary>
    /// 网络事件：(动作, 消息)。订阅者负责把记录插入 UI History 集合（需 Dispatcher.Invoke）。
    /// </summary>
    public event Action<ScheduleAction, string?>? NetworkEvent;

    public NetworkMonitorService(StorageService storage, AppSettings settings)
    {
        _storage = storage;
        _settings = settings;
    }

    /// <summary>启动后台轮询 + 订阅 OS 事件</summary>
    public void Start()
    {
        if (_disposed) return;
        try
        {
            NetworkChange.NetworkAvailabilityChanged += OnNetworkAvailabilityChanged;
        }
        catch { /* 某些系统/网络环境下订阅事件可能失败，不影响轮询 */ }
        var interval = TimeSpan.FromSeconds(Math.Max(2, _settings.PollIntervalSeconds));
        _pollTimer = new Timer(_ => PollSafe(), null, TimeSpan.Zero, interval);
    }

    private void PollSafe()
    {
        try { Poll(); }
        catch { /* 任何异常吞掉，避免线程池任务异常终止 */ }
    }

    /// <summary>
    /// 主动触发一次检测（UI 调试按钮 / 立即检测网络时调用）
    /// </summary>
    public void CheckNow()
    {
        PollSafe();
    }

    private void Poll()
    {
        bool online = CheckOnline();

        lock (_stateLock)
        {
            if (online != _isOnline)
            {
                _isOnline = online;
                if (!online)
                {
                    // 新发生的断网
                    RecordAndNotify(ScheduleAction.NetworkDisconnected, "检测到网络断开", success: true);
                    // 立即尝试一次重连（不等退避）
                    TryReconnect(allowSpam: true);
                }
                else
                {
                    // 从断网恢复
                    _consecutiveFailures = 0;
                    RecordAndNotify(ScheduleAction.NetworkReconnected, "网络已恢复", success: true);
                }
            }
            else if (!online)
            {
                // 持续断网中：到退避间隔后再试一次
                var sinceLast = DateTime.Now - _lastReconnectAttempt;
                if (sinceLast >= ReconnectBackoff())
                {
                    TryReconnect(allowSpam: false);
                }
            }
        }
    }

    private TimeSpan ReconnectBackoff()
    {
        // 第 1 次重连：立即；第 2 次：30s；后续每次翻倍，上限 5 分钟
        if (_consecutiveFailures <= 1) return TimeSpan.FromSeconds(30);
        var seconds = Math.Min(30 * (int)Math.Pow(2, _consecutiveFailures - 1), 300);
        return TimeSpan.FromSeconds(seconds);
    }

    private void OnNetworkAvailabilityChanged(object? sender, NetworkAvailabilityEventArgs e)
    {
        // OS 事件触发后立刻跑一次检测（OS 事件自身不告诉你"现在能不能上外网"）
        PollSafe();
    }

    /// <summary>真实外网连通性：网卡 Up + Ping 配置目标（默认内网主机）</summary>
    private bool CheckOnline()
    {
        // 1) 网卡层
        NetworkInterface? anyUp = null;
        try
        {
            anyUp = NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(i =>
                    i.OperationalStatus == OperationalStatus.Up &&
                    i.NetworkInterfaceType != NetworkInterfaceType.Loopback);
        }
        catch { return false; }

        if (anyUp == null) return false;

        // 2) 实际可达性：Ping 配置目标（内网/公网 IP，由用户配置决定）
        // 优先尝试 IP（无 DNS 解析延迟），不行再退到主机名
        var target = _settings.PingTarget;
        if (string.IsNullOrWhiteSpace(target)) return true; // 没配置目标时只看网卡 Up

        try
        {
            using var ping = new Ping();
            var reply = ping.Send(target, Math.Max(500, _settings.PingTimeoutMs));
            return reply.Status == IPStatus.Success;
        }
        catch
        {
            return false;
        }
    }

    private void TryReconnect(bool allowSpam)
    {
        // 防止短时间内重连风暴（断网瞬间会连发）
        if (!allowSpam)
        {
            var sinceLast = DateTime.Now - _lastReconnectAttempt;
            if (sinceLast < TimeSpan.FromSeconds(15)) return;
        }

        _lastReconnectAttempt = DateTime.Now;
        _consecutiveFailures++;
        string detail = "";

        // 方案 1：netsh wlan connect name=<profile>
        string? profile = GetLastWifiProfile();
        if (!string.IsNullOrEmpty(profile))
        {
            var exitCode = RunNetsh($"wlan connect name=\"{profile}\"", out var output, out var error, 8000);
            detail = $"profile='{profile}', exit={exitCode}";
            if (CheckOnline())
            {
                RecordAndNotify(ScheduleAction.NetworkReconnected,
                    $"通过 WiFi profile 重连成功（{detail}）", success: true);
                _consecutiveFailures = 0;
                return;
            }
        }

        // 方案 2：disable + enable 无线网卡
        string? wifiName = GetWifiAdapterName();
        if (!string.IsNullOrEmpty(wifiName))
        {
            RunNetsh($"interface set interface \"{wifiName}\" disable", out _, out _, 5000);
            Thread.Sleep(2000);
            RunNetsh($"interface set interface \"{wifiName}\" enable", out _, out _, 5000);
            Thread.Sleep(3000);
            if (CheckOnline())
            {
                RecordAndNotify(ScheduleAction.NetworkReconnected,
                    $"重置无线网卡 '{wifiName}' 后重连成功", success: true);
                _consecutiveFailures = 0;
                return;
            }
            detail += $", reset '{wifiName}' failed";
        }

        // 所有方案都失败
        RecordAndNotify(ScheduleAction.NetworkReconnectFailed,
            $"第 {_consecutiveFailures} 次重连失败（{detail}）", success: false);
    }

    /// <summary>读取当前已连接 WiFi 的 profile 名</summary>
    private static string? GetLastWifiProfile()
    {
        try
        {
            if (!RunNetsh("wlan show interfaces", out var output, out _, 5000)) return null;
            // 输出形如 "    Profile     : MyHomeWiFi"
            var match = Regex.Match(output, @"Profile\s*:\s*(.+)");
            return match.Success ? match.Groups[1].Value.Trim() : null;
        }
        catch { return null; }
    }

    /// <summary>读取当前无线网卡名称（用于 disable/enable）</summary>
    private static string? GetWifiAdapterName()
    {
        try
        {
            var nic = NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211
                                  && n.OperationalStatus == OperationalStatus.Up
                                  && !n.Name.Contains("Virtual", StringComparison.OrdinalIgnoreCase));
            return nic?.Name;
        }
        catch { return null; }
    }

    /// <summary>同步执行 netsh 命令，返回 exit code + 输出</summary>
    private static bool RunNetsh(string arguments, out string stdout, out string stderr, int timeoutMs)
    {
        stdout = "";
        stderr = "";
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "netsh",
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            using var p = Process.Start(psi);
            if (p == null) return false;
            if (!p.WaitForExit(timeoutMs))
            {
                try { p.Kill(); } catch { }
                stderr = "timeout";
                return false;
            }
            stdout = p.StandardOutput.ReadToEnd();
            stderr = p.StandardError.ReadToEnd();
            return p.ExitCode == 0;
        }
        catch (Exception ex)
        {
            stderr = ex.GetType().Name + ": " + ex.Message;
            return false;
        }
    }

    private void RecordAndNotify(ScheduleAction action, string message, bool success)
    {
        var entry = new HistoryEntry
        {
            Id = DateTime.Now.Ticks,
            Timestamp = DateTime.Now,
            Action = action,
            Source = HistorySource.Network,
            ScheduleName = null,
            Success = success,
            Message = message
        };

        // 1) 持久化（线程安全，内部有 lock）
        try { _storage.AppendHistory(entry); }
        catch { /* 落库失败不影响 UI 通知 */ }

        // 2) 通知订阅者（通常在 UI 线程上执行，订阅者自行 Dispatcher.Invoke）
        try { NetworkEvent?.Invoke(action, message); }
        catch { /* 订阅者异常不影响 */ }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { NetworkChange.NetworkAvailabilityChanged -= OnNetworkAvailabilityChanged; } catch { }
        _pollTimer?.Dispose();
        _pollTimer = null;
    }
}
