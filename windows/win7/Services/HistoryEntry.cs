using System;

namespace Scroff.Win7.Services
{
    /// <summary>
    /// 历史记录来源：定时任务触发 / 手动触发 / 网络自动恢复
    /// </summary>
    public enum HistorySource
    {
        /// <summary>由定时任务自动触发</summary>
        Schedule,
        /// <summary>由用户手动触发（托盘菜单、立即执行按钮等）</summary>
        Manual,
        /// <summary>由网络监控自动触发（断网 / 重连 / 重连失败）</summary>
        Network
    }

    /// <summary>
    /// 屏幕控制 / 网络事件历史记录条目
    /// </summary>
    public class HistoryEntry
    {
        /// <summary>唯一 ID（使用时间戳）</summary>
        public long Id { get; set; }

        /// <summary>触发时间（本地时间）</summary>
        public DateTime Timestamp { get; set; }

        /// <summary>执行的动作（屏幕开关 / 网络事件）</summary>
        public ScheduleAction Action { get; set; }

        /// <summary>触发来源</summary>
        public HistorySource Source { get; set; }

        /// <summary>关联的定时任务名称（手动 / 网络触发时为 null）</summary>
        public string ScheduleName { get; set; }

        /// <summary>是否执行成功（OS API 调用是否被接收 / 重连是否成功）</summary>
        public bool Success { get; set; } = true;

        /// <summary>附加说明（如失败原因 / 关联的网卡名 / profile 名）</summary>
        public string Message { get; set; }

        // ===== 便于 XAML 绑定的展示属性 =====

        [Newtonsoft.Json.JsonIgnore]
        public string ActionText
        {
            get
            {
                switch (Action)
                {
                    case ScheduleAction.ScreenOff: return "关闭屏幕";
                    case ScheduleAction.ScreenOn: return "打开屏幕";
                    case ScheduleAction.NetworkDisconnected: return "网络断开";
                    case ScheduleAction.NetworkReconnected: return "网络恢复";
                    case ScheduleAction.NetworkReconnectFailed: return "重连失败";
                    default: return Action.ToString();
                }
            }
        }

        [Newtonsoft.Json.JsonIgnore]
        public string SourceText
        {
            get
            {
                switch (Source)
                {
                    case HistorySource.Schedule: return "定时";
                    case HistorySource.Manual: return "手动";
                    case HistorySource.Network: return "网络";
                    default: return Source.ToString();
                }
            }
        }

        [Newtonsoft.Json.JsonIgnore]
        public string DisplayName
        {
            get { return string.IsNullOrWhiteSpace(ScheduleName) ? "—" : ScheduleName; }
        }

        [Newtonsoft.Json.JsonIgnore]
        public string TimeText
        {
            get { return Timestamp.ToString("yyyy-MM-dd HH:mm:ss"); }
        }

        [Newtonsoft.Json.JsonIgnore]
        public string StatusText
        {
            get
            {
                switch (Action)
                {
                    case ScheduleAction.NetworkDisconnected: return "已断开";
                    case ScheduleAction.NetworkReconnected: return "已恢复";
                    case ScheduleAction.NetworkReconnectFailed: return "失败";
                    default: return Success ? "成功" : "失败";
                }
            }
        }

        [Newtonsoft.Json.JsonIgnore]
        public string StatusColorHex
        {
            get
            {
                switch (Action)
                {
                    case ScheduleAction.NetworkDisconnected: return "#E5A23B"; // 橙
                    case ScheduleAction.NetworkReconnected: return "#30A46E";  // 绿
                    case ScheduleAction.NetworkReconnectFailed: return "#E5484D"; // 红
                    default: return Success ? "#30A46E" : "#E5484D";
                }
            }
        }
    }
}
