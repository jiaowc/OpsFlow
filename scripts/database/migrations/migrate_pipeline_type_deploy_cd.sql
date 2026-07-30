-- pipeline 增加流水线类型；deploy_task / build_job 关联 CD 执行

SET @dbname = DATABASE();

-- pipeline.pipeline_type: ci / cd / cicd
SET @tablename = 'pipeline';
SET @columnname = 'pipeline_type';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname) > 0,
  'SELECT 1',
  'ALTER TABLE `pipeline` ADD COLUMN `pipeline_type` varchar(20) NOT NULL DEFAULT ''cicd'' COMMENT ''流水线类型: ci/cd/cicd'' AFTER `name`, ADD KEY `idx_pipeline_type` (`pipeline_type`)'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `pipeline` SET `pipeline_type` = 'cicd' WHERE `pipeline_type` IS NULL OR `pipeline_type` = '';

-- deploy_task.pipeline_template_id
SET @tablename = 'deploy_task';
SET @columnname = 'pipeline_template_id';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname) > 0,
  'SELECT 1',
  'ALTER TABLE `deploy_task` ADD COLUMN `pipeline_template_id` bigint NULL COMMENT ''CD 流水线模版ID'' AFTER `approval_flow_id`, ADD KEY `idx_task_pipeline` (`pipeline_template_id`)'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- build_job.deploy_task_id
SET @tablename = 'build_job';
SET @columnname = 'deploy_task_id';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname) > 0,
  'SELECT 1',
  'ALTER TABLE `build_job` ADD COLUMN `deploy_task_id` bigint NULL COMMENT ''关联上线任务ID'' AFTER `pipeline_template_id`, ADD KEY `idx_build_deploy_task` (`deploy_task_id`)'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
