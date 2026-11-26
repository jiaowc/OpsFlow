-- 生产环境部署权限。
-- 社区版允许普通流水线直接构建部署生产环境（不经上线任务），用独立权限码控制，避免已有「运行流水线」的角色自动获得生产发布能力。
-- 不分配给任何角色：带 * 的管理员自动拥有，其他角色由管理员在「角色管理」里按需授予。
-- 不写死 id：已有库里可能有自定义权限占用了自增 id；按 code 判重，重复执行也不会插第二条。

INSERT INTO `permission` (`name`, `code`, `description`, `parent_id`, `status`, `create_time`, `update_time`)
SELECT '部署生产环境', 'pipeline:run:prod', '普通流水线直接构建、编辑、重建、开启推送构建时部署到生产环境；商业版开启「生产发布必须走上线任务」时不生效', NULL, 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline:run:prod');
