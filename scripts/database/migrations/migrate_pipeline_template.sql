-- Pipeline 模版表（Dockerfile / Deployment / Service）
CREATE TABLE IF NOT EXISTS `pipeline_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '模版名称',
  `type` varchar(30) NOT NULL COMMENT '模版类型：dockerfile/deployment/service',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `content` mediumtext NOT NULL COMMENT '模版内容',
  `base_image` varchar(200) DEFAULT NULL COMMENT 'Dockerfile 基础镜像',
  `env_id` bigint DEFAULT NULL COMMENT '已废弃：模版与步骤关联，不再关联环境',
  `service_type` varchar(50) DEFAULT NULL COMMENT 'Service 类型',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`type`, `status`)
) COMMENT='Pipeline 模版（Dockerfile/Deployment/Service）';

ALTER TABLE `pipeline_template` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
