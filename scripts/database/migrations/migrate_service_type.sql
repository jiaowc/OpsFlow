-- 服务表增加服务类型（可重复执行）
SET NAMES utf8mb4;

SET @dbname = DATABASE();
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'service_type'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `service` ADD COLUMN `service_type` varchar(20) DEFAULT ''backend'' COMMENT ''服务类型：backend/frontend/lib'' AFTER `default_branch`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

UPDATE `service` SET `service_type` = 'backend' WHERE `service_type` IS NULL OR TRIM(`service_type`) = '';
