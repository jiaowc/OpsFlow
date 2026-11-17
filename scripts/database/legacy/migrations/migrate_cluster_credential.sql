-- cluster 关联钥匙串（kubeconfig）
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'cluster' AND COLUMN_NAME = 'credential_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `cluster` ADD COLUMN `credential_id` bigint NULL COMMENT ''关联钥匙串ID（kubeconfig）'' AFTER `description`, ADD KEY `idx_cluster_credential_id` (`credential_id`)'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
