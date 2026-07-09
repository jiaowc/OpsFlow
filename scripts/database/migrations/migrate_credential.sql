-- 钥匙串（凭证库）表 + 组件关联字段（可重复执行）
SET NAMES utf8mb4;

SET @dbname = DATABASE();

-- credential 表
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'credential') > 0,
  'SELECT 1',
  'CREATE TABLE `credential` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `name` varchar(100) NOT NULL COMMENT ''钥匙名称'',
    `credential_type` varchar(50) NOT NULL COMMENT ''凭证类型'',
    `config_data` text COMMENT ''凭证配置JSON'',
    `description` varchar(500) COMMENT ''描述'',
    `status` tinyint DEFAULT 1 COMMENT ''状态：1-启用 0-禁用'',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_credential_name` (`name`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''钥匙串凭证表'''
));
PREPARE createIfNotExists FROM @preparedStatement;
EXECUTE createIfNotExists;
DEALLOCATE PREPARE createIfNotExists;

-- component.credential_id
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'component' AND COLUMN_NAME = 'credential_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `component` ADD COLUMN `credential_id` bigint NULL COMMENT ''关联钥匙串ID'' AFTER `auth_config`, ADD KEY `idx_credential_id` (`credential_id`)'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
