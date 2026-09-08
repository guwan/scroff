-- ============================================================
-- Scroff 迁移脚本：扩展 screen_log 表
-- ============================================================
-- 适用：从旧版本（screen_log 只有 id/device_id/device_name/action/
--   trigger_type/success/message/executed_at）升级上来的 scroff-server
--
-- 新增 4 个字段（都可空，老数据保持 NULL，应用层兼容）：
--   device_address  冗余 host:port
--   schedule_id     SCHEDULE 触发时关联的 schedule.id
--   schedule_name   冗余 schedule 当时的名字
--   duration_ms     执行 adb 命令耗时（毫秒）
-- 新增 1 个索引：idx_log_schedule
--
-- 用法：
--   mysql -h<host> -u<user> -p scroff < migrate-screen-log-extended.sql
--
-- 注意：执行前建议先备份！
--   mysqldump scroff screen_log > screen-log-backup-$(date +%Y%m%d).sql
-- ============================================================

-- ---------- A. 加 device_address 列（如果不存在） ----------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'screen_log'
      AND COLUMN_NAME = 'device_address'
);

SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE `screen_log` ADD COLUMN `device_address` VARCHAR(100) DEFAULT NULL COMMENT ''冗余 host:port''',
    'SELECT "列 device_address 已存在，跳过 ADD COLUMN" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- B. 加 schedule_id 列（如果不存在） ----------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'screen_log'
      AND COLUMN_NAME = 'schedule_id'
);

SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE `screen_log` ADD COLUMN `schedule_id` BIGINT DEFAULT NULL COMMENT ''SCHEDULE 触发时关联的 schedule.id''',
    'SELECT "列 schedule_id 已存在，跳过 ADD COLUMN" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- C. 加 schedule_name 列（如果不存在） ----------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'screen_log'
      AND COLUMN_NAME = 'schedule_name'
);

SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE `screen_log` ADD COLUMN `schedule_name` VARCHAR(100) DEFAULT NULL COMMENT ''冗余 schedule 当时的名字''',
    'SELECT "列 schedule_name 已存在，跳过 ADD COLUMN" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- D. 加 duration_ms 列（如果不存在） ----------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'screen_log'
      AND COLUMN_NAME = 'duration_ms'
);

SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE `screen_log` ADD COLUMN `duration_ms` INT DEFAULT NULL COMMENT ''执行 adb 命令耗时（毫秒）''',
    'SELECT "列 duration_ms 已存在，跳过 ADD COLUMN" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- E. 加 idx_log_schedule 索引（如果不存在） ----------
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'screen_log'
      AND INDEX_NAME = 'idx_log_schedule'
);

SET @stmt := IF(@idx_exists = 0,
    'ALTER TABLE `screen_log` ADD KEY `idx_log_schedule` (`schedule_id`)',
    'SELECT "索引 idx_log_schedule 已存在，跳过" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- 验证 ----------
SELECT '迁移完成。当前 screen_log 表的列：' AS info;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'screen_log'
ORDER BY ORDINAL_POSITION;

SELECT '迁移完成。当前 screen_log 表的索引：' AS info;
SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'screen_log'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
