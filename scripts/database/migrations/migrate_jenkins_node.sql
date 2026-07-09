-- 为节点表添加缺失的字段（兼容 build_node / jenkins_node）
-- 执行前请先检查字段是否已存在，如果已存在请跳过
-- 此脚本兼容 MySQL 5.7+

-- 检查并添加 host 字段
SET @dbname = DATABASE();
SET @tablename = (
  SELECT CASE
    WHEN EXISTS (
      SELECT 1 FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'build_node'
    ) THEN 'build_node'
    ELSE 'jenkins_node'
  END
);
SET @columnname = 'host';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` varchar(200) COMMENT ''主机地址'' AFTER `jenkins_url`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 port 字段
SET @columnname = 'port';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` int COMMENT ''端口号'' AFTER `host`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 auth_type 字段
SET @columnname = 'auth_type';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` varchar(20) COMMENT ''认证类型：password, private_key'' AFTER `port`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 username 字段
SET @columnname = 'username';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` varchar(100) COMMENT ''SSH用户名'' AFTER `auth_type`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 password 字段
SET @columnname = 'password';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` varchar(500) COMMENT ''SSH密码（当authType为password时使用）'' AFTER `username`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 private_key 字段
SET @columnname = 'private_key';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` text COMMENT ''SSH私钥（当authType为private_key时使用）'' AFTER `password`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 private_key_passphrase 字段
SET @columnname = 'private_key_passphrase';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` varchar(200) COMMENT ''SSH私钥密码（如果私钥有密码）'' AFTER `private_key`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 检查并添加 node_type 字段
SET @columnname = 'node_type';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      (TABLE_SCHEMA = @dbname)
      AND (TABLE_NAME = @tablename)
      AND (COLUMN_NAME = @columnname)
  ) > 0,
  'SELECT 1', -- 字段已存在，不执行任何操作
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname, '` varchar(20) COMMENT ''节点类型：build, deploy'' AFTER `private_key_passphrase`;')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 如果上面的动态SQL执行失败，可以使用下面的简单方式（需要手动检查字段是否存在）
-- ALTER TABLE `jenkins_node` ADD COLUMN `host` varchar(200) COMMENT '主机地址' AFTER `jenkins_url`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `port` int COMMENT '端口号' AFTER `host`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `auth_type` varchar(20) COMMENT '认证类型：password, private_key' AFTER `port`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `username` varchar(100) COMMENT 'SSH用户名' AFTER `auth_type`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `password` varchar(500) COMMENT 'SSH密码（当authType为password时使用）' AFTER `username`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `private_key` text COMMENT 'SSH私钥（当authType为private_key时使用）' AFTER `password`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `private_key_passphrase` varchar(200) COMMENT 'SSH私钥密码（如果私钥有密码）' AFTER `private_key`;
-- ALTER TABLE `jenkins_node` ADD COLUMN `node_type` varchar(20) COMMENT '节点类型：build, deploy' AFTER `private_key_passphrase`;
