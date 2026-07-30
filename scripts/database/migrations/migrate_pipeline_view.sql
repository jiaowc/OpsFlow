CREATE TABLE IF NOT EXISTS `pipeline_view` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '视图名称，如 dev',
  `env_id` bigint NOT NULL COMMENT '关联环境 ID',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `sort_order` int DEFAULT 0 COMMENT '排序，越小越靠前',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_env_status` (`env_id`, `status`)
) COMMENT='流水线视图（按环境筛选任务，类似 Jenkins View）';
