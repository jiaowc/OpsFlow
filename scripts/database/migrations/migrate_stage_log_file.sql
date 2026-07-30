-- 阶段日志改为文件存储：DB 保留路径与末尾预览（可重复执行）
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'log_path'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `build_job_stage` ADD COLUMN `log_path` varchar(500) DEFAULT NULL COMMENT ''阶段日志相对路径'' AFTER `log_text`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job_stage' AND COLUMN_NAME = 'log_preview'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `build_job_stage` ADD COLUMN `log_preview` varchar(4000) DEFAULT NULL COMMENT ''日志末尾预览'' AFTER `log_path`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
