package com.scroff.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 系统级键值对配置。存运行时可改的全局开关。
 *
 * 用途：web UI 上能让用户切换的运行时参数（如激活的 ADB profile id），
 * 改完即时生效、重启不丢（不像 @ConfigurationProperties 那样需要重启）。
 *
 * 写入用 {@code save()} 即可（Hibernate 会按主键 upsert）。
 *
 * <p>注意：列名故意避开 MySQL/MariaDB 保留字（key / value），用 cfg_key / cfg_value。
 * 早期版本用 `key` / `value` 作为列名，导致 Hibernate 生成 DDL 时建表失败
 * （`key` 是保留字），运行时查表才报 "Table doesn't exist"。
 */
@Entity
@Table(name = "system_config")
@Getter
@Setter
@NoArgsConstructor
public class Settings {

    /** 配置项 key，长度上限 100，英文点分（例：scroff.adb.active-profile-id） */
    @Id
    @Column(name = "cfg_key", length = 100)
    private String key;

    /** 配置值，存字符串即可。复杂值（JSON/数字）由调用方序列化 */
    @Column(name = "cfg_value", length = 500)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
