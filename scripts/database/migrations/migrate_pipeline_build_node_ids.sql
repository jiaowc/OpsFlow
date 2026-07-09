-- pipeline 增加 build_node_ids，支持多个 CI 构建节点候选
SET @dbname = DATABASE();

SET @exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'pipeline' AND COLUMN_NAME = 'build_node_ids'
);

SET @sql = IF(@exists = 0,
    'ALTER TABLE `pipeline` ADD COLUMN `build_node_ids` varchar(500) COMMENT ''CI构建节点ID列表，逗号分隔'' AFTER `build_node_id`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `pipeline`
SET `build_node_ids` = CAST(`build_node_id` AS CHAR)
WHERE (`build_node_ids` IS NULL OR TRIM(`build_node_ids`) = '')
  AND `build_node_id` IS NOT NULL;
