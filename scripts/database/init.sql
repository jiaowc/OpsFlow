-- OpsFlow 一键初始化（结构 + 当前配置数据）
-- 生成时间: 2026-11-17 22:07:07 +0800
-- 来源库: 127.0.0.1:3306/opsflow
--
-- 包含：角色权限、环境/集群/服务、流水线/步骤/模版、组件/钥匙、节点、系统配置
-- 不含：构建历史、阶段日志、上线任务、审批记录、站内信、许可证、飞书绑定
-- 账号：只有初始管理员 admin，初始口令 admin，首次登录必须修改
--
-- 使用：
--   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
--   mysql -u root -p opsflow < scripts/database/init.sql
--
-- 注意：含当前环境的组件地址、节点和服务配置（加密凭据已置空），用于受控环境初始化。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO';

CREATE DATABASE IF NOT EXISTS `opsflow` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `opsflow`;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `approval_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批流名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '描述',
  `steps` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '审批步骤JSON，格式：[{"step":1,"approver":"user1","required":true},...]',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=35 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=256 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=122 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=824 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建任务阶段表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Jenkins节点表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='集群配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='钥匙串凭证表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上线任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内信';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline模板表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline步骤定义（可复用）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline 模版（Dockerfile/Deployment/Service）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流水线视图（按环境筛选任务，类似 Jenkins View）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_view_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `view_id` bigint NOT NULL COMMENT '视图 ID',
  `role_id` bigint NOT NULL COMMENT '角色 ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_view_role` (`view_id`,`role_id`),
  KEY `idx_pvr_view` (`view_id`),
  KEY `idx_pvr_role` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流水线视图角色授权';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_webhook_trigger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `anchor_job_id` bigint NOT NULL COMMENT '流水线任务（任务组首条 build_job）ID',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1 推送代码时自动构建 0 关闭',
  `update_by` varchar(100) DEFAULT NULL COMMENT '最后修改人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_webhook_anchor` (`anchor_job_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流水线 Git Webhook 自动构建开关';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `project_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL COMMENT '项目ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DEVELOPER' COMMENT 'ADMIN项目管理员 / DEVELOPER开发 / VIEWER只读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`,`user_id`),
  KEY `idx_project_member_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`) USING BTREE,
  KEY `idx_role_id` (`role_id`) USING BTREE,
  KEY `idx_permission_id` (`permission_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=98 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务/模块表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户飞书信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_role_id` (`role_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=38 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


-- ===================== 配置数据 =====================


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `role` WRITE;
/*!40000 ALTER TABLE `role` DISABLE KEYS */;
INSERT INTO `role` (`id`, `name`, `code`, `description`, `status`, `create_time`, `update_time`) VALUES (1,'管理员','ADMIN','拥有全部权限',1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `role` (`id`, `name`, `code`, `description`, `status`, `create_time`, `update_time`) VALUES (2,'开发者','DEVELOPER','流水线运行、上线申请、服务维护',1,'2026-07-23 00:16:53','2026-07-23 19:50:59');
INSERT INTO `role` (`id`, `name`, `code`, `description`, `status`, `create_time`, `update_time`) VALUES (3,'审批人','APPROVER','查看上线任务并审批',1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `role` (`id`, `name`, `code`, `description`, `status`, `create_time`, `update_time`) VALUES (4,'只读用户','VIEWER','仅查看',1,'2026-07-23 00:16:53','2026-07-23 19:51:11');
INSERT INTO `role` (`id`, `name`, `code`, `description`, `status`, `create_time`, `update_time`) VALUES (6,'运维','DevOps','运维',1,'2026-07-23 22:19:10','2026-07-23 22:19:40');
/*!40000 ALTER TABLE `role` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `permission` WRITE;
/*!40000 ALTER TABLE `permission` DISABLE KEYS */;
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (1,'全部权限','*','超级权限通配符',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (2,'查看统计','statistics:view','查看统计信息',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (3,'查看流水线','pipeline:view','查看流水线视图',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (4,'运行流水线','pipeline:run','触发/重试流水线',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (5,'编辑流水线任务','pipeline:edit','编辑流水线任务配置',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (6,'删除流水线任务','pipeline:delete','删除流水线任务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (7,'回滚部署','pipeline:rollback','回滚到历史成功镜像',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (8,'查看上线任务','deploy:view','查看上线任务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (9,'创建上线任务','deploy:create','创建上线任务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (10,'审批上线','deploy:approve','通过/拒绝上线审批',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (11,'查看服务','service:view','查看服务列表',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (12,'管理服务','service:manage','创建/编辑/删除服务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (13,'查看环境','env:view','查看环境配置',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (14,'管理环境','env:manage','创建/编辑/删除环境',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (15,'查看集群','cluster:view','查看集群',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (16,'管理集群','cluster:manage','创建/编辑/删除集群',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (17,'查看审批流','approval:view','查看审批流程',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (18,'管理审批流','approval:manage','创建/编辑/删除审批流',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (19,'查看节点','node:view','查看构建/部署节点',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (20,'管理节点','node:manage','创建/编辑/删除节点',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (21,'用户管理','user:manage','管理用户账号',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (22,'角色管理','role:manage','管理角色',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (23,'权限管理','permission:manage','管理权限码',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (24,'流水线配置','pipeline_config:manage','管理流水线模版/步骤/视图',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (25,'凭据与组件','credential:manage','管理凭据与集成组件',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (26,'系统配置','system:config','系统集成与通知配置',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (27,'解锁上线任务','deploy:unlock','解除他人锁定的上线任务',NULL,1,'2026-07-31 00:01:41','2026-07-31 00:01:41');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (28,'查看审计日志','audit:view','查看平台操作审计日志',NULL,1,'2026-11-01 22:19:21','2026-11-01 22:19:21');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (29,'项目管理（平台级）','project:manage','创建/删除项目，查看并管理所有项目',NULL,1,'2026-11-02 10:56:16','2026-11-02 10:56:16');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (30,'回滚非生产环境','pipeline:rollback:nonprod','仅可回滚非生产环境；生产环境回滚需「回滚部署」',NULL,1,'2026-11-02 17:06:59','2026-11-02 17:06:59');
INSERT INTO `permission` (`id`, `name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (32,'删除上线任务','deploy:delete','删除上线任务及其关联审批/CD 记录',NULL,1,'2026-11-15 14:20:23','2026-11-15 14:20:23');
/*!40000 ALTER TABLE `permission` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `role_permission` WRITE;
/*!40000 ALTER TABLE `role_permission` DISABLE KEYS */;
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (1,1,1,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (2,1,2,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (3,1,3,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (4,1,4,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (5,1,5,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (6,1,6,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (7,1,7,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (8,1,8,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (9,1,9,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (10,1,10,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (11,1,11,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (12,1,12,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (13,1,13,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (14,1,14,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (15,1,15,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (16,1,16,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (17,1,17,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (18,1,18,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (19,1,19,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (20,1,20,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (21,1,21,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (22,1,22,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (23,1,23,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (24,1,24,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (25,1,25,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (26,1,26,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (47,3,17,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (48,3,10,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (49,3,8,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (50,3,3,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (51,3,2,'2026-07-23 00:16:53');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (69,2,2,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (70,2,3,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (71,2,4,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (72,2,8,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (73,2,9,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (74,2,11,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (75,2,13,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (76,2,15,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (77,2,17,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (78,2,19,'2026-07-23 19:50:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (79,4,2,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (80,4,3,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (81,4,8,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (82,4,11,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (83,4,13,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (84,4,15,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (85,4,17,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (86,4,19,'2026-07-23 19:51:11');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (89,6,1,'2026-07-23 22:19:40');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (90,2,5,'2026-07-24 17:46:15');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (91,2,12,'2026-07-24 17:46:15');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (92,1,27,'2026-07-31 00:01:41');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (93,1,28,'2026-11-01 22:19:21');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (94,1,29,'2026-11-02 10:56:16');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (95,1,30,'2026-11-02 17:06:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (96,2,30,'2026-11-02 17:06:59');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (97,1,32,'2026-11-15 14:20:23');
/*!40000 ALTER TABLE `role_permission` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `cluster` WRITE;
/*!40000 ALTER TABLE `cluster` DISABLE KEYS */;
INSERT INTO `cluster` (`id`, `name`, `server`, `status`, `description`, `credential_id`, `create_time`, `update_time`) VALUES (1,'local','',1,'',4,'2026-07-07 18:41:57','2026-11-07 19:04:04');
INSERT INTO `cluster` (`id`, `name`, `server`, `status`, `description`, `credential_id`, `create_time`, `update_time`) VALUES (3,'local-kind','https://opsflow-control-plane:6443',1,'本地 kind 集群（Docker）',7,'2026-11-15 12:42:57','2026-11-15 12:42:57');
/*!40000 ALTER TABLE `cluster` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `env` WRITE;
/*!40000 ALTER TABLE `env` DISABLE KEYS */;
INSERT INTO `env` (`id`, `name`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (1,'dev','dev','nonprod',1,'2026-01-03 12:38:28','2026-11-15 12:42:57',3);
INSERT INTO `env` (`id`, `name`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (4,'uat','uat','nonprod',1,'2026-07-07 19:13:46','2026-11-15 14:06:06',3);
INSERT INTO `env` (`id`, `name`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (5,'prod','prod','prod',1,'2026-07-20 15:34:12','2026-07-22 17:04:01',1);
INSERT INTO `env` (`id`, `name`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (6,'test','test','nonprod',1,'2026-11-16 01:23:59','2026-11-16 01:23:59',NULL);
/*!40000 ALTER TABLE `env` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `service` WRITE;
/*!40000 ALTER TABLE `service` DISABLE KEYS */;
INSERT INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (3,1,'opsflow','opsflow','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-09 22:35:09','2026-11-07 19:04:05');
INSERT INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (4,1,'service1','service1','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-23 18:37:24','2026-11-02 10:56:16');
INSERT INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (5,1,'service2','service2','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-23 18:37:49','2026-11-02 10:56:16');
INSERT INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (6,1,'service3','service3','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-23 18:38:08','2026-11-02 10:56:16');
INSERT INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (7,1,'ruoyi-server','ruoyi-vue','https://github.com/yangzongzhuan/RuoYi-Vue',7,'yangzongzhuan/RuoYi-Vue','branch','springboot2','backend',8080,NULL,NULL,'Dockerfile',NULL,4,1,'2026-11-15 12:13:16','2026-11-15 12:29:59');
INSERT INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (8,1,'ruoyi-ui','ruoyi-ui','https://github.com/yangzongzhuan/RuoYi-Vue3',7,'yangzongzhuan/RuoYi-Vue3','branch','master','frontend',80,NULL,NULL,'Dockerfile','npm install && npm run build:prod',4,1,'2026-11-15 12:15:32','2026-11-15 12:19:28');
/*!40000 ALTER TABLE `service` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `component` WRITE;
/*!40000 ALTER TABLE `component` DISABLE KEYS */;
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (4,'harbor','harbor','http://localhost','username_password',NULL,5,'',0,'2026-07-07 19:58:29','2026-11-17 20:16:54');
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (7,'github','github','https://github.com','token',NULL,3,'',1,'2026-07-07 22:57:27','2026-07-07 23:07:13');
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (8,'harbor-local','harbor','http://localhost','username_password',NULL,5,'本地 Docker Harbor',1,'2026-11-15 11:39:41','2026-11-17 20:16:54');
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (9,'nexus-local','nexus','http://127.0.0.1:8081','username_password',NULL,6,'本地 Docker Nexus',1,'2026-11-15 11:39:41','2026-11-17 20:16:54');
/*!40000 ALTER TABLE `component` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `credential` WRITE;
/*!40000 ALTER TABLE `credential` DISABLE KEYS */;
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (1,'harbor','username_password',NULL,'',1,'2026-07-07 22:40:44','2026-11-17 20:16:54');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (3,'github-token','token',NULL,'',1,'2026-07-07 23:06:50','2026-11-17 16:55:55');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (4,'local-k8s-config','kubeconfig',NULL,'',1,'2026-07-14 13:30:11','2026-11-17 16:55:55');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (5,'harbor-local','username_password',NULL,'本地 Docker Harbor',1,'2026-11-15 11:39:41','2026-11-17 20:16:54');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (6,'nexus-local','username_password',NULL,'本地 Docker Nexus',1,'2026-11-15 11:39:41','2026-11-17 20:16:54');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (7,'local-kind-kubeconfig','kubeconfig',NULL,'本地 kind 集群 kubeconfig（Docker，可销毁）',1,'2026-11-15 12:42:57','2026-11-17 16:55:55');
/*!40000 ALTER TABLE `credential` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline` WRITE;
/*!40000 ALTER TABLE `pipeline` DISABLE KEYS */;
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (3,'backend-pipline','cicd','','[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":3,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,'7,8',9,1,'2026-07-03 12:01:11','2026-11-15 12:42:57');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (4,'deploy-pipline','cd',NULL,'[{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":13,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null}]',NULL,NULL,9,1,'2026-07-14 13:19:52','2026-11-16 02:16:05');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (5,'lib-pipline','ci',NULL,'[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null}]',NULL,'7,8',NULL,1,'2026-07-24 10:57:16','2026-11-15 12:19:29');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (6,'frontend-pipline','cicd',NULL,'[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":11,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":10,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,'7,8',9,1,'2026-07-24 18:10:44','2026-11-15 12:42:57');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (7,'service-pipeline','cicd','','[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":3,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,'7,8',9,1,'2026-07-27 17:38:37','2026-11-15 12:42:57');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (10,'local-backend-ci','ci','本地构建节点：清理→拉代码→Maven→镜像→推 Harbor','[{\"stepType\": null, \"stepTemplateId\": 7, \"stepName\": null, \"enabled\": true, \"nodeSelection\": null, \"nodeId\": null, \"parameters\": null, \"order\": 1, \"timeoutSeconds\": null}, {\"stepType\": null, \"stepTemplateId\": 1, \"stepName\": null, \"enabled\": true, \"nodeSelection\": null, \"nodeId\": null, \"parameters\": null, \"order\": 2, \"timeoutSeconds\": null}, {\"stepType\": null, \"stepTemplateId\": 2, \"stepName\": null, \"enabled\": true, \"nodeSelection\": null, \"nodeId\": null, \"parameters\": null, \"order\": 3, \"timeoutSeconds\": 1800}, {\"stepType\": null, \"stepTemplateId\": 3, \"stepName\": null, \"enabled\": true, \"nodeSelection\": null, \"nodeId\": null, \"parameters\": null, \"order\": 4, \"timeoutSeconds\": 600}, {\"stepType\": null, \"stepTemplateId\": 4, \"stepName\": null, \"enabled\": true, \"nodeSelection\": null, \"nodeId\": null, \"parameters\": null, \"order\": 5, \"timeoutSeconds\": null}]',NULL,'7,8',NULL,1,'2026-11-15 12:20:07','2026-11-15 12:29:59');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `parameter_definitions`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (11,'local-backend-cicd','cicd','本地构建节点 + kind 部署','[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":1800},{\"stepType\":null,\"stepTemplateId\":3,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":600},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,'7,8',9,1,'2026-11-15 12:45:01','2026-11-15 12:45:01');
/*!40000 ALTER TABLE `pipeline` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_step_def` WRITE;
/*!40000 ALTER TABLE `pipeline_step_def` DISABLE KEYS */;
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (1,'拉取代码','checkout','ci','从 Git 仓库拉取代码','{}',120,1,'2026-07-07 19:50:53','2026-07-24 18:04:58');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (2,'后端构建','build','ci','Maven 构建，并规范产出 target/app.jar（兼容单模块/多模块）','{\"buildCommand\": \"set -e; mvn clean package -DskipTests; mkdir -p target; JAR=$(find . -type f -path \'*/target/*.jar\' ! -name \'*-sources.jar\' ! -name \'*-javadoc.jar\' ! -name \'*-tests.jar\' ! -name \'*-test-sources.jar\' ! -name \'original-*.jar\' -printf \'%s\\\\t%p\\\\n\' | sort -nr | head -1 | cut -f2-); test -n \\\"$JAR\\\"; cp -f \\\"$JAR\\\" target/app.jar; echo \\\"选用 jar: $JAR -> target/app.jar\\\"; ls -lh target/app.jar\"}',1800,1,'2026-07-07 19:50:53','2026-11-15 12:30:11');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (3,'后端镜像制作','docker_build','ci','通用 Java 镜像构建（依赖 target/app.jar）','{\"dockerfilePath\": \"Dockerfile\", \"dockerfileContent\": \"# 通用后端（Java）运行镜像：构建产物约定为 target/app.jar（单模块/多模块均可，由「后端构建」步骤规范产出）\\n# 换 JDK 版本只改 FROM：eclipse-temurin:11-jre / 17-jre / 21-jre\\nFROM eclipse-temurin:8-jre\\n\\nWORKDIR /app\\n\\n# JAVA_OPTS 可在 Deployment 的 env 里整体覆盖；堆大小按容器内存限制的百分比自动计算\\nENV TZ=Asia/Shanghai \\\\\\n    LANG=C.UTF-8 \\\\\\n    JAVA_OPTS=\\\"-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom\\\"\\n\\nCOPY target/app.jar app.jar\\n\\n# 可选：以非 root 运行（应用写日志/上传文件的目录需提前创建并授权）\\n# RUN useradd -r -u 1000 -m app && chown -R app:app /app\\n# USER app\\n\\nEXPOSE ${servicePort}\\n\\n# exec 让 java 成为 1 号进程，能收到 K8s 的 SIGTERM 优雅停机；Deployment 的 args 会作为程序参数追加\\nENTRYPOINT [\\\"sh\\\", \\\"-c\\\", \\\"exec java $JAVA_OPTS -jar /app/app.jar \\\\\\\"$@\\\\\\\"\\\", \\\"--\\\"]\\n\", \"dockerBuildCommand\": \"docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}\", \"dockerfileTemplateId\": \"1\"}',600,1,'2026-07-07 19:50:53','2026-11-17 21:40:22');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (4,'上传镜像','push_image','ci','推送镜像到 Harbor','{\"dockerPushCommand\":\"docker push ${imageFullName}\"}',300,1,'2026-07-07 19:50:53','2026-07-24 18:06:51');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (5,'开始部署','deploy','cd','部署到 K8s 集群','{}',120,1,'2026-07-07 19:50:53','2026-07-24 18:08:31');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (6,'检查状态','check_deploy','cd','等待部署滚动完成','{\"timeoutSeconds\":\"300\"}',330,1,'2026-07-07 19:50:53','2026-11-17 22:06:56');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (7,'清理空间','clean_workspace','ci','清理构建节点工作目录，防止历史代码或缓存影响构建','{\"workspaceBase\":\"~/opsflow\",\"cleanDocker\":\"false\"}',60,1,'2026-07-07 19:50:53','2026-07-24 18:07:30');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (9,'service渲染模版','render_template','cd','渲染通用 Deployment / Service 清单','{\"outputDir\": \"manifests\", \"serviceType\": \"ClusterIP\", \"serviceTemplateId\": \"3\", \"deploymentTemplateId\": \"2\", \"serviceTemplateContent\": \"apiVersion: v1\\nkind: Service\\nmetadata:\\n  name: ${serviceName}-svc\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}\\n    env: ${env}\\n  # 可选：Prometheus 按注解抓取指标时打开（Spring Boot 需引入 actuator + micrometer-registry-prometheus）\\n  # annotations:\\n  #   prometheus.io/scrape: \\\"true\\\"\\n  #   prometheus.io/port: \\\"${servicePort}\\\"\\n  #   prometheus.io/path: /actuator/prometheus\\nspec:\\n  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\\n  # 后端服务建议 ClusterIP，对外流量经 Ingress / 网关进入\\n  type: ${serviceType}\\n  selector:\\n    app: ${serviceName}\\n  ports:\\n  - name: http\\n    # 对外端口与容器端口一致，取自服务管理的「对外端口」\\n    port: ${servicePort}\\n    targetPort: ${servicePort}\\n  # 可选：额外端口（独立的管理/监控端口、gRPC 等），容器需同时监听该端口\\n  # - name: management\\n  #   port: 8081\\n  #   targetPort: 8081\\n\", \"deploymentTemplateContent\": \"apiVersion: apps/v1\\nkind: Deployment\\nmetadata:\\n  name: ${serviceName}\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}\\n    env: ${env}\\nspec:\\n  replicas: 1\\n  revisionHistoryLimit: 5\\n  selector:\\n    matchLabels:\\n      app: ${serviceName}\\n  strategy:\\n    type: RollingUpdate\\n    rollingUpdate:\\n      maxUnavailable: 0\\n      maxSurge: 1\\n  template:\\n    metadata:\\n      labels:\\n        app: ${serviceName}\\n        env: ${env}\\n    spec:\\n      restartPolicy: Always\\n      # 私有仓库拉取密钥（集群里需预先创建同名 Secret）\\n      imagePullSecrets:\\n      - name: harbor-registry-secret\\n      containers:\\n      - name: ${serviceName}\\n        image: ${image}\\n        imagePullPolicy: IfNotPresent\\n        ports:\\n        - name: http\\n          containerPort: ${servicePort}\\n        env:\\n        - name: TZ\\n          value: Asia/Shanghai\\n        # 可选：共享/按服务的配置与密钥（不存在也不影响部署）\\n        envFrom:\\n        - configMapRef:\\n            name: shared-config\\n            optional: true\\n        - secretRef:\\n            name: shared-secret\\n            optional: true\\n        - configMapRef:\\n            name: ${serviceName}-config\\n            optional: true\\n        - secretRef:\\n            name: ${serviceName}-secret\\n            optional: true\\n        # 启动探针：只探端口，避免预热期被误杀（约 10+5*36=190s）\\n        startupProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          initialDelaySeconds: 10\\n          periodSeconds: 5\\n          timeoutSeconds: 2\\n          failureThreshold: 36\\n        # 存活探针：进程端口存活即可\\n        livenessProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          periodSeconds: 10\\n          timeoutSeconds: 3\\n          failureThreshold: 3\\n        # 就绪探针：按项目打开并改 path\\n        # Spring Boot 常见: /actuator/health ；自定义常见: /api/health\\n        # readinessProbe:\\n        #   httpGet:\\n        #     path: /actuator/health\\n        #     port: ${servicePort}\\n        #     scheme: HTTP\\n        #   initialDelaySeconds: 10\\n        #   periodSeconds: 5\\n        #   timeoutSeconds: 2\\n        #   failureThreshold: 3\\n        #   successThreshold: 1\\n        resources:\\n          requests:\\n            cpu: \\\"100m\\\"\\n            memory: \\\"256Mi\\\"\\n          limits:\\n            cpu: \\\"1000m\\\"\\n            memory: \\\"1Gi\\\"\"}',60,1,'2026-07-07 19:50:53','2026-11-17 21:31:34');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (10,'前端镜像制作','docker_build','ci','前端镜像制作','{\"dockerfilePath\": \"Dockerfile\", \"dockerfileContent\": \"# 通用前端 / 静态站点镜像：nginx 托管构建产物，支持 SPA 路由回退\\nFROM nginx:1.30-alpine\\n\\nENV TZ=Asia/Shanghai\\n\\n# nginx 站点配置：监听端口与服务管理的「对外端口」一致\\n# 注释掉的行是可选的接口反代，需要时去掉行首的 # 并改成实际的后端 Service 地址\\nRUN printf \'%s\\\\n\' \\\\\\n    \'server {\' \\\\\\n    \'    listen ${servicePort};\' \\\\\\n    \'    server_name _;\' \\\\\\n    \'    server_tokens off;\' \\\\\\n    \'    root /usr/share/nginx/html;\' \\\\\\n    \'    index index.html;\' \\\\\\n    \'    gzip on;\' \\\\\\n    \'    gzip_min_length 1k;\' \\\\\\n    \'    gzip_types text/plain text/css application/javascript application/json image/svg+xml;\' \\\\\\n    \'    gzip_static on;\' \\\\\\n    \'    location = /healthz {\' \\\\\\n    \'        access_log off;\' \\\\\\n    \'        return 204;\' \\\\\\n    \'    }\' \\\\\\n    \'    location / {\' \\\\\\n    \'        try_files $uri $uri/ /index.html;\' \\\\\\n    \'    }\' \\\\\\n    \'    location = /index.html {\' \\\\\\n    \'        add_header Cache-Control \\\"no-cache\\\";\' \\\\\\n    \'    }\' \\\\\\n    \'    location ~* \\\\.(js|css|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot|map)$ {\' \\\\\\n    \'        expires 7d;\' \\\\\\n    \'        try_files $uri =404;\' \\\\\\n    \'    }\' \\\\\\n#    \'    location /prod-api/ {\' \\\\\\n#    \'        proxy_set_header Host $http_host;\' \\\\\\n#    \'        proxy_set_header X-Real-IP $remote_addr;\' \\\\\\n#    \'        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\' \\\\\\n#    \'        proxy_pass http://backend-svc:8080/;\' \\\\\\n#    \'    }\' \\\\\\n    \'}\' > /etc/nginx/conf.d/default.conf\\n\\nWORKDIR /usr/share/nginx/html\\n\\n# 按实际构建产物目录调整（如 dist / build / public）\\nCOPY dist/ ./\\n\\nEXPOSE ${servicePort}\\n\\nCMD [\\\"nginx\\\", \\\"-g\\\", \\\"daemon off;\\\"]\\n\", \"dockerBuildCommand\": \"docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}\", \"dockerfileTemplateId\": \"4\"}',600,1,'2026-07-24 14:09:59','2026-11-17 21:40:22');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (11,'前端构建','build','ci','前端代码编译','{\"buildCommand\":\"#npm install && npm run build:${env}\\nnpm install && npm run build:prod\"}',300,1,'2026-07-24 14:15:25','2026-11-17 21:12:30');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (12,'回滚部署','rollback','cd','将 K8s Deployment 回滚到上一版本或指定 revision','{\"waitRollout\":\"true\",\"timeoutSeconds\":\"300\"}',330,1,'2026-07-31 00:01:40','2026-11-17 22:06:56');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (13,'通知','notify','cd','通知','{}',60,1,'2026-11-16 02:15:13','2026-11-16 02:15:13');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (14,'前端渲染模版','render_template','cd','','{\"outputDir\": \"manifests\", \"serviceType\": \"ClusterIP\", \"serviceTemplateId\": \"7\", \"deploymentTemplateId\": \"6\", \"serviceTemplateContent\": \"apiVersion: v1\\nkind: Service\\nmetadata:\\n  name: ${serviceName}-svc\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}\\n    env: ${env}\\nspec:\\n  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\\n  # 前端站点建议 ClusterIP，对外流量经 Ingress 进入；本地调试没有 Ingress 时可改 NodePort\\n  type: ${serviceType}\\n  selector:\\n    app: ${serviceName}\\n  ports:\\n  - name: http\\n    # 对外端口与容器端口一致，取自服务管理的「对外端口」（nginx 镜像一般为 80）\\n    port: ${servicePort}\\n    targetPort: ${servicePort}\\n\", \"deploymentTemplateContent\": \"apiVersion: apps/v1\\nkind: Deployment\\nmetadata:\\n  name: ${serviceName}\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}\\n    env: ${env}\\nspec:\\n  replicas: 1\\n  revisionHistoryLimit: 5\\n  selector:\\n    matchLabels:\\n      app: ${serviceName}\\n  strategy:\\n    type: RollingUpdate\\n    rollingUpdate:\\n      maxUnavailable: 0\\n      maxSurge: 1\\n  template:\\n    metadata:\\n      labels:\\n        app: ${serviceName}\\n        env: ${env}\\n    spec:\\n      restartPolicy: Always\\n      imagePullSecrets:\\n      - name: harbor-registry-secret\\n      containers:\\n      - name: ${serviceName}\\n        image: ${image}\\n        imagePullPolicy: IfNotPresent\\n        ports:\\n        - name: http\\n          containerPort: ${servicePort}\\n        env:\\n        - name: TZ\\n          value: Asia/Shanghai\\n        envFrom:\\n        - configMapRef:\\n            name: shared-config\\n            optional: true\\n        - configMapRef:\\n            name: ${serviceName}-config\\n            optional: true\\n        startupProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          initialDelaySeconds: 5\\n          periodSeconds: 5\\n          timeoutSeconds: 2\\n          failureThreshold: 12\\n        livenessProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          periodSeconds: 10\\n          timeoutSeconds: 3\\n          failureThreshold: 3\\n        resources:\\n          requests:\\n            cpu: \\\"50m\\\"\\n            memory: \\\"64Mi\\\"\\n          limits:\\n            cpu: \\\"500m\\\"\\n            memory: \\\"256Mi\\\"\"}',60,1,'2026-11-17 21:22:15','2026-11-17 21:31:34');
/*!40000 ALTER TABLE `pipeline_step_def` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_template` WRITE;
/*!40000 ALTER TABLE `pipeline_template` DISABLE KEYS */;
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`, `create_time`, `update_time`) VALUES (1,'通用后端 Dockerfile 模版','dockerfile','通用 Java 运行镜像：构建产物约定为 target/app.jar；默认 JDK8，换版本只改 FROM；JAVA_OPTS 可由 Deployment 覆盖，支持优雅停机和启动参数','# 通用后端（Java）运行镜像：构建产物约定为 target/app.jar（单模块/多模块均可，由「后端构建」步骤规范产出）\n# 换 JDK 版本只改 FROM：eclipse-temurin:11-jre / 17-jre / 21-jre\nFROM eclipse-temurin:8-jre\n\nWORKDIR /app\n\n# JAVA_OPTS 可在 Deployment 的 env 里整体覆盖；堆大小按容器内存限制的百分比自动计算\nENV TZ=Asia/Shanghai \\\n    LANG=C.UTF-8 \\\n    JAVA_OPTS=\"-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom\"\n\nCOPY target/app.jar app.jar\n\n# 可选：以非 root 运行（应用写日志/上传文件的目录需提前创建并授权）\n# RUN useradd -r -u 1000 -m app && chown -R app:app /app\n# USER app\n\nEXPOSE ${servicePort}\n\n# exec 让 java 成为 1 号进程，能收到 K8s 的 SIGTERM 优雅停机；Deployment 的 args 会作为程序参数追加\nENTRYPOINT [\"sh\", \"-c\", \"exec java $JAVA_OPTS -jar /app/app.jar \\\"$@\\\"\", \"--\"]\n','eclipse-temurin:8-jre',NULL,1,'2026-07-13 11:27:09','2026-11-17 21:40:22');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`, `create_time`, `update_time`) VALUES (2,'通用后端 Deployment 模版','deployment','通用 Deployment：TCP 启停探针、可选 ConfigMap/Secret、无 hostPath；就绪 HTTP 探针按项目取消注释','apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ${serviceName}\n  namespace: ${namespace}\n  labels:\n    app: ${serviceName}\n    env: ${env}\nspec:\n  replicas: 1\n  revisionHistoryLimit: 5\n  selector:\n    matchLabels:\n      app: ${serviceName}\n  strategy:\n    type: RollingUpdate\n    rollingUpdate:\n      maxUnavailable: 0\n      maxSurge: 1\n  template:\n    metadata:\n      labels:\n        app: ${serviceName}\n        env: ${env}\n    spec:\n      restartPolicy: Always\n      # 私有仓库拉取密钥（集群里需预先创建同名 Secret）\n      imagePullSecrets:\n      - name: harbor-registry-secret\n      containers:\n      - name: ${serviceName}\n        image: ${image}\n        imagePullPolicy: IfNotPresent\n        ports:\n        - name: http\n          containerPort: ${servicePort}\n        env:\n        - name: TZ\n          value: Asia/Shanghai\n        # 可选：共享/按服务的配置与密钥（不存在也不影响部署）\n        envFrom:\n        - configMapRef:\n            name: shared-config\n            optional: true\n        - secretRef:\n            name: shared-secret\n            optional: true\n        - configMapRef:\n            name: ${serviceName}-config\n            optional: true\n        - secretRef:\n            name: ${serviceName}-secret\n            optional: true\n        # 启动探针：只探端口，避免预热期被误杀（约 10+5*36=190s）\n        startupProbe:\n          tcpSocket:\n            port: ${servicePort}\n          initialDelaySeconds: 10\n          periodSeconds: 5\n          timeoutSeconds: 2\n          failureThreshold: 36\n        # 存活探针：进程端口存活即可\n        livenessProbe:\n          tcpSocket:\n            port: ${servicePort}\n          periodSeconds: 10\n          timeoutSeconds: 3\n          failureThreshold: 3\n        # 就绪探针：按项目打开并改 path\n        # Spring Boot 常见: /actuator/health ；自定义常见: /api/health\n        # readinessProbe:\n        #   httpGet:\n        #     path: /actuator/health\n        #     port: ${servicePort}\n        #     scheme: HTTP\n        #   initialDelaySeconds: 10\n        #   periodSeconds: 5\n        #   timeoutSeconds: 2\n        #   failureThreshold: 3\n        #   successThreshold: 1\n        resources:\n          requests:\n            cpu: \"100m\"\n            memory: \"256Mi\"\n          limits:\n            cpu: \"1000m\"\n            memory: \"1Gi\"\n',NULL,NULL,1,'2026-07-13 11:38:02','2026-11-17 21:40:22');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`, `create_time`, `update_time`) VALUES (3,'通用后端 Service 模版','service','通用后端 Service：端口与容器端口一致，类型由模版下拉注入；可选 Prometheus 注解、额外端口（默认注释）','apiVersion: v1\nkind: Service\nmetadata:\n  name: ${serviceName}-svc\n  namespace: ${namespace}\n  labels:\n    app: ${serviceName}\n    env: ${env}\n  # 可选：Prometheus 按注解抓取指标时打开（Spring Boot 需引入 actuator + micrometer-registry-prometheus）\n  # annotations:\n  #   prometheus.io/scrape: \"true\"\n  #   prometheus.io/port: \"${servicePort}\"\n  #   prometheus.io/path: /actuator/prometheus\nspec:\n  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\n  # 后端服务建议 ClusterIP，对外流量经 Ingress / 网关进入\n  type: ${serviceType}\n  selector:\n    app: ${serviceName}\n  ports:\n  - name: http\n    # 对外端口与容器端口一致，取自服务管理的「对外端口」\n    port: ${servicePort}\n    targetPort: ${servicePort}\n  # 可选：额外端口（独立的管理/监控端口、gRPC 等），容器需同时监听该端口\n  # - name: management\n  #   port: 8081\n  #   targetPort: 8081\n',NULL,'ClusterIP',1,'2026-07-13 11:51:53','2026-11-17 21:31:34');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`, `create_time`, `update_time`) VALUES (4,'通用前端 Dockerfile 模版','dockerfile','通用前端/静态站点镜像：nginx 托管构建产物（默认 dist/），SPA 路由回退、gzip、静态资源缓存、/healthz；监听端口取服务端口；接口反代默认注释','# 通用前端 / 静态站点镜像：nginx 托管构建产物，支持 SPA 路由回退\nFROM nginx:1.30-alpine\n\nENV TZ=Asia/Shanghai\n\n# nginx 站点配置：监听端口与服务管理的「对外端口」一致\n# 注释掉的行是可选的接口反代，需要时去掉行首的 # 并改成实际的后端 Service 地址\nRUN printf \'%s\\n\' \\\n    \'server {\' \\\n    \'    listen ${servicePort};\' \\\n    \'    server_name _;\' \\\n    \'    server_tokens off;\' \\\n    \'    root /usr/share/nginx/html;\' \\\n    \'    index index.html;\' \\\n    \'    gzip on;\' \\\n    \'    gzip_min_length 1k;\' \\\n    \'    gzip_types text/plain text/css application/javascript application/json image/svg+xml;\' \\\n    \'    gzip_static on;\' \\\n    \'    location = /healthz {\' \\\n    \'        access_log off;\' \\\n    \'        return 204;\' \\\n    \'    }\' \\\n    \'    location / {\' \\\n    \'        try_files $uri $uri/ /index.html;\' \\\n    \'    }\' \\\n    \'    location = /index.html {\' \\\n    \'        add_header Cache-Control \"no-cache\";\' \\\n    \'    }\' \\\n    \'    location ~* \\.(js|css|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot|map)$ {\' \\\n    \'        expires 7d;\' \\\n    \'        try_files $uri =404;\' \\\n    \'    }\' \\\n#    \'    location /prod-api/ {\' \\\n#    \'        proxy_set_header Host $http_host;\' \\\n#    \'        proxy_set_header X-Real-IP $remote_addr;\' \\\n#    \'        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\' \\\n#    \'        proxy_pass http://backend-svc:8080/;\' \\\n#    \'    }\' \\\n    \'}\' > /etc/nginx/conf.d/default.conf\n\nWORKDIR /usr/share/nginx/html\n\n# 按实际构建产物目录调整（如 dist / build / public）\nCOPY dist/ ./\n\nEXPOSE ${servicePort}\n\nCMD [\"nginx\", \"-g\", \"daemon off;\"]\n','nginx:1.30-alpine',NULL,1,'2026-07-24 11:11:20','2026-11-17 21:40:22');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`, `create_time`, `update_time`) VALUES (6,'通用前端 Deployment 模版','deployment','通用前端/静态站点 Deployment：TCP 探针、轻量资源、无 hostPath','apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ${serviceName}\n  namespace: ${namespace}\n  labels:\n    app: ${serviceName}\n    env: ${env}\nspec:\n  replicas: 1\n  revisionHistoryLimit: 5\n  selector:\n    matchLabels:\n      app: ${serviceName}\n  strategy:\n    type: RollingUpdate\n    rollingUpdate:\n      maxUnavailable: 0\n      maxSurge: 1\n  template:\n    metadata:\n      labels:\n        app: ${serviceName}\n        env: ${env}\n    spec:\n      restartPolicy: Always\n      imagePullSecrets:\n      - name: harbor-registry-secret\n      containers:\n      - name: ${serviceName}\n        image: ${image}\n        imagePullPolicy: IfNotPresent\n        ports:\n        - name: http\n          containerPort: ${servicePort}\n        env:\n        - name: TZ\n          value: Asia/Shanghai\n        envFrom:\n        - configMapRef:\n            name: shared-config\n            optional: true\n        - configMapRef:\n            name: ${serviceName}-config\n            optional: true\n        startupProbe:\n          tcpSocket:\n            port: ${servicePort}\n          initialDelaySeconds: 5\n          periodSeconds: 5\n          timeoutSeconds: 2\n          failureThreshold: 12\n        livenessProbe:\n          tcpSocket:\n            port: ${servicePort}\n          periodSeconds: 10\n          timeoutSeconds: 3\n          failureThreshold: 3\n        resources:\n          requests:\n            cpu: \"50m\"\n            memory: \"64Mi\"\n          limits:\n            cpu: \"500m\"\n            memory: \"256Mi\"\n',NULL,NULL,1,'2026-07-29 17:01:20','2026-11-15 12:34:38');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`, `create_time`, `update_time`) VALUES (7,'通用前端 Service 模版','service','通用前端/静态站点 Service：端口与容器端口一致（nginx 镜像一般为 80），类型由模版下拉注入','apiVersion: v1\nkind: Service\nmetadata:\n  name: ${serviceName}-svc\n  namespace: ${namespace}\n  labels:\n    app: ${serviceName}\n    env: ${env}\nspec:\n  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\n  # 前端站点建议 ClusterIP，对外流量经 Ingress 进入；本地调试没有 Ingress 时可改 NodePort\n  type: ${serviceType}\n  selector:\n    app: ${serviceName}\n  ports:\n  - name: http\n    # 对外端口与容器端口一致，取自服务管理的「对外端口」（nginx 镜像一般为 80）\n    port: ${servicePort}\n    targetPort: ${servicePort}\n',NULL,'ClusterIP',1,'2026-11-17 21:31:34','2026-11-17 21:31:34');
/*!40000 ALTER TABLE `pipeline_template` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_view` WRITE;
/*!40000 ALTER TABLE `pipeline_view` DISABLE KEYS */;
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (2,'dev',1,'dev 环境job',0,1,'2026-07-13 17:22:55','2026-11-16 01:24:37');
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (3,'uat',4,'uat环境job',1,1,'2026-07-13 17:30:21','2026-11-16 01:24:37');
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (4,'prod',5,'prod环境',3,1,'2026-07-20 15:41:37','2026-11-16 01:24:37');
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (5,'test',6,'',2,1,'2026-11-16 01:24:29','2026-11-16 01:24:37');
/*!40000 ALTER TABLE `pipeline_view` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_view_role` WRITE;
/*!40000 ALTER TABLE `pipeline_view_role` DISABLE KEYS */;
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (1,2,4,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (2,2,3,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (3,2,2,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (4,2,1,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (5,3,4,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (6,3,3,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (7,3,2,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (8,3,1,'2026-07-23 11:03:18');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (19,4,1,'2026-07-23 19:53:51');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (20,4,3,'2026-07-23 19:53:51');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (21,2,6,'2026-07-24 17:46:15');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (22,3,6,'2026-07-24 17:46:15');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (23,4,6,'2026-07-24 17:46:15');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (24,4,4,'2026-07-24 17:46:15');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (25,4,2,'2026-07-24 17:46:15');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (26,5,6,'2026-11-16 01:24:30');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (27,5,1,'2026-11-16 01:24:30');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (28,5,2,'2026-11-16 01:24:30');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (29,5,3,'2026-11-16 01:24:30');
INSERT INTO `pipeline_view_role` (`id`, `view_id`, `role_id`, `create_time`) VALUES (30,5,4,'2026-11-16 01:24:30');
/*!40000 ALTER TABLE `pipeline_view_role` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `approval_flow` WRITE;
/*!40000 ALTER TABLE `approval_flow` DISABLE KEYS */;
INSERT INTO `approval_flow` (`id`, `name`, `description`, `steps`, `status`, `create_time`, `update_time`) VALUES (1,'生产审批流','','[{\"step\":1,\"approver\":\"jack\",\"approverName\":\"jack\",\"required\":true,\"status\":null},{\"step\":2,\"approver\":\"leader\",\"approverName\":\"leader\",\"required\":true,\"status\":null}]',1,'2026-07-14 19:15:17','2026-07-23 22:16:47');
/*!40000 ALTER TABLE `approval_flow` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `build_node` WRITE;
/*!40000 ALTER TABLE `build_node` DISABLE KEYS */;
INSERT INTO `build_node` (`id`, `name`, `label`, `host`, `port`, `auth_type`, `username`, `password`, `private_key`, `private_key_passphrase`, `host_key`, `host_key_fingerprint`, `node_type`, `work_dir`, `status`, `description`, `create_time`, `update_time`) VALUES (7,'local-build-1','local-docker','127.0.0.1',2222,'password','root',NULL,NULL,NULL,'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBEbToSbPvBRy52XqOSLRr6AcfuOoYr3XmUN2zRwAd0v','SHA256:h7us2qKK4q/F9pGNyRyvvymUNM5yRYF1J2D6xpiz6DA','build','~/opsflow','在线','本地 Docker 构建节点（调试）','2026-11-15 11:47:47','2026-11-17 20:35:15');
INSERT INTO `build_node` (`id`, `name`, `label`, `host`, `port`, `auth_type`, `username`, `password`, `private_key`, `private_key_passphrase`, `host_key`, `host_key_fingerprint`, `node_type`, `work_dir`, `status`, `description`, `create_time`, `update_time`) VALUES (8,'local-build-2','local-docker','127.0.0.1',2223,'password','root',NULL,NULL,NULL,'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBEbToSbPvBRy52XqOSLRr6AcfuOoYr3XmUN2zRwAd0v','SHA256:h7us2qKK4q/F9pGNyRyvvymUNM5yRYF1J2D6xpiz6DA','build','~/opsflow','在线','本地 Docker 构建节点（调试）','2026-11-15 11:47:47','2026-11-17 20:35:15');
INSERT INTO `build_node` (`id`, `name`, `label`, `host`, `port`, `auth_type`, `username`, `password`, `private_key`, `private_key_passphrase`, `host_key`, `host_key_fingerprint`, `node_type`, `work_dir`, `status`, `description`, `create_time`, `update_time`) VALUES (9,'local-deploy','local-docker','127.0.0.1',2224,'password','root',NULL,NULL,NULL,'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKRIu1K3mKphv89yVaI7CLYUOgL24l33JkTmioOZf+GA','SHA256:/r92cdK/wx4rnHcXjB0eQfzYVGwA/Au+kM7ifxNM4OQ','deploy','~/opsflow','在线','本地 Docker 部署节点（kind）','2026-11-15 12:42:57','2026-11-17 20:38:03');
/*!40000 ALTER TABLE `build_node` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `user` WRITE;
/*!40000 ALTER TABLE `user` DISABLE KEYS */;
INSERT INTO `user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `source`, `create_time`, `update_time`) VALUES (4,'admin','$2a$10$SyILMi5aaEuNikAEkr3bEO/h3slK3Z24MZhCsHbV75n.4XWJyWQIO','系统管理员',NULL,NULL,1,'local','2026-07-23 00:16:53','2026-11-17 18:29:09');
/*!40000 ALTER TABLE `user` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `user_role` WRITE;
/*!40000 ALTER TABLE `user_role` DISABLE KEYS */;
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (14,4,1,'2026-07-23 18:20:30');
/*!40000 ALTER TABLE `user_role` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `system_config` WRITE;
/*!40000 ALTER TABLE `system_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_config` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
