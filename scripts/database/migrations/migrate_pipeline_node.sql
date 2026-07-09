-- Pipeline 模板增加 CI/CD 节点关联（可重复执行）
SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'build_node_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `pipeline` ADD COLUMN `build_node_id` bigint DEFAULT NULL COMMENT ''CI构建节点ID'' AFTER `parameter_definitions`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'deploy_node_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `pipeline` ADD COLUMN `deploy_node_id` bigint DEFAULT NULL COMMENT ''CD部署节点ID'' AFTER `build_node_id`'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
