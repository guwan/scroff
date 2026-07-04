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
        ON, OFF
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
    @Column(nullable = false, length = 10)
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
}
