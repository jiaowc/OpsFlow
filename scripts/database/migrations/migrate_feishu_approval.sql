-- 飞书用户映射表 + 审批记录消息 ID（可重复执行）
SET NAMES utf8mb4;
SET @dbname = DATABASE();

CREATE TABLE IF NOT EXISTS `user_feishu` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '系统用户ID',
  `username` varchar(100) NOT NULL COMMENT '系统用户名',
  `feishu_user_id` varchar(100) DEFAULT NULL COMMENT '飞书 user_id',
  `feishu_open_id` varchar(100) DEFAULT NULL COMMENT '飞书 open_id',
  `feishu_union_id` varchar(100) DEFAULT NULL COMMENT '飞书 union_id',
  `feishu_mobile` varchar(50) DEFAULT NULL COMMENT '飞书手机号',
  `feishu_email` varchar(200) DEFAULT NULL COMMENT '飞书邮箱',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_feishu_username` (`username`),
  KEY `idx_feishu_user_id` (`feishu_user_id`),
  KEY `idx_feishu_open_id` (`feishu_open_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户飞书账号映射';

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'approval_record' AND COLUMN_NAME = 'feishu_message_id'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `approval_record` ADD COLUMN `feishu_message_id` varchar(100) DEFAULT NULL COMMENT ''飞书卡片消息ID'' AFTER `approver_feishu_id`'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
