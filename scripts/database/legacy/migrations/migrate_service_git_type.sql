-- 服务表增加 Git 类型（可重复执行）
SET NAMES utf8mb4;

SET @dbname = DATABASE();
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'git_type'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `service` ADD COLUMN `git_type` varchar(20) DEFAULT ''branch'' COMMENT ''Git类型：branch/tag'' AFTER `git_repo`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

UPDATE `service` SET `git_type` = 'branch' WHERE `git_type` IS NULL OR TRIM(`git_type`) = '';
