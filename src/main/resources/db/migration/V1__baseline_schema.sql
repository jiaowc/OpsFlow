-- OpsFlow 基线：全部表结构（28 张表）
--
-- 由 mysqldump --no-data 从迁移到位的库导出，等价于旧的 schema.sql + 全部 legacy 迁移执行后的结果。
-- 已有的库不会执行本文件（Flyway 基线版本为 2）；只有空库会从这里开始建表。
-- 之后的表结构变更一律新增 V3 起的版本文件，不要修改本文件。

CREATE TABLE `approval_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批流名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '描述',
  `steps` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '审批步骤JSON，格式：[{"step":1,"approver":"user1","required":true},...]',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流表';
CREATE TABLE `approval_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL COMMENT '任务ID（deploy_task.id）',
  `task_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务编号',
  `approval_flow_id` bigint NOT NULL COMMENT '审批流ID',
  `current_step` int DEFAULT '1' COMMENT '当前审批步骤',
  `approver` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人用户名；为空且无候选审批人时任意登录用户可审批',
  `approver_name` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人显示名',
  `approver_feishu_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人飞书用户ID',
  `approver_candidates` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '候选审批人，格式 ,user1,user2, ；任一人审批即可',
  `feishu_message_id` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书审批卡片消息 ID，多个以逗号分隔',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'pending' COMMENT '审批状态：pending, approved, rejected, cancelled',
  `comment` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '审批意见',
  `approve_time` datetime DEFAULT NULL COMMENT '审批时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_task_id` (`task_id`) USING BTREE,
  KEY `idx_approval_flow_id` (`approval_flow_id`) USING BTREE,
  KEY `idx_approver` (`approver`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批记录表';
CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '操作人 ID',
  `username` varchar(100) DEFAULT NULL COMMENT '操作人用户名',
  `module` varchar(50) DEFAULT NULL COMMENT '模块',
  `action` varchar(50) DEFAULT NULL COMMENT '动作',
  `http_method` varchar(10) DEFAULT NULL COMMENT 'HTTP 方法',
  `path` varchar(255) DEFAULT NULL COMMENT '请求路径',
  `target_id` varchar(100) DEFAULT NULL COMMENT '操作对象 ID',
  `request_summary` varchar(2000) DEFAULT NULL COMMENT '请求参数摘要（敏感字段已脱敏）',
  `success` tinyint NOT NULL DEFAULT '1' COMMENT '1成功 0失败',
  `status_code` int DEFAULT NULL COMMENT 'HTTP 状态码',
  `error_message` varchar(500) DEFAULT NULL COMMENT '失败原因',
  `client_ip` varchar(64) DEFAULT NULL COMMENT '客户端 IP',
  `duration_ms` bigint DEFAULT NULL COMMENT '耗时（毫秒）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_time` (`create_time`),
  KEY `idx_audit_user_time` (`username`,`create_time`),
  KEY `idx_audit_module_time` (`module`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志';
CREATE TABLE `build_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务编号',
  `task_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务名称',
  `service_id` bigint NOT NULL COMMENT '服务ID',
  `env_id` bigint NOT NULL COMMENT '环境ID',
  `branch` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Git分支',
  `git_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'branch' COMMENT 'Git类型：branch/tag',
  `build_number` int DEFAULT NULL COMMENT '构建序号',
  `image_tag` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Docker镜像Tag',
  `image_full_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '完整镜像名称',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT '状态：PENDING/BUILDING/DEPLOYING/SUCCESS/FAILED',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人ID',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pipeline_template_id` bigint DEFAULT NULL COMMENT 'Pipeline模板ID',
  `deploy_task_id` bigint DEFAULT NULL COMMENT '关联上线任务ID',
  `build_parameters` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '构建参数（JSON格式）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_service_env` (`service_id`,`env_id`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_build_deploy_task` (`deploy_task_id`),
  KEY `idx_env_create_time` (`env_id`,`create_time`),
  KEY `idx_svc_env_status_ctime` (`service_id`,`env_id`,`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建任务表';
CREATE TABLE `build_job_stage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `build_job_id` bigint NOT NULL COMMENT '构建任务ID',
  `step_order` int NOT NULL COMMENT '步骤顺序',
  `step_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '步骤类型',
  `step_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '步骤名称',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILURE/SKIPPED',
  `duration_ms` bigint DEFAULT '0' COMMENT '耗时毫秒',
  `log_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '阶段日志相对路径',
  `log_preview` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '日志末尾预览',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_build_job_id` (`build_job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建任务阶段表';
CREATE TABLE `build_node` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '节点名称',
  `label` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '节点标签',
  `host` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '主机地址',
  `port` int DEFAULT NULL COMMENT '端口号',
  `auth_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '认证类型：password, private_key',
  `username` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SSH用户名',
  `password` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SSH密码（加密存储）',
  `private_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'SSH私钥（当authType为private_key时使用）',
  `private_key_passphrase` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SSH私钥密码（加密存储）',
  `host_key` text COLLATE utf8mb4_unicode_ci COMMENT 'SSH 主机公钥（类型 + Base64，首次连接记录）',
  `host_key_fingerprint` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SSH 主机指纹（SHA256）',
  `node_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '节点类型：build, deploy',
  `work_dir` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '/tmp/opsflow-workspace' COMMENT '远程工作目录根路径',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'ONLINE' COMMENT '状态：ONLINE/OFFLINE',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Jenkins节点表';
CREATE TABLE `cluster` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '集群名称',
  `server` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '集群地址/API Server',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `credential_id` bigint DEFAULT NULL COMMENT '关联钥匙串ID（kubeconfig）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_name` (`name`),
  KEY `idx_cluster_credential_id` (`credential_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='集群配置表';
CREATE TABLE `component` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '组件名称',
  `type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '组件类型：gitlab, harbor, jenkins等',
  `url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '访问地址',
  `auth_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '认证类型：api_key, username_password, token, oauth',
  `auth_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '认证信息（JSON格式）',
  `credential_id` bigint DEFAULT NULL COMMENT '关联钥匙串ID',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '描述',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_credential_id` (`credential_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件表';
CREATE TABLE `credential` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '钥匙名称',
  `credential_type` varchar(50) NOT NULL COMMENT '凭证类型',
  `config_data` text COMMENT '凭证配置JSON',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credential_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='钥匙串凭证表';
CREATE TABLE `deploy_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint DEFAULT NULL COMMENT '所属项目；为空表示升级前的历史任务',
  `task_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务编号',
  `task_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
  `deploy_modules` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '上线模块列表，JSON格式：["service:version", ...]',
  `deploy_envs` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '部署环境列表，JSON格式：[1, 2, 3]',
  `cluster_id` bigint DEFAULT NULL COMMENT '上线集群ID',
  `k8s_namespace` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上线命名空间',
  `approval_flow_id` bigint DEFAULT NULL COMMENT '审批流ID',
  `notify_channels` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'inbox' COMMENT '通知渠道: inbox,feishu 逗号分隔',
  `approval_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'pending' COMMENT '审批状态：pending, approved, rejected',
  `task_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'pending' COMMENT '任务状态：pending, building, deploying, success, failed, cancelled',
  `locked` tinyint NOT NULL DEFAULT '0' COMMENT '是否锁定：0未锁定 1已锁定',
  `locked_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '锁定人用户名',
  `locked_at` datetime DEFAULT NULL COMMENT '锁定时间',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '描述',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人ID',
  `creator_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人姓名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deploy_time` datetime DEFAULT NULL COMMENT '部署时间',
  `pipeline_template_id` bigint DEFAULT NULL COMMENT 'Pipeline模板ID',
  `deploy_mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'serial' COMMENT '部署策略: serial串行 / parallel有限并行',
  `deploy_parallelism` int NOT NULL DEFAULT '1' COMMENT '并行部署并发数，serial 时为 1',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_task_number` (`task_number`) USING BTREE,
  KEY `idx_status` (`task_status`) USING BTREE,
  KEY `idx_approval_flow` (`approval_flow_id`) USING BTREE,
  KEY `idx_task_cluster` (`cluster_id`),
  KEY `idx_deploy_task_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上线任务表';
CREATE TABLE `env` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '环境名称：dev/test/demo/prod',
  `k8s_namespace` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命名空间',
  `env_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'nonprod' COMMENT '环境类型：prod/nonprod',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `cluster_id` bigint DEFAULT NULL COMMENT '关联集群ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境配置表';
CREATE TABLE `inbox_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL COMMENT '接收人用户名',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` varchar(2000) DEFAULT NULL COMMENT '内容',
  `msg_type` varchar(50) DEFAULT 'approval' COMMENT '消息类型',
  `biz_type` varchar(50) DEFAULT NULL COMMENT '业务类型',
  `biz_id` varchar(100) DEFAULT NULL COMMENT '业务 ID',
  `link_path` varchar(200) DEFAULT NULL COMMENT '站内跳转路径',
  `read_flag` tinyint NOT NULL DEFAULT '0' COMMENT '0未读 1已读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inbox_user_read` (`username`,`read_flag`),
  KEY `idx_inbox_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内信';
CREATE TABLE `permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限名称',
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限代码',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限描述',
  `parent_id` bigint DEFAULT NULL COMMENT '父权限ID（用于权限树）',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`) USING BTREE,
  KEY `idx_parent_id` (`parent_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';
CREATE TABLE `pipeline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Pipeline名称',
  `pipeline_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'cicd' COMMENT '流水线类型: ci/cd/cicd',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline描述',
  `steps_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline步骤配置（JSON格式）',
  `parameter_definitions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline参数定义（JSON格式）',
  `build_node_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'CI构建节点ID列表，逗号分隔',
  `deploy_node_id` bigint DEFAULT NULL COMMENT 'CD部署节点ID',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_pipeline_type` (`pipeline_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline模板表';
CREATE TABLE `pipeline_step_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '步骤名称',
  `step_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '步骤类型',
  `phase` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '阶段：ci/cd',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `content_config` text COLLATE utf8mb4_unicode_ci COMMENT '自定义内容JSON',
  `timeout_seconds` int NOT NULL DEFAULT '60' COMMENT '步骤超时秒数',
  `status` tinyint DEFAULT '1' COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_phase_type` (`phase`,`step_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline步骤定义（可复用）';
CREATE TABLE `pipeline_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版名称',
  `type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版类型：dockerfile/deployment/service',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版内容',
  `base_image` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Dockerfile 基础镜像',
  `service_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Service 类型',
  `status` tinyint DEFAULT '1' COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline 模版（Dockerfile/Deployment/Service）';
CREATE TABLE `pipeline_view` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '视图名称，如 dev',
  `env_id` bigint NOT NULL COMMENT '关联环境 ID',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `sort_order` int DEFAULT '0' COMMENT '排序，越小越靠前',
  `status` tinyint DEFAULT '1' COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_env_status` (`env_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流水线视图（按环境筛选任务，类似 Jenkins View）';
CREATE TABLE `pipeline_view_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `view_id` bigint NOT NULL COMMENT '视图 ID',
  `role_id` bigint NOT NULL COMMENT '角色 ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_view_role` (`view_id`,`role_id`),
  KEY `idx_pvr_view` (`view_id`),
  KEY `idx_pvr_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流水线视图角色授权';
CREATE TABLE `pipeline_webhook_trigger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `anchor_job_id` bigint NOT NULL COMMENT '流水线任务（任务组首条 build_job）ID',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1 推送代码时自动构建 0 关闭',
  `update_by` varchar(100) DEFAULT NULL COMMENT '最后修改人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_webhook_anchor` (`anchor_job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流水线 Git Webhook 自动构建开关';
CREATE TABLE `project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '项目名称',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `owner_user_id` bigint DEFAULT NULL COMMENT '负责人',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目';
CREATE TABLE `project_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DEVELOPER' COMMENT 'ADMIN项目管理员 / DEVELOPER开发 / VIEWER只读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`,`user_id`),
  KEY `idx_project_member_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员';
CREATE TABLE `role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色代码',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色描述',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';
CREATE TABLE `role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`) USING BTREE,
  KEY `idx_role_id` (`role_id`) USING BTREE,
  KEY `idx_permission_id` (`permission_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';
CREATE TABLE `service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint DEFAULT NULL COMMENT '所属项目',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名称',
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务代码',
  `git_repo` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Git仓库地址',
  `component_id` bigint DEFAULT NULL COMMENT '关联 Git 组件ID',
  `git_repo_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '仓库路径（相对或完整）',
  `git_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'branch' COMMENT 'Git类型：branch/tag',
  `default_branch` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'master' COMMENT '默认分支',
  `service_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'backend' COMMENT '服务类型：backend/frontend/lib',
  `service_port` int NOT NULL COMMENT '对外提供服务端口',
  `k8s_deployment` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'K8s Deployment名称',
  `k8s_namespace` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'K8s命名空间',
  `dockerfile_path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'Dockerfile' COMMENT 'Dockerfile路径',
  `build_command` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '构建命令',
  `owner_user_id` bigint DEFAULT NULL COMMENT '负责人ID',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`) USING BTREE,
  KEY `idx_component_id` (`component_id`),
  KEY `idx_service_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务/模块表';
CREATE TABLE `system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `config_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置类型：ldap, harbor, maven, jenkins, feishu, dingtalk, wechatwork等',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置键',
  `config_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '配置值',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_type_key` (`config_type`,`config_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码（加密）',
  `real_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'local' COMMENT '来源：local-本地 feishu-飞书 ldap-LDAP',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
CREATE TABLE `user_feishu` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '系统用户ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '系统用户名',
  `feishu_user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书用户ID',
  `feishu_open_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书Open ID',
  `feishu_union_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书Union ID',
  `feishu_mobile` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书手机号',
  `feishu_email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书邮箱',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE,
  KEY `idx_feishu_user_id` (`feishu_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户飞书信息表';
CREATE TABLE `user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_role_id` (`role_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';
