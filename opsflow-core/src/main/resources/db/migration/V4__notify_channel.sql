-- 通知通道：流水线构建/发布结果推送到外部群（首期只有飞书群机器人，type 预留其他通道）。
-- webhook_url、secret 由应用加密后写入（enc:v1: 前缀），所以用 text 存。
-- project_id 为空表示全局通道，对所有项目生效；非空只对该项目生效。

CREATE TABLE `notify_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '通道名称',
  `type` varchar(30) NOT NULL DEFAULT 'feishu_bot' COMMENT '通道类型：feishu_bot',
  `webhook_url` text NOT NULL COMMENT 'Webhook 地址（加密）',
  `secret` text COMMENT '签名密钥（加密，可空）',
  `project_id` bigint DEFAULT NULL COMMENT '所属项目；为空表示全局',
  `on_success` tinyint NOT NULL DEFAULT '1' COMMENT '成功时通知：1-是 0-否',
  `on_failure` tinyint NOT NULL DEFAULT '1' COMMENT '失败时通知：1-是 0-否',
  `env_scope` varchar(20) NOT NULL DEFAULT 'all' COMMENT '环境范围：all/prod/nonprod',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notify_channel_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知通道表';
