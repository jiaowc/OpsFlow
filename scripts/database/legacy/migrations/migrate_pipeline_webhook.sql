-- 流水线任务的 Git Webhook 自动构建开关（可重复执行）
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `pipeline_webhook_trigger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `anchor_job_id` bigint NOT NULL COMMENT '流水线任务（任务组首条 build_job）ID',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1 推送代码时自动构建 0 关闭',
  `update_by` varchar(100) DEFAULT NULL COMMENT '最后修改人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_webhook_anchor` (`anchor_job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水线 Git Webhook 自动构建开关';
