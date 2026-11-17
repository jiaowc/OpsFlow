USE opsflow;
SET NAMES utf8mb4;

-- 「检查状态」「回滚部署」步骤：步骤超时要大于步骤内部 rollout 的等待时间（默认 300 秒），
-- 否则平台会先把命令终止，日志里只剩一句「步骤超时」。
-- 代码已做兜底（rollout 等待时间不超过步骤超时减去诊断预留），这里把默认值调到 330 秒，让 rollout 能等满 300 秒。
-- 可重复执行；只调大，不会覆盖已经配置得更大的值。
UPDATE `pipeline_step_def`
SET `timeout_seconds` = 330, `update_time` = NOW()
WHERE `step_type` IN ('check_deploy', 'rollback')
  AND (`timeout_seconds` IS NULL OR `timeout_seconds` < 330);

SELECT `id`, `name`, `step_type`, `timeout_seconds`, `content_config`
FROM `pipeline_step_def` WHERE `step_type` IN ('check_deploy', 'rollback') ORDER BY `id`;
