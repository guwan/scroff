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

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * 拼接 ADB 目标地址 host:port，供 adb -s 参数或 adb connect 用
     */
    public String getAddress() {
        return host + ":" + adbPort;
    }
}
