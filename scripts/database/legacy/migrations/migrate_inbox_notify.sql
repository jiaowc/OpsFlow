-- 审批通知渠道 + 站内信（可重复执行）
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'deploy_task' AND COLUMN_NAME = 'notify_channels'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE `deploy_task` ADD COLUMN `notify_channels` varchar(100) DEFAULT ''inbox'' COMMENT ''通知渠道: inbox,feishu 逗号分隔'' AFTER `approval_flow_id`'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `inbox_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL COMMENT '接收人用户名',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` varchar(2000) DEFAULT NULL COMMENT '内容',
  `msg_type` varchar(50) DEFAULT 'approval' COMMENT '消息类型',
  `biz_type` varchar(50) DEFAULT NULL COMMENT '业务类型',
  `biz_id` varchar(100) DEFAULT NULL COMMENT '业务 ID',
  `link_path` varchar(200) DEFAULT NULL COMMENT '站内跳转路径',
  `read_flag` tinyint NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inbox_user_read` (`username`, `read_flag`),
  KEY `idx_inbox_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信';
