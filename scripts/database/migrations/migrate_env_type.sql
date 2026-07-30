-- 为环境表增加生产/非生产类型字段（可重复执行）
SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'env' AND COLUMN_NAME = 'env_type'
  ) = 0,
  'ALTER TABLE `env` ADD COLUMN `env_type` varchar(20) NOT NULL DEFAULT ''nonprod'' COMMENT ''环境类型：prod/nonprod'' AFTER `k8s_namespace`',
  'SELECT 1'
));
PREPARE addIfMissing FROM @preparedStatement;
EXECUTE addIfMissing;
DEALLOCATE PREPARE addIfMissing;

UPDATE `env`
SET `env_type` = 'prod'
WHERE LOWER(TRIM(COALESCE(`name`, ''))) = 'prod';

UPDATE `env`
SET `env_type` = 'nonprod'
WHERE `env_type` IS NULL
   OR TRIM(`env_type`) = ''
   OR LOWER(TRIM(`env_type`)) NOT IN ('prod', 'nonprod');
