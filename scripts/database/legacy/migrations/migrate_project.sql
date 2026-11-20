-- 项目（多团队隔离）：project / project_member，service 与 deploy_task 归属项目（可重复执行）
-- 迁移规则：已有 service.project_name 自动建为项目，未填写的服务归入「默认项目」；
-- 为保持升级前「所有人可见」的行为，现有用户都加入迁移出的项目（开发角色），之后由管理员按需调整。
SET NAMES utf8mb4;
SET @dbname = DATABASE();

CREATE TABLE IF NOT EXISTS `project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '项目名称',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `owner_user_id` bigint DEFAULT NULL COMMENT '负责人',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目';

CREATE TABLE IF NOT EXISTS `project_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role` varchar(20) NOT NULL DEFAULT 'DEVELOPER' COMMENT 'ADMIN项目管理员 / DEVELOPER开发 / VIEWER只读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`, `user_id`),
  KEY `idx_project_member_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员';

-- 与 service 表排序规则一致（服务器默认 utf8mb4_0900_ai_ci 时 JOIN 项目名会报 Illegal mix of collations）
SET @coll := (SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'project');
SET @sql := IF(@coll <> 'utf8mb4_unicode_ci', 'ALTER TABLE `project` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @coll := (SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'project_member');
SET @sql := IF(@coll <> 'utf8mb4_unicode_ci', 'ALTER TABLE `project_member` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'project_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE `service` ADD COLUMN `project_id` bigint DEFAULT NULL COMMENT ''所属项目'' AFTER `id`, ADD KEY `idx_service_project` (`project_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'project_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE `deploy_task` ADD COLUMN `project_id` bigint DEFAULT NULL COMMENT ''所属项目；为空表示升级前的历史任务'' AFTER `id`, ADD KEY `idx_deploy_task_project` (`project_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 已填写 project_name 的服务 → 同名项目（老版本才有 project_name 列，新库跳过）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'project_name');
SET @sql := IF(@exist > 0,
  'INSERT INTO `project` (`name`, `description`, `status`)
   SELECT DISTINCT TRIM(s.project_name), ''由服务的项目名称自动迁移'', 1
   FROM `service` s
   WHERE s.project_id IS NULL AND s.project_name IS NOT NULL AND TRIM(s.project_name) <> ''''
     AND NOT EXISTS (SELECT 1 FROM `project` p WHERE p.name = TRIM(s.project_name))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@exist > 0,
  'UPDATE `service` s JOIN `project` p ON p.name = TRIM(s.project_name)
   SET s.project_id = p.id
   WHERE s.project_id IS NULL AND s.project_name IS NOT NULL AND TRIM(s.project_name) <> ''''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 其余服务 → 默认项目
INSERT INTO `project` (`name`, `description`, `status`)
SELECT '默认项目', '升级时未归属项目的服务', 1 FROM DUAL
WHERE EXISTS (SELECT 1 FROM `service` WHERE project_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM `project` WHERE name = '默认项目');

UPDATE `service` s JOIN `project` p ON p.name = '默认项目'
SET s.project_id = p.id
WHERE s.project_id IS NULL;

-- 仅首次执行本迁移时（project:manage 权限尚未创建），把现有用户加入迁移出的项目，保持升级前的可见性；
-- 重复执行不会把管理员移除的成员加回来
SET @first_run := (SELECT COUNT(*) = 0 FROM `permission` WHERE `code` = 'project:manage');
INSERT INTO `project_member` (`project_id`, `user_id`, `role`)
SELECT p.id, u.id, 'DEVELOPER'
FROM `project` p CROSS JOIN `user` u
WHERE @first_run = 1
  AND NOT EXISTS (SELECT 1 FROM `project_member` pm WHERE pm.project_id = p.id AND pm.user_id = u.id);

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '项目管理（平台级）', 'project:manage', '创建/删除项目，查看并管理所有项目', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'project:manage');

INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r CROSS JOIN `permission` p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
