-- ============================================================
-- Scroff Server 数据库 schema（MariaDB）
-- 字符集：utf8mb4 / 排序规则：utf8mb4_unicode_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS `scroff`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `scroff`;

-- ------------------------------------------------------------
-- device：叫号机设备
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `device`;
CREATE TABLE `device` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(100) NOT NULL COMMENT '设备名（业务标签）',
    `host`          VARCHAR(64)  NOT NULL COMMENT 'ADB 目标 IP 或主机名',
    `adb_port`      INT          NOT NULL DEFAULT 5555 COMMENT 'ADB 端口',
    `serial`        VARCHAR(100)          DEFAULT NULL COMMENT 'adb devices 输出序列号，可空',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE / OFFLINE / ERROR',
    `last_seen_at`  TIMESTAMP    NULL     DEFAULT NULL,
    `last_error`    VARCHAR(500)          DEFAULT NULL,
    `location`      VARCHAR(200)          DEFAULT NULL COMMENT '物理位置（窗口1/大厅）',
    `category`      VARCHAR(50)           DEFAULT NULL COMMENT '设备分类（窗口机/取号机/大屏/自助终端等）',
    `notes`         TEXT                  DEFAULT NULL,
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '排序，数字越小越靠前',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_host_port` (`host`, `adb_port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='叫号机设备';

-- ------------------------------------------------------------
-- schedule：定时任务（关屏 / 开屏）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_device`;
DROP TABLE IF EXISTS `schedule`;
CREATE TABLE `schedule` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `device_id`        BIGINT       NOT NULL DEFAULT 0 COMMENT '向后兼容字段：旧版单台模式主设备ID；多设备模式下同步为 schedule_device 第一个元素',
    `name`             VARCHAR(100) NOT NULL COMMENT '业务名（"晚间关屏"）',
    `action`           VARCHAR(20)  NOT NULL COMMENT 'OFF / ON / OPEN_FILE / OPEN_APP',
    `cron`             VARCHAR(50)  NOT NULL COMMENT 'Spring 6 字段 cron：秒 分 时 日 月 周',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1,
    `target_all`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=指定设备（按 schedule_device 关联表）；1=所有启用设备',
    `target_path`      VARCHAR(500)          DEFAULT NULL COMMENT 'OPEN_FILE/OPEN_APP 的目标路径或包名',
    `last_run_at`      TIMESTAMP    NULL DEFAULT NULL,
    `last_run_status`  VARCHAR(20)           DEFAULT NULL COMMENT 'SUCCESS / FAILED',
    `last_run_message` VARCHAR(500)          DEFAULT NULL,
    `created_at`       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_schedule_device` (`device_id`),
    KEY `idx_schedule_enabled` (`enabled`),
    KEY `idx_schedule_target_all` (`target_all`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时开关屏任务';

-- schedule_device：多设备关联表（ElementCollection）
-- 一条 schedule 可以关联任意数量的设备，没有 10 个的限制
CREATE TABLE `schedule_device` (
    `schedule_id`  BIGINT NOT NULL,
    `device_id`    BIGINT NOT NULL,
    `device_order` INT    NOT NULL DEFAULT 0 COMMENT '保持设备选择顺序',
    PRIMARY KEY (`schedule_id`, `device_id`),
    KEY `idx_sd_device` (`device_id`),
    CONSTRAINT `fk_sd_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务-设备关联表（多对多）';

-- ------------------------------------------------------------
-- screen_log：执行历史（仅保留最近 N 天可在应用层裁剪）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `screen_log`;
CREATE TABLE `screen_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `device_id`      BIGINT       NOT NULL,
    `device_name`    VARCHAR(100) NOT NULL COMMENT '冗余存储，避免 device 改名后日志看不懂',
    `device_address` VARCHAR(100)          DEFAULT NULL COMMENT '冗余 host:port，便于同名设备区分',
    `action`         VARCHAR(20)  NOT NULL COMMENT 'OFF / ON / OPEN_FILE / OPEN_APP',
    `trigger_type`   VARCHAR(20)  NOT NULL COMMENT 'SCHEDULE / MANUAL / API',
    `schedule_id`    BIGINT                DEFAULT NULL COMMENT 'SCHEDULE 触发时关联的 schedule.id',
    `schedule_name`  VARCHAR(100)          DEFAULT NULL COMMENT '冗余 schedule 当时的名字',
    `success`        TINYINT(1)   NOT NULL,
    `message`        VARCHAR(1000)         DEFAULT NULL,
    `duration_ms`    INT                  DEFAULT NULL COMMENT '执行 adb 命令耗时（毫秒）',
    `executed_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_log_device_time` (`device_id`, `executed_at`),
    KEY `idx_log_time` (`executed_at`),
    KEY `idx_log_schedule` (`schedule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='屏幕控制执行日志';

-- ------------------------------------------------------------
-- system_config：系统级键值对配置（运行时可改，重启不丢）
-- 例：scroff.adb.active-profile-id = android-sdk
-- 列名避开 MySQL 保留字（key/value），用 cfg_key/cfg_value
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `system_config`;
CREATE TABLE `system_config` (
    `cfg_key`    VARCHAR(100) NOT NULL,
    `cfg_value`  VARCHAR(500)          DEFAULT NULL,
    `updated_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统级键值对配置（运行时可改）';
