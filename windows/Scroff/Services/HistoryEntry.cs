using System;
using System.Text.Json.Serialization;

namespace Scroff.Services;

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
    public string? ScheduleName { get; set; }

    /// <summary>是否执行成功（OS API 调用是否被接收 / 重连是否成功）</summary>
    public bool Success { get; set; } = true;

    /// <summary>附加说明（如失败原因 / 关联的网卡名 / profile 名）</summary>
    public string? Message { get; set; }

    // ===== 便于 XAML 绑定的展示属性（不序列化） =====

    [JsonIgnore]
    public string ActionText => Action switch
    {
        ScheduleAction.ScreenOff => "关闭屏幕",
        ScheduleAction.ScreenOn => "打开屏幕",
        ScheduleAction.NetworkDisconnected => "网络断开",
        ScheduleAction.NetworkReconnected => "网络恢复",
        ScheduleAction.NetworkReconnectFailed => "重连失败",
        _ => Action.ToString()
    };

    [JsonIgnore]
    public string SourceText => Source switch
    {
        HistorySource.Schedule => "定时",
        HistorySource.Manual => "手动",
        HistorySource.Network => "网络",
        _ => Source.ToString()
    };

    [JsonIgnore]
    public string DisplayName =>
        string.IsNullOrWhiteSpace(ScheduleName) ? "—" : ScheduleName;

    [JsonIgnore]
    public string TimeText => Timestamp.ToString("yyyy-MM-dd HH:mm:ss");

    [JsonIgnore]
    public string StatusText => Action switch
    {
        ScheduleAction.NetworkDisconnected => "已断开",
        ScheduleAction.NetworkReconnected => "已恢复",
        ScheduleAction.NetworkReconnectFailed => "失败",
        _ => Success ? "成功" : "失败"
    };

    [JsonIgnore]
    public string StatusColorHex => Action switch
    {
        ScheduleAction.NetworkDisconnected => "#E5A23B",   // 橙
        ScheduleAction.NetworkReconnected => "#30A46E",    // 绿
        ScheduleAction.NetworkReconnectFailed => "#E5484D", // 红
        _ => Success ? "#30A46E" : "#E5484D"
    };
}
