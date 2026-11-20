-- 删除代码已不再使用的字段，可重复执行
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'commit_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job` DROP COLUMN `commit_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'build_log_url');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job` DROP COLUMN `build_log_url`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'log_text');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` DROP COLUMN `log_text`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'pipeline_parameters');
SET @sql := IF(@exist > 0, 'ALTER TABLE `deploy_task` DROP COLUMN `pipeline_parameters`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'script');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline` DROP COLUMN `script`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_template' AND COLUMN_NAME = 'env_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_template` DROP COLUMN `env_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'service' AND COLUMN_NAME = 'project_name');
SET @sql := IF(@exist > 0, 'ALTER TABLE `service` DROP COLUMN `project_name`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'env' AND COLUMN_NAME = 'k8s_cluster');
SET @sql := IF(@exist > 0, 'ALTER TABLE `env` DROP COLUMN `k8s_cluster`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'build_node');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job` DROP COLUMN `build_node`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'permission' AND COLUMN_NAME = 'resource');
SET @sql := IF(@exist > 0, 'ALTER TABLE `permission` DROP COLUMN `resource`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'permission' AND COLUMN_NAME = 'method');
SET @sql := IF(@exist > 0, 'ALTER TABLE `permission` DROP COLUMN `method`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'build_node_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline` DROP COLUMN `build_node_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
