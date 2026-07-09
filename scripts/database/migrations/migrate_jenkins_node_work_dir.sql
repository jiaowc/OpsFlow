-- 节点远程工作目录（兼容 build_node / jenkins_node）
SET @dbname = DATABASE();
SET @tablename = (
  SELECT CASE
    WHEN EXISTS (
      SELECT 1 FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_node'
    ) THEN 'build_node'
    ELSE 'jenkins_node'
  END
);
SET @columnname = 'work_dir';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname,
         '` varchar(500) DEFAULT ''~/opsflow'' COMMENT ''远程工作目录根路径'' AFTER `node_type`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = CONCAT(
  'UPDATE `', @tablename, '` SET `work_dir` = ''~/opsflow'' ',
  'WHERE `work_dir` IS NULL OR `work_dir` = '''' OR `work_dir` = ''/tmp/opsflow-workspace'''
);
PREPARE updateWorkDir FROM @preparedStatement;
EXECUTE updateWorkDir;
DEALLOCATE PREPARE updateWorkDir;
