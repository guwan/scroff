using System;
using System.Collections.Generic;
using System.IO;
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
        private static readonly string InitMarker = Path.Combine(DataDir, ".initialized");
        private static readonly string LogFile = Path.Combine(DataDir, "scroff-debug.log");

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
        public bool IsFirstRun => !File.Exists(InitMarker);

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

                    // 旧版本数据格式（TimeSpan / 旧字段）检测：清掉后触发首次运行
                    if (LooksLikeLegacyFormat(json))
                    {
                        Log("Load: 检测到旧版数据格式，删除 schedules.json 以触发首次运行");
                        try { File.Delete(DataFile); } catch { }
                        return new List<ScheduleItem>();
                    }

                    var result = JsonConvert.DeserializeObject<List<ScheduleItem>>(json, JsonSettings);
                    Log($"Load: 成功加载 {(result?.Count ?? 0)} 条任务");
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
        /// 检测是否为旧版数据：包含 TimeSpan 格式字符串或缺少 Hour/Minute 字段
        /// 旧版序列化 TimeSpan 输出 "07:50:00"，新版使用 Hour+Minute
        /// </summary>
        private static bool LooksLikeLegacyFormat(string json)
        {
            if (string.IsNullOrEmpty(json)) return false;
            // 新版每条记录都有 "Hour" 和 "Minute"
            if (json.Contains("\"Hour\"") || json.Contains("\"hour\"")) return false;
            // 检测到旧版 TimeSpan 格式（带冒号的时间字符串但不是 Time）
            if (System.Text.RegularExpressions.Regex.IsMatch(json, "\"Time\"\\s*:\\s*\"\\d+:\\d+:\\d+"))
                return true;
            return false;
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
}
