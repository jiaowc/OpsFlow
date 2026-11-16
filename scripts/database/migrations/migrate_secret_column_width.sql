-- 凭据字段改为 AES-GCM 加密存储（enc:v1:Base64），密文比明文长约 1.4 倍 + 40 字符，放宽列宽
-- MODIFY 可重复执行
SET NAMES utf8mb4;

ALTER TABLE `build_node`
  MODIFY COLUMN `password` varchar(1024) DEFAULT NULL COMMENT 'SSH密码（加密存储）',
  MODIFY COLUMN `private_key_passphrase` varchar(1024) DEFAULT NULL COMMENT 'SSH私钥密码（加密存储）';
