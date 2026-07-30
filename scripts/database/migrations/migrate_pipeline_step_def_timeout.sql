-- 步骤定义增加超时时间（秒），默认 60
SET @dbname = DATABASE();

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline_step_def' AND COLUMN_NAME = 'timeout_seconds'
);

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `pipeline_step_def` ADD COLUMN `timeout_seconds` int NOT NULL DEFAULT 60 COMMENT ''步骤超时秒数'' AFTER `content_config`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `pipeline_step_def` SET `timeout_seconds` = 60 WHERE `timeout_seconds` IS NULL OR `timeout_seconds` < 1;
