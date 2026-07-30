-- 环境表
CREATE TABLE `env` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL COMMENT '环境名称：dev/test/demo/prod',
  `cluster_id` bigint COMMENT '关联集群ID',
  `k8s_cluster` varchar(200) COMMENT 'K8s集群地址',
  `k8s_namespace` varchar(100) COMMENT '命名空间',
  `env_type` varchar(20) NOT NULL DEFAULT 'nonprod' COMMENT '环境类型：prod/nonprod',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) COMMENT='环境配置表';

CREATE TABLE `cluster` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '集群名称',
  `server` varchar(255) COMMENT '集群地址/API Server',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `description` varchar(500) COMMENT '描述',
  `credential_id` bigint COMMENT '关联钥匙串ID（kubeconfig）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_name` (`name`),
  KEY `idx_cluster_credential_id` (`credential_id`)
) COMMENT='集群配置表';

-- 服务表
CREATE TABLE `service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '服务名称',
  `code` varchar(50) NOT NULL COMMENT '服务代码',
  `git_repo` varchar(500) COMMENT 'Git仓库地址',
  `component_id` bigint COMMENT '关联 Git 组件ID',
  `git_repo_path` varchar(500) COMMENT '仓库路径（相对或完整）',
  `git_type` varchar(20) DEFAULT 'branch' COMMENT 'Git类型：branch/tag',
  `default_branch` varchar(100) DEFAULT 'master' COMMENT '默认分支或Tag',
  `service_type` varchar(20) DEFAULT 'backend' COMMENT '服务类型：backend/frontend/lib',
  `service_port` int NOT NULL COMMENT '对外提供服务端口',
  `k8s_deployment` varchar(200) COMMENT 'K8s Deployment名称',
  `k8s_namespace` varchar(100) COMMENT 'K8s命名空间',
  `dockerfile_path` varchar(200) DEFAULT 'Dockerfile' COMMENT 'Dockerfile路径',
  `build_command` varchar(500) COMMENT '构建命令',
  `owner_user_id` bigint COMMENT '负责人ID',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) COMMENT='服务/模块表';

INSERT INTO `service` (`name`, `code`, `git_repo`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `status`) VALUES
('示例服务', 'demo-service', 'https://github.com/example/demo-service.git', 'develop', 'backend', 8080, 'demo-service', 'dev', 'Dockerfile', 'mvn clean package -DskipTests', 1);

-- 构建任务表
CREATE TABLE `build_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_number` varchar(50) NOT NULL COMMENT '任务编号',
  `task_name` varchar(200) COMMENT '任务名称',
  `service_id` bigint NOT NULL COMMENT '服务ID',
  `env_id` bigint NOT NULL COMMENT '环境ID',
  `branch` varchar(100) NOT NULL COMMENT 'Git分支或Tag',
  `git_type` varchar(20) DEFAULT 'branch' COMMENT 'Git类型：branch/tag',
  `commit_id` varchar(50) COMMENT '提交ID',
  `build_number` int COMMENT '构建序号',
  `build_node` varchar(100) COMMENT '构建节点名称',
  `image_tag` varchar(200) COMMENT 'Docker镜像Tag',
  `image_full_name` varchar(500) COMMENT '完整镜像名称',
  `status` varchar(20) DEFAULT 'PENDING' COMMENT '状态：PENDING/BUILDING/DEPLOYING/SUCCESS/FAILED',
  `build_log_url` varchar(500) COMMENT '构建日志URL',
  `error_message` text COMMENT '错误信息',
  `creator_id` bigint COMMENT '创建人ID',
  `start_time` datetime COMMENT '开始时间',
  `end_time` datetime COMMENT '结束时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pipeline_template_id` bigint COMMENT 'Pipeline模板ID',
  `deploy_task_id` bigint COMMENT '关联上线任务ID',
  `build_parameters` text COMMENT '构建参数（JSON格式）',
  PRIMARY KEY (`id`),
  KEY `idx_service_env` (`service_id`, `env_id`),
  KEY `idx_status` (`status`),
  KEY `idx_build_deploy_task` (`deploy_task_id`),
  KEY `idx_env_create_time` (`env_id`, `create_time`),
  KEY `idx_svc_env_status_ctime` (`service_id`, `env_id`, `status`, `create_time`)
) COMMENT='构建任务表';

