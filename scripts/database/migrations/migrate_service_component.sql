-- 服务表增加 Git 组件关联与仓库路径（可重复执行）
SET NAMES utf8mb4;

SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'component_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `service` ADD COLUMN `component_id` bigint NULL COMMENT ''关联 Git 组件ID'' AFTER `git_repo`, ADD KEY `idx_component_id` (`component_id`)'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'git_repo_path'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `service` ADD COLUMN `git_repo_path` varchar(500) NULL COMMENT ''仓库路径（相对或完整）'' AFTER `component_id`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
