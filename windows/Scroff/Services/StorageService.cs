using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Scroff.Services;

/// <summary>
/// 持久化存储服务 - 使用 JSON 文件保存定时任务与历史记录
/// </summary>
public class StorageService
{
    private static readonly string DataDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "Scroff"
    );
    private static readonly string DataFile = Path.Combine(DataDir, "schedules.json");

    /// <summary>
    /// 历史记录文件
    /// </summary>
    private static readonly string HistoryFile = Path.Combine(DataDir, "history.json");

    /// <summary>
    /// 标记文件，用于区分"首次运行"与"用户主动清空了所有任务"
    /// </summary>
    private static readonly string InitMarker = Path.Combine(DataDir, ".initialized");

    /// <summary>
    /// 应用设置文件（网络检测目标等）
    /// </summary>
    private static readonly string SettingsFile = Path.Combine(DataDir, "settings.json");

    /// <summary>
    /// 历史记录最大保留条数（超出按时间顺序截断最早的）
    /// </summary>
    private const int MaxHistoryEntries = 500;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Converters = { new JsonStringEnumConverter() }
    };

    // 进程级文件锁，防止同一进程内并发读写冲突
    private static readonly object _fileLock = new();

    public StorageService()
    {
        Directory.CreateDirectory(DataDir);
    }

    public List<ScheduleItem> Load()
    {
        if (!File.Exists(DataFile))
            return new List<ScheduleItem>();

        lock (_fileLock)
        {
            // 使用共享读模式，避免阻塞其他进程读取
            using var stream = new FileStream(
                DataFile, FileMode.Open, FileAccess.Read, FileShare.Read);
            return JsonSerializer.Deserialize<List<ScheduleItem>>(stream, JsonOptions)
                ?? new List<ScheduleItem>();
        }
    }

    public void Save(IEnumerable<ScheduleItem> schedules)
    {
        // 先序列化到内存（不持有文件句柄）
        string json;
        lock (_fileLock)
        {
            json = JsonSerializer.Serialize(schedules, JsonOptions);
        }

        // 多次重试写入，处理文件被短时占用的情况
        const int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                // 直接以共享写入模式打开，覆盖现有内容
                // FileMode.Create + FileShare.Read 允许其他进程读取
                using var stream = new FileStream(
                    DataFile,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read);
                using var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(false));
                writer.Write(json);
                writer.Flush();
                return; // 成功
            }
            catch (IOException) when (attempt < maxAttempts)
            {
                // 文件被占用时短暂等待后重试
                System.Threading.Thread.Sleep(50 * attempt);
            }
            catch (UnauthorizedAccessException) when (attempt < maxAttempts)
            {
                System.Threading.Thread.Sleep(50 * attempt);
            }
        }
    }

    /// <summary>
    /// 加载历史记录
    /// </summary>
    public List<HistoryEntry> LoadHistory()
    {
        if (!File.Exists(HistoryFile))
            return new List<HistoryEntry>();

        lock (_fileLock)
        {
            try
            {
                using var stream = new FileStream(
                    HistoryFile, FileMode.Open, FileAccess.Read, FileShare.Read);
                return JsonSerializer.Deserialize<List<HistoryEntry>>(stream, JsonOptions)
                    ?? new List<HistoryEntry>();
            }
            catch
            {
                // 历史文件损坏时不击穿应用：返回空列表即可
                return new List<HistoryEntry>();
            }
        }
    }

    /// <summary>
    /// 追加一条历史记录（自动按 500 条上限截断）
    /// </summary>
    public void AppendHistory(HistoryEntry entry)
    {
        // 读 -> 追加 -> 截断 -> 写
        var list = LoadHistory();
        list.Add(entry);
        if (list.Count > MaxHistoryEntries)
        {
            list = list.Skip(list.Count - MaxHistoryEntries).ToList();
        }
        SaveHistory(list);
    }

    /// <summary>
    /// 全量保存历史记录（清空后重新写入或批量更新用）
    /// </summary>
    public void SaveHistory(IEnumerable<HistoryEntry> history)
    {
        string json;
        lock (_fileLock)
        {
            json = JsonSerializer.Serialize(history, JsonOptions);
        }

        const int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                using var stream = new FileStream(
                    HistoryFile,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read);
                using var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(false));
                writer.Write(json);
                writer.Flush();
                return;
            }
            catch (IOException) when (attempt < maxAttempts)
            {
                System.Threading.Thread.Sleep(50 * attempt);
            }
            catch (UnauthorizedAccessException) when (attempt < maxAttempts)
            {
                System.Threading.Thread.Sleep(50 * attempt);
            }
        }
    }

    /// <summary>
    /// 清空历史记录
    /// </summary>
    public void ClearHistory()
    {
        lock (_fileLock)
        {
            try
            {
                if (File.Exists(HistoryFile))
                    File.Delete(HistoryFile);
            }
            catch { /* 清空失败不影响 UI 状态 */ }
        }
    }

    /// <summary>
    /// 是否首次运行（InitMarker 文件不存在）
    /// </summary>
    public bool IsFirstRun => !File.Exists(InitMarker);

    /// <summary>
    /// 标记已初始化（后续启动不再注入默认任务）
    /// </summary>
    public void MarkInitialized()
    {
        try { File.WriteAllText(InitMarker, DateTime.Now.ToString("O")); }
        catch { /* 标记失败不影响主流程 */ }
    }

    /// <summary>
    /// 加载应用设置。文件不存在或损坏时返回默认值。
    /// </summary>
    public AppSettings LoadSettings()
    {
        if (!File.Exists(SettingsFile))
        {
            // 首次运行：落盘默认配置，方便用户直接编辑
            var defaults = AppSettings.CreateDefault();
            try { SaveSettings(defaults); } catch { }
            return defaults;
        }

        lock (_fileLock)
        {
            try
            {
                using var stream = new FileStream(
                    SettingsFile, FileMode.Open, FileAccess.Read, FileShare.Read);
                return JsonSerializer.Deserialize<AppSettings>(stream, JsonOptions)
                    ?? AppSettings.CreateDefault();
            }
            catch
            {
                return AppSettings.CreateDefault();
            }
        }
    }

    /// <summary>
    /// 保存应用设置
    /// </summary>
    public void SaveSettings(AppSettings settings)
    {
        string json;
        lock (_fileLock)
        {
            json = JsonSerializer.Serialize(settings, JsonOptions);
        }

        const int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                using var stream = new FileStream(
                    SettingsFile,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read);
                using var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(false));
                writer.Write(json);
                writer.Flush();
                return;
            }
            catch (IOException) when (attempt < maxAttempts)
            {
                System.Threading.Thread.Sleep(50 * attempt);
            }
            catch (UnauthorizedAccessException) when (attempt < maxAttempts)
            {
                System.Threading.Thread.Sleep(50 * attempt);
            }
        }
    }
}

/// <summary>
/// 应用设置：网络检测目标等可由用户调整的运行时参数
/// </summary>
public class AppSettings
{
    /// <summary>
    /// 网络"已恢复"判断的 Ping 目标。内网环境建议填内网主机 IP（如 192.168.0.236），
    /// 公网环境可填 8.8.8.8 / 114.114.114.114。
    /// </summary>
    public string PingTarget { get; set; } = "192.168.0.236";

    /// <summary>Ping 超时（毫秒）</summary>
    public int PingTimeoutMs { get; set; } = 3000;

    /// <summary>检测轮询间隔（秒）</summary>
    public int PollIntervalSeconds { get; set; } = 10;

    public static AppSettings CreateDefault() => new()
    {
        PingTarget = "192.168.0.236",
        PingTimeoutMs = 3000,
        PollIntervalSeconds = 10
    };
}