-- 构建/部署节点表
CREATE TABLE `build_node` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '节点名称',
  `label` varchar(100) COMMENT '节点标签',
  `status` varchar(20) DEFAULT 'ONLINE' COMMENT '状态：ONLINE/OFFLINE',
  `description` varchar(500) COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) COMMENT='构建/部署节点表';

-- 初始化数据
INSERT INTO `cluster` (`name`, `server`, `status`, `description`) VALUES
('cluster-dev', 'https://k8s-dev.example.com', 1, '开发集群'),
('cluster-test', 'https://k8s-test.example.com', 1, '测试集群'),
('cluster-demo', 'https://k8s-demo.example.com', 1, '演示集群');

INSERT INTO `env` (`name`, `cluster_id`, `k8s_cluster`, `k8s_namespace`, `status`) VALUES
('dev', 1, 'cluster-dev', 'dev', 1),
('test', 2, 'cluster-test', 'test', 1),
('demo', 3, 'cluster-demo', 'demo', 1);

INSERT INTO `build_node` (`name`, `label`, `status`, `description`) VALUES
('node-1', 'linux', 'ONLINE', '构建节点1'),
('node-2', 'linux', 'ONLINE', '构建节点2'),
('node-3', 'linux', 'ONLINE', '构建节点3');

-- 组件管理表（Harbor/GitLab 等外部系统连接配置）
CREATE TABLE IF NOT EXISTS `component` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '组件名称',
  `type` varchar(50) NOT NULL COMMENT '组件类型：harbor/gitlab/github/k8s等',
  `url` varchar(500) COMMENT '访问地址',
  `auth_type` varchar(50) COMMENT '认证类型',
  `auth_config` text COMMENT '认证信息JSON',
  `credential_id` bigint COMMENT '关联钥匙串ID',
  `description` varchar(500) COMMENT '描述',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`type`, `status`)
) COMMENT='组件配置表';

-- 钥匙串凭证表
CREATE TABLE IF NOT EXISTS `credential` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '钥匙名称',
  `credential_type` varchar(50) NOT NULL COMMENT '凭证类型',
  `config_data` text COMMENT '凭证配置JSON',
  `description` varchar(500) COMMENT '描述',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credential_name` (`name`)
) COMMENT='钥匙串凭证表';

-- Pipeline 模板表
CREATE TABLE IF NOT EXISTS `pipeline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT 'Pipeline名称',
  `pipeline_type` varchar(20) NOT NULL DEFAULT 'cicd' COMMENT '流水线类型: ci/cd/cicd',
  `description` varchar(500) COMMENT '描述',
  `steps_config` text COMMENT '步骤配置JSON',
  `script` text COMMENT 'Pipeline脚本（可选）',
  `parameter_definitions` text COMMENT '参数定义JSON',
  `build_node_id` bigint COMMENT 'CI构建节点ID（兼容旧字段）',
  `build_node_ids` varchar(500) COMMENT 'CI构建节点ID列表，逗号分隔',
  `deploy_node_id` bigint COMMENT 'CD部署节点ID',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pipeline_type` (`pipeline_type`)
) COMMENT='Pipeline模板表';

-- 构建任务阶段表（平台原生流水线运行时）
CREATE TABLE IF NOT EXISTS `build_job_stage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `build_job_id` bigint NOT NULL COMMENT '构建任务ID',
  `step_order` int NOT NULL COMMENT '步骤顺序',
  `step_type` varchar(50) NOT NULL COMMENT '步骤类型',
  `step_name` varchar(100) NOT NULL COMMENT '步骤名称',
  `status` varchar(20) DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILURE/SKIPPED',
  `duration_ms` bigint DEFAULT 0 COMMENT '耗时毫秒',
  `log_text` mediumtext COMMENT '阶段日志（兼容旧数据，新日志写入文件）',
  `log_path` varchar(500) DEFAULT NULL COMMENT '阶段日志相对路径',
  `log_preview` varchar(4000) DEFAULT NULL COMMENT '日志末尾预览',
  `error_message` text COMMENT '错误信息',
  `start_time` datetime COMMENT '开始时间',
  `end_time` datetime COMMENT '结束时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_build_job_id` (`build_job_id`)
) COMMENT='构建任务阶段表';

