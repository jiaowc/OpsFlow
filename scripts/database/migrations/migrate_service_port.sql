-- 服务表增加对外提供服务端口（可重复执行）
SET NAMES utf8mb4;

SET @dbname = DATABASE();
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'service_port'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `service` ADD COLUMN `service_port` int NULL COMMENT ''对外提供服务端口'' AFTER `service_type`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

UPDATE `service` SET `service_port` = 8080 WHERE `service_port` IS NULL;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'service_port' AND IS_NULLABLE = 'YES'
  ) > 0,
  'ALTER TABLE `service` MODIFY COLUMN `service_port` int NOT NULL COMMENT ''对外提供服务端口''',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
