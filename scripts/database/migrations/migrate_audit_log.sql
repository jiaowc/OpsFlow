-- 操作审计日志 + audit:view 权限（可重复执行）
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '操作人 ID',
  `username` varchar(100) DEFAULT NULL COMMENT '操作人用户名',
  `module` varchar(50) DEFAULT NULL COMMENT '模块',
  `action` varchar(50) DEFAULT NULL COMMENT '动作',
  `http_method` varchar(10) DEFAULT NULL COMMENT 'HTTP 方法',
  `path` varchar(255) DEFAULT NULL COMMENT '请求路径',
  `target_id` varchar(100) DEFAULT NULL COMMENT '操作对象 ID',
  `request_summary` varchar(2000) DEFAULT NULL COMMENT '请求参数摘要（敏感字段已脱敏）',
  `success` tinyint NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  `status_code` int DEFAULT NULL COMMENT 'HTTP 状态码',
  `error_message` varchar(500) DEFAULT NULL COMMENT '失败原因',
  `client_ip` varchar(64) DEFAULT NULL COMMENT '客户端 IP',
  `duration_ms` bigint DEFAULT NULL COMMENT '耗时（毫秒）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_time` (`create_time`),
  KEY `idx_audit_user_time` (`username`, `create_time`),
  KEY `idx_audit_module_time` (`module`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看审计日志', 'audit:view', '查看平台操作审计日志', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'audit:view');

-- ADMIN 绑定全部权限（含新增）
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r CROSS JOIN `permission` p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
