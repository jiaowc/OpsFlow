-- 清理 Jenkins 遗留命名（可重复执行）
SET NAMES utf8mb4;
SET @dbname = DATABASE();

-- jenkins_node → build_node
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'jenkins_node'
  ) > 0
  AND (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_node'
  ) = 0,
  'RENAME TABLE `jenkins_node` TO `build_node`',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 删除节点表遗留 jenkins_url 列
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_node' AND COLUMN_NAME = 'jenkins_url'
  ) > 0,
  'ALTER TABLE `build_node` DROP COLUMN `jenkins_url`',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- build_job 字段重命名
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'jenkins_build_number'
  ) > 0
  AND (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'build_number'
  ) = 0,
  'ALTER TABLE `build_job` CHANGE COLUMN `jenkins_build_number` `build_number` int NULL COMMENT ''构建序号''',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'jenkins_node'
  ) > 0
  AND (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'build_node'
  ) = 0,
  'ALTER TABLE `build_job` CHANGE COLUMN `jenkins_node` `build_node` varchar(100) NULL COMMENT ''构建节点名称''',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'jenkins_job_name'
  ) > 0,
  'ALTER TABLE `build_job` DROP COLUMN `jenkins_job_name`',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- pipeline 删除 jenkins_job_template
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'jenkins_job_template'
  ) > 0,
  'ALTER TABLE `pipeline` DROP COLUMN `jenkins_job_template`',
  'SELECT 1'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
