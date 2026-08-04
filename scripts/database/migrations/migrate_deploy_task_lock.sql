-- 上线任务协作锁定：locked / locked_by / locked_at
-- 可重复执行

SET @db := DATABASE();

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'locked'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `deploy_task` ADD COLUMN `locked` tinyint NOT NULL DEFAULT 0 COMMENT ''是否锁定：0未锁定 1已锁定'' AFTER `task_status`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'locked_by'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `deploy_task` ADD COLUMN `locked_by` varchar(100) DEFAULT NULL COMMENT ''锁定人用户名'' AFTER `locked`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'locked_at'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `deploy_task` ADD COLUMN `locked_at` datetime DEFAULT NULL COMMENT ''锁定时间'' AFTER `locked_by`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 已进入审批/部署的历史任务视为已锁定，避免中途被编辑
UPDATE `deploy_task`
SET `locked` = 1,
    `locked_by` = IFNULL(`locked_by`, `creator_name`),
    `locked_at` = IFNULL(`locked_at`, IFNULL(`update_time`, `create_time`))
WHERE `locked` = 0
  AND (
    `approval_status` IN ('pending', 'approved')
    OR `task_status` IN ('deploying', 'building', 'success', 'failed')
  );

-- 解锁特权权限（创建人始终可解自己的锁；持有本权限可解他人锁）
INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '解锁上线任务', 'deploy:unlock', '解除他人锁定的上线任务', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'deploy:unlock');

-- ADMIN 绑定全部权限（含新增）
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r CROSS JOIN `permission` p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
