-- 为 build_job 表添加缺失的字段
-- 执行前请先检查字段是否已存在，如果已存在请跳过
-- 此脚本兼容 MySQL 5.7+

-- 检查并添加 pipeline_template_id 字段
SET @dbname = DATABASE();
SET @tablename = 'build_job';
SET @columnname = 'pipeline_template_id';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` bigint COMMENT ''Pipeline模板ID'' AFTER `update_time`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 build_parameters 字段
SET @columnname = 'build_parameters';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` text COMMENT ''构建参数（JSON格式）'' AFTER `pipeline_template_id`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 如果上面的动态SQL执行失败，可以使用下面的简单方式（需要手动检查字段是否存在）
-- ALTER TABLE `build_job` 
-- ADD COLUMN `pipeline_template_id` bigint COMMENT 'Pipeline模板ID' AFTER `update_time`;

-- ALTER TABLE `build_job` 
-- ADD COLUMN `build_parameters` text COMMENT '构建参数（JSON格式）' AFTER `pipeline_template_id`;
