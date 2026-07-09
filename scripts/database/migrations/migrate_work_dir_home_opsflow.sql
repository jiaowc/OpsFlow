-- 将节点远程工作目录默认路径更新为 ~/opsflow（兼容 build_node / jenkins_node）
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

SET @preparedStatement = CONCAT(
  'UPDATE `', @tablename, '` ',
  'SET `work_dir` = ''~/opsflow'' ',
  'WHERE `work_dir` IS NULL OR `work_dir` = '''' OR `work_dir` = ''/tmp/opsflow-workspace'''
);
PREPARE updateWorkDir FROM @preparedStatement;
EXECUTE updateWorkDir;
DEALLOCATE PREPARE updateWorkDir;

UPDATE `pipeline_step_def`
SET `content_config` = '{"workspaceBase":"~/opsflow","cleanDocker":"false"}'
WHERE `step_type` = 'clean_workspace';
