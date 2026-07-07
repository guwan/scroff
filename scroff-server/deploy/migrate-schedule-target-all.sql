-- ============================================================
-- Scroff 迁移脚本：修复 schedule.target_all 列
-- ============================================================
-- 适用：从旧版本（<targetAll 字段时代）升级上来的 scroff-server
--
-- 背景：
--   schedule 表加 target_all 列后，可能存在：
--   1. 列根本不存在（ddl-auto 没跑过）→ 由 Hibernate 自动加
--   2. 列存在但没 NOT NULL DEFAULT 约束 → 老行这一列是 NULL
--   3. schedule.device_id 之前有 FK 到 device(id) → 删 FK 才能存 device_id=0
--
-- 这个脚本：
--   A. 移除 schedule.device_id 上的外键约束（如果存在）
--   B. 加上 target_all 列（如果不存在）
--   C. 把老数据 target_all=NULL 归一为 FALSE（单台模式 = 老的默认行为）
--   D. 把 target_all 列设为 NOT NULL DEFAULT 0
--
-- 用法：
--   mysql -h<host> -u<user> -p scroff < migrate-schedule-target-all.sql
--
-- 注意：执行前建议先备份！
--   mysqldump scroff schedule > schedule-backup-$(date +%Y%m%d).sql
-- ============================================================

-- ---------- A. 移除可能存在的外键约束 ----------
-- 不同 MariaDB 版本约束名可能略不同，先看再删
SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'schedule'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
      AND CONSTRAINT_NAME = 'fk_schedule_device'
);

SET @stmt := IF(@fk_exists > 0,
    'ALTER TABLE `schedule` DROP FOREIGN KEY `fk_schedule_device`',
    'SELECT "FK fk_schedule_device 不存在，跳过 DROP" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- B. 加 target_all 列（如果不存在） ----------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'schedule'
      AND COLUMN_NAME = 'target_all'
);

SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE `schedule` ADD COLUMN `target_all` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''0=单台；1=所有设备''',
    'SELECT "列 target_all 已存在，跳过 ADD COLUMN" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- C. 老数据 target_all=NULL 归一为 0 ----------
-- （如果第 B 步是新建的列，老行会拿 DEFAULT 0，不会是 NULL；
--   但如果列之前就有但没 DEFAULT，老行可能是 NULL。这里兜底。）
UPDATE `schedule` SET `target_all` = 0 WHERE `target_all` IS NULL;

-- ---------- D. 加索引（如果不存在） ----------
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'schedule'
      AND INDEX_NAME = 'idx_schedule_target_all'
);

SET @stmt := IF(@idx_exists = 0,
    'ALTER TABLE `schedule` ADD KEY `idx_schedule_target_all` (`target_all`)',
    'SELECT "索引 idx_schedule_target_all 已存在，跳过" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- E. 加 device_id 默认值（让单台模式改所有设备也能存 device_id=0） ----------
-- 注意：如果 device_id 之前是 NOT NULL 没默认，加上 DEFAULT 0 不会破坏现有数据
-- （现有行的 device_id 都 > 0，加 default 只影响后续 INSERT 不指定 device_id 的情况）
SET @col_default := (
    SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'schedule'
      AND COLUMN_NAME = 'device_id'
);

SET @stmt := IF(@col_default IS NULL,
    'ALTER TABLE `schedule` ALTER COLUMN `device_id` SET DEFAULT 0',
    'SELECT "device_id 已有 DEFAULT，跳过" AS msg');

PREPARE st FROM @stmt;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ---------- 验证 ----------
SELECT '迁移完成。当前 schedule 表的 target_all 分布：' AS info;
SELECT
    target_all,
    COUNT(*) AS cnt,
    CASE WHEN target_all = 1 THEN '所有设备' ELSE '单台' END AS scope
FROM `schedule`
GROUP BY target_all;

SELECT '迁移完成。前 10 条 schedule：' AS info;
SELECT id, name, device_id, target_all, action, enabled
FROM `schedule`
ORDER BY id DESC
LIMIT 10;
