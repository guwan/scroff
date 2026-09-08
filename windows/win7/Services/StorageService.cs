using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;

namespace Scroff.Win7.Services
{
    public class StorageService
    {
        private static readonly string DataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Scroff");
        private static readonly string DataFile = Path.Combine(DataDir, "schedules.json");
        /// <summary>历史记录文件</summary>
        private static readonly string HistoryFile = Path.Combine(DataDir, "history.json");
        private static readonly string InitMarker = Path.Combine(DataDir, ".initialized");
        private static readonly string LogFile = Path.Combine(DataDir, "scroff-debug.log");
        private static readonly string SettingsFile = Path.Combine(DataDir, "settings.json");

        /// <summary>历史记录最大保留条数（超出按时间顺序截断最早的）</summary>
        private const int MaxHistoryEntries = 500;

        private static readonly JsonSerializerSettings JsonSettings = new JsonSerializerSettings
        {
            Formatting = Formatting.Indented,
            Converters = { new StringEnumConverter() },
            NullValueHandling = NullValueHandling.Ignore
        };

        private static readonly object _fileLock = new object();

        public StorageService()
        {
            Directory.CreateDirectory(DataDir);
        }

        /// <summary>是否首次运行（未写过初始化标记）</summary>
        public bool IsFirstRun
        {
            get { return !File.Exists(InitMarker); }
        }

        /// <summary>标记已初始化</summary>
        public void MarkInitialized()
        {
            try { File.WriteAllText(InitMarker, DateTime.Now.ToString("O")); }
            catch { }
        }

        public List<ScheduleItem> Load()
        {
            if (!File.Exists(DataFile))
            {
                Log("Load: schedules.json 不存在，返回空列表");
                return new List<ScheduleItem>();
            }

            lock (_fileLock)
            {
                try
                {
                    string json;
                    using (var stream = new FileStream(DataFile, FileMode.Open, FileAccess.Read, FileShare.Read))
                    using (var reader = new StreamReader(stream, new UTF8Encoding(false)))
                    {
                        json = reader.ReadToEnd();
                    }

                    // 旧版本数据格式（TimeSpan 字符串）迁移：把 "Time": "07:50:00" 转成 Hour+Minute
                    // 旧版 ScheduleItem.Time 没有 [JsonIgnore]，新版的 [JsonIgnore] 不会被反序列化。
                    // 如果直接 DeserializeObject，老数据会变成 Hour=0, Minute=0（午夜 00:00 触发），
                    // 用户感受是"定时任务没生效"。所以这里手动转换后回写。
                    string migratedJson = MigrateLegacyTimeField(json);
                    bool migrated = migratedJson != json;
                    if (migrated)
                    {
                        Log("Load: 检测到旧版数据格式，已将 Time 字段迁移到 Hour+Minute 并回写 schedules.json");
                        try
                        {
                            using (var stream = new FileStream(DataFile, FileMode.Create, FileAccess.Write, FileShare.Read))
                            using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
                            {
                                writer.Write(migratedJson);
                                writer.Flush();
                            }
                        }
                        catch (Exception ex)
                        {
                            Log("回写迁移数据失败（不影响本次加载）: " + ex.Message);
                        }
                    }

                    var result = JsonConvert.DeserializeObject<List<ScheduleItem>>(migratedJson, JsonSettings);
                    Log($"Load: 成功加载 {(result?.Count ?? 0)} 条任务" + (migrated ? "（已迁移）" : ""));
                    return result ?? new List<ScheduleItem>();
                }
                catch (Exception ex)
                {
                    Log("Load FAIL: " + ex.GetType().Name + ": " + ex.Message);
                    // 加载失败时不击穿应用
                    return new List<ScheduleItem>();
                }
            }
        }

        /// <summary>
        /// 旧版 schedules.json 包含 "Time": "07:50:00"（TimeSpan 字符串）但没有 "Hour" / "Minute"。
        /// 这里把每条记录转成新格式。返回原 JSON（如果无迁移必要）或新 JSON。
        /// </summary>
        private static string MigrateLegacyTimeField(string json)
        {
            if (string.IsNullOrEmpty(json)) return json;
            try
            {
                var arr = Newtonsoft.Json.Linq.JArray.Parse(json);
                bool changed = false;
                foreach (var item in arr)
                {
                    if (item.Type != Newtonsoft.Json.Linq.JTokenType.Object) continue;
                    bool hasHour = item["Hour"] != null || item["hour"] != null;
                    bool hasMinute = item["Minute"] != null || item["minute"] != null;
                    if (hasHour && hasMinute) continue; // 已经是新格式

                    var timeToken = item["Time"];
                    if (timeToken == null || timeToken.Type != Newtonsoft.Json.Linq.JTokenType.String) continue;

                    if (TimeSpan.TryParse(timeToken.ToString(), out var ts))
                    {
                        item["Hour"] = ts.Hours;
                        item["Minute"] = ts.Minutes;
                        changed = true;
                        Log($"迁移任务 '{item["Name"]}'：Time='{timeToken}' → Hour={ts.Hours}, Minute={ts.Minutes}");
                    }
                }
                return changed ? arr.ToString(Formatting.Indented) : json;
            }
            catch (Exception ex)
            {
                Log("MigrateLegacyTimeField 解析失败，跳过迁移: " + ex.Message);
                return json;
            }
        }

