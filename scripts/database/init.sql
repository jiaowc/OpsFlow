-- OpsFlow 一键初始化（结构 + 当前配置数据）
-- 生成时间: 2026-09-01 15:10:41 +0800
-- 来源库: 127.0.0.1:3306/opsflow
--
-- 包含：用户/角色权限、环境/集群/服务、流水线/步骤/模版、组件/钥匙、节点、系统配置
-- 不含：构建历史、阶段日志、上线任务、审批记录、站内信
--
-- 使用：
--   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
--   mysql -u root -p opsflow < scripts/database/init.sql
--
-- 注意：含当前环境的账号、组件地址与钥匙内容，仅用于受控环境初始化。

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
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `approval_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL COMMENT '任务ID（deploy_task.id）',
  `task_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务编号',
  `approval_flow_id` bigint NOT NULL COMMENT '审批流ID',
  `current_step` int DEFAULT '1' COMMENT '当前审批步骤',
  `approver` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批人（用户名或用户ID）',
  `approver_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人姓名',
  `approver_feishu_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人飞书用户ID',
  `feishu_message_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '飞书卡片消息ID',
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
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `build_job` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务编号',
  `task_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ä»»åŠ¡åç§°',
  `service_id` bigint NOT NULL COMMENT '服务ID',
  `env_id` bigint NOT NULL COMMENT '环境ID',
  `branch` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Git分支',
  `git_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'branch' COMMENT 'Git类型：branch/tag',
  `commit_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交ID',
  `build_number` int DEFAULT NULL COMMENT '构建序号',
  `build_node` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '构建节点名称',
  `image_tag` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Docker镜像Tag',
  `image_full_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '完整镜像名称',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT '状态：PENDING/BUILDING/DEPLOYING/SUCCESS/FAILED',
  `build_log_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '构建日志URL',
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
) ENGINE=InnoDB AUTO_INCREMENT=110 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `build_job_stage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `build_job_id` bigint NOT NULL COMMENT 'æž„å»ºä»»åŠ¡ID',
  `step_order` int NOT NULL COMMENT 'æ­¥éª¤é¡ºåº',
  `step_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ­¥éª¤ç±»åž‹',
  `step_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ­¥éª¤åç§°',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILURE/SKIPPED',
  `duration_ms` bigint DEFAULT '0' COMMENT 'è€—æ—¶æ¯«ç§’',
  `log_text` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'é˜¶æ®µæ—¥å¿—',
  `log_path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '阶段日志相对路径',
  `log_preview` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '日志末尾预览',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT 'é”™è¯¯ä¿¡æ¯',
  `start_time` datetime DEFAULT NULL COMMENT 'å¼€å§‹æ—¶é—´',
  `end_time` datetime DEFAULT NULL COMMENT 'ç»“æŸæ—¶é—´',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_build_job_id` (`build_job_id`)
) ENGINE=InnoDB AUTO_INCREMENT=740 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æž„å»ºä»»åŠ¡é˜¶æ®µè¡¨';
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
  `password` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SSH密码（当authType为password时使用）',
  `private_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'SSH私钥（当authType为private_key时使用）',
  `private_key_passphrase` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'SSH私钥密码（如果私钥有密码）',
  `node_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '节点类型：build, deploy',
  `work_dir` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '/tmp/opsflow-workspace' COMMENT '远程工作目录根路径',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'ONLINE' COMMENT '状态：ONLINE/OFFLINE',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_name` (`name`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Jenkins节点表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cluster` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '集群名称',
  `server` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '集群地址/API Server',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `credential_id` bigint DEFAULT NULL COMMENT '关联钥匙串ID（kubeconfig）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_name` (`name`),
  KEY `idx_cluster_credential_id` (`credential_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='集群配置表';
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
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件表';
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
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='钥匙串凭证表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `deploy_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务编号',
  `task_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
  `deploy_modules` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '上线模块列表，JSON格式：["service:version", ...]',
  `deploy_envs` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '部署环境列表，JSON格式：[1, 2, 3]',
  `cluster_id` bigint DEFAULT NULL COMMENT '上线集群ID',
  `k8s_namespace` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上线命名空间',
  `approval_flow_id` bigint DEFAULT NULL COMMENT '审批流ID',
  `notify_channels` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT 'inbox' COMMENT '通知渠道: inbox,feishu 逗号分隔',
  `approval_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'pending' COMMENT '审批状态：pending, approved, rejected',
  `task_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'pending' COMMENT '任务状态：pending, building, deploying, success, failed, cancelled',
  `locked` tinyint NOT NULL DEFAULT '0' COMMENT '是否锁定：0未锁定 1已锁定',
  `locked_by` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '锁定人用户名',
  `locked_at` datetime DEFAULT NULL COMMENT '锁定时间',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '描述',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人ID',
  `creator_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人姓名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deploy_time` datetime DEFAULT NULL COMMENT '部署时间',
  `pipeline_template_id` bigint DEFAULT NULL COMMENT 'Pipeline模板ID',
  `deploy_mode` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'serial' COMMENT '部署策略: serial串行 / parallel有限并行',
  `deploy_parallelism` int NOT NULL DEFAULT '1' COMMENT '并行部署并发数，serial 时为 1',
  `pipeline_parameters` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline参数（JSON格式）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_task_number` (`task_number`) USING BTREE,
  KEY `idx_status` (`task_status`) USING BTREE,
  KEY `idx_approval_flow` (`approval_flow_id`) USING BTREE,
  KEY `idx_task_cluster` (`cluster_id`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上线任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `env` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '环境名称：dev/test/demo/prod',
  `k8s_cluster` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'K8s集群地址',
  `k8s_namespace` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命名空间',
  `env_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'nonprod' COMMENT '环境类型：prod/nonprod',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `cluster_id` bigint DEFAULT NULL COMMENT '关联集群ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_name` (`name`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境配置表';
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
  `resource` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源路径',
  `method` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'HTTP方法（GET, POST, PUT, DELETE等）',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限描述',
  `parent_id` bigint DEFAULT NULL COMMENT '父权限ID（用于权限树）',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`) USING BTREE,
  KEY `idx_parent_id` (`parent_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Pipeline名称',
  `pipeline_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'cicd' COMMENT '流水线类型: ci/cd/cicd',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline描述',
  `steps_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline步骤配置（JSON格式）',
  `script` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline脚本内容（Jenkinsfile，可选）',
  `parameter_definitions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Pipeline参数定义（JSON格式）',
  `build_node_id` bigint DEFAULT NULL COMMENT 'CIæž„å»ºèŠ‚ç‚¹ID',
  `build_node_ids` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'CI构建节点ID列表，逗号分隔',
  `deploy_node_id` bigint DEFAULT NULL COMMENT 'CDéƒ¨ç½²èŠ‚ç‚¹ID',
  `status` tinyint DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_pipeline_type` (`pipeline_type`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline模板表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_step_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ­¥éª¤åç§°',
  `step_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ­¥éª¤ç±»åž‹',
  `phase` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'é˜¶æ®µï¼šci/cd',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æè¿°',
  `content_config` text COLLATE utf8mb4_unicode_ci COMMENT 'è‡ªå®šä¹‰å†…å®¹JSON',
  `timeout_seconds` int NOT NULL DEFAULT '60' COMMENT '步骤超时秒数',
  `status` tinyint DEFAULT '1' COMMENT '1-å¯ç”¨ 0-ç¦ç”¨',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_phase_type` (`phase`,`step_type`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipelineæ­¥éª¤å®šä¹‰ï¼ˆå¯å¤ç”¨ï¼‰';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版名称',
  `type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版类型：dockerfile/deployment/service',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `content` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版内容',
  `base_image` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Dockerfile 基础镜像',
  `env_id` bigint DEFAULT NULL COMMENT 'Deployment 关联环境 ID',
  `service_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Service 类型',
  `status` tinyint DEFAULT '1' COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`type`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pipeline 模版（Dockerfile/Deployment/Service）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_view` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '视图名称，如 dev',
  `env_id` bigint NOT NULL COMMENT '关联环境 ID',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `sort_order` int DEFAULT '0' COMMENT '排序，越小越靠前',
  `status` tinyint DEFAULT '1' COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_env_status` (`env_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流水线视图（按环境筛选任务，类似 Jenkins View）';
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
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流水线视图角色授权';
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
) ENGINE=InnoDB AUTO_INCREMENT=93 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名称',
  `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务代码',
  `git_repo` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Git仓库地址',
  `component_id` bigint DEFAULT NULL COMMENT '关联 Git 组件ID',
  `git_repo_path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '仓库路径（相对或完整）',
  `git_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'branch' COMMENT 'Git类型：branch/tag',
  `default_branch` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'master' COMMENT '默认分支',
  `service_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'backend' COMMENT '服务类型：backend/frontend/lib',
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
  KEY `idx_component_id` (`component_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务/模块表';
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
  `source` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'local' COMMENT '来源：local-本地 feishu-飞书 ldap-LDAP',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
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
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';
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

LOCK TABLES `user` WRITE;
/*!40000 ALTER TABLE `user` DISABLE KEYS */;
INSERT INTO `user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `source`, `create_time`, `update_time`) VALUES (1,'jack','e10adc3949ba59abbe56e057f20f883e','jack',NULL,NULL,1,'local','2026-07-14 19:13:40','2026-07-23 19:45:44');
INSERT INTO `user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `source`, `create_time`, `update_time`) VALUES (2,'test','e10adc3949ba59abbe56e057f20f883e','test',NULL,NULL,1,'local','2026-07-14 19:14:10','2026-07-23 17:08:54');
INSERT INTO `user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `source`, `create_time`, `update_time`) VALUES (3,'leader','e10adc3949ba59abbe56e057f20f883e','leader',NULL,NULL,1,'local','2026-07-20 16:38:46','2026-07-23 22:08:12');
INSERT INTO `user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `source`, `create_time`, `update_time`) VALUES (4,'admin','e10adc3949ba59abbe56e057f20f883e','系统管理员',NULL,NULL,1,'local','2026-07-23 00:16:53','2026-07-23 18:20:30');
INSERT INTO `user` (`id`, `username`, `password`, `real_name`, `email`, `phone`, `status`, `source`, `create_time`, `update_time`) VALUES (5,'view','e10adc3949ba59abbe56e057f20f883e','view',NULL,NULL,1,'local','2026-07-23 19:52:20','2026-07-23 19:52:20');
/*!40000 ALTER TABLE `user` ENABLE KEYS */;
UNLOCK TABLES;

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
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (1,'全部权限','*',NULL,NULL,'超级权限通配符',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (2,'查看统计','statistics:view',NULL,NULL,'查看统计信息',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (3,'查看流水线','pipeline:view',NULL,NULL,'查看流水线视图',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (4,'运行流水线','pipeline:run',NULL,NULL,'触发/重试流水线',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (5,'编辑流水线任务','pipeline:edit',NULL,NULL,'编辑流水线任务配置',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (6,'删除流水线任务','pipeline:delete',NULL,NULL,'删除流水线任务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (7,'回滚部署','pipeline:rollback',NULL,NULL,'回滚到历史成功镜像',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (8,'查看上线任务','deploy:view',NULL,NULL,'查看上线任务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (9,'创建上线任务','deploy:create',NULL,NULL,'创建上线任务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (10,'审批上线','deploy:approve',NULL,NULL,'通过/拒绝上线审批',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (11,'查看服务','service:view',NULL,NULL,'查看服务列表',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (12,'管理服务','service:manage',NULL,NULL,'创建/编辑/删除服务',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (13,'查看环境','env:view',NULL,NULL,'查看环境配置',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (14,'管理环境','env:manage',NULL,NULL,'创建/编辑/删除环境',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (15,'查看集群','cluster:view',NULL,NULL,'查看集群',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (16,'管理集群','cluster:manage',NULL,NULL,'创建/编辑/删除集群',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (17,'查看审批流','approval:view',NULL,NULL,'查看审批流程',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (18,'管理审批流','approval:manage',NULL,NULL,'创建/编辑/删除审批流',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (19,'查看节点','node:view',NULL,NULL,'查看构建/部署节点',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (20,'管理节点','node:manage',NULL,NULL,'创建/编辑/删除节点',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (21,'用户管理','user:manage',NULL,NULL,'管理用户账号',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (22,'角色管理','role:manage',NULL,NULL,'管理角色',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (23,'权限管理','permission:manage',NULL,NULL,'管理权限码',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (24,'流水线配置','pipeline_config:manage',NULL,NULL,'管理流水线模版/步骤/视图',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (25,'凭据与组件','credential:manage',NULL,NULL,'管理凭据与集成组件',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (26,'系统配置','system:config',NULL,NULL,'系统集成与通知配置',NULL,1,'2026-07-23 00:16:53','2026-07-23 00:16:53');
INSERT INTO `permission` (`id`, `name`, `code`, `resource`, `method`, `description`, `parent_id`, `status`, `create_time`, `update_time`) VALUES (27,'解锁上线任务','deploy:unlock',NULL,NULL,'解除他人锁定的上线任务',NULL,1,'2026-07-31 00:01:41','2026-07-31 00:01:41');
/*!40000 ALTER TABLE `permission` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `user_role` WRITE;
/*!40000 ALTER TABLE `user_role` DISABLE KEYS */;
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (4,2,2,'2026-07-23 17:08:54');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (14,4,1,'2026-07-23 18:20:30');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (16,1,2,'2026-07-23 19:45:44');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (17,1,3,'2026-07-23 19:45:44');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (18,5,4,'2026-07-23 19:52:20');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (19,3,1,'2026-07-23 22:08:12');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (20,3,3,'2026-07-23 22:08:12');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (21,1,1,'2026-07-24 17:46:15');
/*!40000 ALTER TABLE `user_role` ENABLE KEYS */;
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
/*!40000 ALTER TABLE `role_permission` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `user_feishu` WRITE;
/*!40000 ALTER TABLE `user_feishu` DISABLE KEYS */;
INSERT INTO `user_feishu` (`id`, `user_id`, `username`, `feishu_user_id`, `feishu_open_id`, `feishu_union_id`, `feishu_mobile`, `feishu_email`, `create_time`, `update_time`) VALUES (1,2,'test',NULL,NULL,NULL,NULL,NULL,'2026-07-23 17:08:54','2026-07-23 17:08:54');
INSERT INTO `user_feishu` (`id`, `user_id`, `username`, `feishu_user_id`, `feishu_open_id`, `feishu_union_id`, `feishu_mobile`, `feishu_email`, `create_time`, `update_time`) VALUES (2,3,'leder',NULL,NULL,NULL,NULL,NULL,'2026-07-23 17:09:13','2026-07-23 17:36:45');
INSERT INTO `user_feishu` (`id`, `user_id`, `username`, `feishu_user_id`, `feishu_open_id`, `feishu_union_id`, `feishu_mobile`, `feishu_email`, `create_time`, `update_time`) VALUES (3,1,'jack',NULL,NULL,NULL,NULL,NULL,'2026-07-23 17:09:47','2026-07-23 19:45:44');
INSERT INTO `user_feishu` (`id`, `user_id`, `username`, `feishu_user_id`, `feishu_open_id`, `feishu_union_id`, `feishu_mobile`, `feishu_email`, `create_time`, `update_time`) VALUES (4,4,'admin',NULL,NULL,NULL,NULL,NULL,'2026-07-23 18:20:30','2026-07-23 18:20:30');
/*!40000 ALTER TABLE `user_feishu` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `cluster` WRITE;
/*!40000 ALTER TABLE `cluster` DISABLE KEYS */;
INSERT INTO `cluster` (`id`, `name`, `server`, `status`, `description`, `credential_id`, `create_time`, `update_time`) VALUES (1,'local','',1,'',4,'2026-07-07 18:41:57','2026-07-20 15:33:31');
/*!40000 ALTER TABLE `cluster` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `env` WRITE;
/*!40000 ALTER TABLE `env` DISABLE KEYS */;
INSERT INTO `env` (`id`, `name`, `k8s_cluster`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (1,'dev','local','dev','nonprod',1,'2026-01-03 12:38:28','2026-07-20 15:33:31',1);
INSERT INTO `env` (`id`, `name`, `k8s_cluster`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (4,'uat','local','uat','nonprod',1,'2026-07-07 19:13:46','2026-07-20 15:33:31',1);
INSERT INTO `env` (`id`, `name`, `k8s_cluster`, `k8s_namespace`, `env_type`, `status`, `create_time`, `update_time`, `cluster_id`) VALUES (5,'prod','local','prod','prod',1,'2026-07-20 15:34:12','2026-07-22 17:04:01',1);
/*!40000 ALTER TABLE `env` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `service` WRITE;
/*!40000 ALTER TABLE `service` DISABLE KEYS */;
INSERT INTO `service` (`id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (3,'opsflow','opsflow','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-09 22:35:09','2026-07-09 22:35:09');
INSERT INTO `service` (`id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (4,'service1','service1','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-23 18:37:24','2026-07-23 18:37:24');
INSERT INTO `service` (`id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (5,'service2','service2','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-23 18:37:49','2026-07-30 23:06:00');
INSERT INTO `service` (`id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`, `create_time`, `update_time`) VALUES (6,'service3','service3','https://github.com/jiaowc/OpsFlow.git',7,'https://github.com/jiaowc/OpsFlow.git','branch','master','backend',8080,NULL,NULL,'Dockerfile',NULL,NULL,1,'2026-07-23 18:38:08','2026-07-23 18:38:08');
/*!40000 ALTER TABLE `service` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `component` WRITE;
/*!40000 ALTER TABLE `component` DISABLE KEYS */;
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (3,'gitlab','gitlab','https://gitlab.mcorp.work','username_password','{\"username\":\"jack.chiao\",\"password\":\"bol^T!uO6QeN#\"}',NULL,'',1,'2026-07-07 19:56:56','2026-07-07 19:56:56');
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (4,'harbor','harbor','https://harbor.mcorp.work','username_password','{\"username\":\"harbor\",\"password\":\"aiK9#L4boSSYbQ*Q\"}',1,'',1,'2026-07-07 19:58:29','2026-07-07 22:41:12');
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (5,'nexus','nexus','https://nexus.cn.qzweb.site/','username_password','{\"username\":\"jiaowc\",\"password\":\"3H&jdAda>E*m<r3w\"}',NULL,'',1,'2026-07-07 20:03:30','2026-07-07 20:03:30');
INSERT INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`, `create_time`, `update_time`) VALUES (7,'github','github','https://github.com','token',NULL,3,'',1,'2026-07-07 22:57:27','2026-07-07 23:07:13');
/*!40000 ALTER TABLE `component` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `credential` WRITE;
/*!40000 ALTER TABLE `credential` DISABLE KEYS */;
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (1,'harbor','username_password','{\"password\":\"aiK9#L4boSSYbQ*Q\",\"username\":\"admin\"}','',1,'2026-07-07 22:40:44','2026-07-07 22:40:44');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (3,'github-token','token','{\"token\":\"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\"}','',1,'2026-07-07 23:06:50','2026-07-07 23:06:50');
INSERT INTO `credential` (`id`, `name`, `credential_type`, `config_data`, `description`, `status`, `create_time`, `update_time`) VALUES (4,'local-k8s-config','kubeconfig','{\"configContent\":\"apiVersion: v1\\nkind: Config\\nclusters:\\n- name: \\\"rancher\\\"\\n  cluster:\\n    server: \\\"https://172.16.50.161\\\"\\n    certificate-authority-data: \\\"LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJ2VENDQ\\\\\\n      VdPZ0F3SUJBZ0lCQURBS0JnZ3Foa2pPUFFRREFqQkdNUnd3R2dZRFZRUUtFeE5rZVc1aGJXbGoKY\\\\\\n      kdsemRHVnVaWEl0YjNKbk1TWXdKQVlEVlFRRERCMWtlVzVoYldsamJHbHpkR1Z1WlhJdFkyRkFNV\\\\\\n      GM0TXpNegpNRGt3TnpBZUZ3MHlOakEzTURZd09EUXhORGRhRncwek5qQTNNRE13T0RReE5EZGFNR\\\\\\n      Vl4SERBYUJnTlZCQW9UCkUyUjVibUZ0YVdOc2FYTjBaVzVsY2kxdmNtY3hKakFrQmdOVkJBTU1IV\\\\\\n      1I1Ym1GdGFXTnNhWE4wWlc1bGNpMWoKWVVBeE56Z3pNek13T1RBM01Ga3dFd1lIS29aSXpqMENBU\\\\\\n      VlJS29aSXpqMERBUWNEUWdBRW5jQzFsdTRNODNjcApKaC9BZ2JVU2pneXBrTzZvajZLSFhRM1l3M\\\\\\n      01WUGczd3UxTFNFS0hBVHJvK2ZwTzZaM08zTy90bWhQV1VvVXF6CkRnbmhMMTNmL0tOQ01FQXdEZ\\\\\\n      1lEVlIwUEFRSC9CQVFEQWdLa01BOEdBMVVkRXdFQi93UUZNQU1CQWY4d0hRWUQKVlIwT0JCWUVGT\\\\\\n      TZNRWdrWVQyOStKREJGRGVtK01yRFNVc3RPTUFvR0NDcUdTTTQ5QkFNQ0EwZ0FNRVVDSUZ5WgpVV\\\\\\n      jlLck1SOXJ1TTdoSXkvMGt4aXFGS0pTOHVKZkVoRTk5L3pBUWtYQWlFQTJrNVh2a29VR0ZvRU5XQ\\\\\\n      VdSRGo5CnU4cTg2Q3FDRzZhRXFwcTBnd2w4TVBNPQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0t\\\"\\n- name: \\\"dev\\\"\\n  cluster:\\n    server: \\\"https://172.16.50.161/k8s/clusters/c-m-pl726thv\\\"\\n    certificate-authority-data: \\\"LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJ2VENDQ\\\\\\n      VdPZ0F3SUJBZ0lCQURBS0JnZ3Foa2pPUFFRREFqQkdNUnd3R2dZRFZRUUtFeE5rZVc1aGJXbGoKY\\\\\\n      kdsemRHVnVaWEl0YjNKbk1TWXdKQVlEVlFRRERCMWtlVzVoYldsamJHbHpkR1Z1WlhJdFkyRkFNV\\\\\\n      GM0TXpNegpNRGt3TnpBZUZ3MHlOakEzTURZd09EUXhORGRhRncwek5qQTNNRE13T0RReE5EZGFNR\\\\\\n      Vl4SERBYUJnTlZCQW9UCkUyUjVibUZ0YVdOc2FYTjBaVzVsY2kxdmNtY3hKakFrQmdOVkJBTU1IV\\\\\\n      1I1Ym1GdGFXTnNhWE4wWlc1bGNpMWoKWVVBeE56Z3pNek13T1RBM01Ga3dFd1lIS29aSXpqMENBU\\\\\\n      VlJS29aSXpqMERBUWNEUWdBRW5jQzFsdTRNODNjcApKaC9BZ2JVU2pneXBrTzZvajZLSFhRM1l3M\\\\\\n      01WUGczd3UxTFNFS0hBVHJvK2ZwTzZaM08zTy90bWhQV1VvVXF6CkRnbmhMMTNmL0tOQ01FQXdEZ\\\\\\n      1lEVlIwUEFRSC9CQVFEQWdLa01BOEdBMVVkRXdFQi93UUZNQU1CQWY4d0hRWUQKVlIwT0JCWUVGT\\\\\\n      TZNRWdrWVQyOStKREJGRGVtK01yRFNVc3RPTUFvR0NDcUdTTTQ5QkFNQ0EwZ0FNRVVDSUZ5WgpVV\\\\\\n      jlLck1SOXJ1TTdoSXkvMGt4aXFGS0pTOHVKZkVoRTk5L3pBUWtYQWlFQTJrNVh2a29VR0ZvRU5XQ\\\\\\n      VdSRGo5CnU4cTg2Q3FDRzZhRXFwcTBnd2w4TVBNPQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0t\\\"\\n\\nusers:\\n- name: \\\"rancher\\\"\\n  user:\\n    token: \\\"kubeconfig-user-l2wzdfftvs:qrstb6lwlml287wwnkh28wdtgzxw5vjn92pbp7dxv275vq7gqxdpl4\\\"\\n\\n\\ncontexts:\\n- name: \\\"rancher\\\"\\n  context:\\n    user: \\\"rancher\\\"\\n    cluster: \\\"rancher\\\"\\n- name: \\\"dev\\\"\\n  context:\\n    user: \\\"rancher\\\"\\n    cluster: \\\"dev\\\"\\n\\ncurrent-context: \\\"dev\\\"\"}','',1,'2026-07-14 13:30:11','2026-07-14 13:30:11');
/*!40000 ALTER TABLE `credential` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline` WRITE;
/*!40000 ALTER TABLE `pipeline` DISABLE KEYS */;
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `script`, `parameter_definitions`, `build_node_id`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (3,'backend-pipline','cicd','','[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":3,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,NULL,4,'4,6',5,1,'2026-07-03 12:01:11','2026-07-29 16:54:10');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `script`, `parameter_definitions`, `build_node_id`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (4,'deploy-pipline','cd',NULL,'[{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null}]',NULL,NULL,NULL,NULL,5,1,'2026-07-14 13:19:52','2026-07-29 16:55:39');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `script`, `parameter_definitions`, `build_node_id`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (5,'lib-pipline','ci',NULL,'[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null}]',NULL,NULL,4,'4,6',NULL,1,'2026-07-24 10:57:16','2026-07-29 16:55:11');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `script`, `parameter_definitions`, `build_node_id`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (6,'frontend-pipline','cicd',NULL,'[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":11,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":10,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,NULL,4,'4,6',5,1,'2026-07-24 18:10:44','2026-07-29 16:54:50');
INSERT INTO `pipeline` (`id`, `name`, `pipeline_type`, `description`, `steps_config`, `script`, `parameter_definitions`, `build_node_id`, `build_node_ids`, `deploy_node_id`, `status`, `create_time`, `update_time`) VALUES (7,'opsflow-pipeline','cicd','','[{\"stepType\":null,\"stepTemplateId\":7,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":1,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":1,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":2,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":2,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":3,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":3,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":4,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":4,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":5,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":9,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":6,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":5,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":7,\"timeoutSeconds\":null},{\"stepType\":null,\"stepTemplateId\":6,\"stepName\":null,\"enabled\":true,\"nodeSelection\":null,\"nodeId\":null,\"parameters\":null,\"order\":8,\"timeoutSeconds\":null}]',NULL,NULL,4,'4,6',5,1,'2026-07-27 17:38:37','2026-07-27 17:38:37');
/*!40000 ALTER TABLE `pipeline` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_step_def` WRITE;
/*!40000 ALTER TABLE `pipeline_step_def` DISABLE KEYS */;
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (1,'拉取代码','checkout','ci','从 Git 仓库拉取代码','{}',120,1,'2026-07-07 19:50:53','2026-07-24 18:04:58');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (2,'后端构建','build','ci','Maven/Gradle 构建','{\"buildCommand\":\"mvn clean package -Pdev -DskipTests\"}',300,1,'2026-07-07 19:50:53','2026-07-30 19:12:19');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (3,'后端镜像制作','docker_build','ci','后段服务镜像构建','{\"dockerfileTemplateId\":\"1\",\"dockerfilePath\":\"Dockerfile\",\"dockerfileContent\":\"FROM eclipse-temurin:8-jre-alpine\\nWORKDIR /app\\nCOPY target/*.jar app.jar\\nEXPOSE ${servicePort}\\nENTRYPOINT [\\\"java\\\", \\\"-jar\\\", \\\"app.jar\\\"]\",\"dockerBuildCommand\":\"docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}\"}',120,1,'2026-07-07 19:50:53','2026-07-24 18:06:24');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (4,'上传镜像','push_image','ci','推送镜像到 Harbor','{\"dockerPushCommand\":\"docker push ${imageFullName}\"}',300,1,'2026-07-07 19:50:53','2026-07-24 18:06:51');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (5,'开始部署','deploy','cd','部署到 K8s 集群','{}',120,1,'2026-07-07 19:50:53','2026-07-24 18:08:31');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (6,'检查状态','check_deploy','cd','等待部署滚动完成','{\"timeoutSeconds\":\"300\"}',120,1,'2026-07-07 19:50:53','2026-07-24 18:07:22');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (7,'清理空间','clean_workspace','ci','清理构建节点工作目录，防止历史代码或缓存影响构建','{\"workspaceBase\":\"~/opsflow\",\"cleanDocker\":\"false\"}',60,1,'2026-07-07 19:50:53','2026-07-24 18:07:30');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (9,'渲染模版','render_template','cd','根据 Deployment / Service 模版渲染 K8s 清单','{\"deploymentTemplateId\":\"2\",\"serviceTemplateId\":\"3\",\"serviceType\":\"ClusterIP\",\"outputDir\":\"manifests\",\"deploymentTemplateContent\":\"apiVersion: apps/v1\\nkind: Deployment\\nmetadata:\\n  name: ${serviceName}\\n  namespace: ${namespace}\\nspec:\\n  replicas: 1\\n  selector:\\n    matchLabels:\\n      app: ${serviceName}\\n  template:\\n    metadata:\\n      labels:\\n        app: ${serviceName}\\n    spec:\\n      restartPolicy: Always\\n      imagePullSecrets:\\n      - name: harbor-registry-secret\\n      containers:\\n      - name: ${serviceName}\\n        # 由流水线渲染后的镜像地址与标签\\n        image: ${image}\\n        ports:\\n        # 容器业务端口\\n        - containerPort: ${servicePort}\\n        # 启动探针：仅检查端口是否打开，避免应用预热期间因业务检查失败被误杀\\n        # 最大等待时间 = initialDelaySeconds + periodSeconds × failureThreshold = 30 + 5×40 = 230s\\n        startupProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          initialDelaySeconds: 30\\n          periodSeconds: 5\\n          timeoutSeconds: 2\\n          failureThreshold: 40\\n        # 存活探针：startupProbe 成功后才生效，仅检查进程端口存活，减少外部依赖抖动导致的频繁重启\\n        livenessProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          initialDelaySeconds: 30\\n          periodSeconds: 5\\n          timeoutSeconds: 3\\n          failureThreshold: 40\\n        # 就绪探针：检查业务健康接口，通过后才加入 Service 负载\\n        # readinessProbe:\\n        #   httpGet:\\n        #     path: /actuator/health\\n        #     port: ${servicePort}\\n        #     scheme: HTTP\\n        #   initialDelaySeconds: 20\\n        #   periodSeconds: 10\\n        #   timeoutSeconds: 2\\n        #   failureThreshold: 5\\n        #   successThreshold: 1\\n        # CPU 限制：防止容器使用超过宿主机核心数导致宿主机过载\\n        resources:\\n          requests:\\n            cpu: \\\"100m\\\"\\n          limits:\\n            cpu: \\\"4\\\"\\n        # 挂载节点时区文件，使容器时区与所在节点保持一致\\n        volumeMounts:\\n        - name: host-localtime\\n          mountPath: /etc/localtime\\n          readOnly: true\\n        - name: host-timezone\\n          mountPath: /etc/timezone\\n          readOnly: true\\n        - name: host-data-logs\\n          mountPath: /data/logs\\n        # 环境变量配置\\n        # env:\\n        # - name: RUN_ENV\\n        #   valueFrom:\\n        #     configMapKeyRef:\\n        #       name: app-config\\n        #       key: APP_ENV\\n      volumes:\\n      - name: host-localtime\\n        hostPath:\\n          path: /etc/localtime\\n          type: File\\n      - name: host-timezone\\n        hostPath:\\n          path: /etc/timezone\\n          type: FileOrCreate\\n      - name: host-data-logs\\n        hostPath:\\n          path: /data/logs\\n          type: DirectoryOrCreate\",\"serviceTemplateContent\":\"apiVersion: v1\\nkind: Service\\nmetadata:\\n  name: ${serviceName}-svc\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}-svc\\n    monitor: \\\"true\\\"\\nspec:\\n  # 可选值: ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\\n  type: ${serviceType}\\n  selector:\\n    app: ${serviceName}\\n  ports:\\n  - name: http\\n    # 暴露端口和容器端口一致\\n    port: ${servicePort}\\n    # Pod 容器端口\\n    targetPort: ${servicePort}\"}',60,1,'2026-07-07 19:50:53','2026-07-24 18:07:41');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (10,'前端镜像制作','docker_build','ci','前端镜像制作','{\"dockerfileTemplateId\":\"4\",\"dockerfilePath\":\"Dockerfile\",\"dockerfileContent\":\"FROM nginx:1.25-alpine\\nWORKDIR /usr/share/nginx/html\\n# 按实际构建产物目录调整（如 dist / build / public）\\nCOPY dist/ ./\\nEXPOSE ${servicePort}\\nCMD [\\\"nginx\\\", \\\"-g\\\", \\\"daemon off;\\\"]\",\"dockerBuildCommand\":\"docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}\"}',60,1,'2026-07-24 14:09:59','2026-07-24 18:04:40');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (11,'前端构建','build','ci','前端代码编译','{\"buildCommand\":\"npm install\"}',300,1,'2026-07-24 14:15:25','2026-07-24 18:04:03');
INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `timeout_seconds`, `status`, `create_time`, `update_time`) VALUES (12,'回滚部署','rollback','cd','将 K8s Deployment 回滚到上一版本或指定 revision','{\"waitRollout\":\"true\",\"timeoutSeconds\":\"300\"}',60,1,'2026-07-31 00:01:40','2026-07-31 00:01:40');
/*!40000 ALTER TABLE `pipeline_step_def` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_template` WRITE;
/*!40000 ALTER TABLE `pipeline_template` DISABLE KEYS */;
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `env_id`, `service_type`, `status`, `create_time`, `update_time`) VALUES (1,'java镜像构建模版','dockerfile','Dockerfile','FROM eclipse-temurin:8-jre-alpine\n\nWORKDIR /app\n\nRUN apk add --no-cache \\\n    bash \\\n    curl \\\n    busybox-extras \\\n    bind-tools \\\n    netcat-openbsd \\\n    tzdata \\\n    mysql-client\n\nCOPY target/*.jar app.jar\n\nEXPOSE 8080\n\nENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]','eclipse-temurin:8-jre-alpine',NULL,NULL,1,'2026-07-13 11:27:09','2026-07-30 16:24:02');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `env_id`, `service_type`, `status`, `create_time`, `update_time`) VALUES (2,'java服务Deployment 模版','deployment','Deployment.yaml','apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ${serviceName}\n  namespace: ${namespace}\nspec:\n  replicas: 1\n  selector:\n    matchLabels:\n      app: ${serviceName}\n  template:\n    metadata:\n      labels:\n        app: ${serviceName}\n    spec:\n      restartPolicy: Always\n      imagePullSecrets:\n      - name: harbor-registry-secret\n      containers:\n      - name: ${serviceName}\n        image: ${image}\n        ports:\n        - containerPort: ${servicePort}\n\n        startupProbe:\n          tcpSocket:\n            port: ${servicePort}\n          initialDelaySeconds: 30\n          periodSeconds: 5\n          timeoutSeconds: 2\n          failureThreshold: 40\n\n        livenessProbe:\n          tcpSocket:\n            port: ${servicePort}\n          initialDelaySeconds: 30\n          periodSeconds: 5\n          timeoutSeconds: 3\n          failureThreshold: 40\n\n        readinessProbe:\n          httpGet:\n            path: /api/health\n            port: ${servicePort}\n            scheme: HTTP\n          initialDelaySeconds: 20\n          periodSeconds: 10\n          timeoutSeconds: 2\n          failureThreshold: 5\n          successThreshold: 1\n\n        resources:\n          requests:\n            cpu: \"100m\"\n          limits:\n            cpu: \"4\"\n\n        volumeMounts:\n        - name: host-localtime\n          mountPath: /etc/localtime\n          readOnly: true\n        - name: host-timezone\n          mountPath: /etc/timezone\n          readOnly: true\n        - name: host-data-logs\n          mountPath: /data/logs\n        - name: app-config\n          mountPath: /app/config\n          readOnly: true\n\n        envFrom:\n        - configMapRef:\n            name: shared-config\n            optional: true\n        - secretRef:\n            name: shared-secret\n            optional: true\n        - configMapRef:\n            name: ${serviceName}-config\n            optional: true\n        - secretRef:\n            name: ${serviceName}-secret\n            optional: true\n\n      volumes:\n      - name: host-localtime\n        hostPath:\n          path: /etc/localtime\n          type: File\n      - name: host-timezone\n        hostPath:\n          path: /etc/timezone\n          type: FileOrCreate\n      - name: host-data-logs\n        hostPath:\n          path: /data/logs\n          type: DirectoryOrCreate\n      - name: app-config\n        configMap:\n          name: ${serviceName}-config-file\n          optional: true',NULL,NULL,NULL,1,'2026-07-13 11:38:02','2026-07-30 15:29:32');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `env_id`, `service_type`, `status`, `create_time`, `update_time`) VALUES (3,'service','service','service','apiVersion: v1\nkind: Service\nmetadata:\n  name: ${serviceName}-svc\n  namespace: ${namespace}\n  labels:\n    app: ${serviceName}-svc\n    monitor: \"true\"\nspec:\n  # 可选值: ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\n  type: ${serviceType}\n  selector:\n    app: ${serviceName}\n  ports:\n  - name: http\n    # 暴露端口和容器端口一致\n    port: ${servicePort}\n    # Pod 容器端口\n    targetPort: ${servicePort}',NULL,NULL,'ClusterIP',1,'2026-07-13 11:51:53','2026-07-23 19:54:48');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `env_id`, `service_type`, `status`, `create_time`, `update_time`) VALUES (4,'Nginx 前端静态资源','dockerfile','适用于前端/静态站点：将构建产物拷贝到 nginx 目录并对外暴露端口','FROM nginx:1.25-alpine\nWORKDIR /usr/share/nginx/html\n# 按实际构建产物目录调整（如 dist / build / public）\nCOPY dist/ ./\nEXPOSE ${servicePort}\nCMD [\"nginx\", \"-g\", \"daemon off;\"]','nginx:1.25-alpine',NULL,NULL,1,'2026-07-24 11:11:20','2026-07-24 11:11:20');
INSERT INTO `pipeline_template` (`id`, `name`, `type`, `description`, `content`, `base_image`, `env_id`, `service_type`, `status`, `create_time`, `update_time`) VALUES (6,'前端部署模版','deployment','','apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ${serviceName}\n  namespace: ${namespace}\nspec:\n  replicas: 1\n  selector:\n    matchLabels:\n      app: ${serviceName}\n  template:\n    metadata:\n      labels:\n        app: ${serviceName}\n    spec:\n      restartPolicy: Always\n      imagePullSecrets:\n      - name: harbor-registry-secret\n      containers:\n      - name: ${serviceName}\n        # 由流水线渲染后的镜像地址与标签\n        image: ${image}\n        ports:\n        # 容器业务端口\n        - containerPort: ${servicePort}\n        # 启动探针：仅检查端口是否打开，避免应用预热期间因业务检查失败被误杀\n        # 最大等待时间 = initialDelaySeconds + periodSeconds × failureThreshold = 30 + 5×40 = 230s\n        startupProbe:\n          tcpSocket:\n            port: ${servicePort}\n          initialDelaySeconds: 30\n          periodSeconds: 5\n          timeoutSeconds: 2\n          failureThreshold: 40\n        # 存活探针：startupProbe 成功后才生效，仅检查进程端口存活，减少外部依赖抖动导致的频繁重启\n        livenessProbe:\n          tcpSocket:\n            port: ${servicePort}\n          initialDelaySeconds: 30\n          periodSeconds: 5\n          timeoutSeconds: 3\n          failureThreshold: 40\n        # 就绪探针：检查业务健康接口，通过后才加入 Service 负载\n        # readinessProbe:\n        #   httpGet:\n        #     path: /actuator/health\n        #     port: ${servicePort}\n        #     scheme: HTTP\n        #   initialDelaySeconds: 20\n        #   periodSeconds: 10\n        #   timeoutSeconds: 2\n        #   failureThreshold: 5\n        #   successThreshold: 1\n        # CPU 限制：防止容器使用超过宿主机核心数导致宿主机过载\n        resources:\n          requests:\n            cpu: \"100m\"\n          limits:\n            cpu: \"4\"\n        # 挂载节点时区文件，使容器时区与所在节点保持一致\n        volumeMounts:\n        - name: host-localtime\n          mountPath: /etc/localtime\n          readOnly: true\n        - name: host-timezone\n          mountPath: /etc/timezone\n          readOnly: true\n        - name: host-data-logs\n          mountPath: /data/logs\n        # 环境变量配置\n        # env:\n        # - name: RUN_ENV\n        #   valueFrom:\n        #     configMapKeyRef:\n        #       name: app-config\n        #       key: APP_ENV\n      volumes:\n      - name: host-localtime\n        hostPath:\n          path: /etc/localtime\n          type: File\n      - name: host-timezone\n        hostPath:\n          path: /etc/timezone\n          type: FileOrCreate\n      - name: host-data-logs\n        hostPath:\n          path: /data/logs\n          type: DirectoryOrCreate',NULL,NULL,NULL,1,'2026-07-29 17:01:20','2026-07-29 17:01:20');
/*!40000 ALTER TABLE `pipeline_template` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `pipeline_view` WRITE;
/*!40000 ALTER TABLE `pipeline_view` DISABLE KEYS */;
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (2,'dev',1,'dev 环境job',0,1,'2026-07-13 17:22:55','2026-07-22 18:21:21');
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (3,'uat',4,'uat环境job',1,1,'2026-07-13 17:30:21','2026-07-22 18:21:21');
INSERT INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`, `create_time`, `update_time`) VALUES (4,'prod',5,'prod环境',2,1,'2026-07-20 15:41:37','2026-07-23 19:53:51');
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
/*!40000 ALTER TABLE `pipeline_view_role` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `approval_flow` WRITE;
/*!40000 ALTER TABLE `approval_flow` DISABLE KEYS */;
INSERT INTO `approval_flow` (`id`, `name`, `description`, `steps`, `status`, `create_time`, `update_time`) VALUES (1,'生产审批流','','[{\"step\":1,\"approver\":\"jack\",\"approverName\":\"jack\",\"required\":true,\"status\":null},{\"step\":2,\"approver\":\"leader\",\"approverName\":\"leader\",\"required\":true,\"status\":null}]',1,'2026-07-14 19:15:17','2026-07-23 22:16:47');
/*!40000 ALTER TABLE `approval_flow` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `build_node` WRITE;
/*!40000 ALTER TABLE `build_node` DISABLE KEYS */;
INSERT INTO `build_node` (`id`, `name`, `label`, `host`, `port`, `auth_type`, `username`, `password`, `private_key`, `private_key_passphrase`, `node_type`, `work_dir`, `status`, `description`, `create_time`, `update_time`) VALUES (4,'dev-build-1','dev-build','172.16.50.179',22,'password','root','root@2025!',NULL,NULL,'build','~/opsflow','在线','dev-build','2026-07-03 11:58:18','2026-07-03 17:00:30');
INSERT INTO `build_node` (`id`, `name`, `label`, `host`, `port`, `auth_type`, `username`, `password`, `private_key`, `private_key_passphrase`, `node_type`, `work_dir`, `status`, `description`, `create_time`, `update_time`) VALUES (5,'dev-deploy','dev-deploy','172.16.50.161',22,'password','root','root@123',NULL,NULL,'deploy','~/opsflow','在线','dev-deploy','2026-07-03 11:59:15','2026-07-13 17:49:36');
INSERT INTO `build_node` (`id`, `name`, `label`, `host`, `port`, `auth_type`, `username`, `password`, `private_key`, `private_key_passphrase`, `node_type`, `work_dir`, `status`, `description`, `create_time`, `update_time`) VALUES (6,'dev-build-2','dev-build','172.16.50.179',22,'password','root','root@2025!',NULL,NULL,'build','~/opsflow','在线','','2026-07-08 16:48:15','2026-07-08 16:49:02');
/*!40000 ALTER TABLE `build_node` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `system_config` WRITE;
/*!40000 ALTER TABLE `system_config` DISABLE KEYS */;
INSERT INTO `system_config` (`id`, `config_type`, `config_key`, `config_value`, `description`, `create_time`, `update_time`) VALUES (1,'license','features','deploy_approval',NULL,'2026-07-23 14:25:56','2026-07-23 15:44:08');
INSERT INTO `system_config` (`id`, `config_type`, `config_key`, `config_value`, `description`, `create_time`, `update_time`) VALUES (2,'license','importedAt','2026-07-23T15:44:08.312',NULL,'2026-07-23 14:25:56','2026-07-23 15:44:08');
INSERT INTO `system_config` (`id`, `config_type`, `config_key`, `config_value`, `description`, `create_time`, `update_time`) VALUES (3,'license','raw','{\n  \"payload\" : {\n    \"product\" : \"OpsFlow\",\n    \"licenseId\" : \"f64c15b83aec4e2cbdbc7e4ac7a20ee0\",\n    \"customer\" : \"历史落库测试\",\n    \"features\" : [ \"deploy_approval\" ],\n    \"issuedAt\" : \"2026-07-23T15:10:33.378435\",\n    \"expiresAt\" : \"2026-10-21T15:10:33.37985\"\n  },\n  \"signature\" : \"YxFzMF3c1Tu/DYDAcNkm2AxQzmGBpQimvBS7jOWDKiqbu9fETOMXF2zkSM1QdKt9sqOezFHmjfdEKVo/9wG+f2w7vluIAKx/X70/iCdnejWCRW253qRibr/Gd46AKzmI9qZcGqTmJP4b/K7dLkGMAHt8i4NC3tLq6W/plwmLQFIhnTPQOq87J4rM6L82XvNxDWMrUTs4YbmHxwJrxt1fVp8T0fRXtMmhDVzYA3tNmqgtAN3JnkTJrmEHWBXms6CAvnT75dFGuTSQ2tLyERyU83uHytGeurZxHYlE9PDw08R3uz7SSXG0P93wIAiFQ0i+4btOBJD6aOHp6JcbxPXnpA==\"\n}',NULL,'2026-07-23 14:25:56','2026-07-23 15:44:08');
INSERT INTO `system_config` (`id`, `config_type`, `config_key`, `config_value`, `description`, `create_time`, `update_time`) VALUES (4,'license','licenseId','f64c15b83aec4e2cbdbc7e4ac7a20ee0',NULL,'2026-07-23 14:25:56','2026-07-23 15:44:08');
INSERT INTO `system_config` (`id`, `config_type`, `config_key`, `config_value`, `description`, `create_time`, `update_time`) VALUES (5,'license','expiresAt','2026-10-21T15:10:33.37985',NULL,'2026-07-23 14:25:56','2026-07-23 15:44:08');
INSERT INTO `system_config` (`id`, `config_type`, `config_key`, `config_value`, `description`, `create_time`, `update_time`) VALUES (6,'license','customer','历史落库测试',NULL,'2026-07-23 14:25:56','2026-07-23 15:44:08');
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
