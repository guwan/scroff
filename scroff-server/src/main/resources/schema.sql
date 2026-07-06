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
DROP TABLE IF EXISTS `schedule`;
CREATE TABLE `schedule` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `device_id`        BIGINT      NOT NULL,
    `name`             VARCHAR(100) NOT NULL COMMENT '业务名（"晚间关屏"）',
    `action`           VARCHAR(10)  NOT NULL COMMENT 'OFF / ON',
    `cron`             VARCHAR(50)  NOT NULL COMMENT 'Spring 6 字段 cron：秒 分 时 日 月 周',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1,
    `last_run_at`      TIMESTAMP    NULL DEFAULT NULL,
    `last_run_status`  VARCHAR(20)           DEFAULT NULL COMMENT 'SUCCESS / FAILED',
    `last_run_message` VARCHAR(500)          DEFAULT NULL,
    `created_at`       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_schedule_device` (`device_id`),
    KEY `idx_schedule_enabled` (`enabled`),
    CONSTRAINT `fk_schedule_device` FOREIGN KEY (`device_id`)
        REFERENCES `device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时开关屏任务';

-- ------------------------------------------------------------
-- screen_log：执行历史（仅保留最近 N 天可在应用层裁剪）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `screen_log`;
CREATE TABLE `screen_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `device_id`     BIGINT       NOT NULL,
    `device_name`   VARCHAR(100) NOT NULL COMMENT '冗余存储，避免 device 改名后日志看不懂',
    `action`        VARCHAR(10)  NOT NULL COMMENT 'OFF / ON',
    `trigger_type`  VARCHAR(20)  NOT NULL COMMENT 'SCHEDULE / MANUAL / API',
    `success`       TINYINT(1)   NOT NULL,
    `message`       VARCHAR(1000)         DEFAULT NULL,
    `executed_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_log_device_time` (`device_id`, `executed_at`),
    KEY `idx_log_time` (`executed_at`)
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
