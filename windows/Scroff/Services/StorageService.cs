using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Scroff.Services;

/// <summary>
/// 持久化存储服务 - 使用 JSON 文件保存定时任务
/// </summary>
public class StorageService
{
    private static readonly string DataDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "Scroff"
    );
    private static readonly string DataFile = Path.Combine(DataDir, "schedules.json");

    /// <summary>
    /// 标记文件，用于区分"首次运行"与"用户主动清空了所有任务"
    /// </summary>
    private static readonly string InitMarker = Path.Combine(DataDir, ".initialized");

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
}
