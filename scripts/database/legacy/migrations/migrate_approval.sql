-- 审批流 / 审批记录 / 上线任务表（可重复执行）
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `approval_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '审批流名称',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `steps` text COMMENT '审批步骤 JSON',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_approval_flow_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批流定义';

CREATE TABLE IF NOT EXISTS `deploy_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_number` varchar(50) NOT NULL COMMENT '任务编号',
  `task_name` varchar(200) DEFAULT NULL COMMENT '任务名称',
  `deploy_modules` text COMMENT '上线模块 JSON',
  `deploy_envs` text COMMENT '部署环境 ID JSON',
  `approval_flow_id` bigint DEFAULT NULL COMMENT '关联审批流 ID',
  `approval_status` varchar(30) DEFAULT 'pending' COMMENT 'pending/approved/rejected/none',
  `task_status` varchar(30) DEFAULT 'pending' COMMENT '任务状态',
  `description` varchar(1000) DEFAULT NULL,
  `creator_id` bigint DEFAULT NULL,
  `creator_name` varchar(100) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deploy_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_number` (`task_number`),
  KEY `idx_approval_flow` (`approval_flow_id`),
  KEY `idx_approval_status` (`approval_status`),
  KEY `idx_task_status` (`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上线任务';

CREATE TABLE IF NOT EXISTS `approval_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL COMMENT '上线任务 ID',
  `task_number` varchar(50) DEFAULT NULL COMMENT '任务编号',
  `approval_flow_id` bigint DEFAULT NULL COMMENT '审批流 ID',
  `current_step` int NOT NULL COMMENT '步骤序号，从 1 开始',
  `approver` varchar(100) DEFAULT NULL COMMENT '审批人用户名',
  `approver_name` varchar(100) DEFAULT NULL COMMENT '审批人显示名',
  `approver_feishu_id` varchar(100) DEFAULT NULL COMMENT '飞书用户 ID',
  `status` varchar(30) NOT NULL DEFAULT 'waiting' COMMENT 'waiting/pending/approved/rejected/cancelled',
  `comment` varchar(1000) DEFAULT NULL COMMENT '审批意见',
  `approve_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_approver_status` (`approver`, `status`),
  KEY `idx_flow_id` (`approval_flow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批记录';
