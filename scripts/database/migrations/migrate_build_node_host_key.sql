-- 构建/部署节点 SSH 主机指纹（首次信任 TOFU）：首次连接记录，之后指纹变化即拒绝连接
SET NAMES utf8mb4;

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'build_node'
    AND COLUMN_NAME = 'host_key'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `build_node` ADD COLUMN `host_key` text COMMENT ''SSH 主机公钥（类型 + Base64，首次连接记录）'' AFTER `private_key_passphrase`',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'build_node'
    AND COLUMN_NAME = 'host_key_fingerprint'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `build_node` ADD COLUMN `host_key_fingerprint` varchar(128) DEFAULT NULL COMMENT ''SSH 主机指纹（SHA256）'' AFTER `host_key`',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
