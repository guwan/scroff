package com.scroff.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
        /** 开屏 */
        ON,
        /** 关屏 */
        OFF,
        /** 打开指定文件（如视频 mp4），targetPath 存 Android 文件路径 */
        OPEN_FILE,
        /** 打开指定应用程序，targetPath 存包名或 component（如 com.example.app/.MainActivity） */
        OPEN_APP
    }

    public enum LastRunStatus {
        SUCCESS, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 主设备 ID（向后兼容字段）。
     * <ul>
     *   <li>targetAll=true 时为 0 占位</li>
     *   <li>targetAll=false 且 deviceIds 为空时为 0</li>
     *   <li>targetAll=false 且 deviceIds 非空时，此字段同步为 deviceIds 的第一个元素</li>
     * </ul>
     * 保留此列是为了老迁移/老查询的兼容；新逻辑一律用 deviceIds（ElementCollection）。
     */
    @Column(name = "device_id", nullable = false)
    private Long deviceId = 0L;

    /**
     * 多设备 ID 列表（通过独立关联表 schedule_device 存储）。
     * <p>
     * 用 @ElementCollection + @CollectionTable 代替之前的逗号分隔列，
     * 好处：
     * <ul>
     *   <li>没有最多 10 个 ID 的限制（之前 CROSS JOIN + SUBSTRING_INDEX 受限于展开次数）</li>
     *   <li>findOverridingDeviceIds 可以写成简单 JOIN，不再需要原生 SQL</li>
     *   <li>类型安全：JPA 自动把 List&lt;Long&gt; 与关联表双向同步</li>
     * </ul>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "schedule_device",
                     joinColumns = @JoinColumn(name = "schedule_id"))
    @Column(name = "device_id")
    @OrderColumn(name = "device_order")
    private List<Long> deviceIds = new ArrayList<>();

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    /**
     * 动作目标路径/标识。
     * ON/OFF 时为 null；
     * OPEN_FILE 时存 Android 文件路径（如 /sdcard/Movies/gongnengke.mp4）；
     * OPEN_APP 时存包名或 component（如 com.example.app/.MainActivity 或 com.example.app）。
     */
    @Column(name = "target_path", length = 500)
    private String targetPath;

    /** Spring cron：秒 分 时 日 月 周 [年] */
    @Column(nullable = false, length = 50)
    private String cron;

    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 是否对所有启用设备生效。
     * <p>true → 执行时遍历所有 enabled 的设备，deviceIds / deviceId 被忽略
     * false → 执行时按 deviceIds 多台设备（或老的 deviceId 单台）执行
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
     * 获取"有效设备 ID 列表"。
     * targetAll=true 时返回空列表（语义由调用方决定：遍历所有启用设备）；
     * 否则返回 deviceIds 集合（按插入顺序）。
     */
    @Transient
    public List<Long> getDeviceIdList() {
        if (Boolean.TRUE.equals(targetAll)) return List.of();
        if (deviceIds != null && !deviceIds.isEmpty()) return deviceIds;
        // 回退到老的单 deviceId（兼容 schedule_device 还没迁移完成的老数据）
        if (deviceId != null && deviceId > 0) {
            return List.of(deviceId);
        }
        return List.of();
    }

    /**
     * 把 List<Long> 设置为 deviceIds，同时把 deviceId 同步为第一个元素或 0。
     */
    public void setDeviceIdList(List<Long> ids) {
        if (ids == null) ids = new ArrayList<>();
        this.deviceIds = new ArrayList<>(ids); // ElementCollection 需要可变列表
        this.deviceId = ids.isEmpty() ? 0L : ids.get(0);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (targetAll == null) targetAll = false;
        if (enabled == null) enabled = true;
        if (deviceId == null) deviceId = 0L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (targetAll == null) targetAll = false;
    }

    @PostLoad
    void onLoad() {
        if (targetAll == null) targetAll = false;
        if (deviceId == null) deviceId = 0L;
        if (enabled == null) enabled = true;
        if (deviceIds == null) deviceIds = new ArrayList<>();
    }

    /**
     * 是否"对所有设备"生效。
     */
    public boolean isForAllDevices() {
        return Boolean.TRUE.equals(targetAll);
    }
}
