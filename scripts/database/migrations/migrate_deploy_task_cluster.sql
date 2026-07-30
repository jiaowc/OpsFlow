-- deploy_task 增加上线集群与 namespace
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'cluster_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `deploy_task` ADD COLUMN `cluster_id` bigint NULL COMMENT ''上线集群ID'' AFTER `deploy_envs`, ADD KEY `idx_task_cluster` (`cluster_id`)'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'k8s_namespace'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `deploy_task` ADD COLUMN `k8s_namespace` varchar(200) NULL COMMENT ''上线命名空间'' AFTER `cluster_id`'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
