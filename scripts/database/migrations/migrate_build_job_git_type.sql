-- build_job 增加 git_type（从 service 迁移语义到流水线 Job）
SET @dbname = DATABASE();

SET @exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_job' AND COLUMN_NAME = 'git_type'
);
SET @sql = IF(@exists = 0,
    'ALTER TABLE `build_job` ADD COLUMN `git_type` varchar(20) DEFAULT ''branch'' COMMENT ''Git类型：branch/tag'' AFTER `branch`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `build_job` b
INNER JOIN `service` s ON b.service_id = s.id
SET b.git_type = CASE WHEN LOWER(TRIM(s.git_type)) = 'tag' THEN 'tag' ELSE 'branch' END
WHERE b.git_type IS NULL OR TRIM(b.git_type) = '';

UPDATE `build_job` SET `git_type` = 'branch' WHERE `git_type` IS NULL OR TRIM(`git_type`) = '';