        public void Save(IEnumerable<ScheduleItem> schedules)
        {
            string json;
            lock (_fileLock)
            {
                try
                {
                    json = JsonConvert.SerializeObject(schedules, JsonSettings);
                }
                catch (Exception ex)
                {
                    Log("Serialize FAIL: " + ex.GetType().Name + ": " + ex.Message);
                    throw;
                }
            }

            for (int attempt = 1; attempt <= 5; attempt++)
            {
                try
                {
                    using (var stream = new FileStream(DataFile, FileMode.Create, FileAccess.Write, FileShare.Read))
                    using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
                    {
                        writer.Write(json);
                        writer.Flush();
                    }
                    Log("Save: 写入成功");
                    return;
                }
                catch (Exception ex) when (attempt < 5)
                {
                    Log($"Save retry {attempt}: {ex.GetType().Name}: {ex.Message}");
                    System.Threading.Thread.Sleep(50 * attempt);
                }
                catch (Exception ex)
                {
                    Log("Save FAIL: " + ex.GetType().Name + ": " + ex.Message);
                    throw;
                }
            }
        }

        /// <summary>加载历史记录</summary>
        public List<HistoryEntry> LoadHistory()
        {
            if (!File.Exists(HistoryFile)) return new List<HistoryEntry>();

            lock (_fileLock)
            {
                try
                {
                    string json;
                    using (var stream = new FileStream(HistoryFile, FileMode.Open, FileAccess.Read, FileShare.Read))
                    using (var reader = new StreamReader(stream, new UTF8Encoding(false)))
                    {
                        json = reader.ReadToEnd();
                    }
                    var result = JsonConvert.DeserializeObject<List<HistoryEntry>>(json, JsonSettings);
                    return result ?? new List<HistoryEntry>();
                }
                catch
                {
                    // 历史文件损坏时不击穿应用
                    return new List<HistoryEntry>();
                }
            }
        }

        /// <summary>追加一条历史记录（自动按 500 条上限截断）</summary>
        public void AppendHistory(HistoryEntry entry)
        {
            var list = LoadHistory();
            list.Add(entry);
            if (list.Count > MaxHistoryEntries)
            {
                list = list.Skip(list.Count - MaxHistoryEntries).ToList();
            }
            SaveHistory(list);
        }

        /// <summary>全量保存历史记录</summary>
        public void SaveHistory(IEnumerable<HistoryEntry> history)
        {
            string json;
            lock (_fileLock)
            {
                json = JsonConvert.SerializeObject(history, JsonSettings);
            }

            for (int attempt = 1; attempt <= 5; attempt++)
            {
                try
                {
                    using (var stream = new FileStream(HistoryFile, FileMode.Create, FileAccess.Write, FileShare.Read))
                    using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
                    {
                        writer.Write(json);
                        writer.Flush();
                    }
                    return;
                }
                catch (Exception) when (attempt < 5)
                {
                    System.Threading.Thread.Sleep(50 * attempt);
                }
            }
        }

        /// <summary>清空历史记录</summary>
        public void ClearHistory()
        {
            lock (_fileLock)
            {
                try
                {
                    if (File.Exists(HistoryFile)) File.Delete(HistoryFile);
                }
                catch { /* 清空失败不影响 UI 状态 */ }
            }
        }

        /// <summary>加载应用设置。文件不存在或损坏时返回默认值（首次运行会落盘默认）</summary>
        public AppSettings LoadSettings()
        {
            if (!File.Exists(SettingsFile))
            {
                var defaults = AppSettings.CreateDefault();
                try { SaveSettings(defaults); } catch { }
                return defaults;
            }

            lock (_fileLock)
            {
                try
                {
                    string json;
                    using (var stream = new FileStream(SettingsFile, FileMode.Open, FileAccess.Read, FileShare.Read))
                    using (var reader = new StreamReader(stream, new UTF8Encoding(false)))
                    {
                        json = reader.ReadToEnd();
                    }
                    var result = JsonConvert.DeserializeObject<AppSettings>(json, JsonSettings);
                    return result ?? AppSettings.CreateDefault();
                }
                catch
                {
                    return AppSettings.CreateDefault();
                }
            }
        }

        /// <summary>保存应用设置</summary>
        public void SaveSettings(AppSettings settings)
        {
            string json;
            lock (_fileLock)
            {
                try
                {
                    json = JsonConvert.SerializeObject(settings, JsonSettings);
                }
                catch { return; }
            }

            for (int attempt = 1; attempt <= 5; attempt++)
            {
                try
                {
                    using (var stream = new FileStream(SettingsFile, FileMode.Create, FileAccess.Write, FileShare.Read))
                    using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
                    {
                        writer.Write(json);
                        writer.Flush();
                    }
                    return;
                }
                catch (Exception) when (attempt < 5)
                {
                    System.Threading.Thread.Sleep(50 * attempt);
                }
            }
        }

        private static void Log(string message)
        {
            try
            {
                File.AppendAllText(LogFile,
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {message}{Environment.NewLine}",
                    new UTF8Encoding(false));
            }
            catch { }
        }
    }

    /// <summary>
    /// 应用设置：网络检测目标等可由用户调整的运行时参数
    /// </summary>
    public class AppSettings
    {
        /// <summary>网络"已恢复"判断的 Ping 目标。内网建议填内网主机 IP（如 192.168.0.236）。</summary>
        public string PingTarget { get; set; } = "192.168.0.236";

        /// <summary>Ping 超时（毫秒）</summary>
        public int PingTimeoutMs { get; set; } = 3000;

        /// <summary>检测轮询间隔（秒）</summary>
        public int PollIntervalSeconds { get; set; } = 10;

        public static AppSettings CreateDefault()
        {
            return new AppSettings
            {
                PingTarget = "192.168.0.236",
                PingTimeoutMs = 3000,
                PollIntervalSeconds = 10
            };
        }
    }
}
