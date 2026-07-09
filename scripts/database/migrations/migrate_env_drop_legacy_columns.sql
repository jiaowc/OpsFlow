-- 删除环境表遗留字段（可重复执行）
SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'env' AND COLUMN_NAME = 'harbor_project'
  ) > 0,
  'ALTER TABLE `env` DROP COLUMN `harbor_project`',
  'SELECT 1'
));
PREPARE dropIfExists FROM @preparedStatement;
EXECUTE dropIfExists;
DEALLOCATE PREPARE dropIfExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'env' AND COLUMN_NAME = 'jenkins_job_template'
  ) > 0,
  'ALTER TABLE `env` DROP COLUMN `jenkins_job_template`',
  'SELECT 1'
));
PREPARE dropIfExists FROM @preparedStatement;
EXECUTE dropIfExists;
DEALLOCATE PREPARE dropIfExists;