-- Pipeline 步骤定义表（可复用）
CREATE TABLE IF NOT EXISTS `pipeline_step_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '步骤名称',
  `step_type` varchar(50) NOT NULL COMMENT '步骤类型',
  `phase` varchar(10) NOT NULL COMMENT '阶段：ci/cd',
  `description` varchar(500) COMMENT '描述',
  `content_config` text COMMENT '自定义内容JSON',
  `timeout_seconds` int NOT NULL DEFAULT 60 COMMENT '步骤超时秒数',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_phase_type` (`phase`, `step_type`)
) COMMENT='Pipeline步骤定义';

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
) COMMENT='Pipeline 模版';

-- 流水线视图（按环境筛选任务，类似 Jenkins View）
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
) COMMENT='流水线视图';

-- 流水线视图角色授权
CREATE TABLE IF NOT EXISTS `pipeline_view_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `view_id` bigint NOT NULL COMMENT '视图 ID',
  `role_id` bigint NOT NULL COMMENT '角色 ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_view_role` (`view_id`, `role_id`),
  KEY `idx_pvr_view` (`view_id`),
  KEY `idx_pvr_role` (`role_id`)
) COMMENT='流水线视图角色授权';

-- 审批流定义
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
) COMMENT='审批流定义';

-- 上线任务
CREATE TABLE IF NOT EXISTS `deploy_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_number` varchar(50) NOT NULL COMMENT '任务编号',
  `task_name` varchar(200) DEFAULT NULL COMMENT '任务名称',
  `deploy_modules` text COMMENT '上线模块 JSON',
  `deploy_envs` text COMMENT '部署环境 ID JSON',
  `cluster_id` bigint DEFAULT NULL COMMENT '上线集群ID',
  `k8s_namespace` varchar(200) DEFAULT NULL COMMENT '上线命名空间',
  `approval_flow_id` bigint DEFAULT NULL COMMENT '关联审批流 ID',
  `pipeline_template_id` bigint DEFAULT NULL COMMENT 'CD 流水线模版ID',
  `deploy_mode` varchar(20) NOT NULL DEFAULT 'serial' COMMENT '部署策略: serial串行 / parallel有限并行',
  `deploy_parallelism` int NOT NULL DEFAULT 1 COMMENT '并行部署并发数，serial 时为 1',
  `notify_channels` varchar(100) DEFAULT 'inbox' COMMENT '通知渠道: inbox,feishu',
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
  KEY `idx_task_pipeline` (`pipeline_template_id`)
) COMMENT='上线任务';

-- 审批记录
CREATE TABLE IF NOT EXISTS `approval_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL COMMENT '上线任务 ID',
  `task_number` varchar(50) DEFAULT NULL COMMENT '任务编号',
  `approval_flow_id` bigint DEFAULT NULL COMMENT '审批流 ID',
  `current_step` int NOT NULL COMMENT '步骤序号，从 1 开始',
  `approver` varchar(100) DEFAULT NULL COMMENT '审批人用户名',
  `approver_name` varchar(100) DEFAULT NULL COMMENT '审批人显示名',
  `approver_feishu_id` varchar(100) DEFAULT NULL COMMENT '飞书用户 ID',
  `feishu_message_id` varchar(100) DEFAULT NULL COMMENT '飞书卡片消息ID',
  `status` varchar(30) NOT NULL DEFAULT 'waiting' COMMENT 'waiting/pending/approved/rejected/cancelled',
  `comment` varchar(1000) DEFAULT NULL COMMENT '审批意见',
  `approve_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_approver_status` (`approver`, `status`)
) COMMENT='审批记录';

-- 用户飞书账号映射
CREATE TABLE IF NOT EXISTS `user_feishu` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '系统用户ID',
  `username` varchar(100) NOT NULL COMMENT '系统用户名',
  `feishu_user_id` varchar(100) DEFAULT NULL COMMENT '飞书 user_id',
  `feishu_open_id` varchar(100) DEFAULT NULL COMMENT '飞书 open_id',
  `feishu_union_id` varchar(100) DEFAULT NULL COMMENT '飞书 union_id',
  `feishu_mobile` varchar(50) DEFAULT NULL COMMENT '飞书手机号',
  `feishu_email` varchar(200) DEFAULT NULL COMMENT '飞书邮箱',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_feishu_username` (`username`),
  KEY `idx_feishu_user_id` (`feishu_user_id`),
  KEY `idx_feishu_open_id` (`feishu_open_id`)
) COMMENT='用户飞书账号映射';

