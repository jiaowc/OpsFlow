-- 上线任务删除权限
INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '删除上线任务', 'deploy:delete', '删除上线任务及其关联审批/CD 记录', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'deploy:delete');

-- ADMIN 绑定全部具体权限（含新增的 deploy:delete）
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r
CROSS JOIN `permission` p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
