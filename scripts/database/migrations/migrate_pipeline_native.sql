-- 平台原生流水线表迁移
CREATE TABLE IF NOT EXISTS `pipeline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT 'Pipeline名称',
  `description` varchar(500) COMMENT '描述',
  `steps_config` text COMMENT '步骤配置JSON',
  `script` text COMMENT 'Jenkinsfile脚本（可选）',
  `jenkins_job_template` varchar(200) COMMENT '兼容字段',
  `parameter_definitions` text COMMENT '参数定义JSON',
  `status` tinyint DEFAULT 1 COMMENT '1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) COMMENT='Pipeline模板表';

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

INSERT INTO `pipeline` (`name`, `description`, `steps_config`, `status`)
SELECT '标准构建发布流水线', '拉取代码 → 代码构建 → 镜像制作 → 上传镜像 → 开始部署 → 检查部署状态',
'[{"stepType":"checkout","stepName":"拉取代码","enabled":true,"order":1},{"stepType":"build","stepName":"代码构建","enabled":true,"order":2},{"stepType":"docker_build","stepName":"镜像制作","enabled":true,"order":3},{"stepType":"push_image","stepName":"上传镜像","enabled":true,"order":4},{"stepType":"deploy","stepName":"开始部署","enabled":true,"order":5},{"stepType":"check_deploy","stepName":"检查部署状态","enabled":true,"order":6}]',
1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `pipeline` LIMIT 1);
