package com.scroff.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 屏幕控制执行日志。
 *
 * 设计为 append-only，定期清理（应用层或数据库事件）。
 */
@Entity
@Table(name = "screen_log")
@Getter
@Setter
@NoArgsConstructor
public class ScreenLog {

    public enum TriggerType {
        SCHEDULE, MANUAL, API
    }

    public enum Action {
        ON, OFF, OPEN_FILE, OPEN_APP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 冗余存储 device 当时的 name，避免后续改名/删除看不懂 */
    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private TriggerType triggerType;

    @Column(nullable = false)
    private Boolean success;

    @Column(length = 1000)
    private String message;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt = LocalDateTime.now();

    // ---- 扩展字段（首页"最近执行"用，更丰富的日志信息） ----
    // 以下字段都可空：
    //   旧库升级上来老行为 NULL（ddl-auto: update 加列不带 DEFAULT）
    //   触发类型非 SCHEDULE 时 scheduleId/scheduleName 为 NULL
    //   老逻辑没埋点时 durationMs 为 NULL

    /** 冗余存储 device 当时的 host:port，便于同名设备区分 */
    @Column(name = "device_address", length = 100)
    private String deviceAddress;

    /** 触发该日志的 schedule id（仅 SCHEDULE 触发时有值） */
    @Column(name = "schedule_id")
    private Long scheduleId;

    /** 冗余存储 schedule 当时的 name，避免后续 schedule 改名/删除看不懂 */
    @Column(name = "schedule_name", length = 100)
    private String scheduleName;

    /** 执行 adb 命令的耗时（毫秒）。从入参校验通过到 adb 返回的时间 */
    @Column(name = "duration_ms")
    private Integer durationMs;
}
