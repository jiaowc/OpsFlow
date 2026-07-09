-- 环境表
CREATE TABLE `env` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL COMMENT '环境名称：dev/test/demo/prod',
  `cluster_id` bigint COMMENT '关联集群ID',
  `k8s_cluster` varchar(200) COMMENT 'K8s集群地址',
  `k8s_namespace` varchar(100) COMMENT '命名空间',
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
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_name` (`name`)
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
  `build_parameters` text COMMENT '构建参数（JSON格式）',
  PRIMARY KEY (`id`),
  KEY `idx_service_env` (`service_id`, `env_id`),
  KEY `idx_status` (`status`)
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
  PRIMARY KEY (`id`)
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
  `log_text` mediumtext COMMENT '阶段日志',
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
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_phase_type` (`phase`, `step_type`)
) COMMENT='Pipeline步骤定义';

-- 默认 Pipeline 模板
INSERT INTO `pipeline` (`name`, `description`, `steps_config`, `status`) VALUES
('标准构建发布流水线', '拉取代码 → 代码构建 → 镜像制作 → 上传镜像 → 开始部署 → 检查部署状态',
'[{"stepType":"checkout","stepName":"拉取代码","enabled":true,"order":1},{"stepType":"build","stepName":"代码构建","enabled":true,"order":2},{"stepType":"docker_build","stepName":"镜像制作","enabled":true,"order":3},{"stepType":"push_image","stepName":"上传镜像","enabled":true,"order":4},{"stepType":"deploy","stepName":"开始部署","enabled":true,"order":5},{"stepType":"check_deploy","stepName":"检查部署状态","enabled":true,"order":6}]',
1);

