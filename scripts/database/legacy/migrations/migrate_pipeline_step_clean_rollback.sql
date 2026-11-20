-- 增量添加：清理空间、回滚部署步骤（可重复执行）
SET NAMES utf8mb4;

INSERT INTO `pipeline_step_def` (`name`, `step_type`, `phase`, `description`, `content_config`, `status`)
SELECT '清理空间', 'clean_workspace', 'ci',
       '清理构建节点工作目录，防止历史代码或缓存影响构建',
       '{"workspaceBase":"~/opsflow","cleanDocker":"false"}', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `pipeline_step_def` WHERE `step_type` = 'clean_workspace');

INSERT INTO `pipeline_step_def` (`name`, `step_type`, `phase`, `description`, `content_config`, `status`)
SELECT '回滚部署', 'rollback', 'cd',
       '将 K8s Deployment 回滚到上一版本或指定 revision',
       '{"waitRollout":"true","timeoutSeconds":"300"}', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `pipeline_step_def` WHERE `step_type` = 'rollback');
