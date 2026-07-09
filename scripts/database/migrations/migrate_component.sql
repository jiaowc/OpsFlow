-- 组件管理表迁移（已有 opsflow 库时单独执行）
CREATE TABLE IF NOT EXISTS `component` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '组件名称',
  `type` varchar(50) NOT NULL COMMENT '组件类型：jenkins/harbor/gitlab等',
  `url` varchar(500) COMMENT '访问地址',
  `auth_type` varchar(50) COMMENT '认证类型',
  `auth_config` text COMMENT '认证信息JSON',
  `description` varchar(500) COMMENT '描述',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`type`, `status`)
) COMMENT='组件配置表';
