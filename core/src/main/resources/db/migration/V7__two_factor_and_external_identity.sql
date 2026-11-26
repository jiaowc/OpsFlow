-- 两步验证（TOTP 动态码 + 恢复码）和单点登录账号对应关系。只建表，不改已有数据。
-- 策略存在 system_config（config_type = security，config_key = twoFactorPolicy：off / optional / required），不另建表。

-- 每个用户一条：确认绑定后才写入；secret 由应用加密后写入（enc:v1: 前缀）
CREATE TABLE `user_two_factor` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `secret` varchar(255) NOT NULL COMMENT '动态码密钥（Base32，加密存储）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1-启用 0-停用',
  `confirmed_time` datetime DEFAULT NULL COMMENT '确认绑定时间',
  `last_used_step` bigint DEFAULT NULL COMMENT '最后一次用过的时间窗序号（防重放）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_two_factor_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户两步验证表';

-- 恢复码：只存哈希，每个只能用一次（used_time 非空表示已用）
CREATE TABLE `user_recovery_code` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `code_hash` varchar(100) NOT NULL COMMENT '恢复码哈希（BCrypt）',
  `used_time` datetime DEFAULT NULL COMMENT '使用时间；为空表示未使用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_recovery_code_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户恢复码表';

-- 外部身份（单点登录账号）和本地用户的对应关系；provider 为登录方式标识，subject 为该方式下的用户 ID。
-- 一个外部账号只对应一个本地用户，一个本地用户在每种登录方式下只有一条记录
CREATE TABLE `user_external_identity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `provider` varchar(32) NOT NULL COMMENT '登录方式标识',
  `subject` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '该登录方式下的用户 ID（区分大小写、精确匹配，写入前去掉首尾空白）',
  `union_id` varchar(128) DEFAULT NULL COMMENT '跨应用的统一 ID（可空）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_external_identity_subject` (`provider`, `subject`),
  UNIQUE KEY `uk_user_external_identity_user_provider` (`user_id`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户外部身份表';
