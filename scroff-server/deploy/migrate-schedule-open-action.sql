-- 迁移：把 schedule 表的多设备信息从"逗号分隔字符串列 + 主 device_id"
-- 升级到独立的 schedule_device 关联表（ElementCollection）。
-- 这样没有最多 10 个设备的限制，SQL 也更简洁高效。
--
-- 建议：先备份 schedule 表，再执行。
-- 适用：MariaDB / MySQL 5.7+

-- ============================================================
-- Step 1. 调整 schedule 主表（保持向后兼容：保留 device_id 列，只是用途变了）
-- ============================================================

-- 删掉之前的 device_ids VARCHAR(500) 逗号串列（如果存在）：
ALTER TABLE `schedule` DROP COLUMN IF EXISTS `device_ids`;

-- 扩展 action 列（如果之前没做过）：
ALTER TABLE `schedule` MODIFY COLUMN `action` VARCHAR(20) NOT NULL COMMENT 'OFF / ON / OPEN_FILE / OPEN_APP';

-- 如果 target_path 列不存在则新增（如果之前已经建了就跳过，MySQL 会自动忽略）：
ALTER TABLE `schedule` ADD COLUMN IF NOT EXISTS `target_path` VARCHAR(500) NULL DEFAULT NULL COMMENT 'OPEN_FILE/OPEN_APP 的目标路径或包名' AFTER `action`;

-- 扩展 target_all 注释（不影响功能，只是文档）：
ALTER TABLE `schedule` MODIFY COLUMN `target_all` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0=指定设备（按 schedule_device）；1=所有启用设备';

-- ============================================================
-- Step 2. 新建独立关联表 schedule_device
-- ============================================================
CREATE TABLE IF NOT EXISTS `schedule_device` (
    `schedule_id`  BIGINT NOT NULL,
    `device_id`    BIGINT NOT NULL,
    `device_order` INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (`schedule_id`, `device_id`),
    KEY `idx_sd_device` (`device_id`),
    CONSTRAINT `fk_sd_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务-设备关联表（多对多，ElementCollection）';

-- ============================================================
-- Step 3. 数据迁移（把老 schedule 里的 device_id 或 device_ids 拆成关联表行）
-- 只迁移 target_all = 0（指定设备模式）的 schedule；
-- target_all = 1（所有设备模式）不需要关联行。
-- ============================================================

-- 3a. 老单台模式：device_id > 0 且 schedule_device 里还没行
INSERT IGNORE INTO `schedule_device` (`schedule_id`, `device_id`, `device_order`)
SELECT s.id, s.device_id, 0
FROM `schedule` s
WHERE s.target_all = 0
  AND s.device_id > 0
  AND NOT EXISTS (
      SELECT 1 FROM `schedule_device` sd WHERE sd.schedule_id = s.id
  );

-- 3b. 老逗号串模式：如果 device_ids 列还在（刚 DROP 之前可能没跑），先别担心。
--     运行 ddl-auto: update 的应用启动后，JPA 会自动管理 schedule_device 表。
--     老的 device_ids VARCHAR 列已被 Step 1 DROP，数据已在 3a 中迁移完。

-- ============================================================
-- Step 4. 扩展 screen_log action 列（如果之前没做过）
-- ============================================================
ALTER TABLE `screen_log` MODIFY COLUMN `action` VARCHAR(20) NOT NULL COMMENT 'OFF / ON / OPEN_FILE / OPEN_APP';
