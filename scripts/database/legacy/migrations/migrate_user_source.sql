-- 用户来源：local / feishu / ldap
SET NAMES utf8mb4;

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user'
    AND COLUMN_NAME = 'source'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `user` ADD COLUMN `source` varchar(20) NOT NULL DEFAULT ''local'' COMMENT ''来源：local-本地 feishu-飞书 ldap-LDAP'' AFTER `status`',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `user` SET `source` = 'local' WHERE `source` IS NULL OR `source` = '';
