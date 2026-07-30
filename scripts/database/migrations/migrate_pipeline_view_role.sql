-- 流水线视图 ↔ 角色授权
CREATE TABLE IF NOT EXISTS `pipeline_view_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `view_id` bigint NOT NULL COMMENT '视图 ID',
  `role_id` bigint NOT NULL COMMENT '角色 ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_view_role` (`view_id`, `role_id`),
  KEY `idx_pvr_view` (`view_id`),
  KEY `idx_pvr_role` (`role_id`)
) COMMENT='流水线视图角色授权';

-- 兼容存量：已有视图授权给所有具备 pipeline:view 或 * 的启用角色
INSERT INTO `pipeline_view_role` (`view_id`, `role_id`)
SELECT pv.id, r.id
FROM `pipeline_view` pv
CROSS JOIN `role` r
WHERE r.status = 1
  AND EXISTS (
      SELECT 1
      FROM `role_permission` rp
      INNER JOIN `permission` p ON p.id = rp.permission_id
      WHERE rp.role_id = r.id
        AND (p.code = 'pipeline:view' OR p.code = '*')
  )
  AND NOT EXISTS (
      SELECT 1 FROM `pipeline_view_role` pvr
      WHERE pvr.view_id = pv.id AND pvr.role_id = r.id
  );
