-- schedule 和 screen_log 表：支持 open_file / open_app 新动作
-- 执行：MariaDB / MySQL 5.7+

-- ============ schedule 表 ============
-- 1. 把 action 列从 VARCHAR(10) 扩到 VARCHAR(20)，存下 OPEN_FILE / OPEN_APP
ALTER TABLE `schedule` MODIFY COLUMN `action` VARCHAR(20) NOT NULL COMMENT 'OFF / ON / OPEN_FILE / OPEN_APP';

-- 2. 新增 target_path 列（OPEN_FILE 时存 Android 文件路径，OPEN_APP 时存包名或 component）
ALTER TABLE `schedule` ADD COLUMN `target_path` VARCHAR(500) NULL DEFAULT NULL COMMENT 'OPEN_FILE/OPEN_APP 的目标路径或包名' AFTER `action`;

-- ============ screen_log 表 ============
-- 把 action 列从 VARCHAR(10) 扩到 VARCHAR(20)，支持 OPEN_FILE / OPEN_APP 日志写入
ALTER TABLE `screen_log` MODIFY COLUMN `action` VARCHAR(20) NOT NULL COMMENT 'OFF / ON / OPEN_FILE / OPEN_APP';
