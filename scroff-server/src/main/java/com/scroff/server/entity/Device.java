package com.scroff.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 叫号机设备实体。
 *
 * 状态机：
 *   OFFLINE ──adb connect 成功──▶ ONLINE
 *   ONLINE  ──心跳失败──▶ OFFLINE
 *   任意状态 ──adb 返回非 0──▶ ERROR
 */
@Entity
@Table(name = "device", uniqueConstraints = {
        @UniqueConstraint(name = "uk_device_host_port", columnNames = {"host", "adb_port"})
})
@Getter
@Setter
@NoArgsConstructor
public class Device {

    public enum Status {
        ONLINE, OFFLINE, ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 64)
    private String host;

    @Column(name = "adb_port", nullable = false)
    private Integer adbPort = 5555;

    @Column(length = 100)
    private String serial;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OFFLINE;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(length = 200)
    private String location;

    @Column(length = 50)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 新建时由 JPA 回调设置 created_at / updated_at。
     * 不依赖 DB DEFAULT CURRENT_TIMESTAMP，避免 ddl-auto: update 时旧表缺 DEFAULT 报
     * "Field 'created_at' doesn't have a default value"。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 拼接 ADB 目标地址 host:port，供 adb -s 参数或 adb connect 用
     */
    public String getAddress() {
        return host + ":" + adbPort;
    }
}
