-- deploy_task 增加部署策略与并发数
SET @dbname = DATABASE();

SET @exist := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'deploy_mode'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE `deploy_task` ADD COLUMN `deploy_mode` varchar(20) NOT NULL DEFAULT ''serial'' COMMENT ''部署策略: serial串行 / parallel有限并行'' AFTER `pipeline_template_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'deploy_parallelism'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE `deploy_task` ADD COLUMN `deploy_parallelism` int NOT NULL DEFAULT 1 COMMENT ''并行部署并发数，serial 时为 1'' AFTER `deploy_mode`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