-- 系统配置
CREATE TABLE IF NOT EXISTS `system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `config_type` varchar(50) NOT NULL COMMENT '配置类型，如 feishu/ldap/dingtalk',
  `config_key` varchar(100) NOT NULL COMMENT '配置键',
  `config_value` text COMMENT '配置值',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_key` (`config_type`, `config_key`),
  KEY `idx_config_type` (`config_type`)
) COMMENT='系统配置';

-- 用户 / 角色 / 权限（RBAC）
CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(200) NOT NULL COMMENT '密码（MD5）',
  `real_name` varchar(100) DEFAULT NULL COMMENT '真实姓名',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `source` varchar(20) NOT NULL DEFAULT 'local' COMMENT '来源：local-本地 feishu-飞书 ldap-LDAP',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '角色名称',
  `code` varchar(50) NOT NULL COMMENT '角色代码',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '权限名称',
  `code` varchar(50) NOT NULL COMMENT '权限代码',
  `resource` varchar(200) DEFAULT NULL COMMENT '资源路径（备注）',
  `method` varchar(20) DEFAULT NULL COMMENT 'HTTP 方法（备注）',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `parent_id` bigint DEFAULT NULL COMMENT '父权限ID',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`),
  KEY `idx_permission_parent` (`parent_id`)
) COMMENT='权限表';

CREATE TABLE IF NOT EXISTS `user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_user` (`user_id`),
  KEY `idx_ur_role` (`role_id`)
) COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS `role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
  KEY `idx_rp_role` (`role_id`),
  KEY `idx_rp_permission` (`permission_id`)
) COMMENT='角色权限关联';

-- 站内信
CREATE TABLE IF NOT EXISTS `inbox_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL COMMENT '接收人用户名',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` varchar(2000) DEFAULT NULL COMMENT '内容',
  `msg_type` varchar(50) DEFAULT 'approval' COMMENT '消息类型',
  `biz_type` varchar(50) DEFAULT NULL COMMENT '业务类型',
  `biz_id` varchar(100) DEFAULT NULL COMMENT '业务 ID',
  `link_path` varchar(200) DEFAULT NULL COMMENT '站内跳转路径',
  `read_flag` tinyint NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inbox_user_read` (`username`, `read_flag`),
  KEY `idx_inbox_biz` (`biz_type`, `biz_id`)
) COMMENT='站内信';

-- 默认 Pipeline 模板
INSERT INTO `pipeline` (`name`, `pipeline_type`, `description`, `steps_config`, `status`) VALUES
('标准构建发布流水线', 'cicd', '拉取代码 → 代码构建 → 镜像制作 → 上传镜像 → 渲染模版 → 开始部署 → 检查部署状态',
'[{"stepType":"checkout","stepName":"拉取代码","enabled":true,"order":1},{"stepType":"build","stepName":"代码构建","enabled":true,"order":2},{"stepType":"docker_build","stepName":"镜像制作","enabled":true,"order":3},{"stepType":"push_image","stepName":"上传镜像","enabled":true,"order":4},{"stepType":"render_template","stepName":"渲染模版","enabled":true,"order":5},{"stepType":"deploy","stepName":"开始部署","enabled":true,"order":6},{"stepType":"check_deploy","stepName":"检查部署状态","enabled":true,"order":7}]',
1);

