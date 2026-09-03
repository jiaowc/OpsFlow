-- 服务表增加所属项目（可重复执行）
SET NAMES utf8mb4;

SET @dbname = DATABASE();
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'project_name'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `service` ADD COLUMN `project_name` varchar(100) DEFAULT NULL COMMENT ''所属项目'' AFTER `name`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @idxExists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND INDEX_NAME = 'idx_service_project_name'
);
SET @idxSql := IF(@idxExists = 0,
  'ALTER TABLE `service` ADD KEY `idx_service_project_name` (`project_name`)',
  'SELECT 1');
PREPARE stmt FROM @idxSql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
