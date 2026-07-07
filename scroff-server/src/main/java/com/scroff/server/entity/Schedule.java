package com.scroff.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 定时开关屏任务。
 *
 * cron 字段使用 Spring 6 字段格式："秒 分 时 日 月 周"
 * 例如 "0 0 22 * * *" = 每天 22:00:00 触发
 */
@Entity
@Table(name = "schedule")
@Getter
@Setter
@NoArgsConstructor
public class Schedule {

    public enum Action {
        ON, OFF
    }

    public enum LastRunStatus {
        SUCCESS, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Action action;

    /** Spring cron：秒 分 时 日 月 周 [年] */
    @Column(nullable = false, length = 50)
    private String cron;

    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 是否对所有启用设备生效。
     * <p>true → deviceId 字段被忽略（实际存 0 占位），executor 会用 controlAll() 并发处理所有设备。
     * false → 只对单台设备生效，走原 control(deviceId) 流程。
     *
     * <p>为什么加这字段而不是把 deviceId 改 nullable：
     * 1. ddl-auto: update 不会把已有 NOT NULL 列改成 NULL（保守策略）
     * 2. 0 作为哨兵值，DB schema 一行不动，老数据天然兼容（默认 false = 单台）
     */
    @Column(name = "target_all", nullable = false)
    private Boolean targetAll = false;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 20)
    private LastRunStatus lastRunStatus;

    @Column(name = "last_run_message", length = 500)
    private String lastRunMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 新建时由 JPA 回调设置 created_at / updated_at。
     * 不依赖 DB DEFAULT CURRENT_TIMESTAMP，避免 ddl-auto: update 时旧表缺 DEFAULT 报错。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        // 防御：targetAll 字段在 INSERT 之前必须非 null
        // （虽然 entity 默认值是 false，DDL 也 NOT NULL，但 ddl-auto: update 加列时
        //   老数据可能为 NULL，@PostLoad 会兜底；此处兜底 INSERT 路径）
        if (targetAll == null) targetAll = false;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (targetAll == null) targetAll = false;
    }

    /**
     * 加载时兜底：ddl-auto: update 给已有 schedule 表加 target_all 列时，
     * 老行这一列可能是 NULL。这里强制归一为 false（单台模式，老行为），
     * 防止 isForAllDevices() / 列表 / 编辑表单因 null 出现意外分支。
     */
    @PostLoad
    void onLoad() {
        if (targetAll == null) targetAll = false;
    }

    /**
     * 是否"对所有设备"生效。给 executor 和 UI 共用，避免到处判 null / equals。
     */
    public boolean isForAllDevices() {
        return Boolean.TRUE.equals(targetAll);
    }
}
