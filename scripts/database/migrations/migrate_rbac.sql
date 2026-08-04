-- RBAC：用户/角色/权限表 + 种子角色与权限码（可重复执行）
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(200) NOT NULL COMMENT '密码（MD5）',
  `real_name` varchar(100) DEFAULT NULL COMMENT '真实姓名',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '角色名称',
  `code` varchar(50) NOT NULL COMMENT '角色代码',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '权限名称',
  `code` varchar(50) NOT NULL COMMENT '权限代码',
  `resource` varchar(200) DEFAULT NULL COMMENT '资源路径（备注）',
  `method` varchar(20) DEFAULT NULL COMMENT 'HTTP 方法（备注）',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `parent_id` bigint DEFAULT NULL COMMENT '父权限ID',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`),
  KEY `idx_permission_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

CREATE TABLE IF NOT EXISTS `user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_user` (`user_id`),
  KEY `idx_ur_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS `role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
  KEY `idx_rp_role` (`role_id`),
  KEY `idx_rp_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联';

-- 种子角色
INSERT INTO `role` (`name`, `code`, `description`, `status`)
SELECT '管理员', 'ADMIN', '拥有全部权限', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'ADMIN');

INSERT INTO `role` (`name`, `code`, `description`, `status`)
SELECT '开发者', 'DEVELOPER', '流水线运行、上线申请、服务维护', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'DEVELOPER');

INSERT INTO `role` (`name`, `code`, `description`, `status`)
SELECT '审批人', 'APPROVER', '查看上线任务并审批', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'APPROVER');

INSERT INTO `role` (`name`, `code`, `description`, `status`)
SELECT '只读用户', 'VIEWER', '仅查看', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = 'VIEWER');

-- 种子权限码
INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '全部权限', '*', '超级权限通配符', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = '*');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看统计', 'statistics:view', '查看统计信息', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'statistics:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看流水线', 'pipeline:view', '查看流水线视图', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '运行流水线', 'pipeline:run', '触发/重试流水线', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline:run');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '编辑流水线任务', 'pipeline:edit', '编辑流水线任务配置', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline:edit');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '删除流水线任务', 'pipeline:delete', '删除流水线任务', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline:delete');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '回滚部署', 'pipeline:rollback', '回滚到历史成功镜像', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline:rollback');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看上线任务', 'deploy:view', '查看上线任务', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'deploy:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '创建上线任务', 'deploy:create', '创建上线任务', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'deploy:create');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '审批上线', 'deploy:approve', '通过/拒绝上线审批', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'deploy:approve');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '解锁上线任务', 'deploy:unlock', '解除他人锁定的上线任务', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'deploy:unlock');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看服务', 'service:view', '查看服务列表', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'service:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '管理服务', 'service:manage', '创建/编辑/删除服务', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'service:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看环境', 'env:view', '查看环境配置', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'env:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '管理环境', 'env:manage', '创建/编辑/删除环境', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'env:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看集群', 'cluster:view', '查看集群', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'cluster:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '管理集群', 'cluster:manage', '创建/编辑/删除集群', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'cluster:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看审批流', 'approval:view', '查看审批流程', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'approval:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '管理审批流', 'approval:manage', '创建/编辑/删除审批流', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'approval:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '查看节点', 'node:view', '查看构建/部署节点', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'node:view');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '管理节点', 'node:manage', '创建/编辑/删除节点', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'node:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '用户管理', 'user:manage', '管理用户账号', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'user:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '角色管理', 'role:manage', '管理角色', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'role:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '权限管理', 'permission:manage', '管理权限码', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'permission:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '流水线配置', 'pipeline_config:manage', '管理流水线模版/步骤/视图', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'pipeline_config:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '凭据与组件', 'credential:manage', '管理凭据与集成组件', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'credential:manage');

INSERT INTO `permission` (`name`, `code`, `description`, `status`)
SELECT '系统配置', 'system:config', '系统集成与通知配置', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `permission` WHERE `code` = 'system:config');

-- ADMIN：绑定 * 与全部具体权限
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r CROSS JOIN `permission` p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- DEVELOPER
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r
JOIN `permission` p ON p.code IN (
  'statistics:view',
  'pipeline:view', 'pipeline:run', 'pipeline:edit',
  'deploy:view', 'deploy:create',
  'service:view', 'service:manage',
  'env:view', 'cluster:view', 'approval:view', 'node:view'
)
WHERE r.code = 'DEVELOPER'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- APPROVER
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r
JOIN `permission` p ON p.code IN (
  'statistics:view',
  'pipeline:view',
  'deploy:view', 'deploy:approve',
  'approval:view'
)
WHERE r.code = 'APPROVER'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- VIEWER
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r
JOIN `permission` p ON p.code IN (
  'statistics:view',
  'pipeline:view',
  'deploy:view',
  'service:view',
  'env:view',
  'cluster:view',
  'approval:view',
  'node:view'
)
WHERE r.code = 'VIEWER'
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 内置 admin 用户（密码 admin 的 MD5）
INSERT INTO `user` (`username`, `password`, `real_name`, `status`)
SELECT 'admin', '21232f297a57a5a743894a0e4a801fc3', '系统管理员', 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `username` = 'admin');

-- 给 admin、jack 绑定 ADMIN（若用户存在）
INSERT INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `user` u
JOIN `role` r ON r.code = 'ADMIN'
WHERE u.username IN ('admin', 'jack')
  AND NOT EXISTS (
    SELECT 1 FROM `user_role` ur
    WHERE ur.user_id = u.id AND ur.role_id = r.id
  );
