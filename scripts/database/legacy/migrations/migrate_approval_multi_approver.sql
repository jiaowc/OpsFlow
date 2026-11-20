-- 审批步骤支持多个候选审批人（任一人审批即可），可重复执行
SET NAMES utf8mb4;
SET @dbname = DATABASE();

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'approval_record' AND COLUMN_NAME = 'approver_candidates');
SET @sql := IF(@exist = 0,
  'ALTER TABLE `approval_record` ADD COLUMN `approver_candidates` varchar(1000) DEFAULT NULL COMMENT ''候选审批人，格式 ,user1,user2, ；任一人审批即可'' AFTER `approver_feishu_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- init.sql 早期把 approver 定义为 NOT NULL，导致「不指定审批人」与多人审批步骤无法写入审批记录
ALTER TABLE `approval_record` MODIFY COLUMN `approver` varchar(100) DEFAULT NULL COMMENT '审批人用户名；为空且无候选审批人时任意登录用户可审批';
-- 多人审批时显示名为多个姓名拼接
ALTER TABLE `approval_record` MODIFY COLUMN `approver_name` varchar(500) DEFAULT NULL COMMENT '审批人显示名';
-- 多人审批会给每个候选人各发一张飞书卡片，消息 ID 以逗号分隔保存，便于审批后统一刷新
ALTER TABLE `approval_record` MODIFY COLUMN `feishu_message_id` varchar(2000) DEFAULT NULL COMMENT '飞书审批卡片消息 ID，多个以逗号分隔';

-- 移除已废弃的用户组表与审批组字段
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'approval_record' AND COLUMN_NAME = 'approver_group_id');
SET @sql := IF(@exist > 0, 'ALTER TABLE `approval_record` DROP COLUMN `approver_group_id`', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
DROP TABLE IF EXISTS `project_group_member`;
DROP TABLE IF EXISTS `user_group_member`;
DROP TABLE IF EXISTS `user_group`;
