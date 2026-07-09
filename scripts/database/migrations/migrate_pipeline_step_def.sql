-- 可复用 Pipeline 步骤定义表
CREATE TABLE IF NOT EXISTS `pipeline_step_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '步骤名称',
  `step_type` varchar(50) NOT NULL COMMENT '步骤类型',
  `phase` varchar(10) NOT NULL COMMENT '阶段：ci/cd',
  `description` varchar(500) COMMENT '描述',
  `content_config` text COMMENT '自定义内容JSON',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_phase_type` (`phase`, `step_type`)
) COMMENT='Pipeline步骤定义（可复用）';

ALTER TABLE `pipeline_step_def` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
