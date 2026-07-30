-- build_job 热查询索引（幂等）
SET @dbname = DATABASE();

SET @exist := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND INDEX_NAME = 'idx_env_create_time'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE `build_job` ADD INDEX `idx_env_create_time` (`env_id`, `create_time`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND INDEX_NAME = 'idx_svc_env_status_ctime'
);
SET @sql := IF(@exist = 0,
  'ALTER TABLE `build_job` ADD INDEX `idx_svc_env_status_ctime` (`service_id`, `env_id`, `status`, `create_time`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
