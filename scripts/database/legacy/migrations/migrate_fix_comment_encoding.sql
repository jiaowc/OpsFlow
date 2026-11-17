-- 修复早期迁移以错误字符集执行导致的乱码注释（可重复执行，仅改注释）
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'task_name');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job` MODIFY COLUMN `task_name` varchar(200) DEFAULT NULL COMMENT ''任务名称''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'build_job_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `build_job_id` bigint NOT NULL COMMENT ''构建任务ID''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'step_order');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `step_order` int NOT NULL COMMENT ''步骤顺序''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'step_type');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `step_type` varchar(50) NOT NULL COMMENT ''步骤类型''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'step_name');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `step_name` varchar(100) NOT NULL COMMENT ''步骤名称''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'duration_ms');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `duration_ms` bigint DEFAULT ''0'' COMMENT ''耗时毫秒''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'error_message');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `error_message` text COMMENT ''错误信息''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'start_time');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `start_time` datetime DEFAULT NULL COMMENT ''开始时间''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'end_time');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` MODIFY COLUMN `end_time` datetime DEFAULT NULL COMMENT ''结束时间''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'deploy_node_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline` MODIFY COLUMN `deploy_node_id` bigint DEFAULT NULL COMMENT ''CD部署节点ID''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'name');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` MODIFY COLUMN `name` varchar(100) NOT NULL COMMENT ''步骤名称''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'step_type');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` MODIFY COLUMN `step_type` varchar(50) NOT NULL COMMENT ''步骤类型''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'phase');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` MODIFY COLUMN `phase` varchar(10) NOT NULL COMMENT ''阶段：ci/cd''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'description');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` MODIFY COLUMN `description` varchar(500) DEFAULT NULL COMMENT ''描述''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'content_config');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` MODIFY COLUMN `content_config` text COMMENT ''自定义内容JSON''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'status');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` MODIFY COLUMN `status` tinyint DEFAULT ''1'' COMMENT ''1-启用 0-禁用''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage');
SET @sql := IF(@exist > 0, 'ALTER TABLE `build_job_stage` COMMENT = ''构建任务阶段表''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def');
SET @sql := IF(@exist > 0, 'ALTER TABLE `pipeline_step_def` COMMENT = ''Pipeline步骤定义（可复用）''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
