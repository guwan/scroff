using System;
using System.Diagnostics;
using System.Linq;
using System.Net.NetworkInformation;
using System.Text.RegularExpressions;
using System.Threading;

namespace Scroff.Win7.Services
{
    /// <summary>
    /// 网络监控服务：检测本机网络连通性，断开时自动重连，并把事件写入历史。
    /// Win7 兼容：仅使用 .NET Framework 4.8 内置 API + netsh 命令行（Win7 自带）。
    /// </summary>
    public class NetworkMonitorService : IDisposable
    {
        private readonly StorageService _storage;
        private readonly AppSettings _settings;
        private Timer _pollTimer;
        private bool _isOnline = true;
        private bool _disposed;
        private DateTime _lastReconnectAttempt = DateTime.MinValue;
        private int _consecutiveFailures = 0;
        private readonly object _stateLock = new object();

        public bool IsOnline { get { return _isOnline; } }

        public event Action<ScheduleAction, string> NetworkEvent;

        public NetworkMonitorService(StorageService storage, AppSettings settings)
        {
            _storage = storage;
            _settings = settings;
        }

        public void Start()
        {
            if (_disposed) return;
            try
            {
                NetworkChange.NetworkAvailabilityChanged += OnNetworkAvailabilityChanged;
            }
            catch { }
            int seconds = _settings != null ? Math.Max(2, _settings.PollIntervalSeconds) : 10;
            _pollTimer = new Timer(_ => PollSafe(), null, TimeSpan.Zero, TimeSpan.FromSeconds(seconds));
        }

        public void CheckNow()
        {
            PollSafe();
        }

        private void PollSafe()
        {
            try { Poll(); }
            catch { }
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
                        RecordAndNotify(ScheduleAction.NetworkDisconnected, "检测到网络断开", true);
                        TryReconnect(true);
                    }
                    else
                    {
                        _consecutiveFailures = 0;
                        RecordAndNotify(ScheduleAction.NetworkReconnected, "网络已恢复", true);
                    }
                }
                else if (!online)
                {
                    var sinceLast = DateTime.Now - _lastReconnectAttempt;
                    if (sinceLast >= ReconnectBackoff())
                    {
                        TryReconnect(false);
                    }
                }
            }
        }

        private TimeSpan ReconnectBackoff()
        {
            if (_consecutiveFailures <= 1) return TimeSpan.FromSeconds(30);
            var seconds = Math.Min(30 * (int)Math.Pow(2, _consecutiveFailures - 1), 300);
            return TimeSpan.FromSeconds(seconds);
        }

        private void OnNetworkAvailabilityChanged(object sender, NetworkAvailabilityEventArgs e)
        {
            PollSafe();
        }

        private bool CheckOnline()
        {
            NetworkInterface anyUp = null;
            try
            {
                anyUp = NetworkInterface.GetAllNetworkInterfaces()
                    .FirstOrDefault(i =>
                        i.OperationalStatus == OperationalStatus.Up &&
                        i.NetworkInterfaceType != NetworkInterfaceType.Loopback);
            }
            catch { return false; }

            if (anyUp == null) return false;

            if (_settings == null || string.IsNullOrWhiteSpace(_settings.PingTarget))
            {
                return true; // 没配置目标时只看网卡 Up
            }

            try
            {
                using (var ping = new Ping())
                {
                    int timeout = _settings != null ? Math.Max(500, _settings.PingTimeoutMs) : 3000;
                    var reply = ping.Send(_settings.PingTarget, timeout);
                    return reply.Status == IPStatus.Success;
                }
            }
            catch
            {
                return false;
            }
        }

        private void TryReconnect(bool allowSpam)
        {
            if (!allowSpam)
            {
                var sinceLast = DateTime.Now - _lastReconnectAttempt;
                if (sinceLast < TimeSpan.FromSeconds(15)) return;
            }

            _lastReconnectAttempt = DateTime.Now;
            _consecutiveFailures++;
            string detail = "";

            string profile = GetLastWifiProfile();
            if (!string.IsNullOrEmpty(profile))
            {
                int exitCode = RunNetsh("wlan connect name=\"" + profile + "\"", 8000);
                detail = "profile='" + profile + "', exit=" + exitCode;
                if (CheckOnline())
                {
                    RecordAndNotify(ScheduleAction.NetworkReconnected,
                        "通过 WiFi profile 重连成功（" + detail + "）", true);
                    _consecutiveFailures = 0;
                    return;
                }
            }

            string wifiName = GetWifiAdapterName();
            if (!string.IsNullOrEmpty(wifiName))
            {
                RunNetsh("interface set interface \"" + wifiName + "\" disable", 5000);
                System.Threading.Thread.Sleep(2000);
                RunNetsh("interface set interface \"" + wifiName + "\" enable", 5000);
                System.Threading.Thread.Sleep(3000);
                if (CheckOnline())
                {
                    RecordAndNotify(ScheduleAction.NetworkReconnected,
                        "重置无线网卡 '" + wifiName + "' 后重连成功", true);
                    _consecutiveFailures = 0;
                    return;
                }
                detail += ", reset '" + wifiName + "' failed";
            }

            RecordAndNotify(ScheduleAction.NetworkReconnectFailed,
                "第 " + _consecutiveFailures + " 次重连失败（" + detail + "）", false);
        }

        private static string GetLastWifiProfile()
        {
            try
            {
                string output;
                if (!RunNetsh("wlan show interfaces", out output, 5000)) return null;
                var match = Regex.Match(output, @"Profile\s*:\s*(.+)");
                return match.Success ? match.Groups[1].Value.Trim() : null;
            }
            catch { return null; }
        }

        private static string GetWifiAdapterName()
        {
            try
            {
                var nic = NetworkInterface.GetAllNetworkInterfaces()
                    .FirstOrDefault(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211
                                      && n.OperationalStatus == OperationalStatus.Up
                                      && n.Name.IndexOf("Virtual", StringComparison.OrdinalIgnoreCase) < 0);
                return nic != null ? nic.Name : null;
            }
            catch { return null; }
        }

        private static bool RunNetsh(string arguments, out string stdout, int timeoutMs)
        {
            stdout = "";
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
                using (var p = Process.Start(psi))
                {
                    if (p == null) return false;
                    if (!p.WaitForExit(timeoutMs))
                    {
                        try { p.Kill(); } catch { }
                        return false;
                    }
                    stdout = p.StandardOutput.ReadToEnd();
                    return p.ExitCode == 0;
                }
            }
            catch
            {
                return false;
            }
        }

        private static int RunNetsh(string arguments, int timeoutMs)
        {
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
                using (var p = Process.Start(psi))
                {
                    if (p == null) return -1;
                    if (!p.WaitForExit(timeoutMs))
                    {
                        try { p.Kill(); } catch { }
                        return -1;
                    }
                    return p.ExitCode;
                }
            }
            catch { return -1; }
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

            try { _storage.AppendHistory(entry); }
            catch { }

            try
            {
                var h = NetworkEvent;
                if (h != null) h(action, message);
            }
            catch { }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            try { NetworkChange.NetworkAvailabilityChanged -= OnNetworkAvailabilityChanged; } catch { }
            if (_pollTimer != null) _pollTimer.Dispose();
            _pollTimer = null;
        }
    }
}
