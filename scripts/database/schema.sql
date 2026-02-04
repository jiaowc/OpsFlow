-- 环境表
CREATE TABLE `env` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL COMMENT '环境名称：dev/test/demo/prod',
  `k8s_cluster` varchar(200) COMMENT 'K8s集群地址',
  `k8s_namespace` varchar(100) COMMENT '命名空间',
  `harbor_project` varchar(100) COMMENT 'Harbor项目名',
  `jenkins_job_template` varchar(200) COMMENT 'Jenkins Job模板名称',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) COMMENT='环境配置表';

-- 服务表
CREATE TABLE `service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '服务名称',
  `code` varchar(50) NOT NULL COMMENT '服务代码',
  `git_repo` varchar(500) COMMENT 'Git仓库地址',
  `default_branch` varchar(100) DEFAULT 'master' COMMENT '默认分支',
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

-- 构建任务表
CREATE TABLE `build_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_number` varchar(50) NOT NULL COMMENT '任务编号',
  `service_id` bigint NOT NULL COMMENT '服务ID',
  `env_id` bigint NOT NULL COMMENT '环境ID',
  `branch` varchar(100) NOT NULL COMMENT 'Git分支',
  `commit_id` varchar(50) COMMENT '提交ID',
  `jenkins_build_number` int COMMENT 'Jenkins构建号',
  `jenkins_job_name` varchar(200) COMMENT 'Jenkins Job名称',
  `jenkins_node` varchar(100) COMMENT 'Jenkins构建节点',
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

-- Jenkins节点表
CREATE TABLE `jenkins_node` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '节点名称',
  `label` varchar(100) COMMENT '节点标签',
  `jenkins_url` varchar(200) COMMENT 'Jenkins地址',
  `status` varchar(20) DEFAULT 'ONLINE' COMMENT '状态：ONLINE/OFFLINE',
  `description` varchar(500) COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) COMMENT='Jenkins节点表';

-- 初始化数据
INSERT INTO `env` (`name`, `k8s_namespace`, `harbor_project`, `jenkins_job_template`, `status`) VALUES
('dev', 'dev', 'dev', 'build-and-deploy', 1),
('test', 'test', 'test', 'build-and-deploy', 1),
('demo', 'demo', 'demo', 'build-and-deploy', 1);

INSERT INTO `jenkins_node` (`name`, `label`, `status`, `description`) VALUES
('node-1', 'linux', 'ONLINE', 'Jenkins节点1'),
('node-2', 'linux', 'ONLINE', 'Jenkins节点2'),
('node-3', 'linux', 'ONLINE', 'Jenkins节点3');

